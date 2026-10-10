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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.secdocs.embed.OllamaEmbeddingClient;
import dev.secdocs.ingest.OwaspSource;
import dev.secdocs.ingest.SourceCatalog;
import dev.secdocs.search.SearchEngine;
import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "eval", havingValue = "true")
public class EvaluateRetrieval implements ApplicationRunner {

    static final int MIN_ANSWERABLE = 10;
    static final int MIN_UNANSWERABLE = 1;

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
        List<GoldenQuestion> questions = parseQuestions(Files.readString(goldenPath, StandardCharsets.UTF_8));
        checkComposition(questions);
        long answerable = questions.stream().filter(GoldenQuestion::answerable).count();
        long unanswerable = questions.size() - answerable;

        Set<String> expectedIds = questions.stream().flatMap(question -> question.expectedChunkIds().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missingIds = new TreeSet<>(expectedIds);
        missingIds.removeAll(searchEngine.existingChunkIds(expectedIds));
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException("IDs esperados ausentes no banco; rode store ou corrija o golden set: "
                    + missingIds);
        }

        Tally tally = new Tally();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GoldenQuestion question : questions) {
            Map<SearchMode, List<SearchHit>> results = searchEngine.compare(question.question(), 5);
            Map<SearchMode, List<SearchHit>> englishResults = question.englishQuestion() == null
                    ? null : searchEngine.compare(question.englishQuestion(), 5);
            Map<String, Object> row = new HashMap<>();
            row.put("id", question.id());
            row.put("question", question.question());
            row.put("translated", question.englishQuestion() != null);
            if (question.englishQuestion() != null) {
                row.put("englishQuestion", question.englishQuestion());
            }
            row.put("answerable", question.answerable());
            row.put("expectedChunkIds", question.expectedChunkIds());
            Map<SearchMode, List<String>> found = ids(results);
            Map<SearchMode, List<String>> foundEnglish = englishResults == null ? null : ids(englishResults);
            for (SearchMode mode : SearchMode.values()) {
                String name = mode.name().toLowerCase();
                row.put(name + "Top5", found.get(mode));
                row.put(name + "TopScore",
                        results.get(mode).isEmpty() ? null : results.get(mode).getFirst().score());
                if (foundEnglish != null) {
                    row.put(name + "EnglishTop5", foundEnglish.get(mode));
                }
                if (question.answerable()) {
                    row.put(name + "Hit", isHit(found.get(mode), question.expectedChunkIds()));
                    if (foundEnglish != null) {
                        row.put(name + "EnglishHit", isHit(foundEnglish.get(mode), question.expectedChunkIds()));
                    }
                }
            }
            tally.add(question, found, foundEnglish);
            rows.add(row);
            System.out.printf("Avaliação %d/%d: %s%n", rows.size(), questions.size(), question.id());
        }

        String model = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        String fingerprint = new OllamaEmbeddingClient(ollamaUrl, model).modelFingerprint();
        Map<String, Object> report = new HashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("corpusRevisions", corpusRevisions(new SourceCatalog().load()));
        report.put("embeddingModel", fingerprint);
        report.put("answerableQuestions", answerable);
        report.put("unanswerableQuestions", unanswerable);
        report.put("recallAt5Definition", "Share of answerable questions with at least one expected chunk in the top five");
        report.put("hitsAt5", byMode(tally.hits));
        report.put("translatedQuestions", tally.translated);
        report.put("translatedSubsetDefinition",
                "Answerable questions with an explicit englishQuestion; both counts use only this subset");
        report.put("originalHitsOnTranslatedAt5", byMode(tally.originalOnTranslated));
        report.put("englishHitsAt5", byMode(tally.englishHits));
        report.put("questions", rows);
        Files.writeString(Path.of("evals", "results.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
        for (SearchMode mode : SearchMode.values()) {
            System.out.printf("%s: %d/%d (%.1f%%); traduzidas, original %d/%d e inglês %d/%d%n",
                    mode, tally.hits.get(mode), answerable, tally.hits.get(mode) * 100.0 / answerable,
                    tally.originalOnTranslated.get(mode), tally.translated,
                    tally.englishHits.get(mode), tally.translated);
        }
        double minimum = environment.getProperty("secdocs.min-recall", Double.class, 0.0);
        if (minimum < 0 || minimum > 1) {
            throw new IllegalArgumentException("secdocs.min-recall deve estar entre 0 e 1");
        }
        if (tally.hits.get(SearchMode.VECTOR) / (double) answerable < minimum) {
            throw new IllegalStateException("Recall@5 vetorial abaixo do limite: " + minimum);
        }
    }

    private static Map<SearchMode, List<String>> ids(Map<SearchMode, List<SearchHit>> results) {
        Map<SearchMode, List<String>> ids = new EnumMap<>(SearchMode.class);
        results.forEach((mode, hits) -> ids.put(mode, hits.stream().map(SearchHit::id).toList()));
        return ids;
    }

    private static Map<String, Integer> byMode(Map<SearchMode, Integer> counts) {
        return counts.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().name().toLowerCase(), Map.Entry::getValue));
    }

    static boolean isHit(List<String> found, List<String> expected) {
        return found.stream().anyMatch(expected::contains);
    }

    /** Hit counts; English hits and their original-language baseline cover only translated answerable items. */
    static final class Tally {
        final Map<SearchMode, Integer> hits = zeros();
        final Map<SearchMode, Integer> originalOnTranslated = zeros();
        final Map<SearchMode, Integer> englishHits = zeros();
        int translated;

        void add(GoldenQuestion question, Map<SearchMode, List<String>> found,
                 Map<SearchMode, List<String>> foundEnglish) {
            if (!question.answerable()) {
                return;
            }
            boolean hasEnglish = question.englishQuestion() != null;
            if (hasEnglish) {
                translated++;
            }
            for (SearchMode mode : SearchMode.values()) {
                boolean hit = isHit(found.get(mode), question.expectedChunkIds());
                if (hit) {
                    hits.merge(mode, 1, Integer::sum);
                }
                if (hasEnglish) {
                    if (hit) {
                        originalOnTranslated.merge(mode, 1, Integer::sum);
                    }
                    if (isHit(foundEnglish.get(mode), question.expectedChunkIds())) {
                        englishHits.merge(mode, 1, Integer::sum);
                    }
                }
            }
        }

        private static Map<SearchMode, Integer> zeros() {
            Map<SearchMode, Integer> counts = new EnumMap<>(SearchMode.class);
            for (SearchMode mode : SearchMode.values()) {
                counts.put(mode, 0);
            }
            return counts;
        }
    }

    static Map<String, String> corpusRevisions(List<OwaspSource> sources) {
        return sources.stream().collect(Collectors.groupingBy(OwaspSource::repository, TreeMap::new,
                Collectors.mapping(OwaspSource::revision,
                        Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new),
                                revisions -> String.join(",", revisions)))));
    }

    static void checkComposition(List<GoldenQuestion> questions) {
        long answerable = questions.stream().filter(GoldenQuestion::answerable).count();
        long unanswerable = questions.size() - answerable;
        if (answerable < MIN_ANSWERABLE || unanswerable < MIN_UNANSWERABLE) {
            throw new IllegalStateException("O golden set precisa de pelo menos " + MIN_ANSWERABLE
                    + " perguntas respondíveis e " + MIN_UNANSWERABLE + " sem resposta; encontrado "
                    + answerable + " e " + unanswerable);
        }
    }

    List<GoldenQuestion> parseQuestions(String json) {
        List<?> raw = objectMapper.readValue(json, List.class);
        List<GoldenQuestion> questions = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("Pergunta inválida no golden set");
            }
            String id = (String) row.get("id");
            String question = (String) row.get("question");
            Object rawEnglish = row.get("englishQuestion");
            if (rawEnglish != null && !(rawEnglish instanceof String text && !text.isBlank()
                    && !text.strip().equals(question == null ? null : question.strip()))) {
                throw new IllegalArgumentException("Pergunta em inglês inválida: " + id);
            }
            String englishQuestion = (String) rawEnglish;
            boolean answerable = Boolean.TRUE.equals(row.get("answerable"));
            String referenceAnswer = (String) row.get("referenceAnswer");
            if (!(row.get("expectedChunkIds") instanceof List<?> rawIds)) {
                throw new IllegalArgumentException("IDs esperados ausentes: " + id);
            }
            List<String> expectedIds = rawIds.stream().map(String.class::cast).toList();
            if (id == null || question == null || answerable == expectedIds.isEmpty()
                    || (answerable && (referenceAnswer == null || referenceAnswer.isBlank()))) {
                throw new IllegalArgumentException("Pergunta inconsistente: " + id);
            }
            questions.add(new GoldenQuestion(id, question, englishQuestion, expectedIds, answerable));
        }
        Set<String> uniqueIds = questions.stream().map(GoldenQuestion::id).collect(Collectors.toSet());
        if (uniqueIds.size() != questions.size()) {
            throw new IllegalArgumentException("IDs repetidos no golden set");
        }
        return questions;
    }

    record GoldenQuestion(String id, String question, String englishQuestion,
                          List<String> expectedChunkIds, boolean answerable) {
    }
}
