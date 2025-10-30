# Plano de Assinatura e Diferenças de Schema (schemaHash)

## Objetivo

- Garantir uma assinatura determinística do JSON de schema (DSL de UI + trechos OpenAPI relevantes) enviada pelo backend.
- Qualquer alteração no JSON (de qualquer propriedade ou ordem relevante) deve produzir um hash diferente.
- Habilitar verificação rápida no frontend via cabeçalho `ETag` (If-None-Match) e, opcionalmente, cabeçalho auxiliar `X-Schema-Hash`.
- Disponibilizar diffs (JSON Patch/Merge Patch) e classificação de mudanças para orientar atualização e merge de overrides.

## Escopo

- Projeto: `backend-libs/praxis-metadata-starter` (backend) e consumidores (frontends, ex.: Praxis Dynamic Fields).
- Endpoint atual: `/schemas/filtered` (mantido). Planejar suporte adicional a hash/condicional e endpoints de diff.

Escopo do hash (definitivo):
- O hash cobre exatamente o payload final retornado por `/schemas/filtered`:
  - O schema específico selecionado (após resolução e, se aplicável, expansão de `$ref`).
  - O bloco `x-ui` de nível de operação (copiado de `paths[<path>][<operation>].x-ui`).
- Não considera extensões `x-ui` em `components/schemas/.../extensions` por propriedade.

## Conceitos

- schemaId: Identidade lógica do schema (ex.: combinação de `path`, `operation`, e `schemaType` ou um alias consistente).
- schemaHash: SHA-256 do JSON canônico entregue pelo endpoint (sem timestamps ou metadados externos).
- Canonicalização: Regras para serializar sempre o mesmo conteúdo nos mesmos bytes, evitando variações não semânticas.

## Regras de Canonicalização (determinísticas)

- Objetos: ordenar chaves lexicograficamente em toda a árvore.
- Arrays: manter a ordem original (ordem é semântica), exceto para `required`, que deve ser ordenado alfabeticamente (case-sensitive) para reduzir churn não semântico.
- Tipos: preservar exatamente o tipo JSON produzido (strings, números, booleanos, null, objetos, arrays).
- Números: normalizar nós numéricos JSON usando `BigDecimal.stripTrailingZeros()` ao gerar `DecimalNode` canônico (evita `1` vs `1.0`). Não converter strings numéricas do `x-ui` para número.
- Valores de `extraProperties`: são strings e permanecem como strings (se o valor for um JSON aninhado, ele continua string, preservando diferenças de whitespace/ordem de chaves conforme enviado pelo backend).
- Não introduzir/remover campos nulos durante a canonicalização (hash reflete exatamente o que o cliente recebe).

Observação: Ordenar chaves de objetos estabiliza a assinatura frente à ordem de inserção em Maps; não reordenar arrays assegura que mudanças na ordenação de `options`, `required`, etc., alterem o hash.

## Cálculo do Hash

- Entrada: o mesmo JSON (payload) retornado por `/schemas/filtered` após todas as transformações finais (ex.: expansão de `$ref` quando habilitada via `includeInternalSchemas`) e com a chave `x-ui` já copiada do nível de operação.
- Pipeline:
  1) Converter o payload final em `JsonNode`.
  2) Canonicalizar recursivamente (regras acima).
  3) Serializar em bytes UTF‑8 sem pretty/indent.
  4) Aplicar SHA‑256 e codificar em hex (ou base64url).

Nenhum dado volátil (p.ex. timestamps) é adicionado ao payload, portanto o mesmo conteúdo produzirá o mesmo hash entre reinícios.

## Integração HTTP (Backend)

- Resposta de `/schemas/filtered`:
  - Header `ETag: "<schemaHash>"` (forte, baseado estritamente no conteúdo canônico).
  - Além do ETag, recomendamos também retornar `X-Schema-Hash: <schemaHash>` como header auxiliar (não canônico).
  - Não incluir `x-schema-hash` no body para evitar autorreferência no próprio payload.
- Requisição condicional:
  - Aceitar `If-None-Match: "<hash-do-cliente>"`.
  - Se igual ao hash atual, responder `304 Not Modified` sem body.
- Opcional (ergonomia): aceitar cabeçalho `X-Schema-Hash: <hash-do-cliente>` ou query param `fromHash` para endpoints de diff.

Headers adicionais recomendados para comportamento consistente via proxies/CDNs:
- `Cache-Control: public, max-age=0, must-revalidate`
- `Vary: Accept-Encoding` (adicionar `X-Tenant` e/ou `Accept-Language` apenas se a representação variar por esses cabeçalhos)

