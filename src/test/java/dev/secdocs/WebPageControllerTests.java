package dev.secdocs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.secdocs.ingest.SourceCatalog;

class WebPageControllerTests {
    private static final String REVISION = "61a544f0fdbb7cc1578e0c3a3037a8a2874a944e";

    @Test
    void linksToPinnedOwaspRepositories() {
        for (String repository : new String[] {"CheatSheetSeries", "Top10", "ASVS"}) {
            String url = "https://github.com/OWASP/" + repository + "/blob/" + REVISION + "/docs/file_1-a.md";
            assertEquals(url, WebPageController.safeUrl(url));
        }
    }

    @Test
    void acceptsEveryCatalogSourceUrlUnchanged() throws Exception {
        for (var source : new SourceCatalog().load()) {
            assertEquals(source.sourceUrl(), WebPageController.safeUrl(source.sourceUrl()), source.id());
        }
    }

    @Test
    void rejectsNull() {
        assertNull(WebPageController.safeUrl(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com.evil.test/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "https://evil.test@github.com/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "http://github.com/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "HTTPS://GITHUB.COM/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "https://GitHub.com/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "https://github.com./OWASP/ASVS/blob/" + REVISION + "/file.md",
            "https://github.com:443/OWASP/ASVS/blob/" + REVISION + "/file.md",
            "https://github.com/OWASP/WSTG/blob/" + REVISION + "/file.md",
            "https://github.com/OWASP/ASVS/tree/" + REVISION + "/file.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/../../evil/repo.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/./file.md",
            "https://github.com/OWASP/ASVS/blob/../../evil/repo/file.md",
            "https://github.com/OWASP/ASVS/blob/%2e%2e/%2e%2e/evil/repo.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/%2E%2E/file.md",
            "https://github.com/OWASP%2FASVS/blob/" + REVISION + "/file.md",
            "https://github.com/OWASP/AS%56S/blob/" + REVISION + "/file.md",
            "https://github.com/OWASP/ASVS/blob//evil.test/x.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "//evil.test/x.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "\\..\\file.md",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/file.md#frag",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/file.md?x=1",
            "https://github.com/OWASP/ASVS/blob/" + REVISION + "/file.html",
            "https://github.com/a b",
            ""
    })
    void rejectsUntrustedLinks(String url) {
        assertNull(WebPageController.safeUrl(url), url);
    }
}
