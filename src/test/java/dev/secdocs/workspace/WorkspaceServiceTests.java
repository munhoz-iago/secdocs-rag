package dev.secdocs.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import dev.secdocs.ingest.SourceCatalog;
import dev.secdocs.workspace.WorkspaceResponse.Metric;

class WorkspaceServiceTests {
    private static final String ZERO_ANSWERABLE = """
            {
              "generatedAt": "2026-10-10T00:34:53.545468Z",
              "embeddingModel": "fixture-model",
              "answerableQuestions": 0,
              "unanswerableQuestions": 1,
              "translatedQuestions": 0,
              "corpusRevisions": {"ASVS": "61a544f0fdbb7cc1578e0c3a3037a8a2874a944e"},
              "recallAt5Definition": "Answerable questions with a hit in the top five",
              "translatedSubsetDefinition": "Translated answerable questions only",
              "hitsAt5": {"vector": 0, "keyword": 0, "hybrid": 0},
              "originalHitsOnTranslatedAt5": {"vector": 0, "keyword": 0, "hybrid": 0},
              "englishHitsAt5": {"vector": 0, "keyword": 0, "hybrid": 0},
              "questions": [{
                "id": "none-01", "question": "Question outside the corpus?",
                "answerable": false, "translated": true, "englishQuestion": "Translated question?",
                "vectorTop5": ["asvs-v1-1"], "keywordTop5": [], "hybridTop5": ["asvs-v1-1"]
              }]
            }
            """;

    @Test
    void mapsThePublishedRetrievalSnapshotWithoutCallingLiveServices() throws Exception {
        var evaluation = service(published(), missing()).read().evaluation();

        assertThat(evaluation.available()).isTrue();
        assertThat(evaluation.generatedAt()).isEqualTo("2026-10-10T00:34:53.545468Z");
        assertThat(evaluation.totalQuestions()).isEqualTo(45);
        assertThat(evaluation.answerableQuestions()).isEqualTo(40);
        assertThat(evaluation.unanswerableQuestions()).isEqualTo(5);
        assertThat(evaluation.translatedQuestions()).isEqualTo(15);
        assertThat(evaluation.embeddingModel()).isEqualTo(
                "bge-m3:567m@7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab");
        assertThat(evaluation.metrics()).containsExactly(
                new Metric("vector", "Vetorial", 39, 40, 39.0 / 40),
                new Metric("keyword", "Textual", 27, 40, 27.0 / 40),
                new Metric("hybrid", "Híbrida", 38, 40, 38.0 / 40));
        assertThat(evaluation.originalTranslatedMetrics()).containsExactly(
                new Metric("vector", "Vetorial", 14, 15, 14.0 / 15),
                new Metric("keyword", "Textual", 9, 15, 9.0 / 15),
                new Metric("hybrid", "Híbrida", 14, 15, 14.0 / 15));
        assertThat(evaluation.englishMetrics()).containsExactly(
                new Metric("vector", "Vetorial", 15, 15, 1.0),
                new Metric("keyword", "Textual", 12, 15, 12.0 / 15),
                new Metric("hybrid", "Híbrida", 14, 15, 14.0 / 15));
        assertThat(evaluation.datasetKind()).isEqualTo("development");
        assertThat(evaluation.dataSource()).isEqualTo("published-snapshot");
        assertThat(evaluation.snapshot()).isEqualTo("/evals/results.json");
        assertThat(evaluation.description()).contains("Snapshot publicado", "sem consulta ao banco ou ao Ollama");
        assertThat(evaluation.recallAt5Definition()).contains("answerable questions");
        assertThat(evaluation.translatedSubsetDefinition()).contains("only this subset");
        assertThat(evaluation.corpusRevisions()).containsEntry("Top10", "3a31f35346c3f4e90f350395124382fa53af2afe");

        assertThat(evaluation.cases()).hasSize(45);
        var first = evaluation.cases().getFirst();
        assertThat(first.id()).isEqualTo("sql-01");
        assertThat(first.question()).isEqualTo("Como consultas parametrizadas ajudam a prevenir SQL injection?");
        assertThat(first.answerable()).isTrue();
        assertThat(first.vectorHit()).isTrue();
        assertThat(first.keywordHit()).isFalse();
        assertThat(first.hybridHit()).isTrue();
        assertThat(first.vectorTop5()).containsExactly(
                "sql-injection-6", "sql-injection-5", "sql-injection-7", "top10-a05-4", "sql-injection-11");
        assertThat(first.keywordTop5()).containsExactly(
                "top10-a05-7", "asvs-v1-3", "sql-injection-1", "sql-injection-2", "top10-a05-6");
        assertThat(first.hybridTop5()).containsExactly(
                "sql-injection-2", "sql-injection-11", "sql-injection-7", "top10-a05-4", "sql-injection-6");
    }

