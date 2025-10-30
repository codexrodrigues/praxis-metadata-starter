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
- Prefira operações específicas (EQUAL, LIKE, BETWEEN) para resultados previsíveis
- Limite o `size` conforme sua política de performance

---

## Por que este filtro é diferente (zero boilerplate, pronto pra produção)

- Expressivo e seguro: você declara no DTO e o Starter gera a Specification JPA correta.
- Cobertura completa (26 operações): de LIKE/IN/BETWEEN a datas relativas (últimos/próximos N dias) e tamanho de coleções.
- Relações sem dor: use `relation="a.b.campo"` para navegar por joins de forma legível.
- Ordem previsível: integrado à ordenação padrão e ao `PageableBuilder` para requests consistentes.

### Dicas de UI para filtros com enums grandes

- `array` de `enum` com muitas opções: o resolver sugere `filterControlType = multiColumnComboBox`.
- Enums pequenos (≤5) tendem a usar `radio`/`chipInput`; médios `select`; grandes `autoComplete`/`multiSelect`.

## Operações de Filtro Suportadas

| Operação           | Descrição                                | Exemplo DTO                               |
|--------------------|-------------------------------------------|-------------------------------------------|
| EQUAL              | Igualdade                                 | `@Filterable(EQUAL)`                      |
| NOT_EQUAL          | Diferente                                 | `@Filterable(NOT_EQUAL)`                  |
| LIKE               | Contém (ci)                               | `@Filterable(LIKE)`                       |
| NOT_LIKE           | Não contém (ci)                           | `@Filterable(NOT_LIKE)`                   |
| STARTS_WITH        | Começa com (ci)                           | `@Filterable(STARTS_WITH)`                |
| ENDS_WITH          | Termina com (ci)                          | `@Filterable(ENDS_WITH)`                  |
| GREATER_THAN       | Maior que                                 | `@Filterable(GREATER_THAN)`               |
| GREATER_OR_EQUAL   | Maior ou igual                            | `@Filterable(GREATER_OR_EQUAL)`           |
| LESS_THAN          | Menor que                                 | `@Filterable(LESS_THAN)`                  |
| LESS_OR_EQUAL      | Menor ou igual                            | `@Filterable(LESS_OR_EQUAL)`              |
| IN                 | Pertence a uma lista                      | `@Filterable(IN)`                         |
| NOT_IN             | Não pertence a uma lista                  | `@Filterable(NOT_IN)`                     |
| BETWEEN            | Entre (2 valores)                         | `@Filterable(BETWEEN)`                    |
| IS_NULL            | É nulo (usar Boolean TRUE no DTO)         | `@Filterable(IS_NULL)` + `Boolean campo`  |
| IS_NOT_NULL        | Não é nulo (usar Boolean TRUE no DTO)     | `@Filterable(IS_NOT_NULL)` + `Boolean`    |

### Lote 2 — Intervalos/Data/Lista/Coleção/Booleanos

### Lote 1 (Core) — Operações Adicionadas

| Operação            | Descrição                                        | Exemplo DTO                                 |
|---------------------|---------------------------------------------------|---------------------------------------------|
| BETWEEN_EXCLUSIVE   | Entre exclusivo: `> a AND < b`                    | `@Filterable(BETWEEN_EXCLUSIVE)`            |
| NOT_BETWEEN         | Negação do between (inclusive)                    | `@Filterable(NOT_BETWEEN)`                  |
| OUTSIDE_RANGE       | Fora do intervalo: `< min OR > max`               | `@Filterable(OUTSIDE_RANGE)`                |
| ON_DATE             | Igual à data (parte de data)                      | `@Filterable(ON_DATE)` + `LocalDate`        |
| IN_LAST_DAYS        | Nos últimos N dias                                | `@Filterable(IN_LAST_DAYS)` + `Integer dias`|
| IN_NEXT_DAYS        | Nos próximos N dias                               | `@Filterable(IN_NEXT_DAYS)` + `Integer dias`|
| SIZE_EQ             | Tamanho de coleção igual a N                      | `@Filterable(SIZE_EQ)` + `Integer`          |
| SIZE_GT             | Tamanho de coleção maior que N                    | `@Filterable(SIZE_GT)` + `Integer`          |
| SIZE_LT             | Tamanho de coleção menor que N                    | `@Filterable(SIZE_LT)` + `Integer`          |
| IS_TRUE             | Campo booleano verdadeiro                         | `@Filterable(IS_TRUE)`                      |
| IS_FALSE            | Campo booleano falso                              | `@Filterable(IS_FALSE)`                     |

