package com.evminsight.storage;

/** Controlled storage failure. {@link #getMessage()} is safe to show to users (no absolute paths, no stack details). */
public class StorageException extends RuntimeException {
    public enum Code {
        IO_FAILURE, CORRUPT, UNSUPPORTED_VERSION, IN_USE, NOT_FOUND, INVALID_NAME,
        BACKUP_EXISTS, CONFIRMATION_REQUIRED, INVALID_BACKUP
    }

    private static final long serialVersionUID = 1L;
    private final Code code;

    public StorageException(Code code, String message) { super(message); this.code = code; }

    public StorageException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }

    public Code code() { return code; }
}
