# Options (id/label) e option-sources

Guia canonico para publicar selects metadata-driven no
`praxis-metadata-starter`.

## Contrato publico

Endpoints canonicos:

- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids?ids=1&ids=2`
- `POST /{resource}/option-sources/{sourceKey}/options/filter`
- `GET /{resource}/option-sources/{sourceKey}/options/by-ids?ids=a&ids=b`

Forma publica de retorno:

- `options/filter` retorna `Page<OptionDTO<...>>`
- `options/by-ids` retorna `List<OptionDTO<...>>`
- `option-sources/*` preserva a mesma forma publica de `OptionDTO{id,label,extra?}`

## Quando usar cada superficie

- `/{resource}/options/*`
  - use quando existe um recurso proprio para a selecao
  - exemplos: `funcionarios`, `cargos`, `departamentos`
- `/{resource}/option-sources/{sourceKey}/options/*`
  - use quando a fonte e derivada do proprio recurso atual e nao possui CRUD proprio
  - exemplos tipicos: buckets categóricos, distinct values governados, dimensoes derivadas

Regra pratica:

- se existe um catalogo proprio e estavel, prefira `/{resource}/options/*`
- se a opcao nasce de dimensao derivada, prefira `option-sources`

## Definindo o label de options do proprio recurso

```java
public class Funcionario {
  @Id
  private Long id;

  @OptionLabel
  private String nomeCompleto;
}
```

Se nao houver anotacao, o framework aplica heuristicas como `getLabel`,
`getNomeCompleto` e `getNome`.

## Registrando option-sources no service

`option-sources` vem do `OptionSourceRegistry` exposto pelo service.

No baseline atual do starter, esse registry do proprio recurso e a fonte canonica da feature. Os controllers base, o enrich de `/schemas/filtered` e os endpoints `/{resource}/option-sources/{sourceKey}/options/*` usam a auto-configuracao padrao do starter; nao e necessario registrar `OptionSourceQueryExecutor` ou `OptionSourceEligibility` manualmente no host comum.

```java
private static final OptionSourceRegistry OPTION_SOURCES = OptionSourceRegistry.builder()
    .add(VwAnalyticsFolhaPagamento.class, new OptionSourceDescriptor(
        "payrollProfile",
        OptionSourceType.DISTINCT_DIMENSION,
        ApiPaths.HumanResources.VW_ANALYTICS_FOLHA_PAGAMENTO,
        "payrollProfile",
        "payrollProfile",
        "payrollProfile",
        "payrollProfile",
        List.of("competenciaBetween", "universo"),
        new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
    ))
    .build();

@Override
public OptionSourceRegistry getOptionSourceRegistry() {
    return OPTION_SOURCES;
}
```

## O que vai para o schema em `/schemas/filtered`

Quando o `ApiDocsController` encontra um campo cujo nome coincide com a source
registrada para o `resourcePath`, ele enriquece o schema com `x-ui.optionSource`.

Importante:

- `x-ui.optionSource.resourcePath` e o path base do recurso
- o runtime Angular compoe `.../option-sources/{key}/options/filter`
- quando `optionSource` existe, ele e mais canonico do que um `endpoint` legado

## Como o Angular consome isso

O `praxis-ui-angular` ja tem consumo explicito para `option-sources`.

Fluxo real:

- o normalizador le `x-ui.optionSource`
- o mapper normaliza `optionSource.resourcePath`
- o select remoto passa a chamar:
  - `filterOptionSourceOptions(sourceKey, ...)`
  - `getOptionSourceOptionsByIds(sourceKey, ids)`

## Suporte atual e limites

Cobertura confirmada hoje:

- o starter expoe os endpoints em controllers base
- o `ApiDocsController` publica `resource.capabilities.optionSources`
- o runtime Angular ja consome `optionSource`

Execucao atualmente implementada no starter:

- `DISTINCT_DIMENSION`
- `CATEGORICAL_BUCKET`

Tipos ainda nao implementados de ponta a ponta no executor JPA:

- `RESOURCE_ENTITY`
- `LIGHT_LOOKUP`
- `STATIC_CANONICAL`

## Boas praticas

- use `option-sources` para dimensoes derivadas
- mantenha `filterField` explicito quando necessario
- use `dependsOn` para cascata
- use `excludeSelfField=true` quando a source nao deve se autofiltrar
- para `OptionDTO`, mantenha `optionLabelKey=label` e `optionValueKey=id`

## Troubleshooting e Debugging

### Problemas Comuns e Soluções

- **Endpoint Retorna 404**: Verifique se o controller herda de `AbstractResourceController` ou `AbstractReadOnlyResourceController` e se o `OptionSourceRegistry` esta registrado no service.
- **Opções Não Aparecem no Frontend**: Confirme que o campo no `@UISchema` tem nome idêntico à `key` do descriptor. Use o browser dev tools para inspecionar `/schemas/filtered` e ver se `x-ui.optionSource` está presente.
- **Busca Não Funciona**: Certifique-se de que `allowSearch: true` na `OptionSourcePolicy` e que o `searchMode` (ex.: "contains") é suportado pelo executor.
- **Paginação Quebrada**: Verifique `defaultPageSize` e `maxPageSize` na policy; o frontend deve enviar `page` e `size` no request.
- **Dependências em Cascata Não Filtram**: Garanta que `dependsOn` lista campos corretos e que o filtro aplicado inclui esses valores. Teste manualmente com `POST /option-sources/{key}/options/filter` incluindo filtros dependentes.
- **Performance Lenta**: Habilite `cacheable: true` se apropriado, ou otimize queries no service (ex.: use índices no banco para distinct values).
- **Erro de Validação**: Leia logs do servidor; descriptors inválidos (ex.: `key` nula) lançam `IllegalArgumentException` na criação.

### Como Testar Manualmente

Use ferramentas como Postman ou curl para testar endpoints:

- **Filtro de Opções**:
  ```bash
  curl -X POST "http://localhost:8080/api/resource/option-sources/universo/options/filter" \
    -H "Content-Type: application/json" \
    -d '{"search": "Empresa", "page": 0, "size": 10}'
  ```
  Esperado: `Page<OptionDTO>` com `id`, `label`.

- **Reidratação por IDs**:
  ```bash
  curl "http://localhost:8080/api/resource/option-sources/universo/options/by-ids?ids=1&ids=2"
  ```
  Esperado: `List<OptionDTO>` para IDs específicos.

- **Verificar Schema**:
  ```bash
  curl "http://localhost:8080/schemas/filtered"
  ```
  Procure por `x-ui.optionSource` nos campos.

### Logs Úteis

- Ative logs em `org.praxisplatform.uischema` para ver resolução de descriptors.
- No frontend, use console logs do Angular para erros de normalização.

## Exemplos Avançados

### Integrando com StatsFieldRegistry

Para `DISTINCT_DIMENSION`, o executor pode usar `StatsFieldRegistry` como backend:

```java
// No service, registre no StatsFieldRegistry primeiro
statsFieldRegistry.register(VwAnalyticsFolhaPagamento.class, "universo");

// Depois, no OptionSourceRegistry
OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
    "universo",
    OptionSourceType.DISTINCT_DIMENSION,
    // ... outros campos
);
```

Isso permite reaproveitar lógica de stats para options.

### Customizando OptionDTO com Extra

Para adicionar metadados (ex.: count de ocorrências):

```java
// No service, sobrescreva filterOptionSourceOptions
@Override
public Page<OptionDTO> filterOptionSourceOptions(String sourceKey, GenericFilterDTO filter, Pageable pageable) {
    // Lógica customizada
    return options.map(option -> option.withExtra(Map.of("count", 42)));
}
```

### Migrando de Endpoint Legado

Campo antigo:
```json
{
  "campo": {
    "endpoint": "/options/filter",
    "valueField": "id",
    "displayField": "label"
  }
}
```

Novo com OptionSource:
```json
{
  "campo": {
    "x-ui.optionSource": {
      "key": "campo",
      "type": "RESOURCE_ENTITY",
      "resourcePath": "/api/campo"
    }
  }
}
```

Durante transição, publique ambos para compatibilidade.
