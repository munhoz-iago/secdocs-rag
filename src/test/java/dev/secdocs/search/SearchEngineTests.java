package dev.secdocs.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchEngineTests {
    private SearchHit hit(String id, SearchMode mode) {
        return new SearchHit(id, "Source", "Section", "https://example.org", "Content", 1.0, mode);
    }

    @Test
    void reciprocalRankFusionRewardsOverlap() {
        var keyword = List.of(hit("a", SearchMode.KEYWORD), hit("shared", SearchMode.KEYWORD));
        var vector = List.of(hit("b", SearchMode.VECTOR), hit("shared", SearchMode.VECTOR));
        var fused = SearchEngine.fuse(keyword, vector);
        assertEquals("shared", fused.getFirst().id());
        assertEquals(3, fused.size());
        assertEquals(SearchMode.HYBRID, fused.getFirst().mode());
    }
}
