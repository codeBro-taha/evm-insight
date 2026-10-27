package com.evminsight.wbs;

import com.evminsight.common.Nodes;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.evminsight.storage.FileStore;
import java.math.BigDecimal;
import java.time.LocalDate;

public record WbsItem(String id, String parentId, WbsKind kind, String name, String description,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal budget, LocalDate plannedStart, LocalDate plannedFinish) {

    public ObjectNode toNode() {
        ObjectNode n = FileStore.newObject();
        n.put("id", id);
        if (parentId != null) n.put("parentId", parentId);
        n.put("kind", kind.name());
        n.put("name", name);
        if (description != null) n.put("description", description);
        if (budget != null) n.put("budget", budget.toPlainString());
        if (plannedStart != null) n.put("plannedStart", plannedStart.toString());
        if (plannedFinish != null) n.put("plannedFinish", plannedFinish.toString());
        return n;
    }

    public static WbsItem fromNode(JsonNode n) {
        return new WbsItem(Nodes.text(n, "id"), Nodes.text(n, "parentId"), WbsKind.valueOf(Nodes.text(n, "kind")), Nodes.text(n, "name"),
                Nodes.text(n, "description"), Nodes.decimal(n, "budget"), Nodes.date(n, "plannedStart"), Nodes.date(n, "plannedFinish"));
    }
}
