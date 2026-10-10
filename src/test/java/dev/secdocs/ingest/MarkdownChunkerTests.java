package dev.secdocs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownChunkerTests {

    private final OwaspSource source = new OwaspSource("example", "Example", "CheatSheetSeries", "example.md", "abc123", "CC-BY-SA-4.0");

    @Test
    void preservesSectionAndSourceMetadata() {
        String markdown = "# Example\n\nIntro text.\n\n## Defense\n\nUse parameterized queries.";

        var chunks = new MarkdownChunker().chunk(source, markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).section()).isEqualTo("Example > Defense");
        assertThat(chunks.get(1).text()).contains("parameterized queries");
        assertThat(chunks.get(1).sourceUrl()).contains("/blob/abc123/example.md");
    }

    @Test
    void ignoresHeadingInsideCodeFence() {
        String markdown = "## Defense\n\n```markdown\n# Not a section\n```\n\nKeep this text.";

        var chunks = new MarkdownChunker().chunk(source, markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().section()).isEqualTo("Defense");
        assertThat(chunks.getFirst().text()).contains("# Not a section", "Keep this text");
    }

    @Test
    void removesDecorativeImageFromSectionName() {
        String markdown = "# A01 Broken Access Control ![icon](../assets/icon.png){: style=\"width:80px\" }\n\nRisk details.";

        var chunks = new MarkdownChunker().chunk(source, markdown);

        assertThat(chunks.getFirst().section()).isEqualTo("A01 Broken Access Control");
    }

    @Test
    void unwrapsLinksAndEscapesInSectionNames() {
        String markdown = "# Guide\n\n## Process If the User HAS [Multifactor Authentication](https://example.org/mfa.html) Enabled\n\nText.\n\n"
                + "## Safe C\\# .NET Example\n\nMore.\n\n## [![badge](i.png)](https://example.org) Title\n\nEnd.";

        var chunks = new MarkdownChunker().chunk(source, markdown);

        assertThat(chunks).extracting(DocumentChunk::section).containsExactly(
                "Guide > Process If the User HAS Multifactor Authentication Enabled",
                "Guide > Safe C# .NET Example",
                "Guide > Title");
        assertThat(chunks).extracting(DocumentChunk::id).containsExactly("example-1", "example-2", "example-3");
    }

    @Test
    void keepsImageOnlyHeadingNonEmpty() {
        assertThat(MarkdownChunker.cleanHeading("![logo](logo.png)")).isEqualTo("![logo](logo.png)");
        assertThat(MarkdownChunker.cleanHeading("  Plain   heading ")).isEqualTo("Plain heading");
    }
}