Suporte a múltiplos ETags no `If-None-Match`:
- Tratar lista de valores (`"h1", "h2"`) e retornar 304 se QUALQUER um coincidir com o hash atual.
- Tratar `*` como “qualquer representação existente” conforme RFC.
- Ao responder 304, ecoar o mesmo `ETag` na resposta.

Assinatura e exemplo de uso no controller (proposta):
- `public ResponseEntity<Map<String, Object>> getFilteredSchema(@RequestParam String path, @RequestParam(defaultValue = "get") String operation, @RequestParam(defaultValue = "false") boolean includeInternalSchemas, @RequestParam(defaultValue = "response") String schemaType, @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch, @RequestHeader(value = "X-Tenant", required = false) String tenant, Locale locale)`
- Fluxo:
  1) Resolver documento e schema; aplicar expansão de `$ref` se `includeInternalSchemas=true`.
  2) Montar `schemaMap` + injetar `x-ui` de operação.
  3) Canonicalizar → bytes → SHA‑256 → `hashHex` → `etag = "\"" + hashHex + "\""`.
  4) Se `IfNoneMatch.matches(ifNoneMatch, etag)` → `304` com `ETag` e cabeçalhos de cache; senão `200` com body + `ETag` + `X-Schema-Id`.

Pseudocódigo de parser If-None-Match (forte, múltiplos):
```
static boolean matches(String header, String currentEtagQuoted) {
  if (header == null || header.isBlank()) return false;
  String h = header.trim();
  if ("*".equals(h)) return true; // recurso existe
  for (String part : h.split(",")) {
    String tag = part.trim();
    if (tag.startsWith("W/")) tag = tag.substring(2).trim(); // ignorar fraqueza
    if (tag.equals(currentEtagQuoted)) return true;
    // tolerância opcional: comparar também sem aspas
    String unquoted = currentEtagQuoted.replace("\"", "");
    if (tag.equals(unquoted)) return true;
  }
  return false;
}
```

## Endpoints de Diff (planejamento)

- Novos endpoints opt‑in (via `praxis.uischema.diff.enabled=true`):
  - `GET /ui-schema/{schemaId}` → retorna o mesmo payload de schema + `ETag`.
  - `GET /ui-schema/{schemaId}/diff?fromHash=...&format=patch|merge|summary` → retorna:
    - `patch`: JSON Patch (RFC 6902).
    - `merge`: JSON Merge Patch (RFC 7396).
    - `summary`: resumo estruturado (campos adicionados/removidos/modificados + classificação).
- Alternativa: incorporar `fromHash` em `/schemas/filtered` e responder com `x-diff` (resumo/patch) no body. Mantém compatibilidade com o fluxo atual.
 - Otimização: quando `fromHash` for igual ao hash atual, responder `204 No Content`.

## Classificação de Mudanças

- Breaking: alterar `type`/`controlType`; tornar opcional → obrigatório; remover campo; restringir faixas; remover opções de enum.
- Non‑breaking: adicionar campo opcional; tornar obrigatório → opcional; ampliar faixas; adicionar opções de enum.
- Potencialmente‑breaking (UX): mudar `label`, `order`, `defaultValue`, `helpText`.

Detalhamento adicional (heurística):
- `required`: adicionar → breaking; remover → non‑breaking; reordenar → não muda hash e não muda classificação.
- `enum`: remover valor → breaking; adicionar valor → non‑breaking; reordenar → potencialmente‑breaking (UX) e muda hash.
- `min/max`: aumentar `min` ou diminuir `max` → breaking; relaxar (diminuir `min`, aumentar `max`) → non‑breaking.
- `pattern`: qualquer alteração → potencialmente‑breaking (sem análise semântica do regex).
- `format` (ex.: `date` → `date-time`): breaking.

## Implementação (Backend) — Fase 1

- Criar utilitários:
  - `SchemaCanonicalizer.canonicalize(JsonNode)` — ordena chaves em objetos; mantém arrays; preserva tipos e nulls.
  - `SchemaHashUtil.compute(JsonNode)` — SHA‑256 em bytes da serialização canônica (hex/base64url).
- Integrar no `ApiDocsController`:
  - Após montar o `schemaMap`, convertê‑lo em `JsonNode`, canonicalizar e calcular `schemaHash`.
  - Mudar assinatura para `ResponseEntity<Map<String,Object>>` e adicionar `ETag` ao `ResponseEntity`.
  - Se header `If-None-Match` for igual ao hash atual, responder 304.
