# FilterOptions → array (Spec Compliance)

Contexto
- Hoje `@UISchema(filterOptions="...")` é serializado como `string` em `x-ui.filterOptions`.
- A spec de campo exige `filterOptions: array` (docs/spec/x-ui-field.schema.json:189).
- Decisão atual: manter `string` por compatibilidade e documentar. Este follow‑up detalha como migrar para `array` com coerção defensiva.

Objetivo
- Serializar `x-ui.filterOptions` sempre como `array` com comportamento previsível, mantendo a informação original quando não for possível parsear como estrutura.

Escopo
- Resolver `@UISchema.filterOptions` → `ArrayNode` no `x-ui`.
- Não alterar outras chaves nem o contrato do endpoint `/schemas/filtered`.

Referências de Código
- Resolver (origem do put): `src/main/java/org/praxisplatform/uischema/extension/CustomOpenApiResolver.java:980`
- Ponto atual do put como string: `src/main/java/org/praxisplatform/uischema/extension/CustomOpenApiResolver.java:1000`
- Utilitários de options (modelo a seguir): `src/main/java/org/praxisplatform/uischema/util/OpenApiUiUtils.java:280`
- Enum de chave: `src/main/java/org/praxisplatform/uischema/FieldConfigProperties.java:187`
- Spec: `docs/spec/x-ui-field.schema.json:189`

Proposta Técnica
- Implementar um utilitário novo em `OpenApiUiUtils` (similar a `populateUiOptionsFromString`):
  - Assinatura: `populateUiFilterOptionsFromString(Map<String,Object> xUiMap, String raw, ObjectMapper mapper)`
  - Regras de coerção (em ordem):
    1. Se `raw` vazio ou `xUiMap` já contém `filterOptions` → return.
    2. Tentar `mapper.readTree(raw)`:
       - Se array → usar diretamente em `x-ui.filterOptions`.
       - Se objeto → envolver em array com 1 elemento.
    3. Se falhar, tentar tratar `raw` como string JSON duplamente escapada (mesma estratégia de `options`).
    4. Se contiver vírgula → `CSV`: split e criar array de strings.
    5. Fallback final → array com a string literal única (`[ raw ]`).
- No `CustomOpenApiResolver`, trocar o `put` direto por chamada ao utilitário novo.

Aceite / Testes
- Atualizar teste existente para refletir array:
  - Arquivo: `src/test/java/org/praxisplatform/uischema/extension/ExplicitAdvancedPropsTest.java:1`
  - Validar que `x-ui.filterOptions` é um array e, quando `filterOptions` for JSON objeto, resulte em `[ { ... } ]`.
- Adicionar testes novos (sugestão: `FilterOptionsCoercionTest`):
  - JSON array válido → mantém array de objetos.
  - JSON objeto válido → array de um objeto.
  - CSV simples ("a,b,c") → array de strings `["a","b","c"]`.
  - String inválida ("foo") → `["foo"]`.
- Rodar: `./mvnw -DskipITs -Dtest=org.praxisplatform.uischema.extension.* test`.

Compatibilidade
- Mudança é forward‑compatible com a spec e resiliente:
  - Consumidores que esperavam string passarão a receber um array. Em casos de objeto JSON, o conteúdo passa a ser mais estruturado conforme esperado pela spec.
  - Em casos não parseáveis, a informação original é preservada como `string` dentro do array.

Risco / Mitigações
- UI que assume `string` pode precisar de ajuste; mitigado pelo fallback consistente e pela validação na spec.
- Parsing de JSON duplamente escapado já está padronizado nos utilitários; replicar abordagem reduz regressões.

Rollout Sugerido
1) Implementar utilitário + troca no resolver.
2) Atualizar/expandir testes conforme acima.
3) Remover a observação temporária em `docs/spec/CONFORMANCE.md` sobre exceção do tipo de `filterOptions`.
4) Comunicar hosts/consumidores: `filterOptions` passa a ser `array` conforme a spec.

Observação
- Não há alteração em `@UISchema` (continua `String filterOptions()`), apenas coerção na serialização do `x-ui`.

