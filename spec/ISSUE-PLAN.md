# Tarefa: Backend Compatível (.NET) — Plano e Critérios

Objetivo
- Implementar uma PoC .NET compatível com a UI do Praxis e formalizar uma Specification validável (x‑ui + /schemas/filtered).

Milestones
1) Levantamento do contrato (starter + UI workspace)
2) Spec JSON Schemas + README (SemVer, precedência)
3) Design técnico (.NET) + protótipos de ETag/304 e grupos OpenAPI
4) Implementação PoC (.NET)
5) Suíte de conformidade (validação contra JSON Schemas + ETag/304 + filtros/paginação)
6) Documentação e guia de adoção

Critérios de Aceitação
- `/schemas/filtered`: parâmetros, ETag forte, `Access-Control-Expose-Headers: ETag,X-Schema-Hash`.
- `x-ui`: valida contra `x-ui-field.schema.json`; `x-ui.validation` consistente; `custom.*` permitido.
- `x-ui.resource`: `idField`, `idFieldValid`, `readOnly`, `capabilities` corretos.
- Operações principais funcionam: `POST /filter`, `POST /filter/cursor`, `POST /locate`, `POST /options/filter`, `GET /options/by-ids`, `GET /all`, `GET /{id}`.
- `schemaId` varia por tenant/locale; 304 operando conforme `If-None-Match`.
- UI Quickstart renderiza tabela/form sem mudanças além de `baseUrl`.

Referências
- docs/spec/ (este diretório)
- praxis-metadata-starter/src/main/javadoc/doc-files/faq.html
- praxis/frontend-libs/praxis-ui-workspace/README.md, docs/HOST-INTEGRATION-GUIDE.md

