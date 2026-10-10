# Plano de estudos do SecDocs RAG

Este plano transforma o repositório em um percurso de aprendizado. Cada sessão deve terminar com uma evidência pequena: uma anotação, um teste, uma comparação medida ou um commit. Reserve 30 a 60 minutos por sessão e use o [guia geral](GUIA_GERAL_SECDOCS_RAG.md) como referência.

## Semana 1 — Java e Spring Boot

| Sessão | Leia | Faça | Explique em voz alta |
|---|---|---|---|
| 1 | `DocumentChunk`, `MarkdownChunker` | Desenhe o caminho de um Markdown até um trecho | Por que `record` é adequado para estes dados? |
| 2 | `OwaspIngestion`, `SourceCatalog`, `owasp-sources.properties` | Identifique repositório, revisão, URL e licença de cada uma das 30 fontes | Como a revisão fixa ajuda a reproduzir o corpus? |
| 3 | `StoreChunks` e migração V1 | Execute a gravação duas vezes e compare a contagem | O que é upsert e quando um vetor deve ser invalidado? |
| 4 | `AnswerController`, `WebPageController` | Faça uma pergunta pela API e pelo formulário | Onde ocorre a validação de entrada? |
| 5 | Testes `MarkdownChunkerTests` | Acrescente um teste para um título ou trecho limite | Qual comportamento o teste protege? |

## Semana 2 — Vetores, SQL e busca

| Sessão | Leia | Faça | Explique em voz alta |
|---|---|---|---|
| 6 | `OllamaEmbeddingClient`, `EmbedChunks` | Confira dimensão e digest dos vetores no banco | O que um embedding representa e por que guardar o digest? |
| 7 | Migração V2, `VectorSql` | Compare a consulta de distância com a lista retornada | O que a distância de cosseno ordena? |
| 8 | `SearchEngine.keyword` | Teste a mesma pergunta em português e inglês | Por que o índice textual inglês pode perder a pergunta em português? |
| 9 | `SearchEngine.fuse` | Calcule à mão o RRF de dois resultados | Por que RRF usa posição e não trata score como probabilidade? |
| 10 | `evals/results.json` | Encontre uma pergunta que a busca vetorial acerta e a híbrida perde (na execução atual, `auth-06`; na anterior era `auth-08`) | Como a fusão fez o trecho certo sair do top 5? |

## Semana 3 — Respostas, citações e segurança

| Sessão | Leia | Faça | Explique em voz alta |
|---|---|---|---|
| 11 | `OllamaAnswerModel` | Compare pergunta, evidência e JSON devolvido | Por que texto recuperado é dado, não instrução? |
| 12 | `AnswerService` | Teste pergunta respondível e pergunta fora do acervo | Qual a diferença entre limiar de similaridade e recusa do modelo? |
| 13 | `AnswerServiceTests` | Crie uma citação com uma palavra alterada | O que a verificação literal garante e o que não garante? |
| 14 | `ProbePromptInjection` | Rode a prova adversarial e leia o JSON bruto/validado | Foi o modelo ou o validador que impediu a instrução? |
| 15 | `ApiRateLimitFilter` | Faça requisições inválidas até receber HTTP 429 | Quais limites deste controle em um serviço público? |

## Semana 4 — Avaliação e apresentação

| Sessão | Leia | Faça | Explique em voz alta |
|---|---|---|---|
| 16 | `evals/golden-set.json` | Escreva cinco perguntas novas sem olhar os resultados | Como evitar adaptar perguntas ao algoritmo? |
| 17 | `EvaluateRetrieval` | Rode a avaliação e recalcule recall@5 | O que entra no denominador? |
| 18 | `EVALS.md`, `LIMITATIONS.md` | Revise manualmente cinco respostas, afirmação por afirmação | Por que uma citação literal não prova toda a resposta? |
| 19 | Workflow `.github/workflows/ci.yml` | Leia o log de CI e reproduza um erro localmente | O que o CI de fato executa? |
| 20 | README e este plano | Grave uma explicação de três minutos para entrevista | Que resultado você mediu e qual é o próximo experimento? |

## Registro de aprendizado

Copie este bloco para suas anotações ao fim de cada sessão:

```text
Data e sessão:
O que li:
O que executei e resultado observado:
Uma decisão técnica que entendi:
Uma dúvida ou hipótese para testar:
Link para commit, teste ou avaliação:
```

## Próximo experimento independente

O conjunto atual tem 45 perguntas e foi criado com conhecimento das seções. Para medir generalização, siga o [protocolo para avaliação independente](BLIND_EVAL_PROTOCOL.md): peça a outra pessoa que escreva perguntas reais sem consultar `evals/results.json`. Registre a fonte esperada antes de rodar qualquer busca. Separe perguntas com resposta das que estão próximas do tema mas fora do corpus. Só então avalie recuperação, recusa e fundamentação. Não ajuste o limiar olhando o mesmo conjunto usado para reportar o resultado final.

## Perguntas frequentes de entrevista

1. Por que escolheu Java e Spring Boot para este RAG?
2. Por que os vetores ficam no PostgreSQL em vez de um serviço vetorial separado?
3. O que mudou quando a busca textual e a vetorial foram combinadas?
4. Como evita citar um trecho que o modelo inventou?
5. O que acontece se os documentos tiverem uma instrução maliciosa?
6. Qual métrica mede recuperação e qual ainda precisa de revisão humana?
7. O que você mudaria antes de publicar a API na internet?
