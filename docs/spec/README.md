# Especificação de Metadados (x‑ui) — Draft 1.0.0

Escopo
- Padronizar o vocabulário x‑ui emitido por backends e consumido pela UI.
- Definir JSON Schemas para validação de:
  - x‑ui de campo (property) — `x-ui-field.schema.json`
  - x‑ui em nível de operação (endpoint) — `x-ui-operation.schema.json`
  - x‑ui.resource no payload de `/schemas/filtered` — `x-ui-resource.schema.json`

Precedência (normativa)
1) Defaults de @UISchema (ou equivalentes no ecossistema)
2) Detecção automática (OpenAPI type/format/enum + heurísticas)
3) Valores explícitos de @UISchema
4) Bean Validation (ex.: NotNull/Size/Pattern → x‑ui.validation)
5) extraProperties/custom.* (precedência máxima)

Normas (MUST/SHOULD)
- MUST: chaves canônicas definidas nesta spec devem respeitar tipo/semântica.
- SHOULD: chaves personalizadas devem usar prefixo `custom.`.
- MUST: `x-ui.validation` conter apenas chaves de validação; mensagens podem ser preenchidas por padrão e sobrescritas.
- MUST: `x-ui.resource` incluir `idField` (string), `idFieldValid` (boolean), `readOnly` (boolean) e `capabilities` (mapa boolean).
- SHOULD: Respostas de `/schemas/filtered` enviarem `ETag` forte e `X-Schema-Hash`; expor via `Access-Control-Expose-Headers`.

Versionamento
- `specVersion`: `1.0.0-draft`. Use SemVer; minor para adições backwards‑compatível; major para breaking changes.

Conformidade
- Validar payloads com os JSON Schemas desta pasta.
- Rodar suíte de conformidade no CI (ex.: ajv/Node ou validador JSON Schema em Java/C#).

Arquivos
- `x-ui-field.schema.json` — chaves por campo
- `x-ui-operation.schema.json` — chaves por operação
- `x-ui-resource.schema.json` — chaves por recurso (/schemas/filtered)

