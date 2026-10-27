package com.evminsight.storage;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** A validated document. {@code migrated} is true when it was upgraded in memory from an older schema version. */
public record StoredDocument(String type, String id, int schemaVersion, ObjectNode data, boolean migrated) {
}
