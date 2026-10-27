package com.evminsight.audit;

import com.evminsight.common.Nodes;
import com.evminsight.storage.FileStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Append-only audit history (FR-20). Timestamps are UTC instants. Single local user in Version 1. */
@Service
public class AuditService {
    static final String TYPE = "audit";
    public static final String LOCAL_ACTOR = "local-user";
    private final FileStore store;
    private final Clock clock;

    public AuditService(FileStore store, Clock clock) { this.store = store; this.clock = clock; }

    public void record(String action, String targetType, String targetId, String detail) {
        Instant now = clock.instant();
        String id = String.format("%015d-%s", now.toEpochMilli(), UUID.randomUUID().toString().substring(0, 8));
        ObjectNode n = FileStore.newObject();
        n.put("timestamp", now.toString());
        n.put("actor", LOCAL_ACTOR);
        n.put("action", action);
        n.put("targetType", targetType);
        n.put("targetId", targetId);
        n.put("detail", detail);
        store.put(TYPE, id, n);
    }

    /** Newest first. */
    public List<AuditEvent> recent(int limit) {
        List<String> ids = new ArrayList<>(store.list(TYPE));
        Collections.reverse(ids);
        List<AuditEvent> out = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(limit, ids.size()))) {
            ObjectNode d = store.get(TYPE, id).orElseThrow().data();
            out.add(new AuditEvent(id, Nodes.instant(d, "timestamp"), Nodes.text(d, "actor"), Nodes.text(d, "action"),
                    Nodes.text(d, "targetType"), Nodes.text(d, "targetId"), Nodes.text(d, "detail")));
        }
        return out;
    }
}
