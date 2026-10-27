package com.evminsight.project;

import java.time.Instant;
import java.time.LocalDate;

public record Project(String id, String identifier, String name, String description, LocalDate plannedStart,
                      LocalDate plannedFinish, String currency, boolean archived, Instant createdAt, Instant updatedAt) {
}
