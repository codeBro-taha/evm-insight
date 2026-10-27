package com.evminsight.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** API tests: status codes, validation, hierarchy rules, baseline versioning/immutability, audit, safe errors (FR-07..FR-09, FR-20, FR-21). */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("evm-api-test"); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { r.add("evm.data-dir", DATA::toString); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ------------------------------------------------------------ helpers
    private JsonNode call(MockHttpServletRequestBuilder b, int expected) throws Exception {
        String body = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? null : om.readTree(body);
    }
    private JsonNode doPost(String url, Object body, int st) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : om.writeValueAsString(body)), st);
    }
    private JsonNode doPut(String url, Object body, int st) throws Exception {
        return call(put(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)), st);
    }
    private JsonNode doGet(String url, int st) throws Exception { return call(get(url), st); }

    private ObjectNode projectBody(String ident, String currency) {
        return om.createObjectNode().put("identifier", ident).put("name", "Project " + ident).put("description", "d")
                .put("plannedStart", "2026-01-01").put("plannedFinish", "2026-12-31").put("currency", currency);
    }
    private String newProject(String currency) throws Exception {
        return doPost("/api/projects", projectBody("P" + UUID.randomUUID().toString().substring(0, 8), currency), 201).get("id").asText();
    }
    private ObjectNode wbsBody(String parent, String kind, String name, String budget, String start, String finish) {
        ObjectNode n = om.createObjectNode().put("kind", kind).put("name", name);
        if (parent != null) n.put("parentId", parent);
        if (budget != null) n.put("budget", budget);
        if (start != null) n.put("plannedStart", start);
        if (finish != null) n.put("plannedFinish", finish);
        return n;
    }
    private String group(String pid, String parent, String name) throws Exception {
        return doPost("/api/projects/" + pid + "/wbs", wbsBody(parent, "GROUP", name, null, null, null), 201).get("id").asText();
    }
    private String wp(String pid, String parent, String name, String budget) throws Exception {
        return doPost("/api/projects/" + pid + "/wbs", wbsBody(parent, "WORK_PACKAGE", name, budget, "2026-02-01", "2026-03-01"), 201).get("id").asText();
    }
    private boolean auditHas(String action, String targetId) throws Exception {
        for (JsonNode e : doGet("/api/audit?limit=1000", 200))
            if (action.equals(e.get("action").asText()) && targetId.equals(e.get("targetId").asText())) return true;
        return false;
    }

    // ------------------------------------------------------------ projects (FR-07, FR-12)
    @Test @DisplayName("create/get project, Unicode names supported")
    void createAndGetProject() throws Exception {
        ObjectNode body = projectBody("U" + UUID.randomUUID().toString().substring(0, 6), "EUR").put("name", "Проект Ünïcödé 项目");
        JsonNode created = doPost("/api/projects", body, 201);
        assertEquals("Проект Ünïcödé 项目", created.get("name").asText());
        assertEquals("EUR", created.get("currency").asText());
        assertFalse(created.get("archived").asBoolean());
        assertEquals(created.get("name"), doGet("/api/projects/" + created.get("id").asText(), 200).get("name"));
    }

    @Test void projectValidationErrorsAreFieldSpecific() throws Exception {
        JsonNode r = doPost("/api/projects", om.createObjectNode().put("identifier", "").put("plannedStart", "2026-05-01").put("plannedFinish", "2026-04-01").put("currency", "usd"), 400);
        assertEquals("VALIDATION_FAILED", r.get("code").asText());
        for (String f : new String[] {"identifier", "name", "plannedFinish", "currency"}) assertTrue(r.get("fieldErrors").has(f), f);
    }

    @Test void invalidCurrencyCodesRejected() throws Exception {
        for (String c : new String[] {"ABC", "usd", "US", "USDX", "XXX"})
            assertTrue(doPost("/api/projects", projectBody("C" + UUID.randomUUID().toString().substring(0, 6), c), 400).get("fieldErrors").has("currency"), c);
    }

    @Test void duplicateIdentifierRejectedCaseInsensitively() throws Exception {
        String ident = "D" + UUID.randomUUID().toString().substring(0, 6);
        doPost("/api/projects", projectBody(ident, "USD"), 201);
        assertEquals("DUPLICATE_IDENTIFIER", doPost("/api/projects", projectBody(ident.toLowerCase(), "USD"), 409).get("code").asText());
    }

    @Test void unknownProjectIs404AndBadIdIs400() throws Exception {
        assertEquals("NOT_FOUND", doGet("/api/projects/" + UUID.randomUUID(), 404).get("code").asText());
        assertEquals("STORAGE_INVALID_NAME", call(get("/api/projects/{id}", "a b"), 400).get("code").asText());
    }

    @Test void updateAllowedButCurrencyLocked() throws Exception {
        String id = newProject("USD");
        ObjectNode upd = projectBody("X" + UUID.randomUUID().toString().substring(0, 6), "USD").put("name", "Renamed");
        assertEquals("Renamed", doPut("/api/projects/" + id, upd, 200).get("name").asText());
        upd.put("currency", "EUR");
        assertEquals("CURRENCY_LOCKED", doPut("/api/projects/" + id, upd, 409).get("code").asText());
    }

    @Test @DisplayName("archive hides from default list, blocks edits, is audited; restore works")
    void archiveAndRestore() throws Exception {
        String id = newProject("USD");
        doPost("/api/projects/" + id + "/archive", "{}", 200);
        assertTrue(auditHas("PROJECT_ARCHIVED", id));
        boolean inDefault = false, inAll = false;
        for (JsonNode p : doGet("/api/projects", 200)) inDefault |= p.get("id").asText().equals(id);
        for (JsonNode p : doGet("/api/projects?includeArchived=true", 200)) inAll |= p.get("id").asText().equals(id);
        assertFalse(inDefault); assertTrue(inAll);
        assertEquals("PROJECT_ARCHIVED", doPut("/api/projects/" + id, projectBody("Z" + UUID.randomUUID().toString().substring(0, 6), "USD"), 409).get("code").asText());
        assertEquals("PROJECT_ARCHIVED", doPost("/api/projects/" + id + "/wbs", wbsBody(null, "GROUP", "g", null, null, null), 409).get("code").asText());
        assertEquals("ALREADY_ARCHIVED", doPost("/api/projects/" + id + "/archive", "{}", 409).get("code").asText());
        doPost("/api/projects/" + id + "/unarchive", "{}", 200);
        assertTrue(auditHas("PROJECT_RESTORED", id));
    }

    // ------------------------------------------------------------ safe errors (FR-21)
    @Test void malformedBodiesGiveSafeErrors() throws Exception {
        for (String bad : new String[] {"{not json", "{\"plannedStart\":\"03/04/2026\"}", "{\"plannedStart\":\"2026-13-45\"}"}) {
            String raw = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals("MALFORMED_REQUEST", om.readTree(raw).get("code").asText());
            assertFalse(raw.contains("Exception") || raw.contains("at com.") || raw.contains(DATA.toString()), raw);
        }
        call(post("/api/projects").contentType(MediaType.TEXT_PLAIN).content("x"), 415);
        assertEquals("NOT_FOUND", doGet("/api/nothing-here", 404).get("code").asText());
    }

    // ------------------------------------------------------------ WBS (FR-08, FR-12)
    @Test @DisplayName("hierarchy rules: parent must be a group; groups carry no money; work packages need budget and dates")
    void wbsRules() throws Exception {
        String pid = newProject("USD");
        String g = group(pid, null, "Phase 1");
        String w = wp(pid, g, "Design", "100.25");
        assertEquals("100.25", doGet("/api/projects/" + pid + "/wbs", 200).get(1).get("budget").asText());
        assertTrue(doPost("/api/projects/" + pid + "/wbs", wbsBody(w, "WORK_PACKAGE", "x", "1", "2026-02-01", "2026-03-01"), 400).get("fieldErrors").has("parentId"));
        assertTrue(doPost("/api/projects/" + pid + "/wbs", wbsBody(UUID.randomUUID().toString(), "GROUP", "x", null, null, null), 400).get("fieldErrors").has("parentId"));
        assertTrue(doPost("/api/projects/" + pid + "/wbs", wbsBody(null, "GROUP", "x", "5", null, null), 400).get("fieldErrors").has("budget"));
        JsonNode e = doPost("/api/projects/" + pid + "/wbs", wbsBody(g, "WORK_PACKAGE", "", null, "2026-03-01", "2026-02-01"), 400).get("fieldErrors");
        for (String f : new String[] {"name", "budget", "plannedFinish"}) assertTrue(e.has(f), f);
        assertTrue(doPost("/api/projects/" + pid + "/wbs", wbsBody(g, "WORK_PACKAGE", "neg", "-1", "2026-02-01", "2026-03-01"), 400).get("fieldErrors").has("budget"));
    }

    @Test @DisplayName("currency precision is respected: JPY has no decimals, USD max two, amounts normalised")
    void currencyPrecision() throws Exception {
        String jpy = newProject("JPY");
        assertTrue(doPost("/api/projects/" + jpy + "/wbs", wbsBody(null, "WORK_PACKAGE", "a", "10.5", "2026-02-01", "2026-03-01"), 400).get("fieldErrors").has("budget"));
        assertEquals("1000", doPost("/api/projects/" + jpy + "/wbs", wbsBody(null, "WORK_PACKAGE", "a", "1000", "2026-02-01", "2026-03-01"), 201).get("budget").asText());
        String usd = newProject("USD");
        assertTrue(doPost("/api/projects/" + usd + "/wbs", wbsBody(null, "WORK_PACKAGE", "a", "10.505", "2026-02-01", "2026-03-01"), 400).get("fieldErrors").has("budget"));
        assertEquals("300.00", doPost("/api/projects/" + usd + "/wbs", wbsBody(null, "WORK_PACKAGE", "a", "300", "2026-02-01", "2026-03-01"), 201).get("budget").asText());
    }

    @Test void circularReferencesRejected() throws Exception {
        String pid = newProject("USD");
        String a = group(pid, null, "A");
        String b = group(pid, a, "B");
        String c = group(pid, b, "C");
        for (String newParent : new String[] {a, b, c}) {   // self, child, grandchild
            JsonNode r = doPut("/api/projects/" + pid + "/wbs/" + a, wbsBody(newParent, "GROUP", "A", null, null, null), 400);
            assertEquals("CIRCULAR_HIERARCHY", r.get("code").asText(), newParent);
        }
        doPut("/api/projects/" + pid + "/wbs/" + c, wbsBody(null, "GROUP", "C", null, null, null), 200);   // legal re-parent
    }

    @Test void deleteRulesAndKindChange() throws Exception {
        String pid = newProject("USD");
        String g = group(pid, null, "G");
        String w = wp(pid, g, "W", "10");
        assertEquals("HAS_CHILDREN", call(delete("/api/projects/" + pid + "/wbs/" + g), 409).get("code").asText());
        assertTrue(doPut("/api/projects/" + pid + "/wbs/" + g, wbsBody(null, "WORK_PACKAGE", "G", "1", "2026-02-01", "2026-03-01"), 400).get("fieldErrors").has("kind"));
        call(delete("/api/projects/" + pid + "/wbs/" + w), 204);
        call(delete("/api/projects/" + pid + "/wbs/" + g), 204);
        call(delete("/api/projects/" + pid + "/wbs/" + g), 404);
    }

    // ------------------------------------------------------------ baselines (FR-09, FR-20)
    @Test void cannotProposeEmptyOrZeroBudgetPlan() throws Exception {
        String pid = newProject("USD");
        assertEquals("NO_WORK_PACKAGES", doPost("/api/projects/" + pid + "/baselines", "{}", 400).get("code").asText());
        group(pid, null, "G");
        wp(pid, null, "zero", "0");
        assertEquals("NO_BUDGET", doPost("/api/projects/" + pid + "/baselines", "{}", 400).get("code").asText());
    }

    @Test @DisplayName("BAC counts leaf work packages only (no parent/child double counting)")
    void bacHasNoDoubleCounting() throws Exception {
        String pid = newProject("USD");
        String g = group(pid, null, "G");
        wp(pid, g, "A", "100.25");
        wp(pid, g, "B", "200.25");
        JsonNode b = doPost("/api/projects/" + pid + "/baselines", "{}", 201);
        assertEquals("300.50", b.get("bac").asText());
        assertEquals("PROPOSED", b.get("status").asText());
        assertTrue(b.get("version").isNull() || !b.has("version"));
    }

    @Test @DisplayName("proposal -> approval -> revision: versions increase, approved baselines are immutable, edits create new versions, all audited")
    void baselineLifecycle() throws Exception {
        String pid = newProject("USD");
        String w1 = wp(pid, null, "A", "100.00");
        JsonNode p1 = doPost("/api/projects/" + pid + "/baselines", "{}", 201);
        String b1 = p1.get("id").asText();
        assertEquals("PROPOSAL_EXISTS", doPost("/api/projects/" + pid + "/baselines", "{}", 409).get("code").asText());

        JsonNode a1 = doPost("/api/projects/" + pid + "/baselines/" + b1 + "/approve", "{}", 200);
        assertEquals(1, a1.get("version").asInt());
        assertEquals("APPROVED", a1.get("status").asText());
        assertFalse(a1.get("approvedAt").isNull());
        assertTrue(auditHas("BASELINE_APPROVED", b1));
        assertEquals("BASELINE_ALREADY_APPROVED", doPost("/api/projects/" + pid + "/baselines/" + b1 + "/approve", "{}", 409).get("code").asText());
        assertEquals("BASELINE_IMMUTABLE", call(delete("/api/projects/" + pid + "/baselines/" + b1), 409).get("code").asText());
        assertEquals("METHOD_NOT_ALLOWED", call(put("/api/projects/" + pid + "/baselines/" + b1).contentType(MediaType.APPLICATION_JSON).content("{}"), 405).get("code").asText());

        // revise the plan: the original baseline must not change
        doPut("/api/projects/" + pid + "/wbs/" + w1, wbsBody(null, "WORK_PACKAGE", "A", "250.00", "2026-02-01", "2026-03-01"), 200);
        assertEquals("100.00", doGet("/api/projects/" + pid + "/baselines/" + b1, 200).get("bac").asText());
        JsonNode p2 = doPost("/api/projects/" + pid + "/baselines", "{}", 201);
        String b2 = p2.get("id").asText();
        JsonNode a2 = doPost("/api/projects/" + pid + "/baselines/" + b2 + "/approve", "{}", 200);
        assertEquals(2, a2.get("version").asInt());
        assertEquals(b1, a2.get("supersedesId").asText());
        assertEquals("250.00", a2.get("bac").asText());
        assertTrue(auditHas("BASELINE_REVISED", b2));
        JsonNode original = doGet("/api/projects/" + pid + "/baselines/" + b1, 200);
        assertEquals(1, original.get("version").asInt());
        assertEquals(a1.get("approvedAt"), original.get("approvedAt"));
        assertEquals(2, doGet("/api/projects/" + pid + "/baselines", 200).size());
    }

    @Test void discardProposalOnly() throws Exception {
        String pid = newProject("USD");
        wp(pid, null, "A", "10");
        String b = doPost("/api/projects/" + pid + "/baselines", "{}", 201).get("id").asText();
        call(delete("/api/projects/" + pid + "/baselines/" + b), 204);
        call(get("/api/projects/" + pid + "/baselines/" + b), 404);
        doPost("/api/projects/" + pid + "/baselines", "{}", 201);
    }

    @Test void archivedProjectCannotChangeBaselines() throws Exception {
        String pid = newProject("USD");
        wp(pid, null, "A", "10");
        String b = doPost("/api/projects/" + pid + "/baselines", "{}", 201).get("id").asText();
        doPost("/api/projects/" + pid + "/archive", "{}", 200);
        assertEquals("PROJECT_ARCHIVED", doPost("/api/projects/" + pid + "/baselines/" + b + "/approve", "{}", 409).get("code").asText());
    }

    @Test @DisplayName("data survives a restart: stored files are readable by a fresh store")
    void dataIsOnDisk() throws Exception {
        String pid = newProject("USD");
        assertTrue(Files.exists(DATA.resolve("data/projects/" + pid + ".json")));
    }
}