- Documentar no README e neste arquivo.

Lacunas atuais no código (status “as is”):
- `ApiDocsController.getFilteredSchema(...)` retorna `Map<String,Object>` diretamente (sem `ResponseEntity`, sem ETag/304/Cache-Control).
- Não existem `SchemaCanonicalizer`/`SchemaHashUtil` no projeto.
- O controller injeta `x-ui` a partir do path/operation (`paths[...][operation].x-ui`) e não dos `components/schemas/.../properties/.../extensions` — o hash deve cobrir exatamente este payload final (schema específico + `x-ui` de operation).

Expansão de `$ref` (includeInternalSchemas):
- A implementação atual substitui `$ref` apenas por `properties` do schema referenciado, perdendo metadados como `type`, `required`, `format`, `description`.
- Diretriz: quando `includeInternalSchemas=true`, expandir o objeto referenciado por inteiro (preservar `type`, `required`, `format`, `description`, `allOf/oneOf/anyOf/items`, etc.). Reaplicar recursão em `items.$ref`.
- Se um “achatamento” de `properties` for mantido por compatibilidade, documentar explicitamente que metadados do schema referenciado não serão incluídos e, portanto, não afetarão o hash.

## SchemaId Canônico

Para alinhar caches (backend e frontend) e evitar colisões, padronizar a composição do `schemaId`:

`schemaId = normalize(decodedPath) + "|" + operation + "|" + schemaType + "|internal:" + includeInternalSchemas + optional("|tenant:" + tenantHeader) + optional("|locale:" + acceptLanguage)`

Notas:
- `normalize(decodedPath)`: path decodificado e normalizado (sem barras finais duplicadas, case preservado).
- Em ambientes que exigem chave curta, pode-se aplicar SHA‑256 sobre a string acima e usar o hex como chave de cache.
- O `schemaId` deve ser idêntico no backend e no frontend para indexar caches/local storage.

## Implementação (Backend) — Fase 2

- Serviço de diff: `SchemaDiffService.compare(JsonNode from, JsonNode to)` → `SchemaDiffResult` com:
  - `jsonPatch` (RFC 6902) — usando `zjsonpatch` ou implementação própria.
  - `mergePatch` (RFC 7396).
  - `summary` (added/removed/modified por `key`/nome do campo) + `classification`.
- Endpoints de diff (ou acoplados ao `/schemas/filtered` via `fromHash`).

## Implementação (Backend) — Fase 3 (opcional)

- Adicionar `key()` em `@UISchema` para identidade estável de campos (fallback: nome do atributo).
- Marcação `deprecated/replacementFor` para ajudar migrações.

---

# Plano de Integração no Frontend

## Armazenamento Local de Schema

- Persistir por `schemaId` (chave que combine path + operation + schemaType, ou um id lógico do backend):
  - `schema`: payload JSON completo.
  - `schemaHash`: string do hash (do header ETag ou campo `x-schema-hash`).
  - `overrides`: JSON Merge Patch local (opcional), aplicado sobre `schema` na renderização.

## Protocolo de Verificação

- Requisição:
  - Preferir `If-None-Match: "<schemaHash local>"`.
  - Alternativamente, enviar `X-Schema-Hash: <schemaHash local>` (apoio à depuração ou gateways que manipulam ETags).
- Resposta:
  - `304 Not Modified`: usar `schema` cacheado localmente; não alterar UI.
  - `200 OK`: ler `ETag`/`x-schema-hash` e comparar com o local.
    - Diferente: considerar “schema atualizado” e seguir estratégia abaixo.

## Estratégia ao Detectar Mudança de Hash

1) Aplicar overrides locais (se houver) como JSON Merge Patch sobre o novo `schema`.
2) Se endpoint de diff estiver disponível:
   - Buscar `summary`/`classification` para feedback ao usuário/desenvolvedor.
3) Regras por classificação (se disponível) ou política geral:
   - Non‑breaking: aplicar e re‑renderizar silenciosamente.
   - Potencialmente‑breaking: aplicar, logar aviso/telemetria, oferecer banner de “UI atualizada”.
   - Breaking: bloquear funcionalidade da tela/fluxo afetado, exibir mensagem clara e, opcionalmente, UI de migração.

## Estados/Erros

- Falha de rede: usar `schema` cacheado (modo offline). Ao voltar a rede, repetir verificação.
- Inconsistência (ETag ausente): tratar como `200` sem hash; comparar payloads por hash calculado no front (fallback) se necessário.

