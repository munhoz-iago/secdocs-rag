# Limitações conhecidas

- **Corpus pequeno:** apenas três Cheat Sheets. OWASP Top 10 e ASVS ainda não foram ingeridos; perguntas sobre eles podem receber recusa ou resposta incompleta.
- **Avaliação pequena e não independente:** o golden set foi criado com conhecimento das seções. O limiar de similaridade foi calibrado nele. Há risco de sobreajuste.
- **Citação não é prova completa de fundamentação:** o Java confirma a presença literal do trecho citado, mas não verifica semanticamente cada afirmação da resposta. O modelo ainda pode incluir uma inferência indevida.
- **Prompt injection:** a instrução no prompt e a checagem de citações limitam algumas saídas. Um teste sintético isolado não prova resistência geral, principalmente se o texto malicioso também parecer evidência relevante.
- **Busca textual em inglês:** o `tsvector` inglês tem desempenho fraco em várias perguntas em português. A fusão RRF piorou uma pergunta do conjunto atual.
- **Latência:** em CPU, uma resposta do `qwen3:1.7b` pode levar cerca de um minuto. Não há fila nem timeout de interface específica para cargas maiores.
- **API local:** sem autenticação ou isolamento multiusuário. O limitador de 20 pedidos por minuto usa o IP remoto e memória do processo; não é distribuído nem adequado para serviço público atrás de proxy sem adaptação.
- **Fontes e licença:** a licença CC BY-SA 4.0 dos textos OWASP acompanha os metadados. Quem redistribuir os trechos deve manter atribuição e observar os termos da licença original.
- **CI não observado:** o workflow está no repositório local, mas não houve execução em GitHub. O download dos modelos e a geração dos 103 embeddings podem consumir tempo e recursos no runner.
