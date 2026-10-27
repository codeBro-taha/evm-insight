package com.evminsight.wbs;

import com.evminsight.common.Money;
import com.evminsight.project.Project;
import com.evminsight.project.ProjectService;
import com.evminsight.storage.FileStore;
import com.evminsight.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** FR-08: hierarchical WBS. Only WORK_PACKAGE leaves carry budgets; invalid and circular hierarchies are rejected. */
@Service
public class WbsService {
    static final String TYPE = "wbs";
    private final FileStore store;
    private final ProjectService projects;

    public WbsService(FileStore store, ProjectService projects) { this.store = store; this.projects = projects; }

    public synchronized List<WbsItem> list(String projectId) {
        projects.get(projectId);
        return load(projectId);
    }

    public synchronized WbsItem create(String projectId, WbsRequest r) {
        Project p = projects.requireActive(projectId);
        List<WbsItem> items = load(projectId);
        WbsItem item = build(p, items, UUID.randomUUID().toString(), r, true);
        items.add(item);
        save(projectId, items);
        return item;
    }

    public synchronized WbsItem update(String projectId, String itemId, WbsRequest r) {
        Project p = projects.requireActive(projectId);
        List<WbsItem> items = load(projectId);
        int idx = indexOf(items, itemId);
        if (idx < 0) throw ApiException.notFound("Work item");
        WbsItem item = build(p, items, itemId, r, false);
        items.set(idx, item);
        save(projectId, items);
        return item;
    }

    public synchronized void delete(String projectId, String itemId) {
        projects.requireActive(projectId);
        List<WbsItem> items = load(projectId);
        int idx = indexOf(items, itemId);
        if (idx < 0) throw ApiException.notFound("Work item");
        if (items.stream().anyMatch(i -> itemId.equals(i.parentId())))
            throw ApiException.conflict("HAS_CHILDREN", "Delete or move the child items first");
        items.remove(idx);
        save(projectId, items);
    }

    private WbsItem build(Project p, List<WbsItem> items, String id, WbsRequest r, boolean isNew) {
        Map<String, String> e = new LinkedHashMap<>();
        if (r.kind() == null) e.put("kind", "Required (GROUP or WORK_PACKAGE)");
        if (r.name() == null || r.name().isBlank()) e.put("name", "Required");
        else if (r.name().trim().length() > 200) e.put("name", "At most 200 characters");
        if (r.description() != null && r.description().length() > 4000) e.put("description", "At most 4000 characters");

        String parentId = r.parentId() == null || r.parentId().isBlank() ? null : r.parentId();
        if (parentId != null) {
            if (parentId.equals(id)) throw circular();
            WbsItem parent = find(items, parentId);
            if (parent == null) e.put("parentId", "The parent item does not exist");
            else if (parent.kind() != WbsKind.GROUP) e.put("parentId", "The parent must be a group, not a work package");
            else if (!isNew) {
                Set<String> seen = new HashSet<>();
                for (WbsItem cur = parent; cur != null && seen.add(cur.id()); cur = cur.parentId() == null ? null : find(items, cur.parentId()))
                    if (cur.id().equals(id)) throw circular();
            }
        }
        if (r.kind() == WbsKind.WORK_PACKAGE && !isNew && items.stream().anyMatch(i -> id.equals(i.parentId())))
            e.put("kind", "A group that has children cannot become a work package");

        if (r.kind() == WbsKind.WORK_PACKAGE) {
            if (r.budget() == null) e.put("budget", "Required");
            else if (r.budget().signum() < 0) e.put("budget", "Must not be negative");
            else if (!Money.fits(r.budget(), p.currency()))
                e.put("budget", "Too many decimal places for " + p.currency() + " (max " + Money.digits(p.currency()) + ")");
            if (r.plannedStart() == null) e.put("plannedStart", "Required (YYYY-MM-DD)");
            if (r.plannedFinish() == null) e.put("plannedFinish", "Required (YYYY-MM-DD)");
            if (r.plannedStart() != null && r.plannedFinish() != null && r.plannedFinish().isBefore(r.plannedStart()))
                e.put("plannedFinish", "Must not be before the planned start");
        } else if (r.kind() == WbsKind.GROUP) {
            if (r.budget() != null) e.put("budget", "Groups carry no budget; give budgets to work packages only");
            if (r.plannedStart() != null || r.plannedFinish() != null) e.put("plannedStart", "Groups carry no dates; give dates to work packages only");
        }
        if (!e.isEmpty()) throw ApiException.validation(e);

        boolean wp = r.kind() == WbsKind.WORK_PACKAGE;
        return new WbsItem(id, parentId, r.kind(), r.name().trim(), r.description() == null || r.description().isBlank() ? null : r.description().trim(),
                wp ? Money.normalise(r.budget(), p.currency()) : null, wp ? r.plannedStart() : null, wp ? r.plannedFinish() : null);
    }

    private static ApiException circular() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CIRCULAR_HIERARCHY", "An item cannot be placed under itself or one of its descendants",
                Map.of("parentId", "Would create a circular reference"));
    }

    private List<WbsItem> load(String projectId) {
        List<WbsItem> out = new ArrayList<>();
        store.get(TYPE, projectId).ifPresent(d -> { for (JsonNode n : d.data().path("items")) out.add(WbsItem.fromNode(n)); });
        return out;
    }

    private void save(String projectId, List<WbsItem> items) {
        ObjectNode n = FileStore.newObject();
        ArrayNode arr = n.putArray("items");
        items.forEach(i -> arr.add(i.toNode()));
        store.put(TYPE, projectId, n);
    }

    private static WbsItem find(List<WbsItem> items, String id) { return items.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null); }
    private static int indexOf(List<WbsItem> items, String id) { for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i; return -1; }
}
