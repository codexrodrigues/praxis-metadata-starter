package org.praxisplatform.uischema.extension.annotation;

import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.*;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/**
 * Anotação usada para definir metadata para renderização de UI e geração de formulários.
 *
 * <h3>Referenciando endpoints de Options em DTOs</h3>
 * <p>Para campos que exibem opções dinâmicas (combos, autocomplete, multi‑select),
 * use {@link #endpoint()}, {@link #valueField()} e {@link #displayField()} para
 * conectar a um endpoint REST e mapear o par id/label.</p>
 *
 * <h4>Padrões suportados</h4>
 * <ol>
 *   <li><strong>OptionDTO (payload leve)</strong>
 *   <pre>{@code
 * @UISchema(
 *   controlType = FieldControlType.SELECT,
 *   endpoint = ApiPaths.Catalog.CATEGORIAS + "/options/filter",
 *   valueField = "id",     // org.praxisplatform.uischema.dto.OptionDTO.id
 *   displayField = "label" // org.praxisplatform.uischema.dto.OptionDTO.label
 * )
 * private Long categoriaId;
 * }</pre>
 *   <p>O label é resolvido no backend via {@code @OptionLabel} ou heurísticas do
 *   {@code BaseCrudService#computeOptionLabel}.</p>
 *   </li>
 *   <li><strong>DTO completo</strong> (recomendado quando a UI precisa de colunas extras no dropdown)
 *   <pre>{@code
 * @UISchema(
 *   controlType = FieldControlType.SELECT,
 *   endpoint = ApiPaths.Catalog.CATEGORIAS + "/filter", // ✅ sempre /filter
 *   valueField = "id",            // campo do DTO usado como value
 *   displayField = "nome"         // campo do DTO usado como label
 * )
 * private Long categoriaId;
 * }</pre>
 *   </li>
 * </ol>
 *
 * <h4>Combos dependentes (interpolação)</h4>
 * <p>O {@code endpoint} suporta interpolação de parâmetros usando o valor atual
 * de outros campos (ex.: {@code ${estado}}) para combos em cascata:</p>
 * <pre>{@code
 * @UISchema(
 *   name = "cidade",
 *   controlType = FieldControlType.SELECT,
 *   endpoint = "/api/cidades?estado=${estado}",
 *   dependentField = "estado",
 *   resetOnDependentChange = true
 * )
 * private String cidade;
 * }
 * </pre>
 *
 * <h4>Reidratação inicial</h4>
 * <p>Para preencher selects com valores previamente salvos, consulte também
 * <code>GET {base}/options/by-ids?ids=...</code> que retorna a lista
 * <code>OptionDTO</code> na mesma ordem dos IDs.</p>
 */
@Target({FIELD, METHOD, PARAMETER, TYPE, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UISchema {
    // Propriedades básicas do Schema
    String description() default "";
    String example() default "";

    // 1. Identificação e Rótulo
    String name() default "";
    String label() default "";

    // 2. Tipo e Componente
    FieldDataType type() default FieldDataType.TEXT;

    /**
     * Tipo de controle de UI desejado.
     *
     * Padrão: {@link FieldControlType#AUTO} (sentinela, indica que não houve escolha explícita pelo desenvolvedor).
     *
     * - Quando {@code AUTO}: o {@code CustomOpenApiResolver} aplicará detecção automática (type/format/enum) e
     *   heurísticas por nome do campo para determinar o controlType efetivo.
     * - Quando diferente de {@code AUTO} (inclusive {@code INPUT}): é tratado como valor explícito e terá precedência
     *   sobre a detecção/heurística.
     */
    FieldControlType controlType() default FieldControlType.AUTO;
    String placeholder() default "";
    String defaultValue() default "";

    // 3. Layout e Estilo
    String group() default "";
    int order() default 0;
    String width() default "";
    boolean isFlex() default false;
    String displayOrientation() default "";

    // 4. Comportamento e Validação
    boolean disabled() default false;
    boolean readOnly() default false;
    boolean multiple() default false;
    boolean editable() default true;
    String validationMode() default "";
    boolean unique() default false;
    String mask() default "";
    boolean sortable() default true;
    String conditionalRequired() default "";
    String viewOnlyStyle() default "";
    String validationTriggers() default "";

    // 5. Visibilidade
    boolean hidden() default false;
    boolean tableHidden() default false;
    boolean formHidden() default false;
    boolean filterable() default false;

    // 6. Dependências e Ações Dinâmicas
    String conditionalDisplay() default "";
    String dependentField() default "";
    boolean resetOnDependentChange() default false;

    // 7. Outras Propriedades de Configuração
    boolean inlineEditing() default false;
    String transformValueFunction() default "";
    int debounceTime() default 0;
    String helpText() default "";
    String hint() default "";
    String hiddenCondition() default "";
    String tooltipOnHover() default "";

    // 8. Ícones e Representação Visual
    String icon() default "";
    IconPosition iconPosition() default IconPosition.LEFT;
    String iconSize() default "";
    String iconColor() default "";
    String iconClass() default "";
    String iconStyle() default "";
    String iconFontSize() default "";

    // 9. Opções e Mapeamento
    String valueField() default "";
    String displayField() default "";
    String endpoint() default "";
    String emptyOptionText() default "";
    String options() default "";

    // 10. Propriedades Específicas para Filtros
    String filter() default "";
    String filterOptions() default "";
    String filterControlType() default "";

    // 11. Propriedades Específicas para Input Numérico
    NumericFormat numericFormat() default NumericFormat.INTEGER;
    String numericStep() default "";
    String numericMin() default "";
    String numericMax() default "";
    String numericMaxLength() default "";

    // 12. Propriedades de Validação (ValidationProperties)
    boolean required() default false;
    int minLength() default 0;
    int maxLength() default 0;
    String min() default "";
    String max() default "";
    ValidationPattern pattern() default ValidationPattern.CUSTOM;
    String requiredMessage() default "";
    String minLengthMessage() default "";
    String maxLengthMessage() default "";
    String patternMessage() default "";
    String rangeMessage() default "";
    String customValidator() default "";
    String asyncValidator() default "";
    int minWords() default 0;
    AllowedFileTypes allowedFileTypes() default AllowedFileTypes.ALL;
    String maxFileSize() default "";

    ExtensionProperty[] extraProperties() default {};

}
