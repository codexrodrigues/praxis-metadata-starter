# Protocolo de Prova dos Guias de IA

## Objetivo

Medir se uma LLM consegue, partindo apenas dos guias oficiais, produzir uma aplicacao Spring Boot funcional e um CRUD metadata-driven aderente ao contrato da plataforma.

## Classificacao da prova

Esta prova e `docs-apenas` no starter, mas valida comportamento de superficies canonicas de alto risco:

- `x-ui`
- `/schemas/filtered`
- `GET /{resource}/schemas`
- `@ApiResource`
- `@ApiGroup`
- `GenericFilterDTO`
- `OptionDTO{id,label}`
- `ETag` e `X-Schema-Hash`

## Regras da rodada

- a LLM deve receber apenas o material explicitamente permitido
- a rodada deve começar em workspace limpo para o projeto sandbox
- nenhuma correcao humana de codigo e permitida antes do veredito da rodada
- falhas de build, runtime, contrato ou consumo contam como reprovação
- toda rodada deve gerar um relatorio preenchido a partir de `TEMPLATE-RELATORIO-DE-RODADA.md`

## Material permitido para a LLM

Material minimo:

- `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
- `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
- `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- o prompt da rodada em `PROMPTS-DE-EXECUCAO.md`

Material adicional opcional:

- `docs/guides/OPTIONS-ENDPOINT.md`
- `docs/guides/FILTROS-E-PAGINACAO.md`
- `docs/guides/CRUD-COM-APIRESOURCE.md`

Nao liberar como baseline:

- snippets copiados do `praxis-api-quickstart`
- correcoes manuais de POM ou properties fora do que o guia ja disser
- respostas do tipo "siga o padrao do quickstart" sem o guia ter detalhado o necessario

## Rodadas canonicas

### Rodada 1: H2 simples

Objetivo:

- criar aplicacao nova
- criar entidade simples sem relacao
- provar build, subida, persistencia e schemas

Entidade recomendada:

- `Categoria`
- campos: `id`, `nome`, `ativo`

### Rodada 2: H2 com relacao

Objetivo:

- manter banco local simples
- introduzir relacao e select remoto

Entidades recomendadas:

- `Categoria`
- `Produto`
- `Produto.categoria`

### Rodada 3: PostgreSQL simples

Objetivo:

- validar que o guia nao esta acidentalmente acoplado a H2
- provar datasource real, Flyway e bootstrap funcional

### Rodada 4: PostgreSQL com relacao

Objetivo:

- fechar o fluxo minimo de CRUD com relacao, options e schemas sob banco real

### Rodada 5: Consumo Angular

Objetivo:

- validar que o recurso gerado e consumivel por `GenericCrudService`
- esta rodada e complementar; nao substitui as quatro rodadas anteriores

## Criterio de aprovacao por rodada

A rodada so e aprovada se todos os itens abaixo passarem sem intervencao humana no codigo gerado:

- `mvn clean package`
- aplicacao sobe
- `GET /v3/api-docs`
- Swagger UI
- `GET /{resource}/schemas`
- `GET /schemas/filtered?path={resource}/all&operation=get&schemaType=response`
- `GET /schemas/filtered?path={resource}/filter&operation=post&schemaType=request`
- `POST /{resource}`
- `POST /{resource}/filter`
- `POST /{resource}/options/filter` quando houver select remoto
- `GET /{resource}/options/by-ids` quando houver select remoto
- persistencia real no banco escolhido
- `ETag` e `X-Schema-Hash` presentes nos schemas filtrados

## Taxonomia obrigatoria de falhas

Cada falha deve ser classificada como uma das categorias abaixo:

- `guia-incompleto`
- `guia-ambiguo`
- `contrato-nao-explicito`
- `dependencia-implicita`
- `prompt-fraco`
- `erro-da-llm`
- `problema-real-do-starter`

## Politica de iteracao

Corrija nesta ordem:

1. guia
2. checklist
3. prompt da rodada
4. somente depois, codigo canônico, se a prova revelou um problema real do starter

Nao normalize workaround local em prompt se a causa correta for falha do guia ou do contrato.

## Validacao minima recomendada

Para cada rodada, registrar:

- comando de build executado
- comando de subida executado
- evidencias HTTP minimas
- erro bruto quando houver falha
- interpretacao da causa
- ajuste documental proposto

## Definicao de "100%"

Para este protocolo, `100%` significa:

- quatro rodadas principais aprovadas
- nenhuma correcao manual de codigo entre prompt e aceite
- nenhum requisito critico do contrato ficando dependente de conhecimento oral ou memoria do quickstart

Isso nao significa `100% para qualquer LLM e qualquer prompt`.
Significa `100% dentro do protocolo finito desta prova`.
