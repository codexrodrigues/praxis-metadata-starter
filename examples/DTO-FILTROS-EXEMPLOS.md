# DTO de Filtros — Exemplos de Todas as Operações

Este exemplo demonstra um único DTO contendo campos anotados com `@Filterable` cobrindo todas as operações suportadas (incluindo o Lote 1 Core).

- Guia completo: [Filtros e Paginação](../guides/FILTROS-E-PAGINACAO.md)
- Javadoc: [`org.praxisplatform.uischema.filter.annotation`](../apidocs/org/praxisplatform/uischema/filter/annotation/package-summary.html)

```java
package com.example.demo.filtros;

import org.praxisplatform.uischema.filter.annotation.Filterable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ExemploFilterDTO {

  // 1) Correlação direta em atributos simples (String)
  @Filterable(operation = Filterable.FilterOperation.EQUAL)
  private String nomeIgual;

  @Filterable(operation = Filterable.FilterOperation.NOT_EQUAL)
  private String nomeDiferente;

  @Filterable(operation = Filterable.FilterOperation.LIKE)
  private String nomeContem;

  @Filterable(operation = Filterable.FilterOperation.NOT_LIKE)
  private String nomeNaoContem;

  @Filterable(operation = Filterable.FilterOperation.STARTS_WITH)
  private String nomeComecaCom;

  @Filterable(operation = Filterable.FilterOperation.ENDS_WITH)
  private String nomeTerminaCom;

  // 2) Numéricos / Comparáveis
  @Filterable(operation = Filterable.FilterOperation.GREATER_THAN)
  private BigDecimal valorMaiorQue;

  @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL)
  private BigDecimal valorMaiorIgual;

  @Filterable(operation = Filterable.FilterOperation.LESS_THAN)
  private BigDecimal valorMenorQue;

  @Filterable(operation = Filterable.FilterOperation.LESS_OR_EQUAL)
  private BigDecimal valorMenorIgual;

  @Filterable(operation = Filterable.FilterOperation.BETWEEN)
  private List<BigDecimal> valorEntreInclusivo; // [min, max]

  @Filterable(operation = Filterable.FilterOperation.BETWEEN_EXCLUSIVE)
  private List<BigDecimal> valorEntreExclusivo; // (min, max)

  @Filterable(operation = Filterable.FilterOperation.NOT_BETWEEN)
  private List<BigDecimal> valorNaoEntre; // NOT BETWEEN [min, max]

  @Filterable(operation = Filterable.FilterOperation.OUTSIDE_RANGE)
  private List<BigDecimal> valorForaDoIntervalo; // < min OR > max

  // 3) Datas / Temporais (mapeadas a Instant internamente)
  @Filterable(operation = Filterable.FilterOperation.ON_DATE)
  private LocalDate criadoEm; // considera apenas a data

  @Filterable(operation = Filterable.FilterOperation.IN_LAST_DAYS)
  private Integer criadosNosUltimosDias; // N dias até agora

  @Filterable(operation = Filterable.FilterOperation.IN_NEXT_DAYS)
  private Integer vencemNosProximosDias; // de agora até N dias

  // 4) Coleções, IN/NOT_IN, Size
  @Filterable(operation = Filterable.FilterOperation.IN)
  private List<UUID> idsEmLista;

  @Filterable(operation = Filterable.FilterOperation.NOT_IN)
  private List<UUID> idsForaDaLista;

  // Tamanho de coleção (ex.: entidade possui atributo: Set<Tag> tags)
  // Usa relation para apontar a coleção na entidade: "tags".
  @Filterable(operation = Filterable.FilterOperation.SIZE_EQ, relation = "tags")
  private Integer tagsTamanhoIgual;

  @Filterable(operation = Filterable.FilterOperation.SIZE_GT, relation = "tags")
  private Integer tagsTamanhoMaiorQue;

  @Filterable(operation = Filterable.FilterOperation.SIZE_LT, relation = "tags")
  private Integer tagsTamanhoMenorQue;

  // 5) Booleanos e Null Checks
  @Filterable(operation = Filterable.FilterOperation.IS_TRUE)
  private Boolean ativoEhVerdadeiro; // quando presente, verifica isTrue

  @Filterable(operation = Filterable.FilterOperation.IS_FALSE)
  private Boolean ativoEhFalso; // quando presente, verifica isFalse

  // Para IS_NULL/IS_NOT_NULL, sugerimos modelar Boolean que ativa o predicado quando TRUE
  @Filterable(operation = Filterable.FilterOperation.IS_NULL)
  private Boolean descricaoIsNull; // TRUE → isNull(descricao)

  @Filterable(operation = Filterable.FilterOperation.IS_NOT_NULL)
  private Boolean descricaoIsNotNull; // TRUE → isNotNull(descricao)

  // 6) Relações com LIKE/ORDER: usar relation para navegar (ex.: tipo.nome)
  @Filterable(operation = Filterable.FilterOperation.LIKE, relation = "tipo.nome")
  private String tipoNomeContem;

  // getters/setters ...
}
```

Notas importantes:
- Para `BETWEEN*`/`OUTSIDE_RANGE`, use listas com exatamente 2 valores: `[min, max]`.
- `ON_DATE` usa `LocalDate` e aplica a janela [início do dia, início do próximo dia) em UTC.
- `IN_LAST_DAYS`/`IN_NEXT_DAYS` esperam `Integer` com número de dias relativos a “agora”.
- `SIZE_*` opera sobre atributos de coleção (use `relation` para apontar a coleção na entidade).
- `IS_TRUE/IS_FALSE` operam diretamente em propriedades booleanas. `IS_NULL/IS_NOT_NULL` são ativadas quando o valor no DTO é `Boolean.TRUE`.
