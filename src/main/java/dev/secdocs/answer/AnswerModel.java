package dev.secdocs.answer;

import java.util.List;

import dev.secdocs.search.SearchHit;

public interface AnswerModel {
    ModelAnswer generate(String question, List<SearchHit> evidence) throws Exception;

    ModelAnswer generateRepair(String question, List<SearchHit> evidence) throws Exception;

    record ModelAnswer(String answer, List<Support> supports, boolean abstained, Failure failure) {
        public ModelAnswer(String answer, List<Support> supports, boolean abstained) {
            this(answer, supports, abstained, abstained ? Failure.MODEL_ABSTAINED : Failure.NONE);
        }
    }

    enum Failure { NONE, MODEL_ABSTAINED, INVALID_RESPONSE, INVALID_CITATION }

    record Support(String id, String quote) {
    }
}
