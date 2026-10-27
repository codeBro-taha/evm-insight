package com.evminsight.storage;

import java.io.IOException;
import java.nio.file.Path;

/** Low-level write primitives. Separate interface so tests can inject disk failures (NFR-12). */
public interface FileIo {
    /** Create a NEW file with the bytes and force them to disk before returning. */
    void writeDurably(Path newFile, byte[] bytes) throws IOException;

    /** Replace target with source atomically where the platform supports it. */
    void replace(Path source, Path target) throws IOException;
}
