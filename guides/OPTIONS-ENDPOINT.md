# Options (id/label) com @OptionLabel, OptionMapper e option-sources

Como retornar opcoes id/label para selects com endpoints prontos, mapeamento configuravel e fontes derivadas governadas.

- Javadoc: [`@OptionLabel`](../apidocs/org/praxisplatform/uischema/annotation/OptionLabel.html)
- Javadoc: [`OptionMapper`](../apidocs/org/praxisplatform/uischema/mapper/base/OptionMapper.html)
- Javadoc: [`BaseCrudService#filterOptions`](../apidocs/org/praxisplatform/uischema/service/base/BaseCrudService.html)
- Javadoc: [`AbstractCrudController` (endpoints `/options` e `/option-sources`)](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)

## Definindo o label

Anote o campo ou getter que fornece o label com `@OptionLabel`.

```java
public class Funcionario {
  @Id private Long id;

  @OptionLabel
  private String nomeCompleto;
}
```

Se nao houver anotacao, o framework aplica heuristicas (`getLabel`, `getNomeCompleto`, `getNome`, etc.).

## Endpoints disponiveis

- `POST /{resource}/options/filter` - opcoes paginadas a partir do filtro padrao
- `GET /{resource}/options/by-ids?ids=1&ids=2` - opcoes por IDs informados, com ordem preservada
- `POST /{resource}/option-sources/{sourceKey}/options/filter` - opcoes paginadas para uma fonte derivada registrada
- `GET /{resource}/option-sources/{sourceKey}/options/by-ids?ids=a&ids=b` - reidratacao de opcoes para uma fonte derivada registrada

## Quando usar cada superficie

- `/{resource}/options/*`
  - use quando o proprio recurso e a fonte de opcoes
  - exemplos: funcionario, cargo, departamento, equipe
- `/{resource}/option-sources/{sourceKey}/options/*`
  - use quando a fonte e derivada e governada pelo recurso atual
  - exemplos: `payrollProfile`, `universo`, `composicaoFolha`, `faixaPctDesconto`

Importante:

- `option-sources/*` preserva o payload publico `OptionDTO`
- a semantica publica continua sendo de options para UX de filtro
- a implementacao interna pode reaproveitar stats/distinct values, mas isso nao vira contrato publico
- hoje a execucao real cobre `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`
- `LIGHT_LOOKUP`, `RESOURCE_ENTITY` e `STATIC_CANONICAL` continuam previstos, mas nao completos nesta rodada
- quando o campo do filtro nao tiver o mesmo nome da source registrada, publique `x-ui.optionSource.filterField`

Exemplo:

```http
POST /api/human-resources/funcionarios/options/filter?page=0&size=20
Content-Type: application/json

{ "nome": "ana" }
```

Resposta (exemplo):

```json
{
  "status": "success",
  "data": {
    "content": [ { "id": 1, "label": "Ana Silva" } ],
    "totalElements": 1,
    "size": 20,
    "number": 0
  },
  "timestamp": "2024-01-01T10:00:00"
}
```

Exemplo de fonte derivada:

```http
POST /api/human-resources/vw-analytics-folha-pagamento/option-sources/payrollProfile/options/filter?page=0&size=20&search=exec
Content-Type: application/json

{ "competenciaBetween": ["2026-01-01", "2026-03-31"] }
```

## Personalizando o mapeamento

Sobrescreva `getOptionMapper()` no service para incluir `extra` ou transformar labels.

```java
@Override
public OptionMapper<Funcionario, Long> getOptionMapper() {
  return entity -> new OptionDTO<>(extractId(entity), entity.getNomeCompleto(), entity.getDepartamento());
}
```

## Referencias

- [`@OptionLabel`](../apidocs/org/praxisplatform/uischema/annotation/OptionLabel.html)
- [`OptionMapper`](../apidocs/org/praxisplatform/uischema/mapper/base/OptionMapper.html)
- [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)
