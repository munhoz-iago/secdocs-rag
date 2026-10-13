# Avaliação de geração em desenvolvimento

`evals/generation-set.json` é um conjunto **de desenvolvimento**, escrito com conhecimento do corpus e do golden set. Não é uma avaliação independente. Seus oito casos congelados são usados na mesma ordem: SQL em português (`sql-01`), PreparedStatement em Java (`sql-02`, pergunta em português), XSS em português (`xss-01`), access control no Top 10 em inglês (`top10-05`), sessões no ASVS em inglês (`asvs-05`), secrets no ASVS em inglês (`asvs-09`), previsão do tempo (`none-03`) e preço (`none-01`). São seis perguntas respondíveis e duas fora do corpus.

O harness usa `AnswerService.answer` uma vez por caso, sequencialmente. Isso inclui recuperação, geração, validação e eventuais novas tentativas internas do serviço. O harness não acrescenta tentativas nem chama o modelo diretamente para gerar respostas. As perguntas e IDs esperados são conferidos contra `evals/golden-set.json` antes de qualquer chamada ao serviço. Uma fixture malformada, duplicada, com composição diferente de 6+2 ou divergente do golden set encerra a execução com erro.

## Executar explicitamente

Com o banco, corpus, embeddings e modelo local já preparados conforme o README, execute a partir da raiz do repositório:

```powershell
$env:SECDOCS_CHAT_MODEL = 'qwen3:1.7b'
$env:SECDOCS_EMBED_MODEL = 'bge-m3:567m'
$env:SECDOCS_OLLAMA_URL = 'http://localhost:11434'
java -jar target/secdocs-rag-0.0.1-SNAPSHOT.jar --secdocs.eval-generation=true --spring.main.web-application-type=none
```

`secdocs.eval-generation` fica desligado por padrão. O relatório usa a `citationStrategy` informada nas respostas do serviço, atualmente `server-passages-v1` para a versão em que o modelo escolhe IDs de excertos fornecidos e o servidor constrói a citação literal. Estratégias diferentes na mesma execução causam erro. Para um serviço legado sem esse campo, `--secdocs.eval-generation-citation-strategy=literal-quotes-v1` informa o rótulo do baseline; esse fallback não muda a geração nem substitui o valor informado pelo serviço. Sem campo ou fallback explícito, o relatório registra `unspecified`.

O resultado é gravado em **UTF-8** em `evals/generation-results.json`, substituindo o snapshot anterior somente depois de concluir todos os casos e montar o relatório. Uma exceção na recuperação ou geração interrompe a execução; ela não vira recusa nem entra artificialmente nas contagens. Se a execução falhar, um relatório de execução anterior pode continuar no caminho: confira `generatedAt`.

## Contrato do snapshot

O relatório contém `generatedAt` em UTC, `data="development"`, `strategy="curated-known-corpus"`, `independent=false`, `fixturePath`, `fixtureSha256`, `chatModel`, `embeddingModel`, `citationStrategy`, revisões do corpus, definições das métricas, contagens, mediana e `cases[]`. `fixtureSha256` usa os bytes exatos do arquivo, inclusive espaços e quebras de linha.

`chatModel` e `embeddingModel` vêm de `SECDOCS_CHAT_MODEL` e `SECDOCS_EMBED_MODEL`, com os mesmos valores padrão do aplicativo. Uma consulta GET a `/api/tags`, com limite de cinco segundos, tenta obter **o digest do modelo de chat configurado**. O relatório registra `chatModelFingerprint="nome@digest"`; se os metadados não estiverem disponíveis, registra `null` e `chatModelFingerprintError`, sem transformar isso em uma recusa. Essa consulta não gera tokens e fica fora da latência por pergunta. O fingerprint não é o do cliente de embeddings.

Cada entrada de `cases[]` preserva a resposta devolvida pelo serviço, incluindo `answer`, `abstained` e `citations`, sem aparar texto ou reescrever excertos. São os valores finais do serviço, depois da validação; não são rascunhos brutos do Ollama. Campos adicionais, como `outcome`, `attempts`, `durationMs` e `evidence`, são preservados quando disponíveis, sem exigir esses acessores para compilar o harness.

O caso acrescenta `id`, `language`, `question`, `expectedAnswerable`, `elapsedMillis`, `acceptedAnswer`, `expectedChunkIds`, `matchedExpectedChunkIds` e `expectedCitationMatch`. Nos casos fora do corpus, `expectedCitationMatch=null`, pois não há IDs esperados.

