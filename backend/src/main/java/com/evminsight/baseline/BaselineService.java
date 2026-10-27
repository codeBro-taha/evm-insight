package com.evminsight.baseline;

import com.evminsight.audit.AuditService;
import com.evminsight.common.Nodes;
import com.evminsight.project.Project;
import com.evminsight.project.ProjectService;
import com.evminsight.storage.FileStore;
import com.evminsight.wbs.WbsItem;
import com.evminsight.wbs.WbsKind;
import com.evminsight.wbs.WbsService;
import com.evminsight.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * FR-09: propose a plan, review it, explicitly approve it as a versioned baseline.
 * Storage id = {projectId}.{baselineId}. There is deliberately no update path for approved baselines.
 */
@Service
public class BaselineService {
    static final String TYPE = "baselines";
    private final FileStore store;
    private final Clock clock;
    private final ProjectService projects;
    private final WbsService wbs;
    private final AuditService audit;

    public BaselineService(FileStore store, Clock clock, ProjectService projects, WbsService wbs, AuditService audit) {
        this.store = store; this.clock = clock; this.projects = projects; this.wbs = wbs; this.audit = audit;
    }

    public synchronized Baseline propose(String projectId) {
        Project p = projects.requireActive(projectId);
        if (all(projectId).stream().anyMatch(b -> b.status() == BaselineStatus.PROPOSED))
            throw ApiException.conflict("PROPOSAL_EXISTS", "A proposed baseline is already waiting for review; approve or discard it first");
        List<WbsItem> items = wbs.list(projectId);
        BigDecimal bac = items.stream().filter(i -> i.kind() == WbsKind.WORK_PACKAGE).map(WbsItem::budget).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (items.stream().noneMatch(i -> i.kind() == WbsKind.WORK_PACKAGE))
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "NO_WORK_PACKAGES", "Add at least one work package before proposing a baseline", Map.of());
        if (bac.signum() <= 0)
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "NO_BUDGET", "The total budget must be greater than zero", Map.of());
        Baseline b = new Baseline(UUID.randomUUID().toString(), projectId, null, BaselineStatus.PROPOSED, clock.instant(), null, null,
                p.currency(), p.plannedStart(), p.plannedFinish(), bac, items);
        save(b);
        return b;
    }

    public synchronized Baseline approve(String projectId, String baselineId) {
        projects.requireActive(projectId);
        Baseline b = get(projectId, baselineId);
        if (b.status() != BaselineStatus.PROPOSED) throw ApiException.conflict("BASELINE_ALREADY_APPROVED", "This baseline is already approved and cannot change");
        List<Baseline> approved = all(projectId).stream().filter(x -> x.status() == BaselineStatus.APPROVED)
                .sorted(Comparator.comparing(Baseline::version)).toList();
        int next = approved.isEmpty() ? 1 : approved.get(approved.size() - 1).version() + 1;
        String supersedes = approved.isEmpty() ? null : approved.get(approved.size() - 1).id();
        Baseline a = new Baseline(b.id(), projectId, next, BaselineStatus.APPROVED, b.createdAt(), clock.instant(), supersedes,
                b.currency(), b.projectPlannedStart(), b.projectPlannedFinish(), b.bac(), b.items());
        save(a);
        audit.record(next == 1 ? "BASELINE_APPROVED" : "BASELINE_REVISED", "baseline", a.id(),
                "version " + next + ", BAC " + a.bac().toPlainString() + " " + a.currency() + (supersedes == null ? "" : ", supersedes " + supersedes));
        return a;
    }

    public synchronized void discard(String projectId, String baselineId) {
        projects.requireActive(projectId);
        Baseline b = get(projectId, baselineId);
        if (b.status() != BaselineStatus.PROPOSED) throw ApiException.conflict("BASELINE_IMMUTABLE", "Approved baselines cannot be deleted");
        store.delete(TYPE, projectId + "." + baselineId);
        audit.record("BASELINE_PROPOSAL_DISCARDED", "baseline", baselineId, "proposal discarded");
    }

    public List<BaselineSummary> list(String projectId) {
        projects.get(projectId);
        return all(projectId).stream().map(Baseline::summary).toList();
    }

    public Baseline get(String projectId, String baselineId) {
        projects.get(projectId);
        return store.get(TYPE, projectId + "." + baselineId).map(d -> fromNode(d.id(), d.data())).orElseThrow(() -> ApiException.notFound("Baseline"));
    }

    private List<Baseline> all(String projectId) {
        List<Baseline> out = new ArrayList<>();
        for (String id : store.list(TYPE))
            if (id.startsWith(projectId + ".")) out.add(fromNode(id, store.get(TYPE, id).orElseThrow().data()));
        out.sort(Comparator.comparing(Baseline::createdAt));
        return out;
    }

    private void save(Baseline b) {
        ObjectNode n = FileStore.newObject();
        n.put("baselineId", b.id());
        n.put("projectId", b.projectId());
        if (b.version() != null) n.put("version", b.version());
        n.put("status", b.status().name());
        n.put("createdAt", b.createdAt().toString());
        if (b.approvedAt() != null) n.put("approvedAt", b.approvedAt().toString());
        if (b.supersedesId() != null) n.put("supersedesId", b.supersedesId());
        n.put("currency", b.currency());
        n.put("projectPlannedStart", b.projectPlannedStart().toString());
        n.put("projectPlannedFinish", b.projectPlannedFinish().toString());
        n.put("bac", b.bac().toPlainString());
        ArrayNode arr = n.putArray("items");
        b.items().forEach(i -> arr.add(i.toNode()));
        store.put(TYPE, b.projectId() + "." + b.id(), n);
    }

    private static Baseline fromNode(String storageId, ObjectNode n) {
        List<WbsItem> items = new ArrayList<>();
        for (JsonNode i : n.path("items")) items.add(WbsItem.fromNode(i));
        return new Baseline(Nodes.text(n, "baselineId"), Nodes.text(n, "projectId"), Nodes.integer(n, "version"),
                BaselineStatus.valueOf(Nodes.text(n, "status")), Nodes.instant(n, "createdAt"), Nodes.instant(n, "approvedAt"),
                Nodes.text(n, "supersedesId"), Nodes.text(n, "currency"), Nodes.date(n, "projectPlannedStart"),
                Nodes.date(n, "projectPlannedFinish"), Nodes.decimal(n, "bac"), items);
    }
}