    @Test
    void leavesInapplicableHitFlagsNullForUnanswerableQuestions() throws Exception {
        var cases = service(published(), missing()).read().evaluation().cases();
        var unanswerable = cases.stream().filter(row -> !row.answerable()).toList();

        assertThat(unanswerable).hasSize(5).allSatisfy(row -> {
            assertThat(row.vectorHit()).isNull();
            assertThat(row.keywordHit()).isNull();
            assertThat(row.hybridHit()).isNull();
            assertThat(row.vectorTop5()).hasSize(5);
        });
    }

    @Test
    void mapsEverySourceFromTheRealCatalogToItsPinnedOfficialUrl() throws Exception {
        var response = service(missing(), missing()).read();
        var catalog = new SourceCatalog().load();

        assertThat(response.sources()).hasSameSizeAs(catalog).hasSize(30);
        for (int index = 0; index < catalog.size(); index++) {
            var expected = catalog.get(index);
            var actual = response.sources().get(index);
            assertThat(actual.id()).isEqualTo(expected.id());
            assertThat(actual.title()).isEqualTo(expected.title());
            assertThat(actual.family()).isEqualTo(expected.repository());
            assertThat(actual.revision()).isEqualTo(expected.revision()).matches("[0-9a-f]{40}");
            assertThat(actual.license()).isEqualTo(expected.license()).isEqualTo("CC-BY-SA-4.0");
            assertThat(actual.url()).isEqualTo(expected.sourceUrl())
                    .startsWith("https://github.com/OWASP/" + actual.family() + "/blob/" + actual.revision() + "/")
                    .endsWith(".md");
        }
        assertThat(response.sources()).extracting(WorkspaceResponse.Source::family)
                .containsOnly("CheatSheetSeries", "Top10", "ASVS");
        assertThat(response.sources().getFirst().title()).isEqualTo("SQL Injection Prevention Cheat Sheet");
        assertThat(response.sources().get(3).title()).isEqualTo("OWASP Top 10 2025 A01 Broken Access Control");
        assertThat(response.sources().get(13).title()).isEqualTo("OWASP ASVS 5.0 V1 Encoding and Sanitization");
    }

    @Test
    void missingSnapshotsAreUnavailableWithoutFabricatingMetrics() throws Exception {
        var response = service(missing(), missing()).read();
        var evaluation = response.evaluation();

        assertThat(evaluation.available()).isFalse();
        assertThat(evaluation.description()).contains("não encontrado", "/evals/results.json");
        assertThat(evaluation.generatedAt()).isNull();
        assertThat(evaluation.totalQuestions()).isNull();
        assertThat(evaluation.answerableQuestions()).isNull();
        assertThat(evaluation.unanswerableQuestions()).isNull();
        assertThat(evaluation.translatedQuestions()).isNull();
        assertThat(evaluation.embeddingModel()).isNull();
        assertThat(evaluation.metrics()).isEmpty();
        assertThat(evaluation.cases()).isEmpty();
        assertThat(evaluation.corpusRevisions()).isEmpty();
        assertThat(evaluation.datasetKind()).isEqualTo("development");
        assertThat(response.generationEvaluation()).containsEntry("available", false)
                .containsEntry("datasetKind", "development")
                .doesNotContainKeys("totalQuestions", "metrics");
        assertThat(response.generationEvaluation().get("description").toString())
                .contains("não encontrado", "/evals/generation-results.json");
        assertThat(response.generationBaseline()).containsEntry("available", false)
                .containsEntry("snapshot", "/evals/generation-baseline.json");
    }

