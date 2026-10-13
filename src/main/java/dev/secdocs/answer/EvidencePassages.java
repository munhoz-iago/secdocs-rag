package dev.secdocs.answer;

import java.util.ArrayList;
import java.util.List;

import dev.secdocs.search.SearchHit;

/** Request-local, literal source windows. IDs have meaning only within this request. */
final class EvidencePassages {
    private static final int MAX_LENGTH = 600;
    private static final int OVERLAP = 80;
    private static final int MAX_PASSAGES = 30;
    private static final int CHARACTER_BUDGET = 14_000;

    private EvidencePassages() { }

    static List<Passage> from(List<SearchHit> evidence) {
        List<List<Passage>> bySource = new ArrayList<>();
        for (int source = 0; source < evidence.size(); source++) {
            SearchHit hit = evidence.get(source);
            List<Passage> windows = new ArrayList<>();
            for (String paragraph : hit.content().split("\\n\\s*\\n")) {
                String text = paragraph.strip();
                int start = 0;
                while (text.length() - start >= 20) {
                    int end = Math.min(start + MAX_LENGTH, text.length());
                    if (end < text.length()) {
                        int boundary = text.lastIndexOf(' ', end);
                        if (boundary > start + MAX_LENGTH / 2) end = boundary;
                        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    }
                    String excerpt = text.substring(start, end).strip();
                    if (excerpt.length() >= 20) {
                        windows.add(new Passage("E" + (source + 1) + "P" + (windows.size() + 1),
                                hit.id(), excerpt, hit.sourceTitle(), hit.section()));
                    }
                    if (end == text.length()) break;
                    start = end - OVERLAP;
                    if (Character.isLowSurrogate(text.charAt(start))) start++;
                }
            }
            bySource.add(windows);
        }
        // Round-robin gives every retrieved source room in the bounded context.
        List<Passage> selected = new ArrayList<>();
        int characters = 0;
        for (int round = 0; ; round++) {
            boolean found = false;
            for (List<Passage> windows : bySource) {
                if (round >= windows.size()) continue;
                found = true;
                Passage passage = windows.get(round);
                if (selected.size() >= MAX_PASSAGES || characters + passage.text().length() > CHARACTER_BUDGET) {
                    return List.copyOf(selected);
                }
                selected.add(passage);
                characters += passage.text().length();
            }
            if (!found) return List.copyOf(selected);
        }
    }

    record Passage(String id, String chunkId, String text, String sourceTitle, String section) { }
}
