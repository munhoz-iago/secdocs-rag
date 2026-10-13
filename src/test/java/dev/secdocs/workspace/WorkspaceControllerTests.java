package dev.secdocs.workspace;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.secdocs.ingest.SourceCatalog;

class WorkspaceControllerTests {
    @Test
    void getWorkspaceServesThePublishedContractOffline() throws Exception {
        // The complete request is handled with no search, database, or Ollama collaborators.
        var mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(new WorkspaceService())).build();

        mvc.perform(get("/api/workspace"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sources", hasSize(30)))
                .andExpect(jsonPath("$.sources[0].id").value("sql-injection"))
                .andExpect(jsonPath("$.sources[0].title").value("SQL Injection Prevention Cheat Sheet"))
                .andExpect(jsonPath("$.sources[0].family").value("CheatSheetSeries"))
                .andExpect(jsonPath("$.sources[0].revision").value("29994dd8a2e6f50fa3d5607b046b54d7c6945afd"))
                .andExpect(jsonPath("$.sources[0].url").value("https://github.com/OWASP/CheatSheetSeries/blob/"
                        + "29994dd8a2e6f50fa3d5607b046b54d7c6945afd/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.md"))
                .andExpect(jsonPath("$.sources[0].license").value("CC-BY-SA-4.0"))
                .andExpect(jsonPath("$.evaluation.available").value(true))
                .andExpect(jsonPath("$.evaluation.generatedAt").value("2026-10-10T00:34:53.545468Z"))
                .andExpect(jsonPath("$.evaluation.totalQuestions").value(45))
                .andExpect(jsonPath("$.evaluation.answerableQuestions").value(40))
                .andExpect(jsonPath("$.evaluation.unanswerableQuestions").value(5))
                .andExpect(jsonPath("$.evaluation.translatedQuestions").value(15))
                .andExpect(jsonPath("$.evaluation.datasetKind").value("development"))
                .andExpect(jsonPath("$.evaluation.dataSource").value("published-snapshot"))
                .andExpect(jsonPath("$.evaluation.metrics", hasSize(3)))
                .andExpect(jsonPath("$.evaluation.metrics[0].mode").value("vector"))
                .andExpect(jsonPath("$.evaluation.metrics[0].label").value("Vetorial"))
                .andExpect(jsonPath("$.evaluation.metrics[0].hits").value(39))
                .andExpect(jsonPath("$.evaluation.metrics[0].total").value(40))
                .andExpect(jsonPath("$.evaluation.metrics[0].recallAt5").value(0.975))
                .andExpect(jsonPath("$.evaluation.metrics[1].mode").value("keyword"))
                .andExpect(jsonPath("$.evaluation.metrics[1].label").value("Textual"))
                .andExpect(jsonPath("$.evaluation.metrics[2].mode").value("hybrid"))
                .andExpect(jsonPath("$.evaluation.metrics[2].label").value("Híbrida"))
                .andExpect(jsonPath("$.evaluation.cases", hasSize(45)))
                .andExpect(jsonPath("$.evaluation.cases[0].vectorHit").value(true))
                .andExpect(jsonPath("$.evaluation.cases[0].keywordHit").value(false))
                .andExpect(jsonPath("$.evaluation.cases[0].hybridHit").value(true))
                .andExpect(jsonPath("$.evaluation.cases[0].vectorTop5", hasSize(5)))
                .andExpect(jsonPath("$.evaluation.cases[0].keywordTop5", hasSize(5)))
                .andExpect(jsonPath("$.evaluation.cases[0].hybridTop5", hasSize(5)))
                .andExpect(jsonPath("$.evaluation.cases[40].answerable").value(false))
                .andExpect(jsonPath("$.evaluation.cases[40].vectorHit").value(nullValue()))
                .andExpect(jsonPath("$.generationEvaluation.available").isBoolean());
    }

    @Test
    void getWorkspaceRemainsSuccessfulWhenBothSnapshotsAreMissing() throws Exception {
        var missing = new ClassPathResource("evals/workspace-test-missing.json");
        var service = new WorkspaceService(new SourceCatalog(), missing, missing, missing);
        var mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(service)).build();

        mvc.perform(get("/api/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasSize(30)))
                .andExpect(jsonPath("$.evaluation.available").value(false))
                .andExpect(jsonPath("$.evaluation.totalQuestions").value(nullValue()))
                .andExpect(jsonPath("$.evaluation.metrics", hasSize(0)))
                .andExpect(jsonPath("$.evaluation.cases", hasSize(0)))
                .andExpect(jsonPath("$.evaluation.description").isNotEmpty())
                .andExpect(jsonPath("$.generationEvaluation.available").value(false))
                .andExpect(jsonPath("$.generationEvaluation.description").isNotEmpty())
                .andExpect(jsonPath("$.generationBaseline.available").value(false))
                .andExpect(jsonPath("$.generationBaseline.description").isNotEmpty());
    }

    @Test
    void getWorkspaceReportsCorruptSnapshotsWithoutFailingTheEndpoint() throws Exception {
        var corrupt = new ByteArrayResource("{invalid JSON".getBytes(StandardCharsets.UTF_8));
        var service = new WorkspaceService(new SourceCatalog(), corrupt, corrupt, corrupt);
        var mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(service)).build();

        mvc.perform(get("/api/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasSize(30)))
                .andExpect(jsonPath("$.evaluation.available").value(false))
                .andExpect(jsonPath("$.evaluation.metrics", hasSize(0)))
                .andExpect(jsonPath("$.generationEvaluation.available").value(false))
                .andExpect(jsonPath("$.generationBaseline.available").value(false));
    }
}
