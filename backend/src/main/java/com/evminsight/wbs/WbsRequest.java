package com.evminsight.wbs;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WbsRequest(String parentId, WbsKind kind, String name, String description, BigDecimal budget,
                         LocalDate plannedStart, LocalDate plannedFinish) {
}