## Fases no Frontend

- Fase A (mínima):
  - Persistir `schema` + `schemaHash`.
  - Usar `If-None-Match` e aceitar `304`.
  - Atualizar cache e re-render ao receber `200` com hash diferente.

- Fase B (melhorada):
  - Manter `overrides` como JSON Merge Patch (somente ajustes de layout/UX locais).
  - Aplicar `overrides` a cada atualização de schema.

- Fase C (com diff e classificação):
  - Consumir endpoint de `summary`/`patch`.
  - Exibir painel de mudanças e status (breaking/potential/non‑breaking).
  - Automatizar decisões (silencioso vs. alerta vs. bloqueio).

## Métricas/Telemetria

- Registrar quando o hash muda, tempo entre mudanças, telas afetadas, quantidade de breaking vs. non‑breaking, e se houve erros de render pós‑atualização.
- Sugestões de métrica (Micrometer):
  - `praxis.uischema.filtered.requests{status="200|304"}` (counter)
  - `praxis.uischema.hash.time` (timer de canonicalização + hash)
  - `praxis.uischema.payload.bytes` (distribution summary)
  - `praxis.uischema.hash.cache.size` (gauge)
  - `praxis.uischema.ifNoneMatch.matches` (counter)

---

# Próximas Fases e Cenários de Diferença de Hash

1) Hash diferente detectado (cliente → servidor):
   - Backend: já retorna `200` com novo `ETag` e payload atualizado.
   - Frontend: aplica overrides, re-renderiza e salva novo `schemaHash` e `schema` no cache.

2) Hash diferente + endpoint de diff ativo:
   - Frontend: chama `/ui-schema/{schemaId}/diff?fromHash=<hashLocal>&format=summary`.
   - Exibe resumo e, se “breaking”, bloqueia ações críticas e orienta migração.

3) Múltiplas telas/rotas dependentes do mesmo schema:
   - Coordenar atualização em lote; evitar re-fetch redundante com memoização por `schemaId`.

4) Rollback/Feature flags:
   - Se o hash novo causa regressões no front, um feature‑flag pode impedir uso do novo schema até correção (lado front), mantendo o cache anterior (com aviso claro ao usuário sobre desatualização).

---

# Tarefas (Backlog)

- Backend — Fase 1:
  - Implementar `SchemaCanonicalizer` e `SchemaHashUtil`.
  - Integrar ETag e `If-None-Match` em `/schemas/filtered` (retornar `ResponseEntity`, cabeçalhos `ETag`, `Cache-Control`, `Vary`).
  - Parser robusto de `If-None-Match` (múltiplos valores, `*`, ETags fracas `W/` ignoradas para comparação de hash forte).
  - Documentar contrato de hashing e headers.
  - Métricas básicas (Micrometer): contadores 200 vs 304, tempo de canonicalização+hash, tamanho médio do payload.
  - Corrigir expansão de `$ref` para copiar o objeto inteiro do schema referenciado (não apenas `properties`).
  - `SchemaIdBuilder` e cache por `schemaId` do `canonicalJson` + `schemaHash`, com invalidação coordenada ao limpar `documentCache` em `ApiDocsController`.

- Backend — Fase 2:
  - Implementar `SchemaDiffService` (JSON Patch/Merge Patch/Summary) e endpoints opt‑in.
  - Classificação de mudanças.

- Backend — Fase 3 (opcional):
  - `@UISchema.key()` e marcações `deprecated/replacementFor`.
  - Cache por `schemaId` do `canonicalJson` + `schemaHash` com invalidation alinhada à limpeza do cache de documentos OpenAPI (evitar recomputo por request).

- Frontend — Fase A:
  - Cache com `schema` + `schemaHash` por `schemaId`.
  - Requisições com `If-None-Match` e handling de `304`.

- Frontend — Fase B:
  - Suporte a `overrides` como JSON Merge Patch e aplicação pós‑update.

- Frontend — Fase C:
  - Consumo de diff/summary, exibição de mudanças e aplicação de políticas por classificação.

---

# Revisão Crítica e Decisões de Política

Esta seção captura decisões refinadas após revisão do código atual e riscos práticos.

1) Arrays potencialmente instáveis
- Opções A/B:
  - A) Ordenar apenas arrays com semântica de conjunto (ex.: `required`) para reduzir churn não semântico. Recomendada.
  - B) Não ordenar nenhum array (como no plano inicial), aceitando churn por reorder. Simples, porém ruidoso.
