# Schema-driven UI

## Definição

**Schema-driven UI** é o padrão arquitetural em que interfaces de usuário — formulários, tabelas, barras de filtro, diálogos de workflow e visualizações analíticas — são construídas dinamicamente a partir de contratos estruturais e semânticos publicados pelo backend, reduzindo código manual repetitivo por tela. Na **Praxis Platform**, o mesmo contrato semântico que alimenta a **interface tradicional** (`@praxisui/*`) também alimenta **interfaces generativas**, **agentes de IA** e **integrações**.

## Como o `praxis-metadata-starter` implementa

- **Resolução contextual por operação**: em vez de um schema estático único por entidade, `/schemas/filtered?path=...&operation=...&schemaType=request|response` resolve exatamente os campos, obrigatoriedades e metadados `x-ui` daquela operação (`POST`, `PUT`, `PATCH`, `/filter`, `/actions/{actionKey}`).
- **Enriquecimento automático (`CustomOpenApiResolver`)**: combina `@Schema`, `@UISchema`, Bean Validation (`@NotNull`, `@NotBlank`, `@Size`, `@Pattern`), `@Filterable` e inferências seguras de tipo/formato em um objeto `x-ui` padronizado.
- **Materialização em múltiplas superfícies**:
  - **Runtime oficial (`@praxisui/*`)**: componentes como `praxis-dynamic-form`, `praxis-table` e `praxis-dynamic-page` consomem `/schemas/filtered`, `/schemas/surfaces`, `/schemas/actions` e `/{resource}/capabilities`.
  - **Inspeção embutida (`/praxis/cockpit`)**: o próprio backend renderiza o inventário completo do domínio diretamente a partir dos schemas publicados.
- **Cache e revalidação**: respostas de `/schemas/filtered` incluem `X-Schema-Hash` e `ETag` para evitar reprocessamento desnecessário no cliente.

## Referências internas

- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)
- [UI Schema vs Data Schema](ui-schema-vs-data-schema.md)
- [Integração Spring e exemplos de DTO](../examples/spring-integration.md)

## Referências públicas

- [JSON Forms](https://jsonforms.io/)
- [react-jsonschema-form](https://rjsf-team.github.io/react-jsonschema-form/)
- [Formly (Angular)](https://formly.dev/)
