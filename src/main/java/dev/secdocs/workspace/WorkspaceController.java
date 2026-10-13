package dev.secdocs.workspace;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceController {
    private final WorkspaceService workspace;

    public WorkspaceController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/api/workspace")
    public WorkspaceResponse workspace() throws IOException {
        return workspace.read();
    }
}
