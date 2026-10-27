package com.evminsight.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/** Real implementation: temp file + fsync + atomic rename. Owner-only permissions where POSIX is available (FR-06). */
public final class DefaultFileIo implements FileIo {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    static FileAttribute<?>[] dirAttrs() {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
                     : new FileAttribute<?>[0];
    }

    static FileAttribute<?>[] fileAttrs() {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                     : new FileAttribute<?>[0];
    }

    @Override
    public void writeDurably(Path newFile, byte[] bytes) throws IOException {
        try (FileChannel ch = FileChannel.open(newFile, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), fileAttrs())) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
    }

    @Override
    public void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Documented fallback: non-atomic replace. The FileStore has already kept a valid ".prev" copy.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
