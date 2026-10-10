# Protocolo para avaliação independente

O conjunto em `evals/golden-set.json` foi escrito depois de ler as fontes. Ele serve para desenvolvimento e regressão, não para estimar desempenho em perguntas novas.

## Coleta

1. Peça a uma pessoa que ainda não viu o código, as seções usadas no conjunto de desenvolvimento ou `evals/results.json` para escrever de 15 a 20 perguntas que ela faria a um assistente sobre segurança de aplicações. Ela pode consultar o índice público do Top 10:2025, ASVS 5.0 e Cheat Sheet Series para conhecer o escopo, sem ver os IDs dos trechos.
2. Inclua perguntas respondíveis, perguntas próximas do tema mas ausentes do acervo e perguntas que pedem exemplos de código. Guarde as perguntas antes de executar busca ou geração.
3. Dois revisores devem marcar, para cada pergunta, se há resposta no corpus e quais trechos a sustentam. Divergências são resolvidas antes de executar o sistema. Registre a revisão dos três repositórios e a data.
4. Congele as perguntas, os IDs esperados e a rubrica. Execute busca vetorial, textual e híbrida no mesmo corpus. Não ajuste pesos, limiar ou prompts com base nos resultados desse conjunto.
5. Publique a contagem de acertos e erros por pergunta, separando idioma, tema, perguntas sem resposta e pedidos de código. Repita a avaliação em outro conjunto se alterar o sistema depois de ver os erros.

## Rubrica de resposta

Para cada resposta, o revisor lê a pergunta, a resposta e os trechos citados. Marque **sustentada** quando cada afirmação factual decorre dos trechos e qualquer código é reproduzido literalmente; **parcial** quando apenas parte está sustentada; **não sustentada** quando há afirmação central sem apoio, citação irrelevante ou código inventado. Marque **recusa apropriada** ou **recusa indevida** separadamente. Uma citação literal presente no arquivo não basta para a primeira categoria.

## Registro sugerido

| id | idioma | pergunta | respondível? | IDs aceitos | modo | top 5 | resposta | julgamento | justificativa |
|---|---|---|---|---|---|---|---|---|---|

O autor do projeto não deve escrever as perguntas e depois chamar o resultado de independente. Até haver participantes e revisão externos, nenhum número deste protocolo é apresentado como medição independente.
