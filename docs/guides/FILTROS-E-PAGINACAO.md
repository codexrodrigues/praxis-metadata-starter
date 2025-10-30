# Filtros e Paginação

Como implementar filtros e paginação usando `@Filterable`, `GenericFilterDTO` e `GenericSpecificationsBuilder`.

- Javadoc: [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- Javadoc: [`GenericSpecificationsBuilder`](../apidocs/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.html)
- Javadoc: [`GenericSpecification`](../apidocs/org/praxisplatform/uischema/filter/specification/GenericSpecification.html)
- Javadoc: [`PageableBuilder`](../apidocs/org/praxisplatform/uischema/util/PageableBuilder.html)

## Estrutura básica

1) Defina um DTO de filtro que implemente `GenericFilterDTO`

```java
public class FuncionarioFilterDTO implements GenericFilterDTO {
  @Filterable(operation = Filterable.FilterOperation.LIKE)
  private String nome;

  @Filterable(operation = Filterable.FilterOperation.EQUAL)
  private String departamento;

  // getters/setters
}
```

2) O service base resolve as Specifications automaticamente

```java
public class FuncionarioService extends AbstractBaseCrudService<Funcionario, FuncionarioDTO, Long, FuncionarioFilterDTO> {
  public FuncionarioService(BaseCrudRepository<Funcionario, Long> repo, Class<Funcionario> entityClass) {
    super(repo, entityClass);
  }
}
```

3) Utilize os endpoints de filtragem do controller base

```http
POST /api/human-resources/funcionarios/filter?page=0&size=20
Content-Type: application/json

{ "nome": "ana", "departamento": "RH" }
```

## Ordenação com filtros

- Se o parâmetro `sort` não for informado, aplica-se a ordenação padrão definida pela entidade (`@DefaultSortColumn`).
- Para ordenar explicitamente: `?sort=nome,asc&sort=departamento,desc`.

## Boas práticas

- Mantenha os tipos do DTO de filtro coerentes com os campos da entidade/DTO
- Prefira operações específicas (CONTAINS, EQUAL) para resultados previsíveis
- Limite o `size` conforme sua política de performance

### Dicas de UI para filtros com enums grandes

- `array` de `enum` com muitas opções: o resolver sugere `filterControlType = multiColumnComboBox`.
- Enums pequenos (≤5) tendem a usar `radio`/`chipInput`; médios `select`; grandes `autoComplete`/`multiSelect`.

## Referências

- [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- [`GenericSpecificationsBuilder`](../apidocs/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.html)
- [`PageableBuilder`](../apidocs/org/praxisplatform/uischema/util/PageableBuilder.html)
- [Ordenação Padrão](ORDEM-PADRAO.md)
