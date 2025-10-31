package org.praxisplatform.uischema.filter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação para definir campos de DTOs que podem ser utilizados como critérios de filtro em consultas JPA dinâmicas.
 *
 * <p>Esta anotação é utilizada para marcar os campos do DTO que devem ser considerados ao construir
 * especificações genéricas para consultas. Permite configurar a operação de filtro e especificar relacionamentos
 * complexos, quando necessário.</p>
 *
 * <h2>Configuração</h2>
 * <ul>
 *     <li><b>operation</b>: Especifica o tipo de operação de filtro a ser aplicado no campo, como
 *         igualdade, maior que, menor que, entre outros. Consulte {@link FilterOperation} para as opções disponíveis.</li>
 *     <li><b>relation</b>: Define o caminho de relacionamento da entidade, se o campo não pertence diretamente
 *         à entidade raiz. Use o formato "relacao1.relacao2.campo" para navegar por relacionamentos aninhados.</li>
 * </ul>
 *
 * <h2>Exemplo de Uso</h2>
 * <p>Considere um DTO que representa os filtros aplicáveis a uma consulta de pessoa física:</p>
 *
 * <pre>{@code
 * public class PessoaFisicaFilterDTO {
 *
 *     // Filtro de igualdade em um campo direto da entidade
 *     @Filterable(operation = FilterOperation.EQUAL)
 *     private UUID tipoSexoUuid;
 *
 *     // Filtro de LIKE em um campo relacionado (e.g., "tipoSexo.nome")
 *     @Filterable(operation = FilterOperation.LIKE, relation = "tipoSexo.nome")
 *     private String tipoSexoDescricao;
 *
 *     // Filtro de maior que em um campo de data
 *     @Filterable(operation = FilterOperation.GREATER_THAN)
 *     private LocalDate dataNascimento;
 *
 *     @Schema(description = "Filtro por intervalo de datas de verificação",
 *             example = "[\"2019-01-01\", \"2022-12-31\"]")
 *     @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "dataVerificacao")
 *     private List<LocalDate> dataVerificacaoFiltro;
 * }
 * }</pre>
 * <p>
 *  * <h2>Validação de Campos de Filtro</h2>
 *  * <p>Certifique-se de que os campos de filtro, como {@code dataVerificacao}, sejam usados apenas para consultas e, se necessário,
 *  * configurados para serem ignorados nos mapeamentos com a entidade principal.</p>
 *
 * <h2>Notas para Relacionamentos</h2>
 * <p>Quando o filtro envolve campos relacionados:</p>
 * <ul>
 *     <li>Use o atributo <b>relation</b> para especificar o caminho até o campo da entidade relacionada.</li>
 *     <li>O caminho deve estar no formato <code>"relacao1.relacao2.campo"</code>, onde cada parte do caminho representa
 *         uma associação no modelo JPA.</li>
 *     <li>Exemplo: Para filtrar pelo campo <code>nome</code> de uma entidade <code>tipoSexo</code> relacionada à entidade principal:
 *         <pre>{@code
 *         @Filterable(operation = FilterOperation.LIKE, relation = "tipoSexo.nome")
 *         private String tipoSexoDescricao;
 *         }
 *         }</pre>
 *     </li>
 * </ul>
 *
 * <h2>Resumo executivo (venda técnica)</h2>
 * <ul>
 *   <li><strong>26 operações embutidas</strong> — de LIKE/IN/BETWEEN a datas relativas (últimos/próximos N dias),
 *   tamanho de coleções e nulidade/booleanos.</li>
 *   <li><strong>Relações legíveis</strong> — {@code relation="a.b.campo"} para navegar por joins sem dor.</li>
 *   <li><strong>Zero Specification manual</strong> — o builder converte DTOs anotados em Criteria com segurança.</li>
 * </ul>
 *
 * @since 1.0.0
 * @see FilterOperation
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Filterable {

    /**
     * Tipo de operação de filtragem a ser aplicada ao campo.
     *
     * @return A operação de filtragem.
     */
    FilterOperation operation() default FilterOperation.EQUAL;

    /**
     * Caminho do relacionamento na entidade, no formato "relacao1.relacao2.campo".
     * <p>Deixe em branco se o campo pertence diretamente à entidade raiz.</p>
     *
     * @return O caminho do relacionamento.
     */
    String relation() default "";

    /**
     * Enumeração com as operações de filtro disponíveis.
     */
    enum FilterOperation {
        /**
         * Filtro de igualdade (e.g., campo = valor).
         */
        EQUAL,
        /**
         * Filtro de diferença (e.g., {@code campo <> valor}).
         */
        NOT_EQUAL,
        /**
         * Filtro de semelhança (e.g., campo LIKE '%valor%').
         */
        LIKE,
        /**
         * Filtro de não semelhança (e.g., NOT LIKE), case-insensitive.
         */
        NOT_LIKE,
        /**
         * Filtro por prefixo (e.g., LIKE 'valor%'), case-insensitive.
         */
        STARTS_WITH,
        /**
         * Filtro por sufixo (e.g., LIKE '%valor'), case-insensitive.
         */
        ENDS_WITH,
        /**
         * Filtro de maior que (e.g., {@code campo > valor}).
         */
        GREATER_THAN,
        /**
         * Filtro de maior ou igual (e.g., {@code campo >= valor}).
         */
        GREATER_OR_EQUAL,
        /**
         * Filtro de menor que (e.g., {@code campo < valor}).
         */
        LESS_THAN,
        /**
         * Filtro de menor ou igual (e.g., {@code campo <= valor}).
         */
        LESS_OR_EQUAL,
        /**
         * Filtro de valores dentro de uma lista (e.g., campo IN (valores)).
         */
        IN,
        /**
         * Filtro de valores fora de uma lista (e.g., campo NOT IN (valores)).
         */
        NOT_IN,
        /**
         * Filtro de valores entre dois limites (e.g., campo BETWEEN valor1 AND valor2).
         */
        BETWEEN,
        /**
         * Filtro que verifica se o campo é NULL.
         * Espera valor {@code Boolean.TRUE} para ativar.
         */
        IS_NULL,
        /**
         * Filtro que verifica se o campo não é NULL.
         * Espera valor {@code Boolean.TRUE} para ativar.
         */
        IS_NOT_NULL,
        /**
         * Filtro estritamente entre (exclusivo): {@code campo > a AND campo < b}.
         */
        BETWEEN_EXCLUSIVE,
        /**
         * Filtro de negação do between (inclusive): NOT (campo BETWEEN a AND b).
         */
        NOT_BETWEEN,
        /**
         * Fora do intervalo (exclusivo): {@code campo < min OR campo > max}.
         */
        OUTSIDE_RANGE,
        /**
         * Igual a data (apenas a parte de data é comparada). Valor esperado: LocalDate.
         */
        ON_DATE,
        /**
         * Nos últimos N dias: Valor esperado: Integer (dias). Usa horário atual como referência.
         */
        IN_LAST_DAYS,
        /**
         * Nos próximos N dias: Valor esperado: Integer (dias). Usa horário atual como referência.
         */
        IN_NEXT_DAYS,
        /**
         * Tamanho de coleção igual a N. Valor esperado: Integer.
         */
        SIZE_EQ,
        /**
         * Tamanho de coleção maior que N. Valor esperado: Integer.
         */
        SIZE_GT,
        /**
         * Tamanho de coleção menor que N. Valor esperado: Integer.
         */
        SIZE_LT,
        /**
         * Campo booleano verdadeiro.
         */
        IS_TRUE,
        /**
         * Campo booleano falso.
         */
        IS_FALSE
    }
}
