package dev.secdocs.answer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import dev.secdocs.search.SearchEngine;
import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;

@Service
public class AnswerService {

    private static final double MIN_VECTOR_SIMILARITY = 0.55;
    private static final String NO_ANSWER = "Não encontrei informação suficiente nos documentos consultados para responder com segurança.";

    private final SearchEngine searchEngine;
    private final AnswerModel answerModel;

    public AnswerService(SearchEngine searchEngine, AnswerModel answerModel) {
        this.searchEngine = searchEngine;
        this.answerModel = answerModel;
    }

    public AnswerResponse answer(String question) throws Exception {
        Map<SearchMode, List<SearchHit>> results = searchEngine.compare(question, 5);
        List<SearchHit> vector = results.get(SearchMode.VECTOR);
        if (vector.isEmpty() || vector.getFirst().score() < MIN_VECTOR_SIMILARITY) {
            return abstain();
        }

        // The measured vector run found 25/25 expected chunks at five; hybrid found 24/25.
        List<SearchHit> evidence = vector;
        AnswerModel.ModelAnswer draft = answerModel.generate(question, evidence);
        return validate(draft, evidence);
    }

    public static AnswerResponse validate(AnswerModel.ModelAnswer draft, List<SearchHit> evidence) {
        if (draft == null || draft.abstained() || draft.answer() == null
                || draft.answer().isBlank() || draft.answer().length() > 4000
                || draft.supports() == null || draft.supports().isEmpty()
                || draft.supports().size() > 3) {
            return abstain();
        }
        Set<String> allowedIds = new HashSet<>();
        for (SearchHit hit : evidence) {
            allowedIds.add(hit.id());
        }
        List<AnswerModel.Support> verifiedSupports = draft.supports().stream()
                .filter(support -> allowedIds.contains(support.id())
                        && support.quote() != null && support.quote().length() >= 20
                        && support.quote().length() <= 600
                        && evidence.stream().anyMatch(hit -> hit.id().equals(support.id())
                                && hit.content().contains(support.quote())))
                .toList();
        if (verifiedSupports.isEmpty()) {
            return abstain();
        }
        List<Citation> citations = verifiedSupports.stream()
                .map(support -> {
                    SearchHit hit = evidence.stream().filter(item -> item.id().equals(support.id()))
                            .findFirst().orElseThrow();
                    return new Citation(hit.id(), hit.sourceTitle(), hit.section(), hit.sourceUrl(), support.quote());
                }).distinct()
                .toList();
        return new AnswerResponse(draft.answer().trim(), false, citations);
    }

    private static AnswerResponse abstain() {
        return new AnswerResponse(NO_ANSWER, true, List.of());
    }

    public record AnswerResponse(String answer, boolean abstained, List<Citation> citations) {
    }

    public record Citation(String id, String sourceTitle, String section, String sourceUrl, String excerpt) {
    }
}
