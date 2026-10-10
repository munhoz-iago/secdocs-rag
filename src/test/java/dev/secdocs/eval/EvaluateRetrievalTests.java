package dev.secdocs.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.secdocs.eval.EvaluateRetrieval.GoldenQuestion;
import dev.secdocs.ingest.OwaspSource;
import dev.secdocs.ingest.SourceCatalog;
import dev.secdocs.search.SearchMode;

import static org.junit.jupiter.api.Assertions.*;

class EvaluateRetrievalTests {
    private final EvaluateRetrieval evaluator = new EvaluateRetrieval(null, null);

    private static Map<SearchMode, List<String>> found(String... ids) {
        Map<SearchMode, List<String>> found = new EnumMap<>(SearchMode.class);
        for (SearchMode mode : SearchMode.values()) {
            found.put(mode, List.of(ids));
        }
        return found;
    }

    @Test
    void englishHitsCountOnlyTranslatedQuestionsWithTheirOriginalBaseline() {
        var tally = new EvaluateRetrieval.Tally();
        var english = new GoldenQuestion("en", "What is XSS?", null, List.of("xss-1"), true);
        var translated = new GoldenQuestion("pt", "O que é XSS?", "What is XSS?", List.of("xss-1"), true);
        var none = new GoldenQuestion("none", "Receita de bolo?", "Cake recipe?", List.of(), false);

        tally.add(english, found("xss-1"), null);
        tally.add(translated, found("other"), found("xss-1"));
        tally.add(none, found("xss-1"), found("xss-1"));

        assertEquals(1, tally.translated);
        assertEquals(1, tally.hits.get(SearchMode.VECTOR));
        assertEquals(0, tally.originalOnTranslated.get(SearchMode.VECTOR));
        assertEquals(1, tally.englishHits.get(SearchMode.VECTOR));
    }

    @Test
    void corpusRevisionsComeFromTheCatalog() throws Exception {
        var revisions = EvaluateRetrieval.corpusRevisions(new SourceCatalog().load());
        assertEquals(List.of("ASVS", "CheatSheetSeries", "Top10"), new ArrayList<>(revisions.keySet()));
        revisions.values().forEach(revision -> assertTrue(revision.matches("[0-9a-f]{40}"), revision));

        var mixed = EvaluateRetrieval.corpusRevisions(List.of(
                new OwaspSource("a", "A", "ASVS", "a.md", "bbb", "CC-BY-SA-4.0"),
                new OwaspSource("b", "B", "ASVS", "b.md", "aaa", "CC-BY-SA-4.0")));
        assertEquals(Map.of("ASVS", "aaa,bbb"), mixed);
    }

    @Test
    void goldenSetNeedsUnanswerableAndEnoughAnswerableQuestions() {
        List<GoldenQuestion> questions = new ArrayList<>();
        for (int i = 0; i < EvaluateRetrieval.MIN_ANSWERABLE; i++) {
            questions.add(new GoldenQuestion("q" + i, "Pergunta " + i, null, List.of("c-" + i), true));
        }
        assertThrows(IllegalStateException.class, () -> EvaluateRetrieval.checkComposition(questions));
        questions.add(new GoldenQuestion("out", "Fora do acervo", null, List.of(), false));
        assertDoesNotThrow(() -> EvaluateRetrieval.checkComposition(questions));
        questions.removeFirst();
        assertThrows(IllegalStateException.class, () -> EvaluateRetrieval.checkComposition(questions));
    }

    @Test
    void currentGoldenSetPassesTheCompositionCheck() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("evals", "golden-set.json"));
        var questions = evaluator.parseQuestions(json);
        assertDoesNotThrow(() -> EvaluateRetrieval.checkComposition(questions));
    }

    @Test
    void rejectsBlankOrUntranslatedEnglishQuestion() {
        String template = "[{\"id\":\"a\",\"question\":\"O que é XSS?\",\"englishQuestion\":%s,"
                + "\"answerable\":true,\"referenceAnswer\":\"r\",\"expectedChunkIds\":[\"xss-1\"]}]";
        assertThrows(IllegalArgumentException.class, () -> evaluator.parseQuestions(template.formatted("\" \"")));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.parseQuestions(template.formatted("\"O que é XSS?\"")));
        assertThrows(IllegalArgumentException.class, () -> evaluator.parseQuestions(template.formatted("42")));
        assertEquals("What is XSS?",
                evaluator.parseQuestions(template.formatted("\"What is XSS?\"")).getFirst().englishQuestion());
        assertNull(evaluator.parseQuestions(template.formatted("null")).getFirst().englishQuestion());
    }
}
