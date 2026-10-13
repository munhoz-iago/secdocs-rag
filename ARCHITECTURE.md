# Arquitetura

## Fluxo de dados

1. `SourceCatalog` lista 30 arquivos Markdown em três repositórios da OWASP, com revisão fixa por arquivo. `OwaspIngestion` baixa cada arquivo e registra origem, URL, revisão e licença.
2. `MarkdownChunker` divide o texto por títulos, com teto aproximado de 3.500 caracteres. Antes de gravar, `StoreChunks` exige que todas as fontes do catálogo estejam presentes e confere IDs e metadados dos trechos. Faz upsert por ID no PostgreSQL. Conteúdo alterado invalida seu embedding; fontes reimportadas perdem trechos antigos, e os trechos de fontes removidas do catálogo são apagados.
3. `EmbedChunks` pede vetores de 1.024 dimensões ao `bge-m3:567m` no Ollama. O digest do modelo é salvo junto do vetor para impedir mistura acidental de versões.
4. `SearchEngine` executa busca vetorial por cosseno com índice HNSW e busca textual com `tsvector` e stemming inglês; os termos da pergunta são combinados por OR, montados a partir dos lexemas, para que aspas e sinais de menos não virem operadores. A busca híbrida soma `1 / (60 + posição)` para até 20 candidatos de cada modalidade.
5. `AnswerService` recupera os cinco melhores trechos vetoriais, recusa se a maior similaridade for menor que `0,55` e envia a pergunta e os trechos ao `qwen3:1.7b` local.
6. `EvidencePassages` produz excertos literais com IDs locais à consulta, de 20 a 600 caracteres, até 30 excertos ou 14.000 caracteres. O modelo devolve JSON com resposta, indicador de recusa e até três IDs; o servidor resolve os IDs para texto original. IDs desconhecidos ou repetidos são rejeitados. Código entre crases ou em bloco cercado só passa se aparecer literalmente dentro de uma citação validada. Fora desses trechos, sinais de código continuam causando recusa. Uma falha de citação ou código admite no máximo uma reparação com a mesma evidência e o mesmo validador. A API registra resultado, tentativas, duração e evidências recuperadas. A verificação de código é heurística e não detecta toda sintaxe.
7. `WorkspaceService` lê catálogo e relatórios empacotados para a interface de fontes e avaliação. Não consulta banco ou modelo, nem apresenta snapshots como estado vivo. JavaScript progressivo atualiza consulta, filtros e visualizações; o formulário de consulta tem fallback Thymeleaf.
8. `scripts/export_demo.py` exporta a página renderizada, os arquivos estáticos e os resultados reais para GitHub Pages. A demonstração pública reproduz casos gravados, com identificação explícita; perguntas novas exigem a aplicação local.

## Componentes e contratos

| Camada | Arquivos principais | Responsabilidade |
|---|---|---|
| Entrada | `AnswerController`, `SearchController`, `WebPageController` | Validar JSON e renderizar o formulário HTML |
| Ingestão | `OwaspIngestion`, `MarkdownChunker`, `StoreChunks` | Criar e persistir trechos reproduzíveis |
| Vetores | `OllamaEmbeddingClient`, `EmbedChunks` | Gerar vetores e guardar digest do modelo |
| Recuperação | `SearchEngine`, `SearchHit` | Buscar, ordenar e fundir resultados |
| Geração | `EvidencePassages`, `OllamaAnswerModel`, `AnswerService` | Selecionar excertos, montar citações e validar código |
| Avaliação | `EvaluateRetrieval`, `EvaluateGeneration`, `ProbePromptInjection` | Medir recuperação, recusas/latência e executar uma prova adversarial |
| Apresentação | `WorkspaceController`, `WorkspaceService`, HTML/CSS/JS | Consultar, explorar fontes e visualizar snapshots |

## Persistência

`V1__create_document_chunks.sql` cria a tabela de trechos e o índice textual. `V2__add_embedding_index.sql` acrescenta a coluna `vector(1024)` e o índice HNSW. Flyway aplica as migrações automaticamente quando o banco está configurado. O banco e o Ollama ficam em volumes locais do Docker; as portas são publicadas somente no loopback.

## Decisões

- [0001: embeddings locais multilíngues](docs/decisions/0001-local-multilingual-embeddings.md)
- [0002: recuperação medida e geração local](docs/decisions/0002-retrieval-and-generation.md)
- [0003: citações montadas pelo servidor](docs/decisions/0003-server-owned-citations.md)
- [Protocolo para avaliação independente](BLIND_EVAL_PROTOCOL.md)

## Fronteiras de confiança

Pergunta e documentos são dados externos. Consultas SQL usam parâmetros. O conteúdo recuperado é marcado como evidência, não como instrução ao modelo. O modelo ainda pode interpretar mal esses dados; a citação montada pelo servidor comprova a origem do texto citado, sem validar todas as afirmações da resposta. Thymeleaf escapa conteúdo; JavaScript usa texto e elementos DOM, e links são restritos aos arquivos OWASP fixados. A demonstração pública contém somente fontes públicas e casos de desenvolvimento; não expõe o banco nem o Ollama local.
