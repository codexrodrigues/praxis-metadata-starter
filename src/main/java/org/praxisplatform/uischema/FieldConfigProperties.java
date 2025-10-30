package org.praxisplatform.uischema;

/**
 * Define as chaves canônicas {@code x-ui} utilizadas para descrever campos em
 * formulários, tabelas e filtros.
 *
 * <p>
 * O {@link org.praxisplatform.uischema.extension.CustomOpenApiResolver}
 * utiliza esse enum para serializar metadados gerados a partir de
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema} e das
 * validações Bean Validation. As propriedades estão agrupadas por contexto para
 * facilitar a consulta e seguem o vocabulário documentado em
 * {@code docs/architecture-overview.md}.
 * </p>
 *
 * <p>Grupos principais:</p>
 * <ol>
 *   <li><strong>Identificação e Rótulo</strong> – chaves básicas de nome, label e descrição</li>
 *   <li><strong>Tipo e Componente</strong> – descrevem o tipo de dado e o controle visual</li>
 *   <li><strong>Layout e Estilo</strong> – controlam ordem, largura e agrupamento</li>
 *   <li><strong>Comportamento e Validação</strong> – habilitam regras dinâmicas e restrições</li>
 *   <li><strong>Visibilidade</strong> – permitem ocultar campos em contextos específicos</li>
 *   <li><strong>Dependências e Ações</strong> – definem reações a outros campos</li>
 *   <li><strong>Ícones e Representação Visual</strong> – customizam ícones associados</li>
 *   <li><strong>Opções e Mapeamento</strong> – conectam campos a endpoints e listas</li>
 *   <li><strong>Filtros</strong> – ajustes específicos para telas de busca</li>
 * </ol>
 *
 * @since 1.0.0
 */
public enum FieldConfigProperties {

    // ----------------------------------------------------------
    // 1. Identificação e Rótulo
    // ----------------------------------------------------------
    /** Nome do campo (identificador interno) */
    NAME("name"),
    /** Rótulo que será exibido na UI */
    LABEL("label"),
    /** Descrição do campo */
    DESCRIPTION("description"),

    // ----------------------------------------------------------
    // 2. Tipo e Componente
    // ----------------------------------------------------------
    /** Tipo de dado do campo, padrão é "text". Valor padrão: {@link FieldDataType#TEXT} */
    TYPE("type"),
    /** Define o controle/componente que será utilizado pelo front-end.
     * Os possíveis valores estão em {@code FieldControlType} */
    CONTROL_TYPE("controlType"),
    /** Texto exibido como placeholder no campo */
    PLACEHOLDER("placeholder"),
    /** Valor padrão do campo */
    DEFAULT_VALUE("defaultValue"),

    // ----------------------------------------------------------
    // 3. Layout e Estilo
    // ----------------------------------------------------------
    /** Agrupamento ou seção do campo */
    GROUP("group"),
    /** Ordem de exibição do campo */
    ORDER("order"),
    /** Largura do campo */
    WIDTH("width"),
    /** Indica se o campo utiliza layout flex */
    IS_FLEX("isFlex"),
    /** Define a orientação de exibição do campo */
    DISPLAY_ORIENTATION("displayOrientation"),

    // ----------------------------------------------------------
    // 4. Comportamento e Validação
    // ----------------------------------------------------------
    /** Define se o campo está desabilitado */
    DISABLED("disabled"),
    /** Define se o campo é somente leitura */
    READ_ONLY("readOnly"),
    /** Permite seleção múltipla (por exemplo, em selects) */
    MULTIPLE("multiple"),
    /** Define se o campo é editável */
    EDITABLE("editable"),
    /** Modo de validação a ser aplicado */
    VALIDATION_MODE("validationMode"),
    /** Define se o valor do campo deve ser único */
    UNIQUE("unique"),
    /** Máscara para formatação do campo */
    MASK("mask"),
    /** Define se o campo pode ser ordenado em uma grid */
    SORTABLE("sortable"),
    /** Define se o campo é condicionalmente obrigatório */
    CONDITIONAL_REQUIRED("conditionalRequired"),
    /** Define o estilo para visualização somente (quando aplicável) */
    VIEW_ONLY_STYLE("viewOnlyStyle"),
    /** Define os gatilhos que disparam a validação */
    VALIDATION_TRIGGERS("validationTriggers"),

    // ----------------------------------------------------------
    // 5. Visibilidade (por contexto)
    // ----------------------------------------------------------
    /**
     * Indica se o campo deve ser oculto de forma global.
     * Essa propriedade pode ser usada como padrão e/ou sobrescrita pelos
     * contextos específicos abaixo.
     */
    HIDDEN("hidden"),
    /** Especifica se o campo deve ser oculto na exibição da tabela (grid) */
    TABLE_HIDDEN("tableHidden"),
    /** Especifica se o campo deve ser oculto no formulário */
    FORM_HIDDEN("formHidden"),
    /**
     * Indica que o campo é filtrável.
     * A presença dessa propriedade (ou o uso da anotação {@code @Filterable})
     * define que o campo deve ser considerado na geração de filtros dinâmicos.
     * Não interfere na exibição do campo nas tabelas ou formulários.
     * Apenas indica que o campo pode ser utilizado como critério de filtro no component de filtro exibido acima da Table.
     */
    FILTERABLE("filterable"),

