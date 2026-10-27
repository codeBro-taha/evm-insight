package com.evminsight.baseline;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

public record BaselineSummary(String id, Integer version, BaselineStatus status, Instant createdAt, Instant approvedAt, String supersedesId,
                              String currency, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bac, int workPackageCount) {
}
