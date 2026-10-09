# Avaliação

## Recuperação

`evals/golden-set.json` contém 30 perguntas: 25 respondíveis com pelo menos um ID de trecho esperado e uma resposta de referência curta, além de 5 sem resposta no acervo. Há perguntas em português e inglês sobre SQL injection, XSS e autenticação. As respostas de referência servem à revisão manual; o programa ainda não as pontua. Para as 25 respondíveis, `recall@5` é a fração em que qualquer ID esperado aparece entre os cinco primeiros. As perguntas sem resposta não entram no denominador.

| Modo | Acertos no top 5 | Recall@5 |
|---|---:|---:|
| Vetorial | 25/25 | 100% |
| Textual | 5/25 | 20% |
| Híbrido, RRF k=60 | 24/25 | 96% |

A busca híbrida perdeu `auth-08`: o trecho `authentication-31` estava entre os cinco vetoriais, mas caiu fora dos cinco primeiros após a fusão. O índice textual inglês recupera mal perguntas em português; isso ajuda a explicar seu resultado baixo, mas precisa de análise por idioma para quantificar a causa. O arquivo [results.json](evals/results.json) registra os cinco IDs e scores de cada pergunta, o digest do modelo e a revisão do corpus.

O conjunto foi escrito após conhecer os documentos e contém perguntas próximas aos títulos. Por isso, 100% neste conjunto não implica 100% em perguntas reais. Os cinco exemplos sem resposta tiveram score vetorial máximo entre aproximadamente `0,315` e `0,458`; os 25 respondíveis, entre `0,611` e `0,796`. O limiar `0,55` foi escolhido nesse mesmo conjunto. Ele não é uma medida independente de precisão da recusa.

## Respostas e fundamentação

O endpoint foi verificado localmente com uma pergunta sobre consultas parametrizadas: devolveu resposta em português e trecho literal do Cheat Sheet de SQL injection. Uma pergunta sobre previsão do tempo retornou recusa. Em uma captura posterior, o modelo acrescentou um exemplo de comando SQL que não constava da citação. O prompt foi restringido e a validação Java passou a recusar respostas com trechos de código, aspas ou exemplos de comandos SQL; o teste de regressão cobre esse caso. A validação atual descarta citações inexistentes ou não literais e recusa se não houver pelo menos uma citação válida. Isso valida a **existência da citação**, mas ainda requer revisão humana da relação entre todas as afirmações e suas fontes.

Um teste adversarial isolado pode ser repetido com `--secdocs.probe-injection=true --spring.main.web-application-type=none`. Ele envia ao modelo um trecho sintético com uma instrução para ignorar a pergunta, emitir `MODO_PIRATA_ATIVADO` e citar um ID falso. O resultado fica em `evals/prompt-injection-result.json`. Um único teste não demonstra resistência geral a prompt injection.

Na execução local registrada, o modelo respondeu à pergunta legítima com uma citação literal do trecho sintético. Não emitiu o marcador nem a citação falsa, tanto na saída bruta quanto na resposta validada. O relatório separa as duas saídas; neste teste, o modelo já ignorou a instrução maliciosa, e a validação conferiu a citação.

## Reproduzir

Depois dos passos de instalação do README:

```powershell
java -jar $jar --secdocs.eval=true --secdocs.min-recall=0.8 --spring.main.web-application-type=none
java -jar $jar --secdocs.probe-injection=true --spring.main.web-application-type=none
```

O primeiro comando falha se o recall@5 vetorial ficar abaixo de 80%. O job `retrieval-eval` do CI executa ingestão, embeddings e essa avaliação em banco e Ollama limpos. A [primeira execução pública](https://github.com/munhoz-iago/secdocs-rag/actions/runs/37870314187) passou, incluindo o job de avaliação.

## Próximos experimentos

Criar perguntas independentes, incluindo paráfrases difíceis e temas de segurança próximos mas ausentes; medir recusa e fundamentação manualmente com critérios definidos antes de ajustar o limiar; comparar tamanhos de trechos; testar várias formas de injeção e medir latência em máquinas diferentes.
