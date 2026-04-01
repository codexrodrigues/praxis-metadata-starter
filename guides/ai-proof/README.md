# Prova Operacional dos Guias de IA

Este pacote transforma os guias do `praxis-metadata-starter` em um processo
repetivel de prova operacional.

## Objetivo

- verificar se uma LLM, seguindo apenas os guias oficiais, consegue criar uma
  aplicacao nova e um recurso metadata-driven no baseline atual sem
  intervencao humana corretiva
- coletar falhas reais de execucao
- retroalimentar os guias ate fechar o protocolo sem depender de conhecimento
  implicito do time

## Escopo

- `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`
- `CHECKLIST-VALIDACAO-IA.md`

## Arquivos deste pacote

- `PROTOCOLO-DE-PROVA.md`
- `PROMPTS-DE-EXECUCAO.md`
- `SANDBOX-RECOMENDADO.md`
- `TEMPLATE-RELATORIO-DE-RODADA.md`

## Regra de execucao

1. rode uma rodada limpa
2. registre todas as falhas no relatorio
3. classifique cada falha
4. corrija primeiro o guia
5. repita a mesma rodada do zero

Nao considere uma rodada aprovada se houve:

- correcao manual de codigo no meio da execucao
- consulta a app externo como fonte necessaria de implementacao
- ajuste ad hoc de dependencias, paths ou contratos fora do que o guia publica

## Resultado esperado

- aprovacao em backend H2 simples
- aprovacao em backend H2 com relacao
- aprovacao em backend H2 com MapStruct
- aprovacao em backend H2 com filtros ricos
- aprovacao em consumo pelo runtime Angular oficial
- aprovacao em frontend Angular completo

Extensao recomendada:

- prova material de app Angular novo do zero com pacotes publicados
- smoke browser-level em trilha propria, separado da aprovacao material
