package dev.secdocs.answer;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePassagesTests {
    private SearchHit hit(String id, String text) {
        return new SearchHit(id, "Source", "Section", "url", text, .8, SearchMode.VECTOR);
    }

    @Test
    void everyWindowRemainsLiteralWithOriginalLanguageAndWhitespace() {
        String text = "  Original English paragraph with precise punctuation.\n\n"
                + "A long row with tables and multiline source content. ".repeat(35) + "\nEnd of original text.";
        var passages = EvidencePassages.from(List.of(hit("source-1", text)));
        assertTrue(passages.size() > 2);
        assertEquals(passages.size(), passages.stream().map(EvidencePassages.Passage::id).distinct().count());
        for (var passage : passages) {
            assertTrue(text.contains(passage.text()));
            assertTrue(passage.text().length() >= 20 && passage.text().length() <= 600);
            assertEquals("source-1", passage.chunkId());
        }
        assertTrue(passages.getLast().text().endsWith("End of original text."));
    }

    @Test
    void boundedContextGivesEveryRetrievedSourceAnInitialWindow() {
        var hits = IntStream.range(0, 5).mapToObj(i -> hit("source-" + i,
                ("Evidence for source " + i + ": relevant statement. ").repeat(100))).toList();
        var passages = EvidencePassages.from(hits);
        assertEquals(5, passages.subList(0, 5).stream().map(EvidencePassages.Passage::chunkId).distinct().count());
        assertTrue(passages.size() <= 30);
        assertTrue(passages.stream().mapToInt(p -> p.text().length()).sum() <= 14_000);
    }

    @Test
    void shortEmptyAndUnicodeTextNeverProducesInvalidWindows() {
        assertTrue(EvidencePassages.from(List.of(hit("empty", "tiny\n\n  "))).isEmpty());
        for (int prefix : List.of(519, 599)) {
            String text = "A".repeat(prefix) + "😀" + "Z".repeat(650);
            for (var passage : EvidencePassages.from(List.of(hit("unicode", text)))) {
                assertFalse(Character.isHighSurrogate(passage.text().charAt(passage.text().length() - 1)));
                assertFalse(Character.isLowSurrogate(passage.text().charAt(0)));
                assertTrue(text.contains(passage.text()));
            }
        }
    }
}
