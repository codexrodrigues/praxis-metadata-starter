package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Define de forma declarativa a projecao padrao de colunas visiveis e fixadas de uma tabela para um DTO de resposta.
 *
 * <p>Propriedades do DTO fora de {@link #columns()} recebem {@code x-ui.tableHidden = true} automaticamente;
 * propriedades listadas recebem {@code x-ui.tableOrder} pelo indice declarado e podem ser fixadas no
 * inicio ({@code pinned = "start"}) ou no fim ({@code pinned = "end"}) da grade.</p>
 *
 * <pre>{@code
 * @TableView(
 *         columns = {"code", "name", "departmentName", "status", "monthlyAmount"},
 *         pinnedStart = {"code", "name"},
 *         pinnedEnd = {"status"}
 * )
 * public record EmployeeSummaryDTO(...) {}
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableView {

    /** Lista ordenada dos nomes de propriedades visiveis por padrao na tabela principal. */
    String[] columns();

    /** Subconjunto de {@link #columns()} fixado a esquerda / inicio da tabela. */
    String[] pinnedStart() default {};

    /** Subconjunto de {@link #columns()} fixado a direita / fim da tabela. */
    String[] pinnedEnd() default {};
}
