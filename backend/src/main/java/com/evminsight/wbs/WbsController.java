package com.evminsight.wbs;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/wbs")
public class WbsController {
    private final WbsService service;

    public WbsController(WbsService service) { this.service = service; }

    @GetMapping
    public List<WbsItem> list(@PathVariable String projectId) { return service.list(projectId); }

    @PostMapping
    public ResponseEntity<WbsItem> create(@PathVariable String projectId, @RequestBody WbsRequest r) {
        WbsItem i = service.create(projectId, r);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/wbs/" + i.id())).body(i);
    }

    @PutMapping("/{itemId}")
    public WbsItem update(@PathVariable String projectId, @PathVariable String itemId, @RequestBody WbsRequest r) {
        return service.update(projectId, itemId, r);
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String itemId) {
        service.delete(projectId, itemId);
        return ResponseEntity.noContent().build();
    }
}
