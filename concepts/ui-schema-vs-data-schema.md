# UI Schema vs Data Schema

## Definição

Em arquiteturas metadata-driven, **Data Schema** e **UI Schema** cumprem papéis distintos e complementares:

- **Data Schema** define a estrutura de transporte, os tipos de dados, formatos, restrições de integridade e semântica de negócio do payload (`@Schema`, tipos Java, Bean Validation).
- **UI Schema** define a intenção de interação e apresentação para humanos e runtimes visuais (`@UISchema`, `controlType`, `group`, `order`, `readOnly`, `tableHidden`, `formHidden`, `valuePresentation`, `optionSource`).

## Como o `praxis-metadata-starter` articula os dois

No Praxis, ambos coexistem no mesmo DTO de contrato (Create, Update, Response, Filter ou Command Request), mas são publicados com responsabilidades claras no OpenAPI e em `/schemas/filtered`:

| Dimensão | Fonte no código Java | Publicação no contrato | Exemplo de responsabilidade |
| :--- | :--- | :--- | :--- |
| **Data & Domain Schema** | Tipos Java + `@Schema` + Jakarta Validation (`@NotBlank`, `@Size`, `@Positive`) + `@DomainGovernance` | Propriedades OpenAPI padrão (`type`, `format`, `description`, `required`, `minimum`, `x-domain-governance`) | Explicar o significado de negócio do campo, formato de transporte e invariantes obrigatórias. |
| **UI Schema (`x-ui`)** | `@UISchema` + `@Filterable` + inferências do `CustomOpenApiResolver` | Extensão `x-ui` dentro de cada propriedade do schema filtrado | Definir `controlType` (`CURRENCY_INPUT`, `SELECT`, `DATE_PICKER`), ordem, grupo, visibilidade por contexto e `valuePresentation`. |

### Regra prática importante

Nunca use o tipo físico do banco legado para ditar a experiência de UI quando a semântica for textual: códigos, CEPs, telefones e documentos fiscais (`CPF`/`CNPJ`) devem declarar `@UISchema(type = FieldDataType.TEXT, ...)` mesmo que o transporte legado seja numérico, evitando formatação matemática indevida em tabelas e formulários.

## Referências internas

- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)
- [Exemplos de DTOs e Filtros](../examples/DTO-FILTROS-EXEMPLOS.md)

## Referências públicas

- [JSON Schema Specification](https://json-schema.org/specification)
- [JSON Forms — UI Schema Concept](https://jsonforms.io/docs/uischema/)
