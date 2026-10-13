package dev.secdocs.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import dev.secdocs.answer.AnswerService;
import dev.secdocs.eval.EvaluateGeneration.CaseResult;
import dev.secdocs.eval.EvaluateGeneration.GenerationQuestion;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluateGenerationTests {

    private final EvaluateGeneration evaluator = new EvaluateGeneration(null, new MockEnvironment());
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private String fixtureJson() throws Exception {
        return Files.readString(Path.of("evals", "generation-set.json"), StandardCharsets.UTF_8);
    }

    private String goldenJson() throws Exception {
        return Files.readString(Path.of("evals", "golden-set.json"), StandardCharsets.UTF_8);
    }

    private static GenerationQuestion question(String id, boolean answerable) {
        return new GenerationQuestion(id, "pt", "Pergunta " + id, answerable,
                answerable ? List.of("expected-1") : List.of());
    }

    private static Map<String, Object> response(String answer, boolean abstained, String... citationIds) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("abstained", abstained);
        response.put("citations", List.of(citationIds).stream().map(id -> Map.of(
                "id", id, "sourceTitle", "Fonte sintética", "section", "Seção",
                "sourceUrl", "https://example.invalid/source", "excerpt", "Trecho literal de teste.\nSegunda linha.")).toList());
        return response;
    }

    @Test
    void runnerRequiresExplicitOptIn() {
        var context = new ApplicationContextRunner().withBean(AnswerService.class, () -> mock(AnswerService.class))
                .withUserConfiguration(EvaluateGeneration.class);
        context.run(application -> assertTrue(application.getBeansOfType(EvaluateGeneration.class).isEmpty()));
        context.withPropertyValues("secdocs.eval-generation=false")
                .run(application -> assertTrue(application.getBeansOfType(EvaluateGeneration.class).isEmpty()));
        context.withPropertyValues("secdocs.eval-generation=true")
                .run(application -> assertEquals(1, application.getBeansOfType(EvaluateGeneration.class).size()));
    }

    @Test
    void frozenDevelopmentFixtureHasTheRequestedEightCasesAndRealGoldenIds() throws Exception {
        var fixture = evaluator.parseFixture(fixtureJson());
        assertEquals("development", fixture.data());
        assertFalse(fixture.independent());
        assertEquals(List.of("sql-01", "sql-02", "xss-01", "top10-05", "asvs-05", "asvs-09", "none-03", "none-01"),
                fixture.questions().stream().map(GenerationQuestion::id).toList());
        assertEquals(List.of("pt", "pt", "pt", "en", "en", "en", "pt", "pt"),
                fixture.questions().stream().map(GenerationQuestion::language).toList());
        assertEquals(6, fixture.questions().stream().filter(GenerationQuestion::expectedAnswerable).count());
        assertDoesNotThrow(() -> evaluator.validateAgainstGolden(fixture, goldenJson()));
    }

    @Test
    void countsSeparateRefusalsAcceptanceAndExpectedCitationMatching() {
        List<CaseResult> results = List.of(
                new CaseResult(question("legit-1", true), response("Texto sem avaliação semântica", false, "expected-1"), 80),
                new CaseResult(question("legit-2", true), response("Resposta com outra fonte", false, "other-1"), 10),
                new CaseResult(question("legit-3", true), response("Resposta sem citação", false), 50),
                new CaseResult(question("legit-4", true), response("Recusa", true), 20),
                new CaseResult(question("legit-5", true), response("Recusa", true), 40),
                new CaseResult(question("legit-6", true), response("Recusa", true), 60),
                new CaseResult(question("outside-1", false), response("Recusa", true), 70),
                new CaseResult(question("outside-2", false), response("Resposta indevida, sem citação", false), 30));

        Map<String, Object> counts = EvaluateGeneration.counts(results);
        assertEquals(Map.of("total", 6L, "answered", 3L, "falseRefusals", 3L,
                "acceptedAnswers", 2L, "expectedCitationMatches", 1L), counts.get("answerable"));
        assertEquals(Map.of("total", 2L, "abstained", 1L, "answered", 1L), counts.get("outside"));
        assertEquals(45.0, EvaluateGeneration.medianElapsedMillis(results));
        assertTrue(results.get(1).acceptedAnswer(), "Citação fora dos IDs esperados não é julgamento semântico");
        assertFalse(results.get(1).expectedCitationMatch());
        assertFalse(results.get(2).acceptedAnswer());
        assertFalse(results.getLast().acceptedAnswer(), "Resposta fora do corpus sem citação ainda conta como answered");
    }

    @Test
    void blankAnswersAndAbstentionsAreNeverAcceptedEvenWithCitations() {
        List<CaseResult> results = List.of(
                new CaseResult(question("blank", true), response(" \n\t", false, "expected-1"), 10),
                new CaseResult(question("null", true), response(null, false, "expected-1"), 90),
                new CaseResult(question("refusal", true), response("Recusa com citação bruta", true, "expected-1"), 50));
        assertEquals(Map.of("total", 3L, "answered", 2L, "falseRefusals", 1L,
                "acceptedAnswers", 0L, "expectedCitationMatches", 3L), EvaluateGeneration.counts(results).get("answerable"));
        assertEquals(50.0, EvaluateGeneration.medianElapsedMillis(results));
    }

    @Test
    void duplicateCitationsDoNotInflateExpectedMatchCounts() {
        var result = new CaseResult(question("duplicate-citations", true),
                response("Resposta", false, "expected-1", "expected-1", "other-1"), 1);
        assertEquals(List.of("expected-1"), result.reportRow().get("matchedExpectedChunkIds"));
        assertEquals(Map.of("total", 1L, "answered", 1L, "falseRefusals", 0L,
                "acceptedAnswers", 1L, "expectedCitationMatches", 1L), EvaluateGeneration.counts(List.of(result)).get("answerable"));
    }

    @Test
    void utf8SnapshotPreservesRawAnswersCitationsAndOptionalDiagnostics() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("evals", "generation-set.json"));
        var fixture = evaluator.parseFixture(new String(bytes, StandardCharsets.UTF_8));
        Map<String, Object> raw = response("  Resposta bruta com acentuação: proteção.\n  ", false, "sql-injection-6");
        raw.put("outcome", "ANSWERED");
        raw.put("attempts", 2);
        raw.put("durationMs", 123);
        raw.put("evidence", List.of(Map.of("id", "sql-injection-6", "score", 0.75)));
        raw.put("citationStrategy", "server-passages-v1");
        List<CaseResult> results = fixture.questions().stream().map(question ->
                new CaseResult(question, question.id().equals("sql-01") ? raw : response("Recusa", true), 125)).toList();
        var generatedAt = Instant.parse("2026-10-12T14:00:00Z");
        var report = EvaluateGeneration.report(fixture, results, EvaluateGeneration.fixtureSha256(bytes),
                "synthetic-chat", "synthetic-embedding", EvaluateGeneration.citationStrategy(results, "legacy-label"), generatedAt);
        Path output = temporaryDirectory.resolve("generation-results.json");

        evaluator.writeReport(output, report);
        Map<?, ?> snapshot = mapper.readValue(Files.readString(output, StandardCharsets.UTF_8), Map.class);
        Map<?, ?> firstCase = (Map<?, ?>) ((List<?>) snapshot.get("cases")).getFirst();
        raw.forEach((key, value) -> assertEquals(value, firstCase.get(key), key));
        assertEquals("2026-10-12T14:00:00Z", snapshot.get("generatedAt"));
        assertEquals("development", snapshot.get("data"));
        assertEquals(false, snapshot.get("independent"));
        assertEquals("server-passages-v1", snapshot.get("citationStrategy"));
        assertEquals("synthetic-chat", snapshot.get("chatModel"));
        assertEquals(EvaluateGeneration.fixtureSha256(bytes), snapshot.get("fixtureSha256"));
        assertEquals(EvaluateGeneration.ACCEPTED_ANSWER_DEFINITION, snapshot.get("acceptedAnswerDefinition"));
        assertEquals(true, firstCase.get("acceptedAnswer"));
        assertEquals(125, firstCase.get("elapsedMillis"));
        assertEquals(8, ((List<?>) snapshot.get("cases")).size());
        assertNull(((Map<?, ?>) ((List<?>) snapshot.get("cases")).getLast()).get("expectedCitationMatch"));
    }

    @Test
    void snapshotRejectsMissingReorderedAndDuplicateResults() throws Exception {
        var fixture = evaluator.parseFixture(fixtureJson());
        var results = fixture.questions().stream()
                .map(question -> new CaseResult(question, response("Recusa", true), 0)).toList();
        List<List<CaseResult>> invalid = new ArrayList<>();
        invalid.add(results.subList(0, 7));
        List<CaseResult> reordered = new ArrayList<>(results);
        java.util.Collections.swap(reordered, 0, 1);
        invalid.add(reordered);
        List<CaseResult> duplicated = new ArrayList<>(results);
        duplicated.set(1, duplicated.getFirst());
        invalid.add(duplicated);
        for (List<CaseResult> rows : invalid) {
            assertThrows(IllegalArgumentException.class, () -> EvaluateGeneration.report(fixture, rows, "hash",
                    "chat", "embed", "synthetic", Instant.EPOCH));
        }
    }

    @Test
    void fixtureRejectsInvalidMetadataTypesCompositionAndDuplicates() throws Exception {
        List<Consumer<Map<String, Object>>> corruptions = List.of(
                root -> root.put("data", "independent"),
                root -> root.put("independent", true),
                root -> root.remove("independent"),
                root -> root.put("strategy", " "),
                root -> root.put("questions", "wrong-type"),
                root -> fixtureRows(root).removeLast(),
                root -> fixtureRows(root).getFirst().put("expectedAnswerable", "true"),
                root -> fixtureRows(root).getFirst().remove("expectedAnswerable"),
                root -> fixtureRows(root).getFirst().put("id", " "),
                root -> fixtureRows(root).get(1).put("id", fixtureRows(root).getFirst().get("id")),
                root -> fixtureRows(root).get(1).put("question", fixtureRows(root).getFirst().get("question")),
                root -> fixtureRows(root).getFirst().put("question", ""),
                root -> fixtureRows(root).getFirst().put("language", "es"),
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of()),
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of("sql-injection-6", "sql-injection-6")),
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of(42)),
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of(" ")),
                root -> fixtureRows(root).getFirst().remove("expectedChunkIds"),
                root -> fixtureRows(root).getLast().put("expectedChunkIds", List.of("sql-injection-6")),
                root -> {
                    fixtureRows(root).getFirst().put("expectedAnswerable", false);
                    fixtureRows(root).getFirst().put("expectedChunkIds", List.of());
                });
        for (int index = 0; index < corruptions.size(); index++) {
            Map<String, Object> root = fixtureMap();
            corruptions.get(index).accept(root);
            String invalid = mapper.writeValueAsString(root);
            assertThrows(IllegalArgumentException.class, () -> evaluator.parseFixture(invalid), "Corruption " + index);
        }
        for (String invalid : List.of("{", "[]", "null", "[42]", fixtureJson() + " {}",
                fixtureJson().replace("\"data\": \"development\"", "\"data\": \"development\", \"data\": \"development\""))) {
            assertThrows(IllegalArgumentException.class, () -> evaluator.parseFixture(invalid), invalid);
        }
    }

    @Test
    void expectedIdsQuestionsAndAnswerabilityMustAgreeWithTheRealGoldenSet() throws Exception {
        List<Consumer<Map<String, Object>>> divergences = List.of(
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of("invented-chunk")),
                root -> fixtureRows(root).getFirst().put("expectedChunkIds", List.of("asvs-v7-4")),
                root -> fixtureRows(root).getFirst().put("id", "invented-question"),
                root -> fixtureRows(root).getFirst().put("question", "Outra pergunta sobre SQL"),
                root -> {
                    fixtureRows(root).getFirst().put("expectedAnswerable", false);
                    fixtureRows(root).getFirst().put("expectedChunkIds", List.of());
                    fixtureRows(root).getLast().put("expectedAnswerable", true);
                    fixtureRows(root).getLast().put("expectedChunkIds", List.of("sql-injection-6"));
                });
        for (Consumer<Map<String, Object>> divergence : divergences) {
            var root = fixtureMap();
            divergence.accept(root);
            var fixture = evaluator.parseFixture(mapper.writeValueAsString(root));
            assertThrows(IllegalArgumentException.class, () -> evaluator.validateAgainstGolden(fixture, goldenJson()));
        }
        var fixture = evaluator.parseFixture(fixtureJson());
        assertThrows(IllegalArgumentException.class, () -> evaluator.validateAgainstGolden(fixture, "[]"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.validateAgainstGolden(fixture,
                "[{\"id\":\"sql-01\"},{\"id\":\"sql-01\"}]"));
    }

    @Test
    void invalidFixtureFailsBeforeCallingServiceOrReplacingAnEarlierSnapshot() throws Exception {
        var service = mock(AnswerService.class);
        var runner = new EvaluateGeneration(service, new MockEnvironment());
        Path fixture = temporaryDirectory.resolve("invalid-set.json");
        Path output = temporaryDirectory.resolve("generation-results.json");
        Files.writeString(fixture, fixtureJson().replace("\"id\": \"sql-02\"", "\"id\": \"sql-01\""), StandardCharsets.UTF_8);
        Files.writeString(output, "earlier snapshot", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> runner.evaluate(fixture, Path.of("evals", "golden-set.json"), output));
        verifyNoInteractions(service);
        assertEquals("earlier snapshot", Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void hashUsesTheExactFixtureBytes() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EvaluateGeneration.fixtureSha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(EvaluateGeneration.fixtureSha256("abc".getBytes(StandardCharsets.UTF_8)),
                EvaluateGeneration.fixtureSha256("abc\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyLatencySamplesAndNegativeDurationsFailInsteadOfReportingMisleadingNumbers() {
        assertThrows(IllegalArgumentException.class, () -> EvaluateGeneration.medianElapsedMillis(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CaseResult(question("invalid-duration", true), response("Recusa", true), -1));
    }

    @Test
    void citationStrategyFallsBackForLegacyResponsesAndRejectsMixedRuns() {
        var legacy = new CaseResult(question("legacy", true), response("Recusa", true), 0);
        assertEquals("literal-quotes-v1", EvaluateGeneration.citationStrategy(List.of(legacy), "literal-quotes-v1"));
        Map<String, Object> current = response("Recusa", true);
        current.put("citationStrategy", "server-passages-v1");
        var serverPassages = new CaseResult(question("current", true), current, 0);
        assertEquals("server-passages-v1", EvaluateGeneration.citationStrategy(List.of(serverPassages), "unspecified"));
        Map<String, Object> other = response("Recusa", true);
        other.put("citationStrategy", "literal-quotes-v1");
        assertThrows(IllegalArgumentException.class, () -> EvaluateGeneration.citationStrategy(
                List.of(serverPassages, new CaseResult(question("other", true), other, 0)), "unspecified"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixtureMap() throws Exception {
        return mapper.readValue(fixtureJson(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fixtureRows(Map<String, Object> fixture) {
        return (List<Map<String, Object>>) fixture.get("questions");
    }
}
