# Protocolo de Prova dos Guias de IA

## Objetivo

Medir se uma LLM consegue, partindo apenas dos guias oficiais, produzir:

- uma aplicacao Spring Boot funcional
- um recurso metadata-driven aderente ao baseline atual da plataforma
- um frontend Angular capaz de consumir esse contrato

## Regras da rodada

- a LLM deve receber apenas o material explicitamente permitido
- a rodada deve comecar em workspace limpo para o sandbox
- nenhuma correcao humana de codigo e permitida antes do veredito
- falhas de build, runtime, contrato ou consumo contam como reprova
- toda rodada deve gerar um relatorio a partir de
  `TEMPLATE-RELATORIO-DE-RODADA.md`

## Material permitido para a LLM

Material minimo:

- `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `docs/guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`
- `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md`

Material adicional opcional:

- `docs/guides/OPTIONS-ENDPOINT.md`
- `docs/guides/FILTROS-E-PAGINACAO.md`
- `docs/guides/READ-ONLY-VIEWS.md`

Nao liberar como baseline:

- snippets copiados de app externo
- respostas do tipo "siga o app de referencia"
- correcoes manuais de contrato fora do que o guia ja publicar

## Rodadas canonicas

### Rodada 1

- backend H2 simples

### Rodada 2

- backend H2 com relacao

### Rodada 3

- backend H2 com MapStruct

### Rodada 4

- backend H2 com filtros ricos

### Rodada 5

- consumo Angular

### Rodada 6

- frontend Angular completo

### Rodada 7

- app Angular novo do zero

## Criterio de aprovacao

Uma rodada so e aprovada se o que ela prometeu provar foi validado sem
intervencao humana no codigo gerado.

Para backend:

- `mvn clean package`
- aplicacao sobe
- `GET /v3/api-docs`
- `GET /{resource}/schemas`
- schemas filtrados de request e response
- `POST /{resource}`
- `POST /{resource}/filter`
- options quando houver select remoto
- `ETag` e `X-Schema-Hash` nos schemas filtrados

Para frontend:

- host Angular compilavel
- `praxis-crud` como shell principal quando o caso for CRUD completo
- `resource.path` coerente com `table.resourcePath`
- `crudContext.resourcePath` e `crudContext.idField` coerentes
- consumo de schema sem workaround local fora do contrato

Para a rodada 7:

- `npm install` concluido com o baseline documentado
- `ng build` do app novo aprovado
- `ng serve` respondendo `200` na rota principal
- proxy oficial do Angular ativo para `/api` e `/schemas`
- `POST /{resource}/filter` respondendo `200` no mesmo origin do host

O smoke browser-level completo pode ser registrado em trilha propria quando o
harness ainda nao for canonico e estavel.

## Politica de iteracao

Corrija nesta ordem:

1. guia
2. checklist
3. prompt da rodada
4. codigo canonico, se a prova revelar um problema real da plataforma

## Definicao de 100%

`100%` significa:

- rodadas principais aprovadas dentro do protocolo finito
- nenhuma correcao manual de codigo entre prompt e aceite
- nenhum requisito critico do contrato dependendo de conhecimento oral do time

Isso nao significa sucesso para qualquer LLM ou qualquer prompt.
