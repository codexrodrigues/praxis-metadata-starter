# Ordenação Padrão com @DefaultSortColumn

Como definir ordenação padrão automática nas listagens quando nenhum `sort` é informado.

- Javadoc: [`@DefaultSortColumn`](../apidocs/org/praxisplatform/uischema/service/base/annotation/DefaultSortColumn.html)
- Javadoc: [`BaseCrudService#getDefaultSort`](../apidocs/org/praxisplatform/uischema/service/base/BaseCrudService.html)

## Como funciona

1) Anote os campos da entidade com `@DefaultSortColumn`

```java
@Entity
public class Funcionario {
  @Id private Long id;

  @DefaultSortColumn(priority = 1, ascending = true)
  private String departamento;

  @DefaultSortColumn(priority = 2, ascending = true)
  private String nomeCompleto;
}
```

2) A ordenação é aplicada automaticamente quando `sort` não é enviado

- GET `/api/human-resources/funcionarios/all` → `ORDER BY departamento ASC, nomeCompleto ASC`
- POST `/api/human-resources/funcionarios/filter` → aplica a mesma ordem padrão

3) Quando `sort` é enviado, ele tem precedência

- GET `/api/human-resources/funcionarios/all?sort=salario,desc` → usa `salario DESC`

## Recomendações

- Use prioridades únicas para ordem determinística
- Prefira campos indexados para melhor performance
- Evite ordenar por relacionamentos profundos

## Referências

- [`@DefaultSortColumn`](../apidocs/org/praxisplatform/uischema/service/base/annotation/DefaultSortColumn.html)
- [`BaseCrudService`](../apidocs/org/praxisplatform/uischema/service/base/BaseCrudService.html)
