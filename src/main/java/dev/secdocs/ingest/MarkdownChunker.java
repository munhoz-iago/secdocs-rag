package dev.secdocs.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final int MAX_CHARS = 3500;

    public List<DocumentChunk> chunk(OwaspSource source, String markdown) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String section = source.title();
        List<String> headingPath = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : markdown.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeFence = !inCodeFence;
            }
            Matcher heading = HEADING.matcher(line);
            if (!inCodeFence && heading.matches()) {
                addSection(chunks, source, section, body.toString());
                int level = heading.group(1).length();
                while (headingPath.size() >= level) {
                    headingPath.removeLast();
                }
                headingPath.add(heading.group(2).trim());
                section = String.join(" > ", headingPath);
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(chunks, source, section, body.toString());
        return chunks;
    }

    private void addSection(List<DocumentChunk> chunks, OwaspSource source, String section, String body) {
        String remaining = body.trim();
        while (!remaining.isEmpty()) {
            int end = Math.min(MAX_CHARS, remaining.length());
            if (end < remaining.length()) {
                int paragraph = remaining.lastIndexOf("\n\n", end);
                int space = remaining.lastIndexOf(' ', end);
                end = paragraph > MAX_CHARS / 2 ? paragraph : (space > MAX_CHARS / 2 ? space : end);
            }
            String text = remaining.substring(0, end).trim();
            if (!text.isEmpty()) {
                chunks.add(new DocumentChunk(source.id() + "-" + (chunks.size() + 1), source.id(), source.title(),
                        section, source.sourceUrl(), source.revision(), source.license(), text));
            }
            remaining = remaining.substring(end).trim();
        }
    }
}
