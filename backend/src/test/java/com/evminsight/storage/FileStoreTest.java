package com.evminsight.storage;

import static com.evminsight.storage.StorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies FR-01..FR-05, NFR-03, NFR-06, NFR-12 for the filesystem store. */
class FileStoreTest {
    @TempDir Path tmp;

    private Path root() { return tmp.resolve("store"); }
    private FileStore open() { return FileStore.open(root(), new MigrationRegistry()); }
    private static ObjectNode project(String name) { return FileStore.newObject().put("name", name); }
    private static StorageException fails(Runnable r) { return assertThrows(StorageException.class, r::run); }

    /** FileIo that can fail at chosen steps. */
    static class FlakyIo implements FileIo {
        final FileIo real = new DefaultFileIo();
        boolean failWrite, failReplace;
        public void writeDurably(Path p, byte[] b) throws IOException {
            if (failWrite) throw new IOException("disk full");
            real.writeDurably(p, b);
        }
        public void replace(Path s, Path t) throws IOException {
            if (failReplace) throw new IOException("disk unavailable");
            real.replace(s, t);
        }
    }

    // ---- FR-01 basics, NFR-06 durability
    @Test @DisplayName("put/get round trip survives close and reopen; unknown fields and decimal text preserved")
    void roundTripAndRestart() {
        try (FileStore s = open()) {
            s.put("projects", "p1", project("Ünïcödé 项目").put("budget", new BigDecimal("1500.50")).put("extra", "keep-me"));
        }
        try (FileStore s = open()) {
            StoredDocument d = s.get("projects", "p1").orElseThrow();
            assertEquals("Ünïcödé 项目", d.data().get("name").asText());
            assertEquals(new BigDecimal("1500.50"), d.data().get("budget").decimalValue());
            assertEquals("keep-me", d.data().get("extra").asText());
            assertFalse(d.migrated());
            assertEquals(List.of("p1"), s.list("projects"));
        }
    }

    @Test void missingDocumentIsEmptyNotError() {
        try (FileStore s = open()) { assertTrue(s.get("projects", "nope").isEmpty()); assertTrue(s.list("projects").isEmpty()); }
    }

    @Test void deleteRemovesDocument() {
        try (FileStore s = open()) {
            s.put("projects", "p1", project("a"));
            assertTrue(s.delete("projects", "p1"));
            assertFalse(s.delete("projects", "p1"));
            assertTrue(s.get("projects", "p1").isEmpty());
        }
    }

    @Test @DisplayName("names that could escape the data directory are rejected")
    void invalidNamesRejected() {
        try (FileStore s = open()) {
            for (String bad : new String[] {"../x", "a/b", "a\\b", "", ".hidden", "/abs", "x y"})
                assertEquals(INVALID_NAME, fails(() -> s.put("projects", bad, project("x"))).code(), bad);
            assertEquals(INVALID_NAME, fails(() -> s.put("Projects", "p", project("x"))).code());
            assertEquals(INVALID_NAME, fails(() -> s.put("../etc", "p", project("x"))).code());
            assertEquals(INVALID_NAME, fails(() -> s.get(null, "p")).code());
        }
    }

    // ---- FR-02 safe saves
    @Test @DisplayName("failed write is not reported as success and the old version stays intact")
    void failedWriteKeepsOldVersion() {
        FlakyIo io = new FlakyIo();
        try (FileStore s = FileStore.open(root(), new MigrationRegistry(), io)) {
            s.put("projects", "p1", project("v1"));
            io.failWrite = true;
            StorageException e = fails(() -> s.put("projects", "p1", project("v2")));
            assertEquals(IO_FAILURE, e.code());
            assertEquals("v1", s.get("projects", "p1").orElseThrow().data().get("name").asText());
        }
    }

