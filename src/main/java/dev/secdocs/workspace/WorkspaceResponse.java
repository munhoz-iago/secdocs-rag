package dev.secdocs.workspace;

import java.util.List;
import java.util.Map;

public record WorkspaceResponse(List<Source> sources, Evaluation evaluation,
                                Map<String, Object> generationEvaluation, Map<String, Object> generationBaseline) {

    public record Source(String id, String title, String family, String url, String revision, String license) {
    }

    public record Metric(String mode, String label, int hits, int total, double recallAt5) {
    }

    public record Case(String id, String question, boolean answerable,
                       Boolean vectorHit, Boolean keywordHit, Boolean hybridHit,
                       List<String> vectorTop5, List<String> keywordTop5, List<String> hybridTop5) {
    }

    public record Evaluation(boolean available, String generatedAt, Integer totalQuestions,
                             Integer answerableQuestions, Integer unanswerableQuestions,
                             Integer translatedQuestions, String embeddingModel,
                             List<Metric> metrics, List<Case> cases, String datasetKind,
                             Map<String, String> corpusRevisions, String recallAt5Definition,
                             String translatedSubsetDefinition, List<Metric> originalTranslatedMetrics,
                             List<Metric> englishMetrics, String snapshot, String dataSource,
                             String description) {
    }
}
