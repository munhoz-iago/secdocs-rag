package dev.secdocs.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.secdocs.embed.OllamaEmbeddingClient;
import dev.secdocs.search.SearchEngine;
import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "eval", havingValue = "true")
public class EvaluateRetrieval implements ApplicationRunner {

    private final SearchEngine searchEngine;
    private final Environment environment;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvaluateRetrieval(SearchEngine searchEngine, Environment environment) {
        this.searchEngine = searchEngine;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path goldenPath = Path.of("evals", "golden-set.json");
        List<GoldenQuestion> questions = readQuestions(goldenPath);
        long answerable = questions.stream().filter(GoldenQuestion::answerable).count();
        long unanswerable = questions.size() - answerable;
        if (questions.size() != 30 || answerable != 25 || unanswerable != 5) {
            throw new IllegalStateException("O golden set deve ter 25 perguntas respondíveis e 5 sem resposta");
        }

        Map<SearchMode, Integer> hits = new EnumMap<>(SearchMode.class);
        for (SearchMode mode : SearchMode.values()) {
            hits.put(mode, 0);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GoldenQuestion question : questions) {
            Map<SearchMode, List<SearchHit>> results = searchEngine.compare(question.question(), 5);
            Map<String, Object> row = new HashMap<>();
            row.put("id", question.id());
            row.put("question", question.question());
            row.put("answerable", question.answerable());
            row.put("expectedChunkIds", question.expectedChunkIds());
            for (SearchMode mode : SearchMode.values()) {
                List<String> found = results.get(mode).stream().map(SearchHit::id).toList();
                row.put(mode.name().toLowerCase() + "Top5", found);
                row.put(mode.name().toLowerCase() + "TopScore",
                        results.get(mode).isEmpty() ? null : results.get(mode).getFirst().score());
                if (question.answerable()) {
                    boolean success = found.stream().anyMatch(question.expectedChunkIds()::contains);
                    row.put(mode.name().toLowerCase() + "Hit", success);
                    if (success) {
                        hits.merge(mode, 1, Integer::sum);
                    }
                }
            }
            rows.add(row);
            System.out.printf("Avaliação %d/%d: %s%n", rows.size(), questions.size(), question.id());
        }

        String model = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        String fingerprint = new OllamaEmbeddingClient(ollamaUrl, model).modelFingerprint();
        Map<String, Object> report = new HashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("corpusRevision", "29994dd8a2e6f50fa3d5607b046b54d7c6945afd");
        report.put("embeddingModel", fingerprint);
        report.put("answerableQuestions", answerable);
        report.put("unanswerableQuestions", unanswerable);
        report.put("recallAt5Definition", "Share of answerable questions with at least one expected chunk in the top five");
        report.put("hitsAt5", hits.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().name().toLowerCase(), Map.Entry::getValue)));
        report.put("questions", rows);
        Files.writeString(Path.of("evals", "results.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
        for (SearchMode mode : SearchMode.values()) {
            System.out.printf("%s: %d/%d (%.1f%%)%n", mode, hits.get(mode), answerable,
                    hits.get(mode) * 100.0 / answerable);
        }
        double minimum = environment.getProperty("secdocs.min-recall", Double.class, 0.0);
        if (minimum < 0 || minimum > 1) {
            throw new IllegalArgumentException("secdocs.min-recall deve estar entre 0 e 1");
        }
        if (hits.get(SearchMode.VECTOR) / (double) answerable < minimum) {
            throw new IllegalStateException("Recall@5 vetorial abaixo do limite: " + minimum);
        }
    }

    private List<GoldenQuestion> readQuestions(Path path) throws Exception {
        List<?> raw = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), List.class);
        List<GoldenQuestion> questions = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("Pergunta inválida no golden set");
            }
            String id = (String) row.get("id");
            String question = (String) row.get("question");
            boolean answerable = Boolean.TRUE.equals(row.get("answerable"));
            String referenceAnswer = (String) row.get("referenceAnswer");
            List<?> rawIds = (List<?>) row.get("expectedChunkIds");
            List<String> expectedIds = rawIds.stream().map(String.class::cast).toList();
            if (id == null || question == null || answerable == expectedIds.isEmpty()
                    || (answerable && (referenceAnswer == null || referenceAnswer.isBlank()))) {
                throw new IllegalArgumentException("Pergunta inconsistente: " + id);
            }
            questions.add(new GoldenQuestion(id, question, expectedIds, answerable));
        }
        Set<String> uniqueIds = questions.stream().map(GoldenQuestion::id).collect(Collectors.toSet());
        if (uniqueIds.size() != questions.size()) {
            throw new IllegalArgumentException("IDs repetidos no golden set");
        }
        return questions;
    }

    private record GoldenQuestion(String id, String question, List<String> expectedChunkIds, boolean answerable) {
    }
}