    // ----------------------------------------------------------
    // 6. Dependências e Ações Dinâmicas
    // ----------------------------------------------------------
    /** Define se a exibição do campo depende do valor de outro campo */
    CONDITIONAL_DISPLAY("conditionalDisplay"),
    /** Campo que o atual depende (para comportamento dinâmico) */
    DEPENDENT_FIELD("dependentField"),
    /** Define se o valor deve ser resetado quando o campo dependente mudar */
    RESET_ON_DEPENDENT_CHANGE("resetOnDependentChange"),

    // ----------------------------------------------------------
    // 7. Outras Propriedades de Configuração
    // ----------------------------------------------------------
    /** Indica se o campo pode ser editado inline */
    INLINE_EDITING("inlineEditing"),
    /** Define se o campo é transformado através de uma função customizada */
    TRANSFORM_VALUE_FUNCTION("transformValueFunction"),
    /** Tempo de debounce para validações ou ações */
    DEBOUNCE_TIME("debounceTime"),
    /** Texto de ajuda que pode ser exibido para o usuário */
    HELP_TEXT("helpText"),
    /** Dica ou hint exibido próximo ao campo */
    HINT("hint"),
    /** Define a condição que determina se o campo deve ser ocultado */
    HIDDEN_CONDITION("hiddenCondition"),
    /**
     * Define o tooltip que será exibido quando o usuário passar o mouse sobre o campo.
     * Essa propriedade pode auxiliar na compreensão do propósito ou funcionamento do campo.
     */
    TOOLTIP_ON_HOVER("tooltipOnHover"),

    // ----------------------------------------------------------
    // 8. Ícones e Representação Visual
    // ----------------------------------------------------------
    /** Define o ícone associado ao campo */
    ICON("icon"),
    /** Posição do ícone em relação ao campo */
    ICON_POSITION("iconPosition"),
    /** Tamanho do ícone */
    ICON_SIZE("iconSize"),
    /** Cor do ícone */
    ICON_COLOR("iconColor"),
    /** Classe CSS para o ícone */
    ICON_CLASS("iconClass"),
    /** Estilo inline para o ícone */
    ICON_STYLE("iconStyle"),
    /** Tamanho da fonte para o ícone */
    ICON_FONT_SIZE("iconFontSize"),

    // ----------------------------------------------------------
    // 9. Opções e Mapeamento
    // ----------------------------------------------------------
    /** Campo que representa o valor (para selects, por exemplo) */
    VALUE_FIELD("valueField"),
    /** Campo que representa o rótulo ou descrição a ser exibido */
    DISPLAY_FIELD("displayField"),
    /** Endpoint para obtenção de dados ou opções dinâmicas */
    ENDPOINT("endpoint"),
    /** Texto para opção vazia (quando nenhuma opção é selecionada) */
    EMPTY_OPTION_TEXT("emptyOptionText"),
    /** Lista de opções disponíveis para o campo, usado em campos de listas como combobox */
    OPTIONS("options"),

    // ----------------------------------------------------------
    // 10. Propriedades Específicas para Filtros
    // ----------------------------------------------------------
    /** Define a propriedade de filtro (pode indicar o tipo de operação ou similar) */
    FILTER("filter"),
    /** Define as opções disponíveis para filtros, quando aplicável */
    FILTER_OPTIONS("filterOptions"),
    /**
     * Define o tipo de controle a ser utilizado no contexto de filtros.
     * Por exemplo, pode ser configurado como "multiColumnComboBox" para utilizar o componente
     * Kendo UI for Angular MultiColumnComboBox, mesmo que o controle padrão (CONTROL_TYPE)
     * seja "select" ou "combobox" para formulários de inclusão/alteração.
     */
    FILTER_CONTROL_TYPE("filterControlType"),

    // ----------------------------------------------------------
    // 11. Propriedades Específicas para Input Numérico
    // ----------------------------------------------------------
    /** Define o tipo de formatação de um input numérico */
    NUMERIC_FORMAT("numericFormat"),
    /** Define a quantidade de incremento ou decremento por step de um input numérico */
    NUMERIC_STEP("numericStep"),
    /** Define o valor mínimo para um input numérico */
    NUMERIC_MIN("numericMin"),
    /** Define o valor máximo para um input numérico */
    NUMERIC_MAX("numericMax"),
    /** Define a quantidade máxima de caracteres de um input numérico */
    NUMERIC_MAX_LENGTH("numericMaxLength");

    // --- Store the string value ---
    private final String value;

    FieldConfigProperties(String value) {
        this.value = value;
    }

    // --- Getter for the string value ---
    public String getValue() {
        return value;
    }

    // --- Method to get Enum from string value ---
    public static FieldConfigProperties fromValue(String value) {
        for (FieldConfigProperties prop : values()) {
            if (prop.value.equalsIgnoreCase(value)) {
                return prop;
            }
        }
        // Consider how to handle unknown values: throw exception or return null
        // For consistency with FieldDataType, let's throw an exception.
        throw new IllegalArgumentException("Unknown FieldConfigProperties value: " + value);
    }
}
