# Plano de Controle Explícito de Cache/ETag no Frontend e Migração para Persistência no Servidor

## 1) Objetivo e Escopo

- Controlar explicitamente a revalidação de schemas via `ETag`/`If-None-Match` no frontend, sem depender do cache implícito do navegador.
- Padronizar identidade (`schemaId`), cache local, overrides e telemetria.
- Preparar desde já a arquitetura para migrar a persistência de `schema`/`overrides`/telemetria para o servidor, com mínimo retrabalho.

Outros objetivos:
- Evitar recomputo e tráfego redundante (304 em vez de 200 quando possível).
- Garantir UX estável ao atualizar schemas (overrides + política de diffs no futuro).

## 2) Estado Atual (Backend)

- Endpoint: `GET /schemas/filtered`.
- Implementado: `ETag` (forte), `If-None-Match` (304), `Cache-Control: public, max-age=0, must-revalidate`, `Vary: Accept-Encoding`.
- O `ETag` é calculado sobre o JSON canônico do payload final (schema selecionado + `x-ui` de operação; refs expandidas quando `includeInternalSchemas=true`).

## 3) Identidade: `schemaId`

```
schemaId = normalize(path)
          + '|' + operation          // 'get' (padrão), 'post', ...
          + '|' + schemaType         // 'response' (padrão) ou 'request'
          + '|internal:' + includeInternalSchemas
          + (tenant ? '|tenant:' + tenant : '')
          + (locale ? '|locale:' + locale : '')
```

Notas:
- `normalize(path)`: remover barras duplicadas (sem alterar maiúsculas/minúsculas), sem barra final redundante.
- O cálculo deve ser idêntico no front e no back (referência: `SchemaIdBuilder`).

## 4) Abstrações e Contratos

### 4.1 CacheAdapter (front)

- Contrato estável para leitura/escrita/local/servidor:
```
interface CacheAdapter {
  get(schemaId: string): Promise<{ schema: any; schemaHash: string; overrides?: any } | undefined>;
  set(schemaId: string, entry: { schema: any; schemaHash: string; overrides?: any }): Promise<void>;
  remove(schemaId: string): Promise<void>;
  subscribe?(cb: (schemaId: string, entry?: { schema: any; schemaHash: string }) => void): () => void;
}
```

- Implementações:
  - LocalStorageCacheAdapter (agora) — persistência local + memória.
  - ServerCacheAdapter (futuro) — consome APIs do servidor.
  - HybridCacheAdapter (migração) — dual-read/dual-write com feature flag.

### 4.2 fetchWithETag (front)

