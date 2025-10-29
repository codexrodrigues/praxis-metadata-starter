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
         * Filtro de semelhança (e.g., campo LIKE '%valor%').
         */
        LIKE,
        /**
         * Filtro de maior que (e.g., campo > valor).
         */
        GREATER_THAN,
        /**
         * Filtro de menor que (e.g., campo &lt; valor).
         */
        LESS_THAN,
        /**
         * Filtro de valores dentro de uma lista (e.g., campo IN (valores)).
         */
        IN,
        /**
         * Filtro de valores entre dois limites (e.g., campo BETWEEN valor1 AND valor2).
         */
        BETWEEN
    }
}