- Decisão: A (ordenar apenas `required`). Observação: isso implica que reordenar `required` sozinho não altera o hash, por ser não semântico.

2) Números e escala
- Normalizar números nativos com `BigDecimal.stripTrailingZeros()` durante a serialização canônica (evita `1` vs `1.0`).
- Manter strings numéricas do `x-ui` como strings (sem transformar tipo).

3) extraProperties
- Política: manter como string crua (qualquer alteração textual muda o hash). Documentar claramente.
- Opcional (futuro): permitir propriedades com sufixo `Json` para indicar parsing/canonicalização de JSON embutido (opt‑in).

4) Corpo vs. headers para hash
- Não incluir `x-schema-hash` no body. Usar `ETag` (forte) e header auxiliar `X-Schema-Hash`.

5) includeInternalSchemas
- O hash representa o payload final entregue (com ou sem expansão de `$ref`, conforme parâmetro). `schemaId` deve refletir também essa variação de representação.

6) HTTP Cache/Proxies
- Adotar `Cache-Control: public, max-age=0, must-revalidate` e `Vary: Accept, Accept-Encoding` para coerência com CDNs/proxies.
- Implementar parsing robusto de `If-None-Match` (múltiplas ETags e `*`).

7) Observabilidade/segurança
- Métricas de 200 vs 304, tempo de canonicalização, tamanho do payload.
- Evitar logar `schemaHash` em nível INFO em multi‑tenant; prefira DEBUG.
- Revisar conteúdo de `x-ui` para ambientes multi‑tenant (sanitização se necessário por feature flag).

---

# Plano de Testes

Unitários (Canonicalizer/Hash):
- Objetos: ordem diferente de chaves → bytes iguais e hash igual.
- Arrays:
  - `required` com ordem A/B → hash igual (política A).
  - `enum`/`options` reordenadas → hash diferente (ordem preservada).
- Números: `1` (número) vs `1.0` (BigDecimal com escala) → hash igual pela normalização.
- extraProperties: JSON em string com espaços diferentes → hash diferente (política string crua).

Integração (ETag/304):
- GET sem `If-None-Match` → 200 com `ETag` e headers de cache.
- GET com `If-None-Match` igual → 304 sem body, com `ETag`.
- `If-None-Match` com múltiplos ETags → 304 se qualquer um bater.

Arrays e `$ref` (includeInternalSchemas):
- Com e sem expansão de `$ref`, conferindo que o hash reflete o payload final.

Diff (quando implementado):
- `summary` classifica corretamente: adicionar campo opcional, remover item de enum, tornar `required`, relaxar/restringir `min/max` e `pattern`.

---

# Status de Implementação (Atual)

- ETag/If-None-Match em `/schemas/filtered`: implementado
  - Resposta 200 inclui `ETag: "<schemaHash>"`, `Cache-Control: public, max-age=0, must-revalidate`, `Vary: Accept-Encoding`
  - Requisição condicional com `If-None-Match` retorna 304 sem body quando o ETag coincide (suporta múltiplos valores e `*`)
- Canonicalização + hash: implementado
  - `SchemaCanonicalizer`: objetos ordenados, arrays preservados (exceto `required` ordenado), números normalizados, strings preservadas
  - `SchemaHashUtil`: SHA-256 do JSON canônico (bytes compactos)
- Expansão de `$ref` com `includeInternalSchemas=true`: implementado para copiar o schema inteiro referenciado (não apenas `properties`)
- `SchemaIdBuilder`: disponível para composição de chaves; cache por `schemaId` ainda pendente
- Métricas (Micrometer): pendente

Assinatura atual do controller
- `ResponseEntity<Map<String,Object>> getFilteredSchema(String path, String operation, boolean includeInternalSchemas, String schemaType, String ifNoneMatch, String tenant, Locale locale)`

Referências de código
- `org.praxisplatform.uischema.hash.SchemaCanonicalizer`
- `org.praxisplatform.uischema.hash.SchemaHashUtil`
- `org.praxisplatform.uischema.http.IfNoneMatchUtils`
- `org.praxisplatform.uischema.id.SchemaIdBuilder`
- `org.praxisplatform.uischema.controller.docs.ApiDocsController`

Exemplos (E2E de teste)
- `GET /schemas/filtered?path=/e2e&operation=post&schemaType=request&includeInternalSchemas=true` → 200 com ETag; chamada condicional subsequente retorna 304
- `GET /schemas/filtered?path=/e2e/with-ref&schemaType=response&includeInternalSchemas=false|true` → ETags diferentes (expansão de `$ref` altera o hash)