    @Test
    void corruptAndStructurallyInvalidRetrievalSnapshotsAreUnavailable() throws Exception {
        for (String json : List.of("", "{", "null", "[]", "{}", "42",
                ZERO_ANSWERABLE.replace("\"answerableQuestions\": 0", "\"answerableQuestions\": -1"),
                ZERO_ANSWERABLE.replace("\"answerableQuestions\": 0", "\"answerableQuestions\": 0.5"),
                ZERO_ANSWERABLE.replace("\"unanswerableQuestions\": 1", "\"unanswerableQuestions\": 2"),
                ZERO_ANSWERABLE.replace("\"translatedQuestions\": 0", "\"translatedQuestions\": 1"),
                ZERO_ANSWERABLE.replace("\"vector\": 0", "\"vector\": 1"),
                ZERO_ANSWERABLE.replace("\"keyword\": 0", "\"keyword\": \"0\""),
                ZERO_ANSWERABLE.replace("\"answerable\": false", "\"answerable\": \"false\""),
                ZERO_ANSWERABLE.replace("\"vectorTop5\": [\"asvs-v1-1\"]", "\"vectorTop5\": [false]"),
                ZERO_ANSWERABLE.replace("2026-10-10T00:34:53.545468Z", "invalid-date"))) {
            var evaluation = service(json(json), missing()).read().evaluation();
            assertThat(evaluation.available()).as(json).isFalse();
            assertThat(evaluation.description()).contains("inválido ou ilegível");
            assertThat(evaluation.metrics()).isEmpty();
            assertThat(evaluation.cases()).isEmpty();
        }
    }

    @Test
    void zeroDenominatorsYieldFiniteZeroRecallAndExcludeTranslatedUnanswerableRows() throws Exception {
        var evaluation = service(json(ZERO_ANSWERABLE), missing()).read().evaluation();

        assertThat(evaluation.available()).isTrue();
        assertThat(evaluation.totalQuestions()).isEqualTo(1);
        assertThat(evaluation.answerableQuestions()).isZero();
        assertThat(evaluation.translatedQuestions()).isZero();
        for (var group : List.of(evaluation.metrics(), evaluation.originalTranslatedMetrics(), evaluation.englishMetrics())) {
            assertThat(group).hasSize(3).allSatisfy(metric -> {
                assertThat(metric.hits()).isZero();
                assertThat(metric.total()).isZero();
                assertThat(metric.recallAt5()).isFinite().isZero();
            });
        }
        assertThat(evaluation.cases().getFirst().vectorHit()).isNull();
    }

    @Test
    void passesThroughGenerationFieldsAndNestedResultsWithSnapshotProvenance() throws Exception {
        var generation = service(missing(), json("""
                {
                  "generatedAt": "2026-10-12T12:00:00Z",
                  "data": "development",
                  "generationModel": "fixture-only",
                  "totalQuestions": 2,
                  "summary": {"supportedAnswers": 1},
                  "cases": [{"id": "fixture-01", "abstained": false}],
                  "futureField": ["preserved"],
                  "dataSource": "live-database"
                }
                """)).read().generationEvaluation();

        assertThat(generation).containsEntry("available", true)
                .containsEntry("generatedAt", "2026-10-12T12:00:00Z")
                .containsEntry("generationModel", "fixture-only")
                .containsEntry("totalQuestions", 2)
                .containsEntry("dataSource", "published-snapshot")
                .containsEntry("datasetKind", "development")
                .containsEntry("snapshot", "/evals/generation-results.json")
                .containsEntry("summary", java.util.Map.of("supportedAnswers", 1))
                .containsEntry("cases", List.of(java.util.Map.of("id", "fixture-01", "abstained", false)))
                .containsEntry("futureField", List.of("preserved"));
    }

