# Arquitetura

## Fluxo de dados

1. `SourceCatalog` lista 30 arquivos Markdown em três repositórios da OWASP, com revisão fixa por arquivo. `OwaspIngestion` baixa cada arquivo e registra origem, URL, revisão e licença.
2. `MarkdownChunker` divide o texto por títulos, com teto aproximado de 3.500 caracteres. Antes de gravar, `StoreChunks` exige que todas as fontes do catálogo estejam presentes e confere IDs e metadados dos trechos. Faz upsert por ID no PostgreSQL. Conteúdo alterado invalida seu embedding; fontes reimportadas perdem trechos antigos, e os trechos de fontes removidas do catálogo são apagados.
3. `EmbedChunks` pede vetores de 1.024 dimensões ao `bge-m3:567m` no Ollama. O digest do modelo é salvo junto do vetor para impedir mistura acidental de versões.
4. `SearchEngine` executa busca vetorial por cosseno com índice HNSW e busca textual com `tsvector` e stemming inglês; os termos da pergunta são combinados por OR, montados a partir dos lexemas, para que aspas e sinais de menos não virem operadores. A busca híbrida soma `1 / (60 + posição)` para até 20 candidatos de cada modalidade.
5. `AnswerService` recupera os cinco melhores trechos vetoriais, recusa se a maior similaridade for menor que `0,55` e envia a pergunta e os trechos ao `qwen3:1.7b` local.
6. O modelo devolve JSON com resposta, indicador de recusa e até três pares de ID/trecho. O servidor exige que todas as citações tenham ID recuperado e texto contíguo no trecho original; qualquer citação inválida causa recusa. Código entre crases ou em bloco cercado só passa se aparecer literalmente dentro de uma citação validada. Fora desses trechos, o texto é recusado se tiver sinais de código, como crases soltas, ponto e vírgula em posição de código, tags, operadores de shell ou pares de palavras-chave SQL, inclusive com separadores ofuscados. Uma resposta substantiva rejeitada por essas regras pode receber uma única tentativa de correção com os mesmos trechos; a segunda saída passa pelo mesmo validador. É uma heurística e não detecta toda sintaxe.

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
- [Protocolo para avaliação independente](BLIND_EVAL_PROTOCOL.md)

## Fronteiras de confiança

Pergunta e documentos são dados externos. Consultas SQL usam parâmetros. O conteúdo recuperado é marcado como evidência, não como instrução ao modelo. O modelo ainda pode interpretar mal esses dados; a validação de citações só comprova que a frase citada existe no trecho, não que todas as frases da resposta sejam verdadeiras. O Thymeleaf escapa resposta e trechos na página, e o controlador só transforma URLs OWASP permitidas em links.
