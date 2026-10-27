package com.evminsight.baseline;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/baselines")
public class BaselineController {
    private final BaselineService service;

    public BaselineController(BaselineService service) { this.service = service; }

    @GetMapping
    public List<BaselineSummary> list(@PathVariable String projectId) { return service.list(projectId); }

    /** Snapshot the current plan as a PROPOSED baseline for review. */
    @PostMapping
    public ResponseEntity<Baseline> propose(@PathVariable String projectId) {
        Baseline b = service.propose(projectId);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/baselines/" + b.id())).body(b);
    }

    @GetMapping("/{baselineId}")
    public Baseline get(@PathVariable String projectId, @PathVariable String baselineId) { return service.get(projectId, baselineId); }

    @PostMapping("/{baselineId}/approve")
    public Baseline approve(@PathVariable String projectId, @PathVariable String baselineId) { return service.approve(projectId, baselineId); }

    @DeleteMapping("/{baselineId}")
    public ResponseEntity<Void> discard(@PathVariable String projectId, @PathVariable String baselineId) {
        service.discard(projectId, baselineId);
        return ResponseEntity.noContent().build();
    }
}
