# Prova Operacional dos Guias de IA

Este pacote transforma os guias de IA do `praxis-metadata-starter` em um processo repetivel de prova operacional.

Objetivo:

- verificar se uma LLM, seguindo apenas os guias, consegue criar uma aplicacao nova e um CRUD metadata-driven sem intervencao humana corretiva
- coletar falhas reais de execucao
- retroalimentar os guias ate fechar o protocolo sem erros

Escopo:

- `GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
- `GUIA-CLAUDE-AI-CRUD-BULK.md`
- contrato real do `praxis-metadata-starter`
- uso operacional de referencia no `praxis-api-quickstart`
- consumo final esperado no `praxis-ui-angular`

Arquivos deste pacote:

- `PROTOCOLO-DE-PROVA.md`
- `PROMPTS-DE-EXECUCAO.md`
- `SANDBOX-RECOMENDADO.md`
- `TEMPLATE-RELATORIO-DE-RODADA.md`

Regra de execucao:

1. rode uma rodada limpa
2. registre todas as falhas no relatorio
3. classifique cada falha
4. corrija primeiro o guia, nao o prompt, quando a causa for ambiguidade documental
5. repita a mesma rodada do zero

Nao considere uma rodada aprovada se houve:

- correcao manual de codigo no meio da execucao
- consulta a exemplos fora do material permitido
- ajuste ad hoc de dependencias, paths ou contratos nao previstos no guia

Resultado esperado:

- aprovacao em `H2 simples`
- aprovacao em `H2 com relacao`
- aprovacao em `PostgreSQL simples`
- aprovacao em `PostgreSQL com relacao`
- depois disso, validacao opcional de consumo pelo Angular
