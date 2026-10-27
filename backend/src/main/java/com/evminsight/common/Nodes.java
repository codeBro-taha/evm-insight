package com.evminsight.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Null-safe readers for stored JSON documents. */
public final class Nodes {
    private Nodes() { }

    public static String text(JsonNode n, String f) { JsonNode v = n.get(f); return v == null || v.isNull() ? null : v.asText(); }
    public static LocalDate date(JsonNode n, String f) { String t = text(n, f); return t == null ? null : LocalDate.parse(t); }
    public static Instant instant(JsonNode n, String f) { String t = text(n, f); return t == null ? null : Instant.parse(t); }
    public static BigDecimal decimal(JsonNode n, String f) { String t = text(n, f); return t == null ? null : new BigDecimal(t); }
    public static Integer integer(JsonNode n, String f) { JsonNode v = n.get(f); return v == null || v.isNull() ? null : v.asInt(); }
    public static boolean bool(JsonNode n, String f) { JsonNode v = n.get(f); return v != null && v.asBoolean(); }
}
