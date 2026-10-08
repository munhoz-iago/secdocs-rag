# SecDocs RAG

Assistente de consulta a documentos públicos da OWASP, desenvolvido em Java. O objetivo é responder perguntas com fontes verificáveis e medir a qualidade da recuperação dos documentos.

## Estado atual

**Etapa 4 — embeddings locais.** A aplicação expõe `GET /api/status`, baixa três Cheat Sheets da OWASP, divide o Markdown por títulos, armazena os trechos no PostgreSQL e gera vetores com um modelo multilíngue local. Busca pela API e respostas ainda serão implementadas.

## Requisitos

- Java 21 ou superior
- Acesso à internet na primeira execução para baixar as dependências

O projeto inclui o Maven Wrapper; não é necessário instalar Maven separadamente.

## Rodar no Windows

```powershell
.\mvnw.cmd spring-boot:run
```

Em outro terminal:

```powershell
Invoke-RestMethod http://localhost:8080/api/status
```

## Ingerir os documentos

```powershell
.\mvnw.cmd package
java -jar target\secdocs-rag-0.0.1-SNAPSHOT.jar --secdocs.ingest=true --spring.main.web-application-type=none
```

O comando gera `data/processed/owasp-chunks.jsonl`. Cada linha é um trecho em JSON com os campos `id`, `sourceId`, `sourceTitle`, `section`, `sourceUrl`, `revision`, `license` e `text`. A pasta gerada fica fora do Git; rode o comando novamente para recriá-la.

## Salvar no PostgreSQL local

O Docker Desktop precisa estar aberto. A configuração abaixo sobe um banco isolado na porta `5433`; ela não altera o PostgreSQL instalado na porta padrão.

```powershell
Copy-Item .env.example .env
# Edite .env e substitua o valor de SECDOCS_DB_PASSWORD por uma senha local.
docker compose up -d --wait

$env:SECDOCS_DB_URL = 'jdbc:postgresql://localhost:5433/secdocs'
$env:SECDOCS_DB_USER = 'secdocs'
$env:SECDOCS_DB_PASSWORD = ((Get-Content .env -Raw) -split '=', 2)[1].Trim()
java -jar target\secdocs-rag-0.0.1-SNAPSHOT.jar --secdocs.store=true --spring.main.web-application-type=none
```

O [Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html) aplica `V1__create_document_chunks.sql` antes da importação. Rodar o comando outra vez atualiza os mesmos IDs e remove trechos antigos das fontes importadas; não cria duplicatas. Para conferir:

```powershell
docker compose exec -T db psql -U secdocs -d secdocs -tAc "SELECT count(*) FROM document_chunks;"
```

O índice `search_text` usa a configuração inglesa do PostgreSQL porque os textos iniciais estão em inglês. A busca vetorial será importante para perguntas em português.

## Gerar embeddings locais

O modelo escolhido para o primeiro experimento é [`bge-m3:567m`](https://ollama.com/library/bge-m3). Veja a [decisão técnica](docs/decisions/0001-local-multilingual-embeddings.md). O download do modelo ocupa cerca de 1,2 GB e a primeira geração pode demorar em CPU.

```powershell
docker compose up -d --wait ollama
docker compose exec -T ollama ollama pull bge-m3:567m

# Reutilize as variáveis SECDOCS_DB_URL, SECDOCS_DB_USER e SECDOCS_DB_PASSWORD acima.
java -jar target\secdocs-rag-0.0.1-SNAPSHOT.jar --secdocs.embed=true --spring.main.web-application-type=none
```

O comando salva lotes de oito vetores e pode ser repetido após uma interrupção: ele processa somente trechos sem vetor ou com digest de modelo diferente. A migração `V2__add_embedding_index.sql` fixa a dimensão em 1.024 e cria o índice HNSW. Para conferir:

```powershell
docker compose exec -T db psql -U secdocs -d secdocs -tAc "SELECT count(*) FILTER (WHERE embedding IS NOT NULL), count(*) FROM document_chunks;"
```

O modelo local produz vetores, mas **a qualidade da recuperação ainda não foi avaliada**. Números de recall só serão publicados depois de criar perguntas de referência e medir o resultado.

Para visualizar os cinco trechos mais próximos de uma pergunta em português:

```powershell
java -jar target\secdocs-rag-0.0.1-SNAPSHOT.jar --secdocs.search-preview=true --spring.main.web-application-type=none '--secdocs.question=Como evitar injeção de SQL em consultas ao banco?'
```

Na primeira verificação local, os cinco resultados vieram do documento certo sobre SQL injection, mas uma seção que desaconselha uma defesa apareceu em segundo lugar. Esse exemplo **não é uma métrica de qualidade**. O próximo passo é criar perguntas de referência e comparar a busca vetorial com a busca híbrida.

As fontes escolhidas estão em `src/main/resources/owasp-sources.properties`. O arquivo fixa a revisão `29994dd8a2e6f50fa3d5607b046b54d7c6945afd` do [repositório OWASP Cheat Sheet Series](https://github.com/OWASP/CheatSheetSeries). Os três textos iniciais são [SQL Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html), [Cross Site Scripting Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html) e [Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html). O conteúdo está sob [CC BY-SA 4.0](https://github.com/OWASP/CheatSheetSeries/blob/master/LICENSE.md); atribuição e licença acompanham cada trecho.

O limite atual é de 3.500 caracteres por trecho, não uma contagem exata de tokens. Vamos comparar alternativas de divisão durante a avaliação.

## Por que esta primeira etapa existe

`SecdocsRagApplication` inicia o servidor. `StatusController` define uma rota HTTP simples. `OwaspIngestion` baixa as fontes, `MarkdownChunker` divide o texto em trechos, `StoreChunks` grava no PostgreSQL, `EmbedChunks` gera os vetores e `SearchPreview` mostra uma busca manual. O `pom.xml` registra a versão de Java e as bibliotecas.

## Próximas etapas

1. Implementar busca vetorial e textual, com comparação medida no conjunto de avaliação.
2. Gerar respostas com citações e recusa quando a evidência for insuficiente.
3. Adicionar testes de segurança, documentação e demonstração.