```
type FetchResult =
  | { status: 304 }
  | { status: 200; schema: any; schemaHash: string };

async function fetchWithETag({ url, schemaHash, tenant, locale, signal }): Promise<FetchResult> {
  const headers: Record<string, string> = {};
  if (schemaHash) headers['If-None-Match'] = `\"${schemaHash}\"`;
  if (tenant) headers['X-Tenant'] = tenant;
  if (locale) headers['Accept-Language'] = locale;

  const res = await fetch(url, { headers, cache: 'no-cache', credentials: 'include', signal });
  if (res.status === 304) return { status: 304 };

  const etag = res.headers.get('ETag') || '';
  const newHash = etag.replace(/^W\\//, '').replace(/^\"|\"$/g, '');
  const schema = await res.json();
  return { status: 200, schema, schemaHash: newHash };
}
```

### 4.3 useSchemaMetadata (hook)

```
function useSchemaMetadata(params: {
  path: string;
  operation?: 'get' | 'post' | string;
  schemaType?: 'response' | 'request';
  includeInternalSchemas?: boolean;
  tenant?: string;
  locale?: string;
  overridesScope?: 'user' | 'tenant' | 'app';
}) {
  // Calcula schemaId, lê cache, chama fetchWithETag, aplica overrides,
  // memoiza requests por schemaId, sincroniza mudanças via subscribe().
  // Retorna { schema, loading, error, refresh }.
}
```

## 5) Overrides

- `overrides` como JSON Merge Patch por `schemaId` (escopo `user` inicialmente).
- `applyOverrides(schema, mergePatch)` antes da renderização.
- Futuro: camadas de overrides (user/tenant/app) resolvidas por precedência e armazenadas no servidor.

## 6) Concorrência, Multi-aba e De-dupe

- Memoização por `schemaId`: `inFlight: Map<string, Promise<...>>` para evitar fetch duplicado.
- Multi-aba: `BroadcastChannel` (ou storage events) para sincronizar atualizações e invalidar caches em tempo real.

## 7) Telemetria (front)

- Contadores e timers:
  - `uischema.requests.200|304`
  - `uischema.fetch.duration`
  - `uischema.cache.hit|miss`
  - `uischema.schema.changed` (hash trocou)
- Futuro (server): POST `/ui-schema/events` para centralizar métricas.

## 8) Erros e Offline

- Sem rede/timeout: retornar `schema` do cache (modo offline) e agendar revalidação posterior (`refresh`).
- 4xx/5xx: não sobrescrever cache; expor erro; opção de retry.
- ETag ausente (caso raro): seguir fluxo e armazenar `schema`; marcar telemetria (não calcular hash no front por padrão).

## 9) APIs do Servidor (Futuro)

- Cache central de schemas:
  - `GET /ui-schema/{schemaId}` → `{ schema, schemaHash, updatedAt }` + `ETag`.
  - `POST /ui-schema/prefetch?...` (opcional) → aquecimento assíncrono.
- Overrides:
  - `GET /ui-schema/{schemaId}/overrides?scope=user|tenant|app`
  - `PUT /ui-schema/{schemaId}/overrides` (com `If-Match` para evitar conflitos)
- Diff/sumário (opcional):
  - `GET /ui-schema/{schemaId}/diff?fromHash=<h>&format=summary|patch`
- Telemetria:
  - `POST /ui-schema/events` (200/304, latência, falhas, mudanças de hash)

## 10) Migração por Fases

1. Preparação (agora)
   - Introduzir `CacheAdapter`, `fetchWithETag`, `useSchemaMetadata`, overrides locais e memoização.
2. Dual-read (feature flag)
   - `ServerCacheAdapter` lê do servidor com fallback local; dual-write seletivo de overrides.
3. Flip para servidor
   - Tornar servidor origem primária; local como fallback offline.
   - Pré-aquecer schemas críticos.
4. Otimizações
   - Telemetria server-side, diffs/classificação, push (SSE/WebSocket) para invalidar caches.

## 11) Testes

- Unitários (front):
  - `buildSchemaId` (variações path/operation/schemaType/internal/tenant/locale)
  - `fetchWithETag`: 200/304, extração de ETag, sem body em 304
  - `useSchemaMetadata`: inicialização com/sem cache, refresh, overrides, memoização
- Integração (mock server):
  - 200 → cache atualizado; 304 subsequente usa cache
  - Mudança de `includeInternalSchemas` → novo `schemaId` e novo 200/ETag

## 12) Critérios de Aceite

- Dado `schema` em cache com `schemaHash`, quando chamar `useSchemaMetadata`, então deve:
  - Enviar `If-None-Match` explícito com o hash vigente;
  - Retornar 304 e usar o cache local sem quebrar;
  - Retornar 200 quando o conteúdo mudar, atualizar `schema`/`schemaHash` e aplicar `overrides`.
- Multi-aba sincroniza mudanças de cache.
- Telemetria registra 200 vs 304 e latência.

## 13) Riscos e Mitigações

- Divergência entre `schemaId` front/back → padronizar util no front + testes.
- Gateways removendo ETag → telemetria + fallback controlado (não calcular hash no front por padrão).
- Concorrência/operação duplicada → memoização por `schemaId` e cancelamento via `AbortController`.

## 14) Pontos a Validar (com outro agente)

- Contrato do `CacheAdapter` cobre migração para servidor com mínimo retrabalho?
- Necessidade de `X-Tenant` e `Accept-Language` no front (variar representação)?
- Estratégia de overrides por escopo (user/tenant/app) e resolução de precedência.
- Eventos de sync entre abas (BroadcastChannel vs storage events) e impacto em browsers antigos.
- Prioridade de telemetria para MVP.

## 15) Checklist de Implementação (Front)

- [ ] `buildSchemaId` + testes
- [ ] `LocalStorageCacheAdapter` + testes
- [ ] `fetchWithETag` + testes
- [ ] `useSchemaMetadata` (memoização + sync) + testes
- [ ] `applyOverrides` (merge patch) + testes
- [ ] Documentação de uso + exemplos (React/Vanilla)
- [ ] Feature flag para `ServerCacheAdapter` (stub da interface e contratos de API)

