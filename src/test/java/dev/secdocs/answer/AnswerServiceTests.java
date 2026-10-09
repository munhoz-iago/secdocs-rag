package dev.secdocs.answer;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;

import static org.junit.jupiter.api.Assertions.*;

class AnswerServiceTests {
    private static final String CONTENT = "Use prepared statements with parameterized queries to separate SQL code from data.";
    private static final SearchHit HIT = new SearchHit("sql-1", "SQL Injection", "Prepared Statements",
            "https://example.org/sql", CONTENT, 0.8, SearchMode.VECTOR);

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
    void dropsInvalidSupportWhenAnotherExactQuoteExists() {
        var draft = new AnswerModel.ModelAnswer("Use consultas parametrizadas.", List.of(
                new AnswerModel.Support("fake", "this quotation is not in the source document"),
                new AnswerModel.Support("sql-1", "prepared statements with parameterized queries")), false);
        var response = AnswerService.validate(draft, List.of(HIT));
        assertFalse(response.abstained());
        assertEquals(1, response.citations().size());
    }

    @Test
    void refusesCodeExamplesInGeneratedAnswer() {
        var draft = new AnswerModel.ModelAnswer("Use prepared statements; DROP TABLE users.",
                List.of(new AnswerModel.Support("sql-1", "prepared statements with parameterized queries")), false);
        assertTrue(AnswerService.validate(draft, List.of(HIT)).abstained());
    }
}
