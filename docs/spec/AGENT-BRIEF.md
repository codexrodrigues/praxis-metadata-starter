# Brief — Backend Compatível com Praxis UI (ex.: .NET)

Objetivo
- Padronizar contrato e metadados (OpenAPI + x‑ui) para que qualquer backend (ex.: .NET/ASP.NET Core) seja plug‑and‑play com o frontend Praxis.
- Entregar plano, especificação validável (JSON Schema), suíte de conformidade e uma PoC mínima em .NET.

Escopo de Compatibilidade (mínimo)
- Endpoint `GET /schemas/filtered` com parâmetros: `path`, `operation`, `schemaType` (response|request), `includeInternalSchemas` (bool), `idField` (opcional), `readOnly` (opcional).
- Resposta mescla `x-ui` e inclui `x-ui.resource` { `idField`, `idFieldValid`, `readOnly`, `capabilities` }.
- ETag forte e 304 com `If-None-Match`; headers: `ETag`, `X-Schema-Hash`, `Access-Control-Expose-Headers: ETag,X-Schema-Hash`.
- `schemaId` inclui: `path|operation|schemaType|internal|tenant|locale`.
- Grupos OpenAPI (documento por grupo com fallback para documento completo).
- x‑ui (campo, validações) e x‑ui (operação) com precedência: defaults @UISchema → detecção (type/format/heurísticas) → valores explícitos @UISchema → Bean Validation → extraProperties (máxima).
- Recursos do controller base: CRUD, `POST /filter`, `POST /filter/cursor`, `POST /locate`, `POST /options/filter`, `GET /options/by-ids`, `GET /all`, `GET /{id}`; ordenação padrão e heurística de `idField`.
- i18n/tenant: `Accept-Language` e `X-Tenant` compõem o `schemaId` (ETag varia por idioma/tenant).
- Segurança fora do starter (configurar no host; JWT/OIDC, CORS, CSRF conforme política).

Repositórios locais (referência)
- Starter Java (referência): `praxis-metadata-starter/`
- UI Angular: `praxis/frontend-libs/praxis-ui-workspace/` e `praxis-ui-quickstart/`
- Complementares: `praxis-backend-seed-app/`, `praxis-openapi-ui-schema-generator/`, `praxis-node/`

Especificação (machine‑readable)
- Esta pasta contém rascunho de JSON Schemas:
  - `x-ui-field.schema.json` — valida `extensions["x-ui"]` de propriedades (campo)
  - `x-ui-operation.schema.json` — valida `paths.{path}.{operation}.x-ui`
  - `x-ui-resource.schema.json` — valida `x-ui.resource` no payload de `/schemas/filtered`
- README da spec: regras normativas (MUST/SHOULD/MAY), precedência e SemVer.

Suíte de Conformidade (esperado)
- Validar `/schemas/filtered` contra os JSON Schemas deste diretório.
- Testar ETag/304, expose headers, variação por `X-Tenant`/`Accept-Language`.
- Testes de `filter`/`filter/cursor`/`locate`/`options/by-ids` mínimos.

PoC .NET (esperada)
- ASP.NET Core 8+, Swashbuckle, filtros para injetar `x‑ui` (campo/operação/recurso).
- Canonicalização determinística e SHA‑256 para ETag forte.
- Expansão `$ref` quando `includeInternalSchemas=true`.

Critérios de Aceitação
- UI Quickstart consome o backend .NET mudando apenas `baseUrl`.
- Renderização dinâmica de tabela/form a partir do contrato (x‑ui), com `displayColumns`, validações e options.
- ETag/304 operativo; schemaId/headers corretos; `x-ui.resource` completo.

Fases do Plano (sugestão)
1) Inventário detalhado (starter + UI workspace)
2) Fechar JSON Schemas + README (SemVer, precedência)
3) Design técnico .NET + POCs de cada requisito
4) Implementação PoC .NET + suíte de conformidade
5) Documentação e guia de adoção para backends compatíveis

