package com.evminsight.storage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-type schema versions and tested upgrade steps (FR-04).
 * Steps operate on the JSON tree, so fields a step does not know about are preserved, never dropped.
 */
public final class MigrationRegistry {
    @FunctionalInterface
    public interface Migration {
        ObjectNode apply(ObjectNode data);
    }

    private final Map<String, Integer> current = new HashMap<>();
    private final Map<String, Map<Integer, Migration>> steps = new HashMap<>();

    /** Declare the current version of a type that has no migrations yet. */
    public MigrationRegistry declareCurrent(String type, int version) {
        current.merge(type, version, Math::max);
        return this;
    }

    /** Register the step that upgrades a document of {@code fromVersion} to {@code fromVersion + 1}. */
    public MigrationRegistry register(String type, int fromVersion, Migration migration) {
        steps.computeIfAbsent(type, t -> new HashMap<>()).put(fromVersion, migration);
        current.merge(type, fromVersion + 1, Math::max);
        return this;
    }

    public int currentVersion(String type) { return current.getOrDefault(type, 1); }

    ObjectNode upgrade(String type, int from, ObjectNode data) {
        ObjectNode node = data;
        for (int v = from; v < currentVersion(type); v++) {
            Migration m = steps.getOrDefault(type, Map.of()).get(v);
            if (m == null)
                throw new StorageException(StorageException.Code.UNSUPPORTED_VERSION,
                        "No migration available from version " + v + " of '" + type + "'");
            node = m.apply(node.deepCopy());
        }
        return node;
    }
}
