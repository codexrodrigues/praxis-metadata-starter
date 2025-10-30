# Plano Integrado de Schema (Backend ↔ Frontend)

Este documento consolida o entendimento do fluxo atual (schema base vs JSON de layout), as decisões já implementadas e o planejamento para evoluir com robustez (hash/ETag, identidade de campos, diff/classificação e persistência de versões/overrides no servidor).

## Resumo do que investiguei e como funciona hoje

Dois “tipos” de JSON convivem no frontend:

- JSON de estrutura (schema base, vindo do servidor)
  - É a resposta do `GET /schemas/filtered` (FieldDefinition[] com `x-ui`).
  - Usado para montar colunas de grid, formulários e filtros.
- JSON de layout (customizações de UI no front)
  - É persistido no storage do front (LocalStorage via `ConfigStorage`).
  - Representa o layout, regras e preferências salvas pelo usuário/editor visual (arranjo de sections/rows, behaviors, mensagens, regras, etc.).
  - Para formulários: chave `praxis-form-config-<formId>` (legado: `form-config:<formId>`, migrado automaticamente).
  - Para CRUD/tabela: chave `crud-overrides:<tableId>` (ex.: openMode por ação, route, formId).

Onde fica cada coisa:
- Estrutura (schema base)
  - Antes: não era salvo permanentemente; buscado a cada carga.
  - Agora: `GenericCrudService` guarda `{ schema, schemaHash }` por `schemaId` (cache LocalStorage+memória) e envia `If-None-Match` com o hash para 304.
- Layout (formulários)
  - `PraxisDynamicForm` carrega primeiro o layout local (`praxis-form-config-<formId>`), depois sincroniza com o schema do servidor (via `syncWithServerMetadata`), preservando customizações e incorporando mudanças do backend.
  - O campo `metadata.serverHash` em `FormConfig` é um hash leve gerado no front (não o ETag do backend) e ajuda a detectar mudanças.
- Layout (CRUD)
  - Overrides por tabela são persistidos em `crud-overrides:<tableId>` e mesclados no `CrudLauncherService` ao abrir ações.

Relação entre os dois JSONs:
- O JSON de estrutura é a verdade do backend; o JSON de layout é derivado dele e enriquece com customizações.
- Em cada carga: o `GenericCrudService` revalida o schema por ETag (304 vs 200) e, em formulários, o layout salvo é sincronizado com o schema atual.

Chaves de storage relevantes:
- Form: `praxis-form-config-<formId>` (legado: `form-config:<formId>`)
- CRUD: `crud-overrides:<tableId>`

Referências de código principais:
- Persistência local: `projects/praxis-core/src/lib/services/config-storage.service.ts`
- Form dinâmico: `projects/praxis-dynamic-form/src/lib/praxis-dynamic-form.ts`
- Modelos e sync: `projects/praxis-core/src/lib/models/form/form-config.model.ts`
- Overrides CRUD: editor `projects/praxis-table/src/lib/crud-integration-editor/crud-integration-editor.component.ts`; merge `projects/praxis-crud/src/lib/crud-launcher.service.ts`
- Busca de schemas: `projects/praxis-core/src/lib/services/generic-crud.service.ts`

## Estado Atual (Decisões Implementadas)

- Backend
  - ETag forte e `If-None-Match` (304) em `/schemas/filtered`.
  - `X-Schema-Hash` como header auxiliar.
  - `Access-Control-Expose-Headers: ETag, X-Schema-Hash` (CORS).
  - Expansão de `$ref` ampliada (top-level, properties, items, allOf/oneOf/anyOf, additionalProperties, varredura genérica).
- Frontend
  - `GenericCrudService`
    - `getSchema()` e `getFilteredSchema()` com ETag/If-None-Match, 304 reaproveitando cache, 200 atualizando `{ schema, schemaHash }` por `schemaId`.
    - `schemaId = normalize(path)|operation|schemaType|internal:<bool>|tenant|locale`.
  - Utilitários exportados: `buildSchemaId`, `fetchWithETag`, `LocalStorageCacheAdapter`.

## Robustez: Recomendações

- Identidade e versionamento
  - `schemaId` estável em todo lugar (front e back). Persistir em `FormConfig.metadata.schemaId`.
  - Guardar ETag real do servidor junto ao layout (ex.: `FormConfig.metadata.serverHash`) para sync defensivo.
  - Adotar `@UISchema.key()` (identidade estável de campos). No front, casar por `key`, fallback em `name`.
- Modelo de armazenamento
  - Preferir layout como ‘overlay’ (JSON Merge Patch) sobre o schema base, reduzindo drift.
  - Separar “state bruto” de editores visuais (ex.: `formRulesState`) do layout final aplicado.
