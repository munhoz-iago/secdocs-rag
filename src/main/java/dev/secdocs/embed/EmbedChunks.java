package dev.secdocs.embed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "embed", havingValue = "true")
public class EmbedChunks implements ApplicationRunner {

    private static final int BATCH_SIZE = 8;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String url = requiredEnv("SECDOCS_DB_URL");
        String user = requiredEnv("SECDOCS_DB_USER");
        String password = requiredEnv("SECDOCS_DB_PASSWORD");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        String model = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");

        Flyway.configure().dataSource(url, user, password).load().migrate();
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(ollamaUrl, model);
        String fingerprint = client.modelFingerprint();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            List<ChunkInput> pending = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, source_title, section, content
                    FROM document_chunks
                    WHERE embedding IS NULL OR embedding_model IS DISTINCT FROM ?
                    ORDER BY id
                    """)) {
                select.setString(1, fingerprint);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        pending.add(new ChunkInput(rows.getString(1), rows.getString(2),
                                rows.getString(3), rows.getString(4)));
                    }
                }
            }

            System.out.printf("Modelo %s; %d trechos pendentes%n", fingerprint, pending.size());
            for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
                List<ChunkInput> batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
                List<String> texts = batch.stream()
                        .map(chunk -> chunk.sourceTitle() + "\n" + chunk.section() + "\n" + chunk.content())
                        .toList();
                List<List<Double>> vectors = client.embed(texts);
                saveBatch(connection, batch, vectors, fingerprint);
                System.out.printf("Vetores salvos: %d/%d%n", Math.min(start + batch.size(), pending.size()), pending.size());
            }
        }
    }

    private static void saveBatch(Connection connection, List<ChunkInput> batch,
                                  List<List<Double>> vectors, String fingerprint) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE document_chunks
                SET embedding = ?::vector, embedding_model = ?, embedded_at = now()
                WHERE id = ?
                """)) {
            for (int i = 0; i < batch.size(); i++) {
                update.setString(1, VectorSql.literal(vectors.get(i)));
                update.setString(2, fingerprint);
                update.setString(3, batch.get(i).id());
                update.addBatch();
            }
            update.executeBatch();
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória: " + key);
        }
        return value;
    }

    private record ChunkInput(String id, String sourceTitle, String section, String content) {
    }
}
