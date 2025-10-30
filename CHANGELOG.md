# Changelog — praxis-metadata-starter

All notable changes to this module will be documented in this file.

## [1.0.0-rc.1] - YYYY-MM-DD

### Changed
- Migração para repositório standalone `praxis-metadata-starter` com metadados SCM corrigidos.
- Adicionado workflow de release para Maven Central com extração de versão via tag `v*` e fallback de GPG key id.
- Adicionado workflow de documentação (Javadoc + Markdown → HTML) publicado em `gh-pages`.
- Heurística de `controlType` (strings): threshold de `textarea` ajustado de `>100` para `>300` e detecção por nome com maior precedência para campos single-line (e.g., `nome`, `titulo`, `assunto` → `input`).
- Enums: inferência por cardinalidade (≤5 → `radio`, 6–25 → `select`, >25 → `autoComplete`).
- Booleanos: padrão `checkbox` (ou `toggle`); `radio` quando enum textual binária.
- Arrays de enums: pequeno → `chipInput`; maiores → `multiSelect` e dica `filterControlType = multiColumnComboBox`.
- Percent: aplica `numericStep=0.01`, `placeholder="0–100%"`, `numericMin=0`, `numericMax=100` (quando ausentes).

### Migration Notes
- Campos `string` que eram inferidos como `textarea` apenas por `maxLength` entre 101 e 300 agora serão `input` por padrão. Para manter `textarea`, use `@UISchema(controlType=TEXTAREA)` ou utilize nomes semânticos como `descricao`/`observacao`.

### Notes
- Este RC prepara a publicação 1.0.0 final; sem mudanças de API em relação ao beta.1.

## [1.0.0-beta.1] - YYYY-MM-DD

### Added
- New annotation `@OptionLabel` to declare the label source for OptionDTO on entity field or getter (supports inheritance).
- Default `OptionMapper` fallback in `BaseCrudService`: if `getOptionMapper()` is not overridden, entities are projected to `OptionDTO` using `extractId()` and `computeOptionLabel()`.

### Compatibility
- No breaking changes. Existing services and custom mappers continue to work unchanged.
