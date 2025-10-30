# Resumo Executivo — schemaHash e Cache Condicional HTTP

## Visão Geral

- Backend aderente ao plano: ETag forte com base no JSON canônico; suporte robusto a If-None-Match (lista e “*”); 304 sem body; headers Cache-Control e Vary presentes. Canonicalização segue as regras (ordem de chaves; arrays preservados, exceto `required` ordenado; normalização numérica; sem coerção de strings). Quando `includeInternalSchemas=true`, a expansão de `$ref` copia o schema referenciado completo em propriedades/itens e recursa.
- Testes: unitários e E2E passam localmente, exercitando 200→304 e mudança de ETag ao alternar `includeInternalSchemas`. Pendências para próxima fase: métricas/telemetria, cache por `schemaId` no servidor, e cobertura adicional de `$ref` em `allOf/oneOf/anyOf` e `$ref` no nível raiz.
- Frontend: plano sólido (CacheAdapter, fetchWithETag, useSchemaMetadata) com itens a detalhar: multi-aba, CORS/expose headers, de-dupe/abort e trilha de migração para persistência no servidor.

## Resultados dos Testes

- SchemaCanonicalizerTest: PASS
- ApiDocsControllerSchemaHashTest: PASS (200 com ETag; 304 subsequente; `$ref` expandido integralmente)
- SchemaHashE2eTest: PASS (200→304; ETag diferente ao alternar `includeInternalSchemas`)

## Achados — Correção

- HTTP condicional: ApiDocsController retorna 200 com ETag forte e 304 sem body; inclui `Cache-Control: public, max-age=0, must-revalidate` e `Vary: Accept-Encoding`.
- If-None-Match: parser forte com suporte a múltiplos ETags e “*”, ignorando fracos `W/`.
- Canonicalização/hash: objetos ordenados; arrays preservados (exceto `required`); números normalizados; strings preservadas; SHA‑256 em bytes compactos.
- Expansão de `$ref`: deep-copy do schema completo para propriedades/itens com recursão.

## Achados — Completude

- Cache por `schemaId` (server-side): pendente (há apenas `documentCache` do OpenAPI). [Iniciada base de cache em memória por `schemaId` no controller; próxima fase: promover a serviço + métricas]
- Métricas/telemetria (Micrometer): pendente.
- `$ref` avançado: pendente para `allOf/oneOf/anyOf` e `$ref` no nível raiz selecionado.

## Riscos de Migração (Server Cache/Overrides)

- Consistência de `schemaId` front/back — padronizar util no front.
- Variação por tenant/locale — quando aplicável, incluir `Vary` correspondente para caches proxy/CDN.
- Invalidação coordenada entre caches (OpenAPI vs schema canônico). 
- Tamanho/memória do cache server-side — TTL/sizing/metrics.

## Propostas

- Header auxiliar `X-Schema-Hash` (ergonomia do front): incluído em 200/304.
- Vary condicional: adicionar `Accept-Language`/`X-Tenant` quando a representação variar.
- `$ref` em `allOf/oneOf/anyOf` e root: estender o substituidor recursivo.
- Cache por `schemaId` (server): map `{canonicalJson, schemaHash, updatedAt}`, TTL e invalidation sincronizada com `clearDocumentCache()`.
- Métricas (Micrometer): `praxis.uischema.filtered.requests{status="200|304"}`, `praxis.uischema.hash.time`, `praxis.uischema.payload.bytes`, `praxis.uischema.cache.size`.

## Assinaturas/Cabeçalhos — Exemplos

- Requisição:
  - `GET /schemas/filtered?path=/e2e/with-ref&schemaType=response&includeInternalSchemas=true`
  - `If-None-Match: "<etagAnterior>"`
  - (opcional) `X-Tenant: acme`, `Accept-Language: pt-BR`
- Resposta 200:
  - `ETag: "<novoEtag>"`, `X-Schema-Hash: <hash>`, `Cache-Control: public, max-age=0, must-revalidate`, `Vary: Accept-Encoding`
- Resposta 304:
  - `ETag: "<etagAnterior>"`, `X-Schema-Hash: <hash>`, sem body

## Frontend — Complementos ao Plano

- `schemaId` e namespaces: incluir `includeInternalSchemas`, `tenant`, `locale`; normalizador idêntico ao backend; namespacing no storage.
- `fetchWithETag`: suportar `AbortController` e de-dupe por `schemaId`; `cache: 'no-cache'`.
- CORS/expose headers: expor `ETag` e `X-Schema-Hash` quando houver CORS.
- Multi-aba: BroadcastChannel/storage events, payload `{schemaId, schemaHash}`.
- Offline/erros: stale-while-revalidate, backoff e telemetria.
- Migração para servidor: `ServerCacheAdapter`, contratos `/ui-schema/*`, `If-Match` em overrides.

## Plano de Ação

- Backend (próxima fase)
  - Estender `$ref` para `allOf/oneOf/anyOf` e root
  - Implementar cache por `schemaId` como serviço + métricas
  - Adicionar métricas (Micrometer)
- Frontend (próxima fase)
  - Implementar `buildSchemaId`, `LocalStorageCacheAdapter`, `fetchWithETag`, `useSchemaMetadata` com de‑dupe/abort e sync multi‑aba
  - Telemetria mínima e feature flag para `ServerCacheAdapter`