- Sincronização e mudanças
  - Sempre revalidar com ETag (If-None-Match). No 200, atualizar cache e `metadata.serverHash`.
  - `syncWithServerMetadata`:
    - Adições: inserir em local seguro (última section/row) e sinalizar.
    - Remoções: marcar órfãos, oferecer limpeza.
    - Modificações: merge preferindo servidor como base, preservando overrides compatíveis.
  - Classificação (quando disponível): usar endpoint de diff para políticas (apply, warn, block).
- Concorrência e overrides no servidor
  - `If-Match` nos `PUT /ui-schema/{schemaId}/overrides` para evitar sobrescrita.
  - ETag efetivo para payload mesclado (ex.: `<schemaHash>:<overridesEtag>`).
- Escopos e governança
  - Overrides por escopo (`user|tenant|app`) com precedência clara.
  - Namespacing por tenant/locale nas chaves de storage e no `schemaId`.
- Resiliência/UX
  - Offline-first (usar cache e revalidar ao voltar a rede), backoff exponencial 
    e experiência previsível ao mudar de versão.

## Migração por Fases

- Fase 0 (feito): ETag/If-None-Match + cache por `schemaId` no front; backend com ETag e expansão de `$ref` robusta.
- Fase 1: `@UISchema.key()` + começar a persistir versões atuais quando hash mudar.
- Fase 2: serviço de diff/classificação + endpoints de overrides (If-Match).
- Fase 3: endpoint `/effective` e flip para ServerCacheAdapter no front.

## Perguntas em aberto / Decisões

- Key obrigatória em todos os DTOs? (recomendado como best practice) ou fallback por nome/heurística.
- Escopo inicial dos overrides (user/tenant/app) e auditoria mínima (updatedBy/updatedAt).
- Estratégia de retenção (N versões ou janela de tempo) — schemas tendem a ser pequenos.

---

Este plano busca manter o front robusto contra mudanças do backend, minimizar drift entre layout e schema, e preparar uma migração gradual para persistir versões/overrides no servidor com governança e auditoria.

---

# Diagramas e Fluxos (Mermaid)

- Sequência: primeira chamada (200)
  - Ver arquivo: `docs/diagrams/schema-seq-first-200.mmd`
- Sequência: chamada condicional (304)
  - Ver arquivo: `docs/diagrams/schema-seq-304.mmd`
- Componentes e Caches (Front/Back)
  - Ver arquivo: `docs/diagrams/schema-components-and-caches.mmd`
- Reconciliador de Tabela (merge por field)
  - Ver arquivo: `docs/diagrams/table-reconciler-seq.mmd`

Os arquivos acima usam Mermaid e podem ser renderizados diretamente por visualizadores compatíveis.

---

# Exemplos de curl (200 e 304)

Assumindo a app exemplo em `http://localhost:8088` e um recurso válido (ex.: `ParametroController`):

1) 200 OK (primeira chamada)

```
curl -i "http://localhost:8088/schemas/filtered?path=/api/parametros/all&operation=get&schemaType=response"

HTTP/1.1 200 OK
ETag: "2f0a3a0c5a1b4e0e0c3a1d9a5b7c9e12aabbccddeeff00112233445566778899"
X-Schema-Hash: 2f0a3a0c5a1b4e0e0c3a1d9a5b7c9e12aabbccddeeff00112233445566778899
Access-Control-Expose-Headers: ETag,X-Schema-Hash
Content-Type: application/json

{ ... schema com x-ui ... }
```

2) 304 Not Modified (com If-None-Match)

```
curl -i -H 'If-None-Match: "2f0a3a0c5a1b4e0e0c3a1d9a5b7c9e12aabbccddeeff00112233445566778899"' \
  "http://localhost:8088/schemas/filtered?path=/api/parametros/all&operation=get&schemaType=response"

HTTP/1.1 304 Not Modified
ETag: "2f0a3a0c5a1b4e0e0c3a1d9a5b7c9e12aabbccddeeff00112233445566778899"
X-Schema-Hash: 2f0a3a0c5a1b4e0e0c3a1d9a5b7c9e12aabbccddeeff00112233445566778899
Access-Control-Expose-Headers: ETag,X-Schema-Hash
```

Notas:
- O backend sempre ecoa `ETag` e `X-Schema-Hash`. O frontend prefere `X-Schema-Hash` quando presente.
- Para perfis multi-tenant/locale, inclua `X-Tenant` e `Accept-Language`.

---

# Exemplo de TableConfigV2 pós‑reconciliação

