# Declarative UI

## Definição

**Declarative UI** (Interface Declarativa) descreve **o que** uma tela, formulário, listagem ou ação de negócio representa e exige, delegando **como** os controles visuais, estados de validação e fluxos HTTP são instanciados ao runtime da plataforma.

## Como o `praxis-metadata-starter` implementa

Em vez de codificar manualmente componentes de formulário, colunas de tabela, barras de filtro e modais de ação para cada recurso, o desenvolvedor declara a intenção no próprio contrato do backend:

- **No nível do campo (`@UISchema` e `@Filterable`)**: rótulo, tipo de controle (`controlType`), agrupamento (`group`), ordem (`order`), visibilidade contextual (`tableHidden`, `formHidden`), formatação de leitura (`valuePresentation`), opções remotas (`optionSource`) e operadores de busca (`operation`).
- **No nível da experiência (`@UiSurface`)**: declara superfícies adicionais de leitura ou edição contextual (ex.: resumo executivo, visão de auditoria, gráfico analítico `CHART`) que aparecem automaticamente em `/schemas/surfaces` e nas capabilities do recurso.
- **No nível do comando de negócio (`@WorkflowAction`)**: declara ações explícitas (ex.: aprovar, cancelar, reabrir) com seus próprios DTOs de request/response, permitindo que a UI renderize o diálogo de confirmação/input sem código ad hoc.

## Referências internas

- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)
- [Quando usar Resource, Surface, Action e Capability](../guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md)

## Referências públicas

- [Declarative Programming — Wikipedia](https://en.wikipedia.org/wiki/Declarative_programming)
- [UI Schema vs Data Schema](ui-schema-vs-data-schema.md)
