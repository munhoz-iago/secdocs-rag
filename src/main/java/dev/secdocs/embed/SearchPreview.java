package dev.secdocs.embed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "search-preview", havingValue = "true")
public class SearchPreview implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> questions = args.getOptionValues("secdocs.question");
        String question = questions == null || questions.isEmpty()
                ? "Como prevenir SQL injection?" : questions.getFirst();
        String model = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(ollamaUrl, model);
        String fingerprint = client.modelFingerprint();
        String queryVector = VectorSql.literal(client.embed(List.of(question)).getFirst());

        String sql = """
                SELECT id, section, source_url, round((1 - (embedding <=> ?::vector))::numeric, 4)
                FROM document_chunks
                WHERE embedding IS NOT NULL AND embedding_model = ?
                ORDER BY embedding <=> ?::vector
                LIMIT 5
                """;
        try (Connection connection = DriverManager.getConnection(requiredEnv("SECDOCS_DB_URL"),
                requiredEnv("SECDOCS_DB_USER"), requiredEnv("SECDOCS_DB_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, queryVector);
            statement.setString(2, fingerprint);
            statement.setString(3, queryVector);
            System.out.println("Pergunta: " + question);
            try (ResultSet rows = statement.executeQuery()) {
                int rank = 0;
                while (rows.next()) {
                    System.out.printf("%d. %s | %s | similaridade %s%n   %s%n",
                            ++rank, rows.getString(1), rows.getString(2), rows.getString(4), rows.getString(3));
                }
                if (rank == 0) {
                    System.out.println("Nenhum vetor encontrado. Rode a geração de embeddings primeiro.");
                }
            }
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória: " + key);
        }
        return value;
    }
}
