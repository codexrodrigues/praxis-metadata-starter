# Options (id/label) com @OptionLabel e OptionMapper

Como retornar opções id/label para selects com endpoints prontos e mapeamento configurável.

- Javadoc: [`@OptionLabel`](../apidocs/org/praxisplatform/uischema/annotation/OptionLabel.html)
- Javadoc: [`OptionMapper`](../apidocs/org/praxisplatform/uischema/mapper/base/OptionMapper.html)
- Javadoc: [`BaseCrudService#filterOptions`](../apidocs/org/praxisplatform/uischema/service/base/BaseCrudService.html)
- Javadoc: [`AbstractCrudController` (endpoints `/options`)](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)

## Definindo o label

Anote o campo ou getter que fornece o label com `@OptionLabel`.

```java
public class Funcionario {
  @Id private Long id;

  @OptionLabel
  private String nomeCompleto;
}
```

Se não houver anotação, o framework aplica heurísticas (`getLabel`, `getNomeCompleto`, `getNome`, etc.).

## Endpoints disponíveis

- `POST /{resource}/options/filter` — opções paginadas a partir do filtro padrão
- `GET  /{resource}/options/by-ids?ids=1&ids=2` — opções por IDs informados (ordem preservada)

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
    "content": [ {"id": 1, "label": "Ana Silva"} ],
    "totalElements": 1,
    "size": 20,
    "number": 0
  },
  "timestamp": "2024-01-01T10:00:00"
}
```

## Personalizando o mapeamento

Sobrescreva `getOptionMapper()` no service para incluir `extra` ou transformar labels.

```java
@Override
public OptionMapper<Funcionario, Long> getOptionMapper() {
  return entity -> new OptionDTO<>(extractId(entity), entity.getNomeCompleto(), entity.getDepartamento());
}
```

## Referências

- [`@OptionLabel`](../apidocs/org/praxisplatform/uischema/annotation/OptionLabel.html)
- [`OptionMapper`](../apidocs/org/praxisplatform/uischema/mapper/base/OptionMapper.html)
- [`AbstractCrudController`](../apidocs/org/praxisplatform/uischema/controller/base/AbstractCrudController.html)
