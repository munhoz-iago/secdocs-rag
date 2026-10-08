package dev.secdocs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownChunkerTests {

    private final OwaspSource source = new OwaspSource("example", "Example", "example.md", "abc123", "CC-BY-SA-4.0");

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
}
