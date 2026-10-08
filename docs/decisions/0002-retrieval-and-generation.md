# ADR 0002 — Recuperação medida e geração local

**Estado:** aceito para a demonstração local, sujeito a novas avaliações.

## Contexto

As perguntas serão em português e inglês, enquanto os documentos iniciais estão em inglês. Precisamos comparar a busca sem assumir que adicionar modalidades melhora o resultado. O usuário escolheu geração local com Ollama.

## Decisão

Manter três modos de busca na API: vetorial, textual e híbrido por RRF (`k=60`). Usar a busca vetorial para fornecer os cinco trechos ao gerador porque, no golden set atual, ela recuperou 25/25 fontes esperadas contra 24/25 da híbrida. Gerar JSON estruturado com o `qwen3:1.7b` e validar IDs e citações literais no Java. Recusar quando a maior similaridade vetorial for menor que `0,55` ou quando não restar citação válida.

## Consequências

O resultado não prova que a busca vetorial será melhor em outras perguntas. O limiar foi escolhido olhando o mesmo conjunto de 30 perguntas; precisa de um conjunto independente. A geração local preserva o fluxo sem chave de API, mas pode ser lenta em CPU. Uma citação literal reduz falsas fontes, porém não garante que toda afirmação da resposta decorra dela. O sistema não deve ser usado como fonte final de decisões de segurança.
