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

## Referencias publicas

- starter canonico: `praxis-metadata-starter`
- repositÃ³rio Git do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes npm relevantes para consumo: `@praxisui/core`, `@praxisui/dynamic-form`
