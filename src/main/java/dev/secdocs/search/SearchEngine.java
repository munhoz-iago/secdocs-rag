package dev.secdocs.search;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.secdocs.embed.OllamaEmbeddingClient;
import dev.secdocs.embed.VectorSql;

@Service
public class SearchEngine {

    private static final int CANDIDATES = 20;
    private static final int RRF_K = 60;

    public List<SearchHit> search(String question, SearchMode mode, int limit) throws Exception {
        if (mode == SearchMode.HYBRID) {
            return compare(question, limit).get(SearchMode.HYBRID);
        }
        try (Connection connection = connect()) {
            return mode == SearchMode.KEYWORD
                    ? keyword(connection, question, limit)
                    : vector(connection, question, limit);
        }
    }

    public Map<SearchMode, List<SearchHit>> compare(String question, int limit) throws Exception {
        try (Connection connection = connect()) {
            List<SearchHit> keywords = keyword(connection, question, CANDIDATES);
            List<SearchHit> vectors = vector(connection, question, CANDIDATES);
            Map<SearchMode, List<SearchHit>> result = new EnumMap<>(SearchMode.class);
            result.put(SearchMode.KEYWORD, keywords.stream().limit(limit).toList());
            result.put(SearchMode.VECTOR, vectors.stream().limit(limit).toList());
            result.put(SearchMode.HYBRID, fuse(keywords, vectors).stream().limit(limit).toList());
            return result;
        }
    }

    private static List<SearchHit> keyword(Connection connection, String question, int limit) throws SQLException {
        String sql = """
                SELECT id, source_title, section, source_url, content,
                       ts_rank_cd(search_text, websearch_to_tsquery('english', ?)) AS score
                FROM document_chunks
                WHERE search_text @@ websearch_to_tsquery('english', ?)
                ORDER BY score DESC, id
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, question);
            statement.setString(2, question);
            statement.setInt(3, limit);
            return rows(statement, SearchMode.KEYWORD);
        }
    }

    private static List<SearchHit> vector(Connection connection, String question, int limit) throws Exception {
        String model = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(ollamaUrl, model);
        String fingerprint = client.modelFingerprint();
        String queryVector = VectorSql.literal(client.embed(List.of(question)).getFirst());
        String sql = """
                SELECT id, source_title, section, source_url, content,
                       1 - (embedding <=> ?::vector) AS score
                FROM document_chunks
                WHERE embedding IS NOT NULL AND embedding_model = ?
                ORDER BY embedding <=> ?::vector, id
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, queryVector);
            statement.setString(2, fingerprint);
            statement.setString(3, queryVector);
            statement.setInt(4, limit);
            return rows(statement, SearchMode.VECTOR);
        }
    }

    private static List<SearchHit> rows(PreparedStatement statement, SearchMode mode) throws SQLException {
        List<SearchHit> hits = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                hits.add(new SearchHit(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getDouble(6), mode));
            }
        }
        return hits;
    }

    public static List<SearchHit> fuse(List<SearchHit> keywords, List<SearchHit> vectors) {
        Map<String, SearchHit> sources = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        addRanks(keywords, sources, scores);
        addRanks(vectors, sources, scores);
        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    SearchHit hit = sources.get(entry.getKey());
                    return new SearchHit(hit.id(), hit.sourceTitle(), hit.section(), hit.sourceUrl(),
                            hit.content(), entry.getValue(), SearchMode.HYBRID);
                })
                .toList();
    }

    private static void addRanks(List<SearchHit> hits, Map<String, SearchHit> sources, Map<String, Double> scores) {
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            sources.putIfAbsent(hit.id(), hit);
            scores.merge(hit.id(), 1.0 / (RRF_K + index + 1), Double::sum);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(requiredEnv("SECDOCS_DB_URL"),
                requiredEnv("SECDOCS_DB_USER"), requiredEnv("SECDOCS_DB_PASSWORD"));
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória: " + key);
        }
        return value;
    }
}
