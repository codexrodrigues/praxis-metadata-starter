# Rules Engines and Specifications

## Definição

O padrão **Specification** e motores de regras declarativas permitem expressar critérios de filtragem, visibilidade condicional, obrigatoriedade dinâmica e elegibilidade de ações como estruturas de dados composáveis e auditáveis, evitando ramificações `if/else` espalhadas entre frontend e backend.

## Como o `praxis-metadata-starter` implementa

O starter aplica especificações e regras declarativas em três frentes complementares:

1. **Consultas dinâmicas tipadas (`@Filterable` + `GenericSpecificationsBuilder`)**:
   - Nos DTOs de filtro (`implements GenericFilterDTO`), `@Filterable(operation = ..., relation = ...)` declara o operador (`LIKE`, `EQUAL`, `IN`, `BETWEEN`, `GREATER_THAN`, etc.) e o caminho na entidade JPA.
   - O `GenericSpecificationsBuilder` converte automaticamente o DTO recebido em `POST /{resource}/filter` ou `/options/filter` em uma `Specification<E>` do Spring Data JPA, com suporte a paginação, ordenação e projeções.
2. **Condicionais declarativas em `x-ui` (Json Logic canônico)**:
   - Propriedades como `conditionalDisplay`, `conditionalRequired` e `conditionalValidation[]` usam expressões **Json Logic** serializáveis em JSON.
   - O backend valida precocemente operadores, aridade e estrutura antes de publicar o schema em `/schemas/filtered`, rejeitando DSLs textuais ambíguas.
3. **Determinações reativas e governança de regras (`x-ui.reactiveDeterminations` + `praxis-config-starter`)**:
   - O metadata starter publica os bindings estruturais em `x-ui.reactiveDeterminations` e a disponibilidade contextual de `@WorkflowAction` / capabilities.
   - Regras versionadas por tenant, simulações e aprovações em Policy Studio são governadas pelo `praxis-config-starter` e consumidas pelo host de forma determinística.

## Referências internas

- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)
- [Exemplos de DTOs e Filtros](../examples/DTO-FILTROS-EXEMPLOS.md)
- [Visão geral da arquitetura](../architecture-overview.md)

## Referências públicas

- [Martin Fowler & Eric Evans — Specification Pattern](https://martinfowler.com/apsupp/spec.pdf)
- [Spring Data JPA Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html)
- [JsonLogic](https://jsonlogic.com/)