Notas:
- Para `ON_DATE`, use `LocalDate` no DTO. A comparação considera o intervalo [início do dia, início do dia seguinte).
- Para `IN_LAST_DAYS/IN_NEXT_DAYS`, o valor é relativo ao horário atual (UTC) e converte para `Instant`.
- Para `SIZE_*`, aplique apenas em atributos de coleção (OneToMany/ManyToMany); o builder usa `CriteriaBuilder.size`.
- Requisito de coleção em `SIZE_*`: defina `relation` para apontar explicitamente para o atributo de coleção na entidade. Se o caminho não for coleção, o builder lança erro informativo.
- Dica de UI `filterControlType = multiColumnComboBox` para enums grandes é uma sugestão de componente; alinhe o valor com o catálogo do frontend.

### Notas de Timezone

- `ON_DATE`, `IN_LAST_DAYS` e `IN_NEXT_DAYS` utilizam normalização com `Instant` e horário atual do backend. Em ambientes multi‑região, verifique a zona padrão da aplicação e do banco. Recomenda‑se padronizar em UTC.

Notas:
- Operações com `ci` (case-insensitive) normalizam usando `lower()`.
- Para IS_NULL/IS_NOT_NULL, sugere-se modelar no DTO como `Boolean campoIsNull`; quando `true`, o predicado é aplicado.
- Para IS_TRUE/IS_FALSE, o predicado é aplicado quando o campo do DTO está presente (não nulo). Recomenda‑se enviar `true` para indicar que o predicado deve ser considerado.

## Exemplos práticos (DTO + chamadas)

### DTO de filtro (vendas)

```java
import org.praxisplatform.uischema.filter.annotation.Filterable;
import java.time.LocalDate;
import java.util.List;

public class VendaFilterDTO implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
  @Filterable(operation = Filterable.FilterOperation.LIKE)
  private String cliente;

  @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL)
  private java.math.BigDecimal valorMin;

  @Filterable(operation = Filterable.FilterOperation.LESS_OR_EQUAL)
  private java.math.BigDecimal valorMax;

  @Filterable(operation = Filterable.FilterOperation.BETWEEN)
  private List<LocalDate> emissaoEntre; // [de, ate]

  @Filterable(operation = Filterable.FilterOperation.IN)
  private List<String> canais; // ["ONLINE","LOJA"]

  @Filterable(operation = Filterable.FilterOperation.LIKE, relation = "vendedor.nome")
  private String vendedorNome;

  @Filterable(operation = Filterable.FilterOperation.IS_TRUE)
  private Boolean pago;
}
```

```http
POST /api/vendas/filter?page=0&size=20
Content-Type: application/json

{
  "cliente": "maria",
  "valorMin": 100.00,
  "valorMax": 1000.00,
  "emissaoEntre": ["2024-01-01", "2024-12-31"],
  "canais": ["ONLINE", "LOJA"],
  "vendedorNome": "silva",
  "pago": true
}
```

---

## Vantagem competitiva (por números)

- 26 operações prontas de filtro — sem escrever Specifications na mão
- Até 13 endpoints por recurso — CRUD, filtros, paginação por cursor, options id/label e schemas
- Redução de ~97% no payload da documentação por grupo OpenAPI — com cache inteligente

> Resultado: menos boilerplate, tempo de entrega menor e APIs/UX mais consistentes — alinhadas com as melhores práticas do ecossistema Spring + OpenAPI.

## Referências

- [`@Filterable`](../apidocs/org/praxisplatform/uischema/filter/annotation/Filterable.html)
- [`GenericSpecificationsBuilder`](../apidocs/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.html)
- [`PageableBuilder`](../apidocs/org/praxisplatform/uischema/util/PageableBuilder.html)
- [Ordenação Padrão](ORDEM-PADRAO.md)
