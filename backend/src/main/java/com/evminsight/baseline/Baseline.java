package com.evminsight.baseline;

import com.evminsight.wbs.WbsItem;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable snapshot of the plan. {@code version} is assigned at approval; approved baselines are never modified (FR-09). */
public record Baseline(String id, String projectId, Integer version, BaselineStatus status, Instant createdAt, Instant approvedAt,
                       String supersedesId, String currency, LocalDate projectPlannedStart, LocalDate projectPlannedFinish,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bac, List<WbsItem> items) {

    public BaselineSummary summary() {
        return new BaselineSummary(id, version, status, createdAt, approvedAt, supersedesId, currency, bac,
                (int) items.stream().filter(i -> i.kind() == com.evminsight.wbs.WbsKind.WORK_PACKAGE).count());
    }
}
