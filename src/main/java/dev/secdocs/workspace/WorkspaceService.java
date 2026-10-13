package dev.secdocs.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import dev.secdocs.ingest.SourceCatalog;
import dev.secdocs.workspace.WorkspaceResponse.Case;
import dev.secdocs.workspace.WorkspaceResponse.Evaluation;
import dev.secdocs.workspace.WorkspaceResponse.Metric;
import dev.secdocs.workspace.WorkspaceResponse.Source;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Reads only packaged catalog metadata and published evaluation snapshots. */
@Service
public class WorkspaceService {
    static final String RETRIEVAL_PATH = "/evals/results.json";
    static final String GENERATION_PATH = "/evals/generation-results.json";
    static final String GENERATION_BASELINE_PATH = "/evals/generation-baseline.json";
    private static final String DATASET_KIND = "development";
    private static final String DATA_SOURCE = "published-snapshot";
    private static final List<String> MODES = List.of("vector", "keyword", "hybrid");
    private static final List<String> LABELS = List.of("Vetorial", "Textual", "Híbrida");

    private final SourceCatalog catalog;
    private final Resource retrieval;
    private final Resource generation;
    private final Resource generationBaseline;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public WorkspaceService() {
        this(new SourceCatalog(), new ClassPathResource(RETRIEVAL_PATH), new ClassPathResource(GENERATION_PATH),
                new ClassPathResource(GENERATION_BASELINE_PATH));
    }

    WorkspaceService(SourceCatalog catalog, Resource retrieval, Resource generation, Resource generationBaseline) {
        this.catalog = catalog;
        this.retrieval = retrieval;
        this.generation = generation;
        this.generationBaseline = generationBaseline;
    }

    public WorkspaceResponse read() throws IOException {
        List<Source> sources = catalog.load().stream()
                .map(source -> new Source(source.id(), source.title(), source.repository(), source.sourceUrl(),
                        source.revision(), source.license()))
                .toList();
        return new WorkspaceResponse(sources, readEvaluation(),
                readGeneration(generation, GENERATION_PATH, "geração"),
                readGeneration(generationBaseline, GENERATION_BASELINE_PATH, "baseline de geração"));
    }

    private Evaluation readEvaluation() {
        if (!retrieval.exists()) {
            return unavailableEvaluation("Snapshot publicado de recuperação não encontrado no classpath: "
                    + RETRIEVAL_PATH + ".");
        }
        try {
            return mapEvaluation(readObject(retrieval));
        } catch (IOException | RuntimeException exception) {
            return unavailableEvaluation("Snapshot publicado de recuperação inválido ou ilegível ("
                    + RETRIEVAL_PATH + "). É necessário um relatório JSON consistente; nenhuma avaliação foi executada.");
        }
    }

    private Map<String, Object> readGeneration(Resource resource, String path, String name) {
        if (!resource.exists()) {
            return unavailableGeneration(path, "Snapshot publicado de " + name + " não encontrado no classpath: "
                    + path + ". A avaliação ainda não está disponível.");
        }
        try {
            Map<?, ?> snapshot = readObject(resource);
            validateGeneration(snapshot);
            Map<String, Object> result = new LinkedHashMap<>();
            snapshot.forEach((key, value) -> result.put((String) key, value));
            result.put("available", true);
            result.put("datasetKind", DATASET_KIND);
            result.put("dataSource", DATA_SOURCE);
            result.put("snapshot", path);
            result.put("description", "Snapshot publicado de " + name + "; resultados da execução registrada, "
                    + "sem consulta ao banco ou ao Ollama.");
            return result;
        } catch (IOException | RuntimeException exception) {
            return unavailableGeneration(path, "Snapshot publicado de " + name + " inválido ou ilegível ("
                    + path + "). O relatório deve identificar data=development, generatedAt válido e cases "
                    + "como lista de objetos com id; summary deve ser um objeto quando presente.");
        }
    }

    private static void validateGeneration(Map<?, ?> snapshot) {
        if (!DATASET_KIND.equals(snapshot.get("data"))) {
            throw new IllegalArgumentException("Conjunto de desenvolvimento não identificado");
        }
        Instant.parse(text(snapshot, "generatedAt"));
        for (Object item : list(snapshot, "cases")) {
            text(object(item), "id");
        }
        if (snapshot.containsKey("summary")) {
            object(snapshot.get("summary"));
        }
    }

