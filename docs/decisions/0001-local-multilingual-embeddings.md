# ADR 0001 — Embeddings multilíngues locais

**Status:** adotado para o primeiro experimento. **Data:** 2026-10-08.

## Contexto

As fontes iniciais da OWASP estão em inglês, mas a API deverá aceitar perguntas em português e inglês. O projeto precisa ser reproduzível sem uma chave paga de embeddings.

## Decisão

Usar `bge-m3:567m` pelo Ollama local. O modelo é multilíngue, aceita entradas maiores que os trechos atuais e produz vetores de 1.024 dimensões. A aplicação Java chama a API local de embeddings e registra no banco o nome e o digest do modelo, para saber quando precisa gerar vetores novamente.

No primeiro experimento local, o Ollama era `0.40.1`, a imagem estava fixada pelo digest no `compose.yaml`, e o modelo instalado tinha o digest `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`.

Fontes técnicas: [cartão do modelo BGE-M3](https://ollama.com/library/bge-m3), [API de embeddings do Ollama](https://docs.ollama.com/api/embed).

## Consequências

- O download do modelo ocupa cerca de 1,2 GB, além da imagem do Ollama. A geração em CPU pode demorar.
- Os vetores podem ser gerados novamente sem custo de API; o comando retoma apenas os trechos pendentes.
- O índice HNSW usa distância cosseno. Sua qualidade para este corpus ainda precisa ser medida com perguntas de referência; suporte multilíngue do modelo não prova recall alto aqui.
- Uma busca manual em português retornou trechos da fonte correta sobre SQL injection, mas também colocou uma seção de defesa desaconselhada em segundo lugar. Isso reforça a necessidade de avaliar e combinar busca textual e vetorial antes de gerar respostas.
- Trocar de modelo exige uma nova migração da dimensão do vetor e nova avaliação. A decisão não é uma afirmação de que BGE-M3 é o melhor modelo.
