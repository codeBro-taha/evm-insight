package com.evminsight.audit;

import java.time.Instant;

public record AuditEvent(String id, Instant timestamp, String actor, String action, String targetType, String targetId, String detail) {
}