```json
{
  "counts": {
    "answerable": {
      "total": 6,
      "answered": 3,
      "falseRefusals": 3,
      "acceptedAnswers": 2,
      "expectedCitationMatches": 1
    },
    "outside": { "total": 2, "abstained": 1, "answered": 1 }
  },
  "medianElapsedMillis": 45.0
}
```

Os números acima são **ilustrativos e sintéticos**, não resultados medidos.

## Fórmulas e interpretação

- **`acceptedAnswer`** = `!abstained && answer != null && !answer.isBlank() && !citations.isEmpty()`. É apenas aceite formal de uma resposta não vazia com citações. **Não significa resposta correta, completa ou semanticamente fundamentada.** Também não exige que o ID citado pertença aos IDs esperados.
- **`counts.answerable.answered`** = número de casos respondíveis com `abstained=false`. Uma resposta vazia ou sem citações ainda entra em `answered`, mas não em `acceptedAnswers`.
- **`counts.answerable.falseRefusals`** = número de casos respondíveis com `abstained=true`; `answered + falseRefusals = total = 6`. Taxa de recusa indevida = `falseRefusals / 6`. É uma classificação operacional de ponta a ponta e não atribui a causa ao modelo, à busca ou à validação; use `outcome` e `evidence` para investigar.
- **`counts.answerable.acceptedAnswers`** = soma de `acceptedAnswer` apenas nos seis casos respondíveis. Taxa de aceite formal = `acceptedAnswers / 6`.
- **`counts.outside.abstained`** = número de casos fora do corpus com `abstained=true`; **`counts.outside.answered`** = número com `abstained=false`, mesmo sem citações. A soma é 2. Taxa de recusa fora do corpus = `abstained / 2`.
- **`expectedCitationMatch`** = existe ao menos um ID em comum entre as citações finais e `expectedChunkIds`. Cada pergunta contribui no máximo uma unidade para `counts.answerable.expectedCitationMatches`; citações repetidas não aumentam o total. A taxa é `expectedCitationMatches / 6`. Isso mede correspondência de IDs, separadamente do aceite formal; não mede correção semântica nem prova que todas as afirmações são sustentadas. Uma fonte alternativa válida pode não coincidir com o golden set.
- **`elapsedMillis`** = duração medida com relógio monotônico em torno de `AnswerService.answer`, truncada para milissegundos. Inclui busca, chamadas ao Ollama, validação e novas tentativas do serviço; não inclui consulta de metadados nem escrita do arquivo. `durationMs`, quando presente, mantém a medição própria do serviço.
- **`medianElapsedMillis`** = mediana das oito durações, incluindo recusas. Ordenadas `t1 <= ... <= t8`, vale `(t4 + t5) / 2`. Recusas sem inferência podem reduzir essa mediana; ela não é a latência de geração condicionada a respostas aceitas.

## Comparação controlada antes/depois

Mantenha os mesmos oito casos, ordem, bytes da fixture, corpus, embeddings, modelo de chat, digest, opções do Ollama e máquina. Preserve o baseline em `evals/generation-baseline.json` e a nova execução em `evals/generation-results.json`. O baseline pertence ao processo de comparação e não é escrito por este harness. Compare primeiro `fixtureSha256` e os campos `cases[].id`, `question` e `expectedAnswerable`, depois o modelo, revisões e `citationStrategy`.

O rótulo da estratégia deve mudar junto com a implementação avaliada. O hash do JAR e o commit do baseline podem ser registrados pelo operador; o harness não calcula esses identificadores automaticamente. Um baseline produzido manualmente pode não ter `outcome`, `attempts`, `durationMs` ou `evidence`; ausência não equivale a zero tentativas ou a um outcome específico.

Use os totais e cada caso para verificar mudanças em recusas, aceite formal, matching e latência. Revise manualmente conteúdo, idioma, relevância dos excertos e sustentação das afirmações. Uma única execução de oito casos conhecidos não demonstra melhoria geral nem resistência a prompt injection. Temperatura zero não garante determinismo entre versões, hardware, cache e estados de carregamento do modelo; para conclusões de latência, controle aquecimento e repita as medições.

## Testes sem modelo

`EvaluateGenerationTests` usa respostas e durações sintéticas, um contexto Spring mínimo sem inicialização de runners e arquivos temporários. Cobre ativação explícita, composição e validação da fixture, divergências do golden set, contagens, mediana par/ímpar, duplicação, preservação dos campos, UTF-8, hash e falha antes de chamar o serviço. Não requer Ollama, banco nem Docker:

```powershell
.\mvnw.cmd -Dtest=EvaluateGenerationTests test
```

Os testes e a avaliação real devem ser executados pelo integrador. Nenhuma medição real de geração é declarada neste documento.
