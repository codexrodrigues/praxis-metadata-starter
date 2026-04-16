package org.praxisplatform.uischema;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum canônico dos controles publicados em {@code x-ui.controlType}.
 *
 * <p>
 * O {@code controlType} informa qual componente visual deve ser usado para capturar ou exibir um
 * campo. O conjunto publicado aqui define a superficie oficial suportada pelo runtime dinâmico da
 * plataforma e serve de contrato entre backend, documentação OpenAPI enriquecida e frontend.
 * </p>
 *
 * <p>
 * O valor {@link #AUTO} funciona como sentinela: quando presente, o resolver decide o controle
 * final com base em schema OpenAPI, formato e heurísticas. Valores diferentes de {@code AUTO}
 * representam escolha explícita do domínio.
 * </p>
 */
public enum FieldControlType {

    /**
     * Sentinela para "sem escolha explícita"; a heurística/resolvedor decide o controle final.
     */
    AUTO("auto"),
    /**
     * Colecao editavel de itens, normalmente publicada com {@code x-ui.array}.
     */
    ARRAY("array"),
    /**
     * Select remoto com busca/publicação explícita no contrato.
     */
    ASYNC_SELECT("async-select"),
    /**
     * Autocomplete Material para catálogos locais ou remotos.
     */
    AUTO_COMPLETE("autoComplete"),
    /**
     * Representação visual compacta de avatar.
     */
    AVATAR("avatar"),
    /**
     * Ação disparável dentro da superfície dinâmica.
     */
    BUTTON("button"),
    /**
     * Grupo segmentado de botões exposto com o controlType canônico do runtime Angular.
     */
    BUTTON_TOGGLE("buttonToggle"),
    /**
     * Checkbox simples ou grupo, conforme metadata adicional.
     */
    CHECKBOX("checkbox"),
    /**
     * Entrada/lista em chips editáveis.
     */
    CHIP_INPUT("chipInput"),
    /**
     * Lista de chips para seleção/exibição.
     */
    CHIP_LIST("chipList"),
    /**
     * Input simples de cor (`color`) com preview e picker nativo.
     */
    COLOR_INPUT("color"),
    /**
     * Picker de cor rico, com paleta/presets.
     */
    COLOR_PICKER("colorPicker"),
    /**
     * Builder corporativo para expressões CRON.
     */
    CRON_BUILDER("cronBuilder"),
    CURRENCY_INPUT("currency"),
    DATE_INPUT("dateInput"),
    DATE_PICKER("date"),
    DATE_RANGE("dateRange"),
    DATE_TIME_PICKER("dateTime"),
    DATE_TIME_RANGE("dateTimeRange"),
    DATETIME_LOCAL_INPUT("dateTimeLocal"),
    EMAIL_INPUT("email"),
    /**
     * Lookup corporativo para selecao de entidades de negocio governadas por optionSource.
     */
    ENTITY_LOOKUP("entityLookup"),
    FILE_UPLOAD("upload"),
    INPUT("input"),
    INLINE_SELECT("inlineSelect"),
    INLINE_SEARCHABLE_SELECT("inlineSearchableSelect"),
    INLINE_ASYNC_SELECT("inlineAsyncSelect"),
    INLINE_ENTITY_LOOKUP("inlineEntityLookup"),
    INLINE_AUTOCOMPLETE("inlineAutocomplete"),
    INLINE_INPUT("inlineInput"),
    INLINE_NUMBER("inlineNumber"),
    INLINE_CURRENCY("inlineCurrency"),
    INLINE_CURRENCY_RANGE("inlineCurrencyRange"),
    INLINE_MULTISELECT("inlineMultiSelect"),
    INLINE_TOGGLE("inlineToggle"),
    INLINE_RANGE("inlineRange"),
    INLINE_DATE("inlineDate"),
    INLINE_DATE_RANGE("inlineDateRange"),
    INLINE_TIME("inlineTime"),
    INLINE_TIME_RANGE("inlineTimeRange"),
    INLINE_TREE_SELECT("inlineTreeSelect"),
    INLINE_RATING("inlineRating"),
    INLINE_DISTANCE_RADIUS("inlineDistanceRadius"),
    INLINE_PIPELINE_STATUS("inlinePipelineStatus"),
    INLINE_SCORE_PRIORITY("inlineScorePriority"),
    INLINE_RELATIVE_PERIOD("inlineRelativePeriod"),
    INLINE_SENTIMENT("inlineSentiment"),
    INLINE_COLOR_LABEL("inlineColorLabel"),
    MONTH_INPUT("month"),
    MULTI_SELECT("multiSelect"),
    MULTI_SELECT_TREE("multiSelectTree"),
    /**
     * Lista de seleção explícita, orientada a opções visíveis em layout vertical.
     */
    SELECTION_LIST("selectionList"),
    TRANSFER_LIST("transferList"),
    NUMERIC_TEXT_BOX("numericTextBox"),
    PASSWORD("password"),
    PHONE("phone"),
    CPF_CNPJ_INPUT("cpfCnpjInput"),
    RADIO("radio"),
    RANGE_SLIDER("rangeSlider"),
    PRICE_RANGE("priceRange"),
    RATING("rating"),
    SEARCH_INPUT("search"),
    /**
     * Select com busca embutida/publicação explícita no contrato.
     */
    SEARCHABLE_SELECT("searchable-select"),
    SELECT("select"),
    SLIDER("slider"),
    TEXTAREA("textarea"),
    TIME_INPUT("time"),
    TIME_PICKER("timePicker"),
    TIME_RANGE("timeRange"),
    TOGGLE("toggle"),
    TREE_VIEW("treeView"),
    TREE_SELECT("treeSelect"),
    URL_INPUT("url"),
    WEEK_INPUT("week"),
    YEAR_INPUT("year");

    private final String value;

    FieldControlType(String value) {
        this.value = value;
    }

    /**
     * Gets the string value of the control type.
     * @return The string value.
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Converts a string value to the corresponding FieldControlType Enum.
     * The comparison is case-insensitive.
     *
     * @param value The string value to convert.
     * @return The matching FieldControlType.
     * @throws IllegalArgumentException if the value does not match any known control type.
     */
    public static FieldControlType fromValue(String value) {
        for (FieldControlType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown control type: " + value);
    }
}
