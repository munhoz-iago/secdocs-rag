# 0003: citações montadas pelo servidor

Status: implementada; avaliação de desenvolvimento registrada separadamente.

## Problema observado

O gerador local frequentemente traduzia ou inventava o trecho que deveria copiar. Uma resposta útil acabava recusada pelo validador, mesmo com uma fonte relevante recuperada. Reforçar o prompt e permitir uma correção não resolveu o problema de forma demonstrada.

## Decisão

`EvidencePassages` divide os cinco resultados vetoriais em excertos literais de 20 a 600 caracteres. Parágrafos longos usam janelas com até 80 caracteres de sobreposição. Os IDs pertencem apenas à consulta atual; não são novos IDs persistidos no banco. A seleção intercala as fontes até o limite de 30 excertos ou 14.000 caracteres.

O modelo recebe esses excertos e devolve `answer`, `evidenceIds` e `abstained`. O JSON Schema restringe os IDs aos enviados naquela chamada. O adaptador também valida a saída em Java: IDs desconhecidos, repetidos ou extras não são aproveitados parcialmente. O servidor resolve cada ID para o texto original e para o ID do trecho persistido. O modelo não fornece o texto da citação.

O validador existente continua exigindo citações presentes na evidência recuperada e código literal dentro da citação selecionada. O limiar vetorial de 0,55 e o máximo de uma reparação permanecem. A API registra motivo do resultado, tentativas, duração e evidência recuperada; não persiste perguntas ou histórico de usuários.

## Limites e custo

- Uma citação construída pelo servidor necessariamente existe na fonte. Isso **não demonstra que a resposta corresponde semanticamente à citação**. O modelo ainda pode selecionar uma fonte inadequada ou fazer uma inferência sem apoio.
- Os limites de contexto podem omitir partes de documentos. Sobreposição reduz cortes no interior de uma frase, sem garantir cobertura de toda informação relevante.
- O esquema anterior e o novo são comparados no mesmo conjunto congelado de desenvolvimento e com o mesmo modelo. Prompt e orçamento de saída também mudaram; a comparação mede as duas versões do fluxo, sem isolar causalmente cada mudança.
- A interface distingue recusa por evidência insuficiente, recusa do modelo e rejeição por validação. Similaridade não é uma probabilidade de correção.

## Revisão

A análise do Claude foi usada para exigir comparação antes/depois, preservar a validação de código e explicitar o limite semântico. Uma revisão Codex separada verifica o contrato e as condições de recusa. Os resultados executados ficam em `evals/generation-baseline.json` e `evals/generation-results.json`; ambos usam `evals/generation-set.json`.

Referência: [saídas estruturadas do Ollama](https://docs.ollama.com/capabilities/structured-outputs). O esquema é uma restrição de formato; a aplicação mantém suas próprias verificações.
