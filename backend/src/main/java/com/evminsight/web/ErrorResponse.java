package com.evminsight.web;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {
}
