package com.evminsight.project;

import com.evminsight.audit.AuditService;
import com.evminsight.common.Money;
import com.evminsight.common.Nodes;
import com.evminsight.storage.FileStore;
import com.evminsight.web.ApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** FR-07: create, view, edit, archive projects. Currency is fixed at creation (amounts would otherwise be reinterpreted). */
@Service
public class ProjectService {
    static final String TYPE = "projects";
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9._-]{1,30}");
    private final FileStore store;
    private final Clock clock;
    private final AuditService audit;

    public ProjectService(FileStore store, Clock clock, AuditService audit) { this.store = store; this.clock = clock; this.audit = audit; }

    public synchronized Project create(ProjectRequest r) {
        validate(r);
        ensureIdentifierFree(r.identifier().trim(), null);
        Instant now = clock.instant();
        Project p = new Project(UUID.randomUUID().toString(), r.identifier().trim(), r.name().trim(), blankToNull(r.description()),
                r.plannedStart(), r.plannedFinish(), r.currency(), false, now, now);
        save(p);
        audit.record("PROJECT_CREATED", "project", p.id(), p.identifier());
        return p;
    }

    public Project get(String id) {
        return store.get(TYPE, id).map(d -> fromNode(d.id(), d.data())).orElseThrow(() -> ApiException.notFound("Project"));
    }

    public Project requireActive(String id) {
        Project p = get(id);
        if (p.archived()) throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived; restore it before changing it");
        return p;
    }

    public List<Project> list(boolean includeArchived) {
        List<Project> out = new ArrayList<>();
        for (String id : store.list(TYPE)) {
            Project p = get(id);
            if (includeArchived || !p.archived()) out.add(p);
        }
        out.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return out;
    }

    public synchronized Project update(String id, ProjectRequest r) {
        Project old = requireActive(id);
        validate(r);
        if (!old.currency().equals(r.currency()))
            throw ApiException.conflict("CURRENCY_LOCKED", "The project currency cannot be changed after creation");
        ensureIdentifierFree(r.identifier().trim(), id);
        Project p = new Project(id, r.identifier().trim(), r.name().trim(), blankToNull(r.description()), r.plannedStart(),
                r.plannedFinish(), old.currency(), false, old.createdAt(), clock.instant());
        save(p);
        return p;
    }

    public synchronized Project setArchived(String id, boolean archived) {
        Project old = get(id);
        if (old.archived() == archived)
            throw ApiException.conflict(archived ? "ALREADY_ARCHIVED" : "NOT_ARCHIVED", archived ? "The project is already archived" : "The project is not archived");
        Project p = new Project(id, old.identifier(), old.name(), old.description(), old.plannedStart(), old.plannedFinish(),
                old.currency(), archived, old.createdAt(), clock.instant());
        save(p);
        audit.record(archived ? "PROJECT_ARCHIVED" : "PROJECT_RESTORED", "project", id, old.identifier());
        return p;
    }

    // ---- internals
    private void validate(ProjectRequest r) {
        Map<String, String> e = new LinkedHashMap<>();
        if (blank(r.identifier())) e.put("identifier", "Required");
        else if (!IDENT.matcher(r.identifier().trim()).matches()) e.put("identifier", "Use 1-30 letters, digits, '.', '_' or '-'");
        if (blank(r.name())) e.put("name", "Required");
        else if (r.name().trim().length() > 200) e.put("name", "At most 200 characters");
        if (r.description() != null && r.description().length() > 4000) e.put("description", "At most 4000 characters");
        if (r.plannedStart() == null) e.put("plannedStart", "Required (YYYY-MM-DD)");
        if (r.plannedFinish() == null) e.put("plannedFinish", "Required (YYYY-MM-DD)");
        if (r.plannedStart() != null && r.plannedFinish() != null && r.plannedFinish().isBefore(r.plannedStart()))
            e.put("plannedFinish", "Must not be before the planned start");
        if (blank(r.currency())) e.put("currency", "Required (ISO 4217 code such as USD)");
        else if (!Money.isValidCurrency(r.currency())) e.put("currency", "Not a valid ISO 4217 currency code (use upper case, e.g. USD)");
        if (!e.isEmpty()) throw ApiException.validation(e);
    }

    private void ensureIdentifierFree(String identifier, String selfId) {
        for (String id : store.list(TYPE))
            if (!id.equals(selfId) && get(id).identifier().equalsIgnoreCase(identifier))
                throw ApiException.conflict("DUPLICATE_IDENTIFIER", "Another project already uses this identifier");
    }

    private void save(Project p) {
        ObjectNode n = FileStore.newObject();
        n.put("identifier", p.identifier());
        n.put("name", p.name());
        if (p.description() != null) n.put("description", p.description());
        n.put("plannedStart", p.plannedStart().toString());
        n.put("plannedFinish", p.plannedFinish().toString());
        n.put("currency", p.currency());
        n.put("archived", p.archived());
        n.put("createdAt", p.createdAt().toString());
        n.put("updatedAt", p.updatedAt().toString());
        store.put(TYPE, p.id(), n);
    }

    private static Project fromNode(String id, ObjectNode n) {
        return new Project(id, Nodes.text(n, "identifier"), Nodes.text(n, "name"), Nodes.text(n, "description"),
                Nodes.date(n, "plannedStart"), Nodes.date(n, "plannedFinish"), Nodes.text(n, "currency"),
                Nodes.bool(n, "archived"), Nodes.instant(n, "createdAt"), Nodes.instant(n, "updatedAt"));
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return blank(s) ? null : s.trim(); }
}
