# Options (id/label) e option-sources

Guia canônico para publicar selects metadata-driven no `praxis-metadata-starter`.

Este documento cobre duas superfícies públicas diferentes:

- `/{resource}/options/*` para quando o próprio recurso é a fonte de opções
- `/{resource}/option-sources/{sourceKey}/options/*` para quando a fonte é derivada e registrada no service

Referências:

- Javadoc: [`@OptionLabel`](../apidocs/org/praxisplatform/uischema/annotation/OptionLabel.html)
- Javadoc: [`OptionMapper`](../apidocs/org/praxisplatform/uischema/mapper/base/OptionMapper.html)
- Javadoc: [`BaseCrudService#filterOptions`](../apidocs/org/praxisplatform/uischema/service/base/BaseCrudService.html)
- Javadoc: [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)

## Contrato público

Endpoints canônicos:

- `POST /{resource}/options/filter`
- `GET /{resource}/options/by-ids?ids=1&ids=2`
- `POST /{resource}/option-sources/{sourceKey}/options/filter`
- `GET /{resource}/option-sources/{sourceKey}/options/by-ids?ids=a&ids=b`

Forma pública de retorno:

- `options/filter` retorna `Page<OptionDTO<...>>`
- `options/by-ids` retorna `List<OptionDTO<...>>`
- `option-sources/*` preserva exatamente a mesma forma pública de `OptionDTO{id,label,extra?}`

O starter não publica um contrato público diferente só porque a implementação interna reutiliza stats, distinct values ou projeções derivadas.

## Quando usar cada superfície

- `/{resource}/options/*`
  - use quando existe um recurso próprio para a seleção
  - exemplos: `funcionarios`, `cargos`, `departamentos`
- `/{resource}/option-sources/{sourceKey}/options/*`
  - use quando a opção é derivada do próprio recurso atual e não possui CRUD próprio
  - exemplos reais no quickstart: `universo`, `payrollProfile`, `composicaoFolha`, `faixaPctDesconto`

Regra prática:

- se existe um catálogo próprio e estável, prefira `/{resource}/options/*`
- se a opção nasce de uma dimensão derivada, bucket categórico ou lookup governado pelo recurso base, prefira `option-sources`

## Definindo o label de options do próprio recurso

Anote o campo ou getter que fornece o label com `@OptionLabel`.

```java
public class Funcionario {
  @Id
  private Long id;

  @OptionLabel
  private String nomeCompleto;
}
```

Se não houver anotação, o framework aplica heurísticas como `getLabel`, `getNomeCompleto` e `getNome`.

## Registrando option-sources no service

`option-sources` não nasce de anotação direta no DTO. O contrato canônico vem do `OptionSourceRegistry` exposto pelo service.

Exemplo alinhado ao quickstart:

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

Sem `OptionSourceRegistry`, o controller até publica as rotas se a implementação existir, mas o enriquecimento metadata-driven em `/schemas/filtered` não terá a semântica canônica da fonte derivada.

## O que vai para o schema em `/schemas/filtered`

Quando o `ApiDocsController` encontra um campo cujo nome coincide com a source registrada para o `resourcePath`, ele enriquece o schema com `x-ui.optionSource`.

Exemplo publicado:

```json
{
  "type": "string",
  "x-ui": {
    "controlType": "async-select",
    "optionSource": {
      "key": "payrollProfile",
      "type": "DISTINCT_DIMENSION",
      "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
      "filterField": "payrollProfile",
      "dependsOn": ["universo"],
      "excludeSelfField": true,
      "searchMode": "contains",
      "pageSize": 25,
      "includeIds": true,
      "cachePolicy": "request-scope"
    }
  }
}
```

Importante:

- `x-ui.optionSource.resourcePath` é o path base do recurso, não o endpoint completo de `options/filter`
- o runtime Angular compõe `.../option-sources/{key}/options/filter` e `.../by-ids` a partir desse `resourcePath`
- quando `optionSource` existe, ele é mais canônico do que um `endpoint` legado hardcoded

## Como o Angular consome isso

O `praxis-ui-angular` já tem consumo explícito para `option-sources`.

Fluxo real:

- o normalizador lê `x-ui.optionSource`
- `field-definition-mapper` normaliza `optionSource.resourcePath`
- `material-async-select` e `simple-base-select` passam a chamar:
  - `filterOptionSourceOptions(sourceKey, ...)`
  - `getOptionSourceOptionsByIds(sourceKey, ids)`

Consequência prática:

- não publique `resourcePath` já apontando para `/option-sources/.../options/filter`
- publique o `resourcePath` base do recurso e deixe o runtime montar a rota derivada

## Respostas esperadas

Exemplo de `POST /api/human-resources/funcionarios/options/filter?page=0&size=20`:

```json
{
  "content": [
    { "id": 1, "label": "Ana Silva" }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

Exemplo de fonte derivada:

```http
POST /api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/filter?page=0&size=20&search=exec
Content-Type: application/json

{
  "competenciaBetween": ["2026-01-01", "2026-03-31"],
  "universo": "Marvel"
}
```

Exemplo de reidratação:

```http
GET /api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/by-ids?ids=OPER&ids=EXEC
```

## Suporte atual e limites

Cobertura confirmada hoje:

- o starter expõe os endpoints em `AbstractCrudController` e `AbstractReadOnlyController`
- o `ApiDocsController` publica `resource.capabilities.optionSources` e enriquece `x-ui.optionSource`
- o runtime Angular já consome `optionSource`
- o quickstart já registra e usa `OptionSourceRegistry`

Execução atualmente implementada no starter:

- `DISTINCT_DIMENSION`
- `CATEGORICAL_BUCKET`

Tipos ainda não implementados de ponta a ponta no executor JPA:

- `RESOURCE_ENTITY`
- `LIGHT_LOOKUP`
- `STATIC_CANONICAL`

Observação importante:

- a execução concreta atual depende do executor JPA do starter
- se o host não oferecer implementação compatível, chamadas de `option-sources` podem retornar `501 Not Implemented`

## Boas práticas

- use `option-sources` para dimensões derivadas, não para substituir catálogos CRUD normais
- mantenha `filterField` explícito quando o nome do campo no DTO não coincidir com a source registrada
- use `dependsOn` para declarar dependências de cascata que o frontend precisa respeitar
- use `excludeSelfField=true` quando a própria source não deve se autofiltrar
- para selects remotos de `OptionDTO`, mantenha `optionLabelKey=label` e `optionValueKey=id`

## Referências operacionais

- starter canônico: `praxis-metadata-starter`
- host de referência: `praxis-api-quickstart`
- consumidor final: `praxis-ui-angular`
