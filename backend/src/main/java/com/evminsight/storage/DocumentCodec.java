package com.evminsight.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * On-disk document envelope:
 * {"type","id","schemaVersion","checksum":"sha256:...","data":{...}}.
 * The checksum covers the canonical (sorted-key) form of "data" and detects truncation/tampering.
 * Decimals are parsed as BigDecimal so monetary text such as 1.50 is not altered by a round trip.
 */
final class DocumentCodec {
    record Decoded(String type, String id, int schemaVersion, ObjectNode data) { }

    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            // keep BigDecimal exactly as given (1500.50 must not become 1500.5)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private DocumentCodec() { }

    static byte[] encode(String type, String id, int schemaVersion, ObjectNode data) {
        try {
            ObjectNode normalised = (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsBytes(data));
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", type);
            envelope.put("id", id);
            envelope.put("schemaVersion", schemaVersion);
            envelope.put("checksum", "sha256:" + sha256(canonical(normalised).getBytes(StandardCharsets.UTF_8)));
            envelope.set("data", normalised);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);
        } catch (IOException e) {
            throw new StorageException(StorageException.Code.IO_FAILURE, "The document could not be encoded", e);
        }
    }

    static Decoded decode(byte[] bytes) {
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw corrupt("it is not valid JSON (possibly truncated)", e);
        }
        if (root == null || !root.isObject()) throw corrupt("it is not a JSON object", null);
        JsonNode type = root.get("type"), id = root.get("id"), ver = root.get("schemaVersion"),
                sum = root.get("checksum"), data = root.get("data");
        if (type == null || !type.isTextual() || id == null || !id.isTextual() || ver == null
                || !ver.isIntegralNumber() || !ver.canConvertToInt() || sum == null || !sum.isTextual()
                || data == null || !data.isObject())
            throw corrupt("its envelope is incomplete", null);
        String expected = "sha256:" + sha256(canonical(data).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(sum.asText())) throw corrupt("its checksum does not match", null);
        return new Decoded(type.asText(), id.asText(), ver.asInt(), (ObjectNode) data);
    }

    static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        canonical(node, sb);
        return sb.toString();
    }

    private static void canonical(JsonNode n, StringBuilder sb) {
        try {
            if (n.isObject()) {
                List<String> names = new ArrayList<>();
                n.fieldNames().forEachRemaining(names::add);
                Collections.sort(names);
                sb.append('{');
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(MAPPER.writeValueAsString(names.get(i))).append(':');
                    canonical(n.get(names.get(i)), sb);
                }
                sb.append('}');
            } else if (n.isArray()) {
                sb.append('[');
                for (int i = 0; i < n.size(); i++) {
                    if (i > 0) sb.append(',');
                    canonical(n.get(i), sb);
                }
                sb.append(']');
            } else {
                sb.append(MAPPER.writeValueAsString(n));
            }
        } catch (IOException e) {
            throw new StorageException(StorageException.Code.IO_FAILURE, "The document could not be encoded", e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StorageException corrupt(String why, Throwable cause) {
        return new StorageException(StorageException.Code.CORRUPT, "The stored document is damaged: " + why, cause);
    }
}
