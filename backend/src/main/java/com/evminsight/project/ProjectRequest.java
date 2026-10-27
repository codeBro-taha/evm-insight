package com.evminsight.project;

import java.time.LocalDate;

public record ProjectRequest(String identifier, String name, String description, LocalDate plannedStart,
                             LocalDate plannedFinish, String currency) {
}
