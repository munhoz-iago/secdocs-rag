package dev.secdocs.answer;

import java.util.List;

import dev.secdocs.search.SearchHit;

public interface AnswerModel {
    ModelAnswer generate(String question, List<SearchHit> evidence) throws Exception;

    ModelAnswer generateRepair(String question, List<SearchHit> evidence) throws Exception;

    record ModelAnswer(String answer, List<Support> supports, boolean abstained) {
    }

    record Support(String id, String quote) {
    }
}
