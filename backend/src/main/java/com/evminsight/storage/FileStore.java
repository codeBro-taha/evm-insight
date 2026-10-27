package com.evminsight.storage;

import static com.evminsight.storage.StorageException.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Versioned filesystem document store (FR-01..FR-05). One active instance per root (OS file lock).
 *
 * Layout:
 *   root/store-meta.json            store format version (envelope + checksum)
 *   root/LOCK                       single-instance lock
 *   root/data/{type}/{id}.json      documents ({id}.json.prev = last known-good previous version)
 *   root/safety/pre-restore-*       copy of the data replaced by a restore
 */
public final class FileStore implements AutoCloseable {
    public static final int STORE_FORMAT_VERSION = 1;
    private static final String META_TYPE = "store-meta";
    private static final String META_ID = "store";
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,100}");
    private static final Pattern BACKUP_PATH = Pattern.compile("[a-z][a-z0-9-]{0,40}/[A-Za-z0-9][A-Za-z0-9._-]{0,100}\\.json");
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;

    public record Problem(String type, String id, StorageException.Code code, String message) { }

    public record VerifyReport(int okCount, List<Problem> problems) {
        public boolean healthy() { return problems.isEmpty(); }
    }

    private final Path root;
    private final Path dataDir;
    private final Path safetyDir;
    private final MigrationRegistry registry;
    private final FileIo io;
    private FileChannel lockChannel;
    private FileLock lock;
    private int recoveredTempFiles;

    private FileStore(Path root, MigrationRegistry registry, FileIo io) {
        this.root = root;
        this.dataDir = root.resolve("data");
        this.safetyDir = root.resolve("safety");
        this.registry = registry;
        this.io = io;
    }

    public static FileStore open(Path root, MigrationRegistry registry) { return open(root, registry, new DefaultFileIo()); }

    public static FileStore open(Path root, MigrationRegistry registry, FileIo io) {
        FileStore s = new FileStore(root, registry, io);
        try {
            s.acquireLock();
            s.initialiseOrValidate();
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private void acquireLock() {
        try {
            Files.createDirectories(root, DefaultFileIo.dirAttrs());
            lockChannel = FileChannel.open(root.resolve("LOCK"), Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                    DefaultFileIo.fileAttrs());
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null)
                throw new StorageException(IN_USE, "The data store is already in use by another running instance");
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "The data directory could not be opened", e);
        }
    }

    private void initialiseOrValidate() {
        Path meta = root.resolve("store-meta.json");
        try {
            if (Files.exists(meta)) {
                DocumentCodec.Decoded d = DocumentCodec.decode(Files.readAllBytes(meta));
                JsonNode v = d.data().get("storeFormatVersion");
                if (!META_TYPE.equals(d.type()) || !META_ID.equals(d.id()) || v == null || !v.isInt())
                    throw new StorageException(CORRUPT, "The store metadata is damaged");
                if (v.asInt() > STORE_FORMAT_VERSION || d.schemaVersion() > 1)
                    throw new StorageException(UNSUPPORTED_VERSION,
                            "This data store was created by a newer version of the application (format " + v.asInt() + ")");
            } else {
                if (Files.exists(dataDir)) {
                    try (Stream<Path> s = Files.list(dataDir)) {
                        if (s.findAny().isPresent())
                            throw new StorageException(CORRUPT,
                                    "The store metadata is missing but project data exists; refusing to re-initialise. Restore from a backup.");
                    }
                }
                Files.createDirectories(dataDir, DefaultFileIo.dirAttrs());
                ObjectNode data = DocumentCodec.MAPPER.createObjectNode();
                data.put("storeFormatVersion", STORE_FORMAT_VERSION);
                data.put("createdAt", Instant.now().toString());
                writeAtomically(meta, DocumentCodec.encode(META_TYPE, META_ID, 1, data));
            }
            Files.createDirectories(dataDir, DefaultFileIo.dirAttrs());
            recoveredTempFiles = removeStaleTempFiles();
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "The data directory could not be read", e);
        }
    }

    private int removeStaleTempFiles() throws IOException {
        int n = 0;
        List<Path> dirs = new ArrayList<>(List.of(root));
        try (Stream<Path> s = Files.walk(dataDir)) { s.filter(Files::isDirectory).forEach(dirs::add); }
        for (Path dir : dirs) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".tmp"))::iterator) {
                    Files.deleteIfExists(p);
                    n++;
                }
            }
        }
        return n;
    }

    /** Number of leftover temp files from interrupted writes that were cleaned when the store was opened. */
    public int recoveredTempFiles() { return recoveredTempFiles; }

    @Override
    public void close() {
        try { if (lock != null) lock.release(); } catch (IOException ignored) { /* closing */ }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { /* closing */ }
        lock = null;
        lockChannel = null;
    }

    public static ObjectNode newObject() { return DocumentCodec.MAPPER.createObjectNode(); }

    // ---------------------------------------------------------------- documents

    public synchronized void put(String type, String id, ObjectNode data) {
        Path target = pathOf(type, id);
        writeAtomically(target, DocumentCodec.encode(type, id, registry.currentVersion(type), data));
    }

    public synchronized Optional<StoredDocument> get(String type, String id) {
        Path p = pathOf(type, id);
        return Files.exists(p) ? Optional.of(load(p, type, id)) : Optional.empty();
    }

    public synchronized boolean delete(String type, String id) {
        Path p = pathOf(type, id);
        try {
            boolean existed = Files.deleteIfExists(p);
            Files.deleteIfExists(prevOf(p));
            return existed;
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "Could not delete " + type + "/" + id, e);
        }
    }

    public synchronized List<String> list(String type) {
        checkName(TYPE, type, "type");
        Path dir = dataDir.resolve(type);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).filter(n -> ID.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "Could not list " + type, e);
        }
    }

    /** Re-validate every document (diagnostics, FR-25). Never modifies data. */
    public synchronized VerifyReport verify() {
        int ok = 0;
        List<Problem> problems = new ArrayList<>();
        List<String> types = new ArrayList<>();
        try (Stream<Path> s = Files.list(dataDir)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> TYPE.matcher(n).matches())
                    .sorted().forEach(types::add);
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "The data directory could not be read", e);
        }
        for (String t : types) {
            for (String id : list(t)) {
                try {
                    load(pathOf(t, id), t, id);
                    ok++;
                } catch (StorageException e) {
                    problems.add(new Problem(t, id, e.code(), e.getMessage()));
                }
            }
        }
        return new VerifyReport(ok, problems);
    }

    /** Rewrite every document that is on an older schema version (the old file is kept as ".prev"). Returns count. */
    public synchronized int migrateAll() {
        int n = 0;
        for (String t : listTypes())
            for (String id : list(t)) {
                StoredDocument d = load(pathOf(t, id), t, id);
                if (d.migrated()) { put(t, id, d.data()); n++; }
            }
        return n;
    }

    /** Explicit recovery: restore the last known-good ".prev" copy of a damaged document. The damaged file is kept. */
    public synchronized void recoverFromPrevious(String type, String id) {
        Path p = pathOf(type, id);
        Path prev = prevOf(p);
        if (!Files.exists(prev)) throw new StorageException(NOT_FOUND, "No previous version exists for " + type + "/" + id);
        try {
            byte[] bytes = Files.readAllBytes(prev);
            DocumentCodec.Decoded d = DocumentCodec.decode(bytes);
            if (!d.type().equals(type) || !d.id().equals(id)) throw new StorageException(CORRUPT, "The previous version does not belong to " + type + "/" + id);
            if (Files.exists(p)) Files.move(p, p.resolveSibling(p.getFileName() + ".corrupt-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            writeAtomically(p, bytes);
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "Could not recover " + type + "/" + id, e);
        }
    }

    private List<String> listTypes() {
        try (Stream<Path> s = Files.list(dataDir)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> TYPE.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "The data directory could not be read", e);
        }
    }

    // ---------------------------------------------------------------- internals

    private StoredDocument load(Path p, String type, String id) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch (IOException e) {
            throw new StorageException(IO_FAILURE, "Could not read " + type + "/" + id, e);
        }
        DocumentCodec.Decoded d;
        try {
            d = DocumentCodec.decode(bytes);
        } catch (StorageException e) {
            throw new StorageException(e.code(), type + "/" + id + ": " + e.getMessage(), e);
        }
        if (!d.type().equals(type) || !d.id().equals(id))
            throw new StorageException(CORRUPT, type + "/" + id + ": the file content belongs to a different record");
        int current = registry.currentVersion(type);
        if (d.schemaVersion() < 1) throw new StorageException(CORRUPT, type + "/" + id + ": invalid schema version");
        if (d.schemaVersion() > current)
            throw new StorageException(UNSUPPORTED_VERSION, type + "/" + id + " was written by a newer version of the application (schema "
                    + d.schemaVersion() + ", supported up to " + current + ")");
        if (d.schemaVersion() < current)
            return new StoredDocument(type, id, current, registry.upgrade(type, d.schemaVersion(), d.data()), true);
        return new StoredDocument(type, id, current, d.data(), false);
    }

    /** FR-02: temp file -> fsync -> keep last good copy -> atomic replace. Throws (never reports success) on any failure. */
    private void writeAtomically(Path target, byte[] bytes) {
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent(), DefaultFileIo.dirAttrs());
            io.writeDurably(tmp, bytes);
            if (Files.exists(target) && isValid(target)) Files.copy(target, prevOf(target), StandardCopyOption.REPLACE_EXISTING);
            io.replace(tmp, target);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            if (e instanceof StorageException se) throw se;
            throw new StorageException(IO_FAILURE, "The data could not be saved; the previously saved version is unchanged", e);
        }
    }

    private static boolean isValid(Path p) {
        try { DocumentCodec.decode(Files.readAllBytes(p)); return true; } catch (IOException | StorageException e) { return false; }
    }

    private Path pathOf(String type, String id) {
        checkName(TYPE, type, "type");
        checkName(ID, id, "id");
        return dataDir.resolve(type).resolve(id + ".json");
    }

    private static Path prevOf(Path p) { return p.resolveSibling(p.getFileName() + ".prev"); }

    private static void checkName(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches())
            throw new StorageException(INVALID_NAME, "Invalid " + what + " name");
    }

    // ---------------------------------------------------------------- backup / restore (FR-03)

    /** Write a validated, checksummed ZIP of all data. Refuses to replace an existing file unless overwrite is true. */
    public synchronized void backup(Path targetZip, boolean overwrite) {
        if (Files.exists(targetZip) && !overwrite)
            throw new StorageException(BACKUP_EXISTS, "A backup file already exists at that location; confirm to overwrite it");
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String t : listTypes())
            for (String id : list(t)) {
                try {
                    load(pathOf(t, id), t, id);                       // refuse to back up damaged data
                    files.put(t + "/" + id + ".json", Files.readAllBytes(pathOf(t, id)));
                } catch (StorageException e) {
                    throw new StorageException(e.code(), "Backup aborted: " + e.getMessage(), e);
                } catch (IOException e) {
                    throw new StorageException(IO_FAILURE, "Backup aborted: could not read " + t + "/" + id, e);
                }
            }
        ObjectNode manifest = newObject();
        manifest.put("format", "evm-insight-backup");
        manifest.put("formatVersion", 1);
        manifest.put("storeFormatVersion", STORE_FORMAT_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        ArrayNode list = manifest.putArray("files");
        files.forEach((path, bytes) -> list.addObject().put("path", path).put("sha256", DocumentCodec.sha256(bytes)));

        Path tmp = targetZip.resolveSibling(targetZip.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (targetZip.getParent() != null) Files.createDirectories(targetZip.getParent());
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW))) {
                z.putNextEntry(new ZipEntry("manifest.json"));
                z.write(DocumentCodec.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                z.closeEntry();
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    z.putNextEntry(new ZipEntry("data/" + e.getKey()));
                    z.write(e.getValue());
                    z.closeEntry();
                }
            }
            io.replace(tmp, targetZip);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            throw new StorageException(IO_FAILURE, "The backup could not be written", e);
        }
    }

    /**
     * Replace the current data with a validated backup. Requires explicit confirmation. The backup is fully
     * validated in a staging folder BEFORE anything live is touched; current data is kept under safety/.
     */
    public synchronized void restore(Path zip, boolean confirmed) {
        if (!confirmed)
            throw new StorageException(CONFIRMATION_REQUIRED,
                    "Restoring replaces the current data. Confirm to continue; the current data will be kept in the safety folder.");
        String stamp = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path staging = root.resolve("staging-" + stamp);
        Path safe = safetyDir.resolve("pre-restore-" + stamp);
        boolean movedLive = false;
        try {
            stageValidated(zip, staging);
            Files.createDirectories(safetyDir, DefaultFileIo.dirAttrs());
            Files.move(dataDir, safe, StandardCopyOption.ATOMIC_MOVE);
            movedLive = true;
            Files.move(staging, dataDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (movedLive) {
                try { Files.move(safe, dataDir, StandardCopyOption.ATOMIC_MOVE); } catch (IOException ignored) { /* reported below */ }
            }
            deleteQuietly(staging);
            throw new StorageException(IO_FAILURE, "The restore failed; the existing data was kept", e);
        } catch (StorageException e) {
            deleteQuietly(staging);
            throw e;
        }
    }

    private void stageValidated(Path zip, Path staging) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (entries.size() >= MAX_ENTRIES) throw invalidBackup("it contains too many files");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                total += copyBounded(z, out, MAX_TOTAL_BYTES - total);
                if (entries.put(e.getName(), out.toByteArray()) != null) throw invalidBackup("it contains duplicate entries");
            }
        } catch (IOException e) {
            throw new StorageException(INVALID_BACKUP, "The backup file could not be read", e);
        }
        byte[] manifestBytes = entries.remove("manifest.json");
        if (manifestBytes == null) throw invalidBackup("manifest is missing");
        JsonNode manifest;
        try { manifest = DocumentCodec.MAPPER.readTree(manifestBytes); } catch (IOException e) { throw invalidBackup("manifest is unreadable"); }
        if (manifest == null || !"evm-insight-backup".equals(manifest.path("format").asText())
                || manifest.path("formatVersion").asInt(-1) != 1 || !manifest.path("files").isArray())
            throw invalidBackup("manifest is not a supported backup format");
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonNode f : manifest.get("files")) declared.put(f.path("path").asText(), f.path("sha256").asText());
        Map<String, byte[]> payload = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> payload.put(name, bytes));
        for (String name : payload.keySet())
            if (!name.startsWith("data/") || !declared.containsKey(name.substring(5))) throw invalidBackup("it contains unlisted or unsafe entries");
        for (Map.Entry<String, String> d : declared.entrySet()) {
            String rel = d.getKey();
            if (!BACKUP_PATH.matcher(rel).matches()) throw invalidBackup("it contains an unsafe path");
            byte[] bytes = payload.get("data/" + rel);
            if (bytes == null) throw invalidBackup("a listed file is missing");
            if (!DocumentCodec.sha256(bytes).equals(d.getValue())) throw invalidBackup("a file does not match its checksum");
            String type = rel.substring(0, rel.indexOf('/'));
            String id = rel.substring(rel.indexOf('/') + 1, rel.length() - 5);
            DocumentCodec.Decoded doc;
            try { doc = DocumentCodec.decode(bytes); } catch (StorageException e) { throw invalidBackup("a document is damaged"); }
            if (!doc.type().equals(type) || !doc.id().equals(id)) throw invalidBackup("a document does not match its path");
            if (doc.schemaVersion() > registry.currentVersion(type))
                throw new StorageException(UNSUPPORTED_VERSION, "The backup contains data from a newer version of the application");
        }
        Files.createDirectories(staging, DefaultFileIo.dirAttrs());
        for (String rel : declared.keySet()) {
            Path out = staging.resolve(rel);
            Files.createDirectories(out.getParent(), DefaultFileIo.dirAttrs());
            io.writeDurably(out, payload.get("data/" + rel));
        }
    }

    private static long copyBounded(InputStream in, ByteArrayOutputStream out, long remaining) throws IOException {
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            n += r;
            if (n > remaining) throw invalidBackup("it is too large");
            out.write(buf, 0, r);
        }
        return n;
    }

    private static StorageException invalidBackup(String why) {
        return new StorageException(INVALID_BACKUP, "The backup is not valid: " + why);
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ } });
        } catch (IOException ignored) { /* best effort */ }
    }
}
