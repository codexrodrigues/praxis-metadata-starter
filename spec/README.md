# Especificação de Metadados (x‑ui) — v1.0.0

Objetivo
- Padronizar, de forma agnóstica de tecnologia, o contrato de metadados que a UI Praxis consome.
- Permitir que qualquer backend produza JSON compatível e valide localmente contra os JSON Schemas desta pasta.

Escopo de validação (JSON)
- x‑ui de campo (por propriedade OpenAPI): `x-ui-field.schema.json`
- x‑ui em nível de operação (por path+operation do OpenAPI): `x-ui-operation.schema.json`
- x‑ui.resource no payload final consumido pela UI (`/schemas/filtered`): `x-ui-resource.schema.json`

Vocabulário (resumo)
- Campo (x‑ui por propriedade)
  - Identificação/Apresentação: `name`, `label`, `description`, `group`, `order`, `width`
  - Tipo/Componente: `type` (enum), `controlType` (enum), `placeholder`, `defaultValue`
  - Estado/Validação: `disabled`, `readOnly`, `editable`, `unique`, `mask`, `sortable`, `filterable`
  - Visibilidade: `hidden`, `tableHidden`, `formHidden`
  - Dependências/Condicionais: `conditionalDisplay`, `dependentField`, `resetOnDependentChange`
  - Layout/Ícone: `hint`, `helpText`, `tooltipOnHover`, `icon*`
  - Seleção/Opções: `options[]`, `endpoint`, `valueField`, `displayField`, `multiple`, `emptyOptionText`
  - Numérico: `numericFormat` (enum), `numericStep`, `numericMin`, `numericMax`, `numericMaxLength`
  - Validação (top‑level): `required`, `minLength`, `maxLength`, `min`, `max`, `pattern`, `range`, mensagens (`*Message`), além de `email`, `url`, `matchField`, `uniqueValidator`, `customValidator`, `asyncValidator`, `minWords`, `validationTrigger(s)`, `validationDebounce`, `showInlineErrors`, `errorPosition`.
  - Legado (opcional): bloco `validation{}` com chaves básicas (a UI atual lê chaves de validação no top‑level do `x-ui`).
- Operação (x‑ui por operação)
  - `displayColumns` (string[]), `displayFields` (string[]), `filterFields` (string[]), `responseSchema` (string), `relatedEntitiesEndpoints` (string[])
- Recurso (x‑ui.resource)
  - `idField` (string), `idFieldValid` (boolean), `idFieldMessage?` (string, opcional)
  - `readOnly` (boolean)
  - `capabilities` (mapa boolean com chaves conhecidas: `create`, `update`, `delete`, `options`, `byId`, `all`, `filter`, `cursor`; valores adicionais MAY existir e DEVEM ser boolean)

Tipos e Enums
- `type` (FieldDataType): `text | number | email | date | password | file | url | boolean | json`
- `controlType` (FieldControlType): ver enum completo no JSON Schema (lista espelhada do starter Java)
- `numericFormat` (NumericFormat): `integer | decimal | currency | scientific | time | date | date-time | duration | number | fraction | percent`

Obrigatoriedade e Defaults
- Campo (x‑ui): não há campos obrigatórios universais. A UI consegue inferir `type/controlType` por heurística, porém recomenda‑se prover ao menos `type` e `label`.
- Operação (x‑ui): todos opcionais; sugerem preferências de renderização.
- Recurso (x‑ui.resource): MUST conter `idField`, `idFieldValid`, `readOnly`, `capabilities`; `idFieldMessage` é opcional e presente quando `idFieldValid=false`.

Precedência (normativa)
1) Defaults de `@UISchema` (ou equivalente no seu backend)
2) Detecção automática (OpenAPI type/format/enum + heurísticas por nome)
3) Valores explícitos de `@UISchema`
4) Bean Validation (ex.: NotBlank/Size/Pattern → chaves de validação no `x-ui`)
5) `extraProperties`/`custom.*` (precedência máxima)

Normas (MUST/SHOULD/MAY)
- MUST: chaves canônicas devem seguir tipos/semântica dos JSON Schemas.
- SHOULD: extensões privadas usarem o prefixo `custom.` (evitar colisão com chaves canônicas).
- MUST: `capabilities` conter apenas valores boolean; chaves adicionais são permitidas.
- SHOULD: `/schemas/filtered` enviar `ETag` forte e `X-Schema-Hash` e expor via `Access-Control-Expose-Headers`.
- MAY: incluir `specVersion` em um envelope/meta do payload para auditoria.

Versionamento da Especificação
- `specVersion`: `1.0.0`. SemVer:
  - `MAJOR`: alterações incompatíveis (ex.: remoção/renome de chaves, mudança de tipo)
  - `MINOR`: adições retrocompatíveis (novas chaves/enums, novos capabilities)
  - `PATCH`: correções editoriais e ajustes de descrição

Arquivos (machine‑readable)
- `x-ui-field.schema.json` — valida x‑ui de campo (por propriedade)
- `x-ui-operation.schema.json` — valida x‑ui por operação
- `x-ui-resource.schema.json` — valida x‑ui.resource no payload final

Exemplos (fixtures)
- Em `examples/`: válidos/ inválidos e um payload canônico que combina campo + operação + recurso.

Observações
- `schemaId`, `ETag` e `X-Schema-Hash` são práticas recomendadas para cache/304 e compõem a identidade de schema (tenant/locale inclusive), mas não fazem parte da validação JSON desta pasta.
