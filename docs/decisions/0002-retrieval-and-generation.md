# ADR 0002 — Recuperação medida e geração local

**Estado:** aceito para a demonstração local, sujeito a novas avaliações.

## Contexto

As perguntas serão em português e inglês, enquanto os documentos iniciais estão em inglês. Precisamos comparar a busca sem assumir que adicionar modalidades melhora o resultado. O usuário escolheu geração local com Ollama.

## Decisão

Manter três modos de busca na API: vetorial, textual e híbrido por RRF (`k=60`). Usar a busca vetorial para fornecer os cinco trechos ao gerador porque, no golden set original (3 documentos, 103 trechos, 30 perguntas), ela recuperou 25/25 fontes esperadas contra 24/25 da híbrida. Gerar JSON estruturado com o `qwen3:1.7b` e validar IDs e citações literais no Java. Recusar quando a maior similaridade vetorial for menor que `0,55` ou quando qualquer citação for inválida (ID não recuperado ou trecho não literal). Código na resposta só é aceito se aparecer literalmente dentro de uma citação validada; texto com sinais de código fora disso também leva à recusa.

## Consequências

O resultado não prova que a busca vetorial será melhor em outras perguntas. O limiar foi escolhido olhando o mesmo conjunto de 30 perguntas; precisa de um conjunto independente. A geração local preserva o fluxo sem chave de API, mas pode ser lenta em CPU. Uma citação literal reduz falsas fontes, porém não garante que toda afirmação da resposta decorra dela. O sistema não deve ser usado como fonte final de decisões de segurança.

## Atualização: acervo ampliado

O acervo passou a ter 30 arquivos (três Cheat Sheets, Top 10:2025 e ASVS 5.0) e 300 trechos, e o golden set passou a ter 45 perguntas (40 respondíveis). Na execução local do novo golden set, a busca vetorial recuperou a fonte esperada no top 5 em 39/40 perguntas respondíveis, a híbrida em 38/40 e a textual em 27/40. A híbrida perdeu `auth-06`, que a vetorial acertou, e não recuperou nenhuma pergunta que a vetorial tenha errado. Nos 15 itens traduzidos, a vetorial acertou 14/15 em português e 15/15 em inglês; a híbrida, 14/15 nos dois idiomas. O limiar `0,55` ainda separa as classes nesse conjunto (respondíveis a partir de `0,562`, sem resposta até `0,527`), com margem estreita. Por isso, a decisão de usar a busca vetorial na geração fica mantida. A diferença é de uma pergunta, e o golden set continua sendo um conjunto de desenvolvimento escrito pelo autor, não uma avaliação independente; a decisão deve ser revista quando houver medida independente. Uma medida independente deve seguir o [protocolo para avaliação independente](../../BLIND_EVAL_PROTOCOL.md).