    @Test @DisplayName("failure during the final replace step leaves the original valid and no temp file behind")
    void failedReplaceKeepsOldVersion() throws IOException {
        FlakyIo io = new FlakyIo();
        try (FileStore s = FileStore.open(root(), new MigrationRegistry(), io)) {
            s.put("projects", "p1", project("v1"));
            io.failReplace = true;
            assertEquals(IO_FAILURE, fails(() -> s.put("projects", "p1", project("v2"))).code());
            io.failReplace = false;
            assertEquals("v1", s.get("projects", "p1").orElseThrow().data().get("name").asText());
            try (var files = Files.list(root().resolve("data/projects"))) {
                assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }

    @Test @DisplayName("stale temp files from a crash are cleaned on next open and never read as data")
    void staleTempCleaned() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("v1")); }
        Files.writeString(root().resolve("data/projects/p1.json.abc.tmp"), "{half written");
        try (FileStore s = open()) {
            assertEquals(1, s.recoveredTempFiles());
            assertEquals(List.of("p1"), s.list("projects"));
            assertTrue(s.verify().healthy());
        }
    }

    @Test void previousGoodVersionKeptOnOverwrite() throws IOException {
        try (FileStore s = open()) {
            s.put("projects", "p1", project("v1"));
            s.put("projects", "p1", project("v2"));
        }
        assertTrue(Files.exists(root().resolve("data/projects/p1.json.prev")));
    }

    // ---- NFR-03 corruption handling
    @Test @DisplayName("truncated file -> controlled CORRUPT error, file untouched, no absolute path leaked")
    void truncatedFile() throws IOException {
        Path file;
        try (FileStore s = open()) { s.put("projects", "p1", project("v1")); }
        file = root().resolve("data/projects/p1.json");
        byte[] all = Files.readAllBytes(file);
        byte[] cut = java.util.Arrays.copyOf(all, all.length / 2);
        Files.write(file, cut);
        try (FileStore s = open()) {
            StorageException e = fails(() -> s.get("projects", "p1"));
            assertEquals(CORRUPT, e.code());
            assertFalse(e.getMessage().contains(tmp.toString()), "message must not leak paths");
            assertArrayEquals(cut, Files.readAllBytes(file), "damaged file must not be silently replaced");
        }
    }

    @Test void tamperedContentDetectedByChecksum() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("original")); }
        Path file = root().resolve("data/projects/p1.json");
        Files.writeString(file, Files.readString(file).replace("original", "tampered"));
        try (FileStore s = open()) { assertEquals(CORRUPT, fails(() -> s.get("projects", "p1")).code()); }
    }

    @Test void fileCopiedToWrongIdDetected() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        Files.copy(root().resolve("data/projects/p1.json"), root().resolve("data/projects/p2.json"));
        try (FileStore s = open()) { assertEquals(CORRUPT, fails(() -> s.get("projects", "p2")).code()); }
    }

    @Test @DisplayName("verify() reports problems without modifying data; recoverFromPrevious restores the last good copy")
    void verifyAndRecover() throws IOException {
        try (FileStore s = open()) {
            s.put("projects", "p1", project("v1"));
            s.put("projects", "p1", project("v2"));
            s.put("projects", "p2", project("ok"));
        }
        Files.writeString(root().resolve("data/projects/p1.json"), "garbage");
        try (FileStore s = open()) {
            FileStore.VerifyReport r = s.verify();
            assertEquals(1, r.okCount());
            assertEquals(1, r.problems().size());
            assertEquals("p1", r.problems().get(0).id());
            s.recoverFromPrevious("projects", "p1");
            assertEquals("v1", s.get("projects", "p1").orElseThrow().data().get("name").asText());
            assertTrue(s.verify().healthy());
        }
    }

    @Test void recoverWithoutPreviousFails() {
        try (FileStore s = open()) { assertEquals(NOT_FOUND, fails(() -> s.recoverFromPrevious("projects", "none")).code()); }
    }

    @Test @DisplayName("a save after corruption does not overwrite the good .prev with the damaged file")
    void saveAfterCorruptionKeepsGoodPrev() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("v1")); s.put("projects", "p1", project("v2")); }
        Files.writeString(root().resolve("data/projects/p1.json"), "garbage");
        try (FileStore s = open()) {
            s.put("projects", "p1", project("v3"));
            assertEquals("v3", s.get("projects", "p1").orElseThrow().data().get("name").asText());
        }
        String prev = Files.readString(root().resolve("data/projects/p1.json.prev"));
        assertTrue(prev.contains("\"v1\""), "last good copy must survive");
    }

    @Test @DisplayName("missing metadata with existing data is refused, never re-initialised as empty")
    void missingMetaWithDataRefused() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        Files.delete(root().resolve("store-meta.json"));
        assertEquals(CORRUPT, fails(() -> open()).code());
        assertTrue(Files.exists(root().resolve("data/projects/p1.json")));
    }

    @Test void newerStoreFormatRejected() throws IOException {
        try (FileStore s = open()) { /* init */ }
        Path meta = root().resolve("store-meta.json");
        // rebuild a valid envelope claiming format 99
        ObjectNode data = FileStore.newObject().put("storeFormatVersion", 99);
        Files.write(meta, DocumentCodec.encode("store-meta", "store", 1, data));
        assertEquals(UNSUPPORTED_VERSION, fails(() -> open()).code());
    }

    // ---- FR-04 schema evolution
    @Test @DisplayName("newer document schema is rejected and left untouched")
    void newerDocumentSchemaRejected() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        Path f = root().resolve("data/projects/p1.json");
        Files.write(f, DocumentCodec.encode("projects", "p1", 99, project("a")));
        byte[] before = Files.readAllBytes(f);
        try (FileStore s = open()) {
            assertEquals(UNSUPPORTED_VERSION, fails(() -> s.get("projects", "p1")).code());
        }
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    @Test @DisplayName("v1 -> v2 migration runs on read, keeps unknown fields, and migrateAll persists it")
    void migrationKeepsUnknownFields() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("Old").put("futureField", 42)); }
        MigrationRegistry reg = new MigrationRegistry().register("projects", 1, d -> { d.put("title", d.get("name").asText()); d.remove("name"); return d; });
        try (FileStore s = FileStore.open(root(), reg)) {
            StoredDocument d = s.get("projects", "p1").orElseThrow();
            assertTrue(d.migrated());
            assertEquals(2, d.schemaVersion());
            assertEquals("Old", d.data().get("title").asText());
            assertEquals(42, d.data().get("futureField").asInt(), "unknown fields must never be dropped");
            assertEquals(1, s.migrateAll());
            assertFalse(s.get("projects", "p1").orElseThrow().migrated());
            assertEquals(0, s.migrateAll());
        }
        assertTrue(Files.readString(root().resolve("data/projects/p1.json.prev")).contains("\"schemaVersion\" : 1"));
    }

    @Test void missingMigrationStepIsControlledError() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        MigrationRegistry reg = new MigrationRegistry().declareCurrent("projects", 3);
        try (FileStore s = FileStore.open(root(), reg)) {
            assertEquals(UNSUPPORTED_VERSION, fails(() -> s.get("projects", "p1")).code());
        }
    }

    // ---- FR-05 concurrent access
    @Test void secondInstanceRefusedUntilFirstCloses() {
        FileStore first = open();
        assertEquals(IN_USE, fails(() -> open()).code());
        first.close();
        open().close();
    }

    // ---- FR-03 backup / restore
    @Test @DisplayName("backup then restore from a real backup file recovers deleted and changed data")
    void backupRestoreRoundTrip() throws IOException {
        Path zip = tmp.resolve("backups/b1.zip");
        try (FileStore s = open()) {
            s.put("projects", "p1", project("one"));
            s.put("baselines", "b1", project("base").put("amount", new BigDecimal("10.00")));
            s.backup(zip, false);
            s.delete("projects", "p1");
            s.put("baselines", "b1", project("changed"));
            s.put("projects", "p9", project("new after backup"));

            s.restore(zip, true);

            assertEquals("one", s.get("projects", "p1").orElseThrow().data().get("name").asText());
            assertEquals("base", s.get("baselines", "b1").orElseThrow().data().get("name").asText());
            assertTrue(s.get("projects", "p9").isEmpty());
            assertTrue(s.verify().healthy());
        }
        try (var safety = Files.list(root().resolve("safety"))) {
            List<Path> kept = safety.toList();
            assertEquals(1, kept.size(), "pre-restore data must be preserved");
            assertTrue(Files.exists(kept.get(0).resolve("projects/p9.json")));
        }
    }

    @Test void restoreRequiresConfirmation() throws IOException {
        Path zip = tmp.resolve("b.zip");
        try (FileStore s = open()) {
            s.put("projects", "p1", project("a"));
            s.backup(zip, false);
            assertEquals(CONFIRMATION_REQUIRED, fails(() -> s.restore(zip, false)).code());
            assertTrue(s.get("projects", "p1").isPresent());
        }
    }

    @Test void backupDoesNotOverwriteWithoutConfirmation() throws IOException {
        Path zip = tmp.resolve("b.zip");
        Files.writeString(zip, "precious");
        try (FileStore s = open()) {
            assertEquals(BACKUP_EXISTS, fails(() -> s.backup(zip, false)).code());
            assertEquals("precious", Files.readString(zip));
            s.backup(zip, true);
            assertNotEquals("precious", Files.readString(zip, StandardCharsets.ISO_8859_1));
        }
    }

    @Test void backupRefusesDamagedData() throws IOException {
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        Files.writeString(root().resolve("data/projects/p1.json"), "garbage");
        try (FileStore s = open()) {
            assertEquals(CORRUPT, fails(() -> s.backup(tmp.resolve("b.zip"), false)).code());
            assertFalse(Files.exists(tmp.resolve("b.zip")));
        }
    }

    @Test @DisplayName("invalid backups (garbage, tampered, path traversal, unlisted file) are rejected and live data is untouched")
    void invalidBackupsRejected() throws IOException {
        Path good = tmp.resolve("good.zip");
        try (FileStore s = open()) {
            s.put("projects", "p1", project("live"));
            s.backup(good, false);

            Path garbage = tmp.resolve("garbage.zip");
            Files.writeString(garbage, "not a zip");
            assertEquals(INVALID_BACKUP, fails(() -> s.restore(garbage, true)).code());

            Path tampered = tmp.resolve("tampered.zip");
            rewriteZip(good, tampered, "live", "evil");
            assertEquals(INVALID_BACKUP, fails(() -> s.restore(tampered, true)).code());

            Path slip = tmp.resolve("slip.zip");
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(slip))) {
                z.putNextEntry(new ZipEntry("manifest.json"));
                z.write("{\"format\":\"evm-insight-backup\",\"formatVersion\":1,\"files\":[]}".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                z.putNextEntry(new ZipEntry("data/../../evil.json"));
                z.write("x".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            assertEquals(INVALID_BACKUP, fails(() -> s.restore(slip, true)).code());
            assertFalse(Files.exists(tmp.resolve("evil.json")));

            assertEquals("live", s.get("projects", "p1").orElseThrow().data().get("name").asText());
            try (var rootFiles = Files.list(root())) {
                assertTrue(rootFiles.noneMatch(p -> p.getFileName().toString().startsWith("staging-")), "staging must be cleaned up");
            }
        }
    }

    @Test void restoreRejectsBackupFromNewerSchema() throws IOException {
        Path zip = tmp.resolve("newer.zip");
        try (FileStore s = open()) {
            s.put("projects", "p1", project("a"));
            s.backup(zip, false);
        }
        MigrationRegistry older = new MigrationRegistry(); // current("projects") == 1
        byte[] newerDoc = DocumentCodec.encode("projects", "p1", 5, project("a"));
        Path crafted = tmp.resolve("crafted.zip");
        String rel = "projects/p1.json";
        String manifest = "{\"format\":\"evm-insight-backup\",\"formatVersion\":1,\"files\":[{\"path\":\"" + rel + "\",\"sha256\":\""
                + DocumentCodec.sha256(newerDoc) + "\"}]}";
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(crafted))) {
            z.putNextEntry(new ZipEntry("manifest.json")); z.write(manifest.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("data/" + rel)); z.write(newerDoc); z.closeEntry();
        }
        try (FileStore s = FileStore.open(tmp.resolve("other"), older)) {
            assertEquals(UNSUPPORTED_VERSION, fails(() -> s.restore(crafted, true)).code());
        }
    }

    // ---- FR-06 permissions
    @Test void filesAreOwnerOnlyOnPosix() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        try (FileStore s = open()) { s.put("projects", "p1", project("a")); }
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root().resolve("data/projects/p1.json"))));
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root().resolve("data"))));
    }

    /** Copy a zip, replacing a text fragment inside the data entries (checksum in manifest then no longer matches). */
    private static void rewriteZip(Path from, Path to, String find, String replace) throws IOException {
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(from));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(to))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] b = in.readAllBytes();
                if (e.getName().startsWith("data/")) b = new String(b, StandardCharsets.UTF_8).replace(find, replace).getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(e.getName()));
                OutputStream o = out; o.write(b);
                out.closeEntry();
            }
        }
    }
}
