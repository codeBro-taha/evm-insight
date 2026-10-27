package com.evminsight.project;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Project> create(@RequestBody ProjectRequest r) {
        Project p = service.create(r);
        return ResponseEntity.created(URI.create("/api/projects/" + p.id())).body(p);
    }

    @GetMapping
    public List<Project> list(@RequestParam(defaultValue = "false") boolean includeArchived) { return service.list(includeArchived); }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) { return service.get(id); }

    @PutMapping("/{id}")
    public Project update(@PathVariable String id, @RequestBody ProjectRequest r) { return service.update(id, r); }

    @PostMapping("/{id}/archive")
    public Project archive(@PathVariable String id) { return service.setArchived(id, true); }

    @PostMapping("/{id}/unarchive")
    public Project unarchive(@PathVariable String id) { return service.setArchived(id, false); }
}
