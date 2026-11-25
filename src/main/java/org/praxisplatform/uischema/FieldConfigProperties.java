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
    /** Preset canonico de apresentação aplicado ao campo */
    PRESENTATION_PRESET("presentationPreset"),
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
    /** Indica se o campo deve ser priorizado/exibido no corpo do Quick View */
    QUICK_VIEW_VISIBLE("quickViewVisible"),
    /** Ordem de exibição do campo dentro do Quick View */
    QUICK_VIEW_ORDER("quickViewOrder"),
    /** Referência semântica cruzada para outro recurso canônico da plataforma */
    RESOURCE_REFERENCE("resourceReference"),
    /**
     * Declara politica contextual de acesso ao campo.
     *
     * <p>Essa chave informa autoridades requeridas para leitura/edicao em runtime,
     * sem substituir a validacao de seguranca do host.</p>
     */
    FIELD_ACCESS("fieldAccess"),
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
    /** Ícone associado ao estado verdadeiro (booleano) */
    ICON_TRUE("iconTrue"),
    /** Ícone associado ao estado falso (booleano) */
    ICON_FALSE("iconFalse"),
    /** Tom semântico visual (success, neutral, warning, danger, info) */
    TONE("tone"),
    /** Tom semântico associado ao estado verdadeiro */
    TONE_TRUE("toneTrue"),
    /** Tom semântico associado ao estado falso */
    TONE_FALSE("toneFalse"),
    /** Rótulo exibido quando o valor for verdadeiro */
    LABEL_TRUE("labelTrue"),
    /** Rótulo exibido quando o valor for falso */
    LABEL_FALSE("labelFalse"),

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
    /** Catalogo ordenado de atalhos de periodo para controles dateRange e inlineDateRange */
    SHORTCUTS("shortcuts"),
    /** Configuracao de materializacao inline do catalogo de atalhos de periodo */
    INLINE_QUICK_PRESETS("inlineQuickPresets"),
    /** Configuracao do overlay inline, incluindo modo de aplicacao e acoes */
    INLINE_OVERLAY("inlineOverlay"),
    /** Contrato canônico de apresentação de valor para display/read-only */
    VALUE_PRESENTATION("valuePresentation"),
    /** Contrato canônico de apresentação visual para células/listas somente leitura */
    PRESENTATION("presentation"),
    /** Regras condicionais em Json Logic para apresentação visual em leitura/tabela */
    PRESENTATION_RULES("presentationRules"),

    // ----------------------------------------------------------
    // 10. Propriedades Específicas para Filtros
    // ----------------------------------------------------------
    /** Define a propriedade de filtro (pode indicar o tipo de operação ou similar) */
    FILTER("filter"),
    /** Define as opções disponíveis para filtros, quando aplicável */
    FILTER_OPTIONS("filterOptions"),
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
    NUMERIC_MAX_LENGTH("numericMaxLength"),

    // ----------------------------------------------------------
    // 12. Layout, Governanca de Superficie e Visualizadores
    // ----------------------------------------------------------
    /** Expressao Json Logic de somente leitura condicional ({@code x-ui.conditionalReadOnly}) */
    CONDITIONAL_READ_ONLY("conditionalReadOnly"),
    /** Largura em colunas (1..12) do campo no grid de formulario ({@code x-ui.colSpan}) */
    COL_SPAN("colSpan"),
    /** Ordem explicita da coluna na tabela declarada via @TableView ({@code x-ui.tableOrder}) */
    TABLE_ORDER("tableOrder"),
    /** Fixacao horizontal da coluna ("start" ou "end") declarada via @TableView ({@code x-ui.pinned}) */
    PINNED("pinned"),
    /** Indica campo de busca ampla primaria em filtros ({@code x-ui.primarySearch}) */
    PRIMARY_SEARCH("primarySearch"),
    /** Indica campo promovido como filtro inline na barra de filtros ({@code x-ui.inlineFilter}) */
    INLINE_FILTER("inlineFilter"),
    /** Vinculo declarativo com dimensao de contexto ativo da sessao ({@code x-ui.contextBinding}) */
    CONTEXT_BINDING("contextBinding"),
    /** Marca campos limpos automaticamente em rascunho de duplicacao ({@code x-ui.draftCleared}) */
    DRAFT_CLEARED("draftCleared"),
    /** Metadados de ciclo de vida publicados por @ResourceState ({@code x-ui.resourceState}) */
    RESOURCE_STATE("resourceState"),
    /** Organizacao em secoes de formulario publicada em nivel de schema ({@code x-ui.formLayout}) */
    FORM_LAYOUT("formLayout"),
    /** Metadados de progressao por niveis publicados em nivel de schema ({@code x-ui.progression}) */
    PROGRESSION("progression"),
    /** Metadados de linha do tempo publicados em nivel de schema ({@code x-ui.timeline}) */
    TIMELINE("timeline"),
    /** Metadados de agenda/escala publicados em nivel de schema ({@code x-ui.schedule}) */
    SCHEDULE("schedule"),

    // ----------------------------------------------------------
    // 13. Faixas Monetárias e Sliders Inline (range / currency / time / distance / rating / score)
    // ----------------------------------------------------------
    /** Controla a exibição das âncoras clicáveis click-to-snap abaixo do slider inline ({@code x-ui.showAnchors}) */
    SHOW_ANCHORS("showAnchors"),
    /** Histograma/distribuição exibido sobre a trilha do slider inline ({@code x-ui.inlineDistribution}) */
    INLINE_DISTRIBUTION("inlineDistribution"),
    /** Título exibido no cabeçalho do popover inline ({@code x-ui.inlinePanelTitle}) */
    INLINE_PANEL_TITLE("inlinePanelTitle"),
    /** Ligadura Material Icons exibida ao lado do título do painel inline ({@code x-ui.inlinePanelIcon}) */
    INLINE_PANEL_ICON("inlinePanelIcon"),
    /** Cor CSS ou de tema aplicada ao ícone do cabeçalho do painel inline ({@code x-ui.inlinePanelIconColor}) */
    INLINE_PANEL_ICON_COLOR("inlinePanelIconColor"),
    /** Controla a exibição de campos monetários editáveis dentro do popover inline ({@code x-ui.showInlineInputs}) */
    SHOW_INLINE_INPUTS("showInlineInputs"),
    /** Incremento específico aplicado à trilha do slider preservando o step dos inputs ({@code x-ui.sliderStep}) */
    SLIDER_STEP("sliderStep"),
    /** Incremento amplo usado com Shift+Setas e PageUp/PageDown no range ({@code x-ui.shiftStep}) */
    SHIFT_STEP("shiftStep"),
    /** Distância mínima exigida entre os limites inferior e superior da faixa ({@code x-ui.minDistance}) */
    MIN_DISTANCE("minDistance"),
    /** Distância máxima permitida entre os limites inferior e superior da faixa ({@code x-ui.maxDistance}) */
    MAX_DISTANCE("maxDistance"),
    /** Exige preenchimento simultâneo de mínimo e máximo na faixa monetária ({@code x-ui.requireTwoBounds}) */
    REQUIRE_TWO_BOUNDS("requireTwoBounds"),
    /** Operadores habilitados no seletor inline de faixa monetária ({@code x-ui.operatorModes}) */
    OPERATOR_MODES("operatorModes"),
    /** Operador inicial selecionado quando o campo de faixa monetária ainda não tem valor ({@code x-ui.defaultOperatorMode}) */
    DEFAULT_OPERATOR_MODE("defaultOperatorMode"),
    /** Marcos visuais declarativos exibidos sobre a trilha do slider inline ({@code x-ui.marks}) */
    MARKS("marks"),
    /** Bandas semânticas de referência exibidas ao longo da trilha do slider inline ({@code x-ui.semanticBands}) */
    SEMANTIC_BANDS("semanticBands"),
    /** Rótulo exibido no campo numérico de limite mínimo do popover inline ({@code x-ui.inputMinLabel}) */
    INPUT_MIN_LABEL("inputMinLabel"),
    /** Rótulo exibido no campo numérico de limite máximo do popover inline ({@code x-ui.inputMaxLabel}) */
    INPUT_MAX_LABEL("inputMaxLabel"),
    /** Rótulo exibido no campo numérico quando o operador ativo é valor exato ({@code x-ui.inputExactLabel}) */
    INPUT_EXACT_LABEL("inputExactLabel");

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