    @Test
    void invalidGenerationSnapshotDoesNotHideValidRetrievalResults() throws Exception {
        String valid = """
                {"data":"development","generatedAt":"2026-10-12T12:00:00Z",
                 "summary":{},"cases":[{"id":"fixture-01"}]}
                """;
        for (String invalid : List.of("", "{", "null", "[]", "{}", "42",
                "{\"summary\":{}} false", "{\"summary\":{},\"summary\":{}}",
                "{\"unexpected\":true}", valid.replace("development", "blind"),
                valid.replace("2026-10-12T12:00:00Z", "invalid-date"),
                valid.replace("\"cases\":[{\"id\":\"fixture-01\"}]", "\"cases\":{}"),
                valid.replace("{\"id\":\"fixture-01\"}", "false"),
                valid.replace("{\"id\":\"fixture-01\"}", "{}"),
                valid.replace("\"summary\":{}", "\"summary\":[]"))) {
            var response = service(published(), json(invalid)).read();

            assertThat(response.evaluation().available()).isTrue();
            assertThat(response.generationEvaluation()).containsEntry("available", false)
                    .doesNotContainKeys("totalQuestions", "cases");
            assertThat(response.generationEvaluation().get("description").toString())
                    .contains("inválido ou ilegível", "/evals/generation-results.json");
        }
    }

    @Test
    void passesThroughBaselineSummaryAndCasesIndependentlyOfTheCurrentGenerationSnapshot() throws Exception {
        var service = new WorkspaceService(new SourceCatalog(), published(), missing(), json("""
                {
                  "generatedAt": "2026-10-12T10:00:00Z",
                  "data": "development",
                  "citationStrategy": "literal-quotes-v1",
                  "summary": {"acceptedAnswers": 0, "totalQuestions": 1},
                  "cases": [{"id": "baseline-01", "abstained": true}],
                  "baselineCommit": "fixture-commit"
                }
                """));
        var response = service.read();

        assertThat(response.evaluation().available()).isTrue();
        assertThat(response.generationEvaluation()).containsEntry("available", false);
        assertThat(response.generationBaseline()).containsEntry("available", true)
                .containsEntry("snapshot", "/evals/generation-baseline.json")
                .containsEntry("dataSource", "published-snapshot")
                .containsEntry("datasetKind", "development")
                .containsEntry("citationStrategy", "literal-quotes-v1")
                .containsEntry("baselineCommit", "fixture-commit")
                .containsEntry("summary", java.util.Map.of("acceptedAnswers", 0, "totalQuestions", 1))
                .containsEntry("cases", List.of(java.util.Map.of("id", "baseline-01", "abstained", true)));
    }

    @Test
    void invalidBaselineDoesNotHideTheCurrentGenerationSnapshot() throws Exception {
        var service = new WorkspaceService(new SourceCatalog(), missing(), json("""
                {"data": "development", "generatedAt": "2026-10-12T12:00:00Z",
                 "summary": {"acceptedAnswers": 1}, "cases": [{"id": "current-01"}]}
                """), json("{broken"));
        var response = service.read();

        assertThat(response.generationEvaluation()).containsEntry("available", true);
        assertThat(response.generationBaseline()).containsEntry("available", false)
                .doesNotContainKeys("summary", "cases");
        assertThat(response.generationBaseline().get("description").toString())
                .contains("baseline de geração inválido", "/evals/generation-baseline.json");
    }

    @Test
    void unreadableSnapshotsAreUnavailableAndLeaveTheCatalogAccessible() throws Exception {
        Resource unreadable = new AbstractResource() {
            @Override
            public String getDescription() {
                return "unreadable test fixture";
            }

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                throw new java.io.IOException("fixture read failure");
            }
        };
        var response = new WorkspaceService(new SourceCatalog(), unreadable, unreadable, unreadable).read();

        assertThat(response.sources()).hasSize(30);
        assertThat(response.evaluation().available()).isFalse();
        assertThat(response.generationEvaluation()).containsEntry("available", false);
        assertThat(response.generationBaseline()).containsEntry("available", false);
    }

    private static WorkspaceService service(Resource retrieval, Resource generation) {
        return new WorkspaceService(new SourceCatalog(), retrieval, generation, missing());
    }

    private static Resource published() {
        return new ClassPathResource(WorkspaceService.RETRIEVAL_PATH);
    }

    private static Resource missing() {
        return new ClassPathResource("evals/workspace-test-missing.json");
    }

    private static Resource json(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }
}