    private Map<?, ?> readObject(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return object(mapper.readValue(input, Object.class));
        }
    }

    private static Evaluation mapEvaluation(Map<?, ?> snapshot) {
        String generatedAt = text(snapshot, "generatedAt");
        Instant.parse(generatedAt);
        String embeddingModel = text(snapshot, "embeddingModel");
        int answerable = count(snapshot, "answerableQuestions");
        int unanswerable = count(snapshot, "unanswerableQuestions");
        int total = Math.addExact(answerable, unanswerable);
        int translated = count(snapshot, "translatedQuestions");
        if (translated > answerable) {
            throw new IllegalArgumentException("Denominador traduzido inconsistente");
        }

        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int actualAnswerable = 0;
        int actualTranslated = 0;
        Map<String, Integer> actualHits = zeros();
        Map<String, Integer> actualOriginalTranslated = zeros();
        Map<String, Integer> actualEnglish = zeros();
        for (Object item : list(snapshot, "questions")) {
            Map<?, ?> row = object(item);
            String id = text(row, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("ID de pergunta repetido");
            }
            String question = text(row, "question");
            boolean canAnswer = bool(row, "answerable");
            boolean isTranslated = bool(row, "translated");
            if (isTranslated) {
                text(row, "englishQuestion");
            }
            if (canAnswer) {
                actualAnswerable++;
                if (isTranslated) {
                    actualTranslated++;
                }
                for (String mode : MODES) {
                    if (bool(row, mode + "Hit")) {
                        actualHits.merge(mode, 1, Integer::sum);
                        if (isTranslated) {
                            actualOriginalTranslated.merge(mode, 1, Integer::sum);
                        }
                    }
                    if (isTranslated && bool(row, mode + "EnglishHit")) {
                        actualEnglish.merge(mode, 1, Integer::sum);
                    }
                }
            }
            cases.add(new Case(id, question, canAnswer,
                    hit(row, "vectorHit", canAnswer), hit(row, "keywordHit", canAnswer),
                    hit(row, "hybridHit", canAnswer), top5(row, "vectorTop5"),
                    top5(row, "keywordTop5"), top5(row, "hybridTop5")));
        }
        if (cases.size() != total || actualAnswerable != answerable || actualTranslated != translated) {
            throw new IllegalArgumentException("Contagens de perguntas inconsistentes");
        }
        List<Metric> metrics = metrics(snapshot, "hitsAt5", answerable, actualHits);
        List<Metric> originals = metrics(snapshot, "originalHitsOnTranslatedAt5", translated,
                actualOriginalTranslated);
        List<Metric> english = metrics(snapshot, "englishHitsAt5", translated, actualEnglish);
        Map<String, String> revisions = new LinkedHashMap<>();
        object(snapshot.get("corpusRevisions")).forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()
                    || !(value instanceof String revision) || revision.isBlank()) {
                throw new IllegalArgumentException("Revisão de corpus inválida");
            }
            revisions.put(name, revision);
        });
        if (revisions.isEmpty()) {
            throw new IllegalArgumentException("Revisões de corpus ausentes");
        }
        return new Evaluation(true, generatedAt, total, answerable, unanswerable, translated, embeddingModel,
                List.copyOf(metrics), List.copyOf(cases), DATASET_KIND, Map.copyOf(revisions),
                text(snapshot, "recallAt5Definition"), text(snapshot, "translatedSubsetDefinition"),
                List.copyOf(originals), List.copyOf(english), RETRIEVAL_PATH, DATA_SOURCE,
                "Snapshot publicado de desenvolvimento; métricas da execução registrada, "
                        + "sem consulta ao banco ou ao Ollama.");
    }

    private static List<Metric> metrics(Map<?, ?> snapshot, String key, int denominator,
                                        Map<String, Integer> actual) {
        Map<?, ?> counts = object(snapshot.get(key));
        List<Metric> metrics = new ArrayList<>();
        for (int index = 0; index < MODES.size(); index++) {
            String mode = MODES.get(index);
            int hits = count(counts, mode);
            if (hits > denominator || hits != actual.get(mode)) {
                throw new IllegalArgumentException("Contagem de acertos inconsistente: " + key + "." + mode);
            }
            metrics.add(new Metric(mode, LABELS.get(index), hits, denominator,
                    denominator == 0 ? 0.0 : hits / (double) denominator));
        }
        return metrics;
    }

    private static Boolean hit(Map<?, ?> row, String key, boolean answerable) {
        if (answerable) {
            return bool(row, key);
        }
        if (row.get(key) != null) {
            throw new IllegalArgumentException("Acerto não aplicável a pergunta sem resposta");
        }
        return null;
    }

    private static List<String> top5(Map<?, ?> row, String key) {
        List<?> values = list(row, key);
        if (values.size() > 5) {
            throw new IllegalArgumentException("Top 5 inválido");
        }
        return values.stream().map(value -> {
            if (!(value instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("ID de trecho inválido");
            }
            return id;
        }).toList();
    }

    private static Map<String, Integer> zeros() {
        Map<String, Integer> result = new LinkedHashMap<>();
        MODES.forEach(mode -> result.put(mode, 0));
        return result;
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> object) {
            return object;
        }
        throw new IllegalArgumentException("Objeto JSON esperado");
    }

    private static List<?> list(Map<?, ?> object, String key) {
        if (object.get(key) instanceof List<?> values) {
            return values;
        }
        throw new IllegalArgumentException("Lista inválida: " + key);
    }

    private static String text(Map<?, ?> object, String key) {
        if (object.get(key) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalArgumentException("Texto inválido: " + key);
    }

    private static boolean bool(Map<?, ?> object, String key) {
        if (object.get(key) instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException("Booleano inválido: " + key);
    }

    private static int count(Map<?, ?> object, String key) {
        Object raw = object.get(key);
        if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() >= 0
                && ((Number) raw).longValue() <= Integer.MAX_VALUE) {
            return ((Number) raw).intValue();
        }
        throw new IllegalArgumentException("Contagem inválida: " + key);
    }

    private static Evaluation unavailableEvaluation(String description) {
        return new Evaluation(false, null, null, null, null, null, null, List.of(), List.of(), DATASET_KIND,
                Map.of(), null, null, List.of(), List.of(), RETRIEVAL_PATH, DATA_SOURCE, description);
    }

    private static Map<String, Object> unavailableGeneration(String path, String description) {
        return Map.of("available", false, "datasetKind", DATASET_KIND, "dataSource", DATA_SOURCE,
                "snapshot", path, "description", description);
    }
}
