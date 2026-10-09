# Arquitetura

## Fluxo de dados

1. `SourceCatalog` lista três arquivos Markdown da OWASP e fixa a revisão do GitHub. `OwaspIngestion` baixa cada arquivo e registra origem, URL, revisão e licença.
2. `MarkdownChunker` divide o texto por títulos, com teto aproximado de 3.500 caracteres. `StoreChunks` faz upsert por ID no PostgreSQL. Conteúdo alterado invalida seu embedding; fontes reimportadas perdem trechos antigos.
3. `EmbedChunks` pede vetores de 1.024 dimensões ao `bge-m3:567m` no Ollama. O digest do modelo é salvo junto do vetor para impedir mistura acidental de versões.
4. `SearchEngine` executa busca vetorial por cosseno com índice HNSW e busca textual com `tsvector` em inglês. A busca híbrida soma `1 / (60 + posição)` para até 20 candidatos de cada modalidade.
5. `AnswerService` recupera os cinco melhores trechos vetoriais, recusa se a maior similaridade for menor que `0,55` e envia a pergunta e os trechos ao `qwen3:1.7b` local.
6. O modelo devolve JSON com resposta, indicador de recusa e até três pares de ID/trecho. O servidor descarta citações com ID desconhecido ou texto não contíguo no trecho original. Também recusa respostas com exemplos de código ou comandos SQL, pois um teste local revelou um exemplo não sustentado pela citação. Se nenhuma citação válida restar, devolve recusa.

## Componentes e contratos

| Camada | Arquivos principais | Responsabilidade |
|---|---|---|
| Entrada | `AnswerController`, `SearchController`, `WebPageController` | Validar JSON e renderizar o formulário HTML |
| Ingestão | `OwaspIngestion`, `MarkdownChunker`, `StoreChunks` | Criar e persistir trechos reproduzíveis |
| Vetores | `OllamaEmbeddingClient`, `EmbedChunks` | Gerar vetores e guardar digest do modelo |
| Recuperação | `SearchEngine`, `SearchHit` | Buscar, ordenar e fundir resultados |
| Geração | `OllamaAnswerModel`, `AnswerService` | Responder e validar citações |
| Avaliação | `EvaluateRetrieval`, `ProbePromptInjection` | Medir recall e executar uma prova adversarial |

## Persistência

`V1__create_document_chunks.sql` cria a tabela de trechos e o índice textual. `V2__add_embedding_index.sql` acrescenta a coluna `vector(1024)` e o índice HNSW. Flyway aplica as migrações automaticamente quando o banco está configurado. O banco e o Ollama ficam em volumes locais do Docker; as portas são publicadas somente no loopback.

## Decisões

- [0001: embeddings locais multilíngues](docs/decisions/0001-local-multilingual-embeddings.md)
- [0002: recuperação medida e geração local](docs/decisions/0002-retrieval-and-generation.md)

## Fronteiras de confiança

Pergunta e documentos são dados externos. Consultas SQL usam parâmetros. O conteúdo recuperado é marcado como evidência, não como instrução ao modelo. O modelo ainda pode interpretar mal esses dados; a validação de citações só comprova que a frase citada existe no trecho, não que todas as frases da resposta sejam verdadeiras. O Thymeleaf escapa resposta e trechos na página, e o controlador só transforma URLs OWASP permitidas em links.
