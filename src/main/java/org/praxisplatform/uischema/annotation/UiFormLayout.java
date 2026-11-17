package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a organizacao estrutural de secoes e grupos de um DTO de formulario ou visualizacao,
 * publicada em {@code x-ui.formLayout}.
 *
 * <pre>{@code
 * @UiFormLayout(
 *         mode = LayoutMode.TABS,
 *         sections = {
 *                 @UiSection(id = "general", title = "Dados Gerais", icon = "badge", order = 1, groups = {"identificacao"}),
 *                 @UiSection(id = "contract", title = "Contrato", icon = "work", order = 2, groups = {"contratacao"})
 *         }
 * )
 * public class EmployeeFormDTO {}
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UiFormLayout {

    /** Estrategia visual de apresentacao das secoes. */
    LayoutMode mode() default LayoutMode.FLAT;

    /** Secoes ordenadas que compoem o layout do formulario. */
    UiSection[] sections() default {};
}
