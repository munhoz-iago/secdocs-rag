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

    @Test
    void keywordQueryDropsWebsearchOperators() {
        assertEquals("cookies OR httponly", SearchEngine.orQuery("cookies -httponly"));
        assertEquals("SQL OR injection OR prevention", SearchEngine.orQuery("\"SQL injection\" prevention"));
        assertEquals("csrf OR xss", SearchEngine.orQuery("csrf or xss"));
        assertEquals("csrf OR xss", SearchEngine.orQuery("csrf OR xss"));
        assertEquals("What OR is OR XSS OR script", SearchEngine.orQuery("What is XSS?!; (<script>) & | !"));
        assertEquals("cross OR site OR scripting", SearchEngine.orQuery("cross-site scripting"));
        assertEquals("autenticação OR multifator", SearchEngine.orQuery("  autenticação\tmultifator  "));
        assertEquals("example OR com OR 8080 OR a OR b OR c", SearchEngine.orQuery("example.com:8080/a?b=c"));
    }

    @Test
    void keywordQueryIsEmptyForBlankOrPunctuationOnlyInput() {
        assertEquals("", SearchEngine.orQuery(""));
        assertEquals("", SearchEngine.orQuery("   "));
        assertEquals("", SearchEngine.orQuery(null));
        assertEquals("", SearchEngine.orQuery("\"-\" -- !? ()"));
        assertEquals("", SearchEngine.orQuery("or OR"));
    }
}
