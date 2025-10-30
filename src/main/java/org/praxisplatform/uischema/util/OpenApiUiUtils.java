package org.praxisplatform.uischema.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.ValidationProperties;

import java.util.List;
import java.util.Map;

/**
 * <h2> Utilitários Centrais para Transformação OpenAPI → UI Metadata</h2>
 *
 * <p>Classe utilitária que concentra toda a lógica de transformação entre
 * esquemas OpenAPI e metadados de UI (extensões x-ui) no framework Praxis.</p>
 *
 * <h3> Principais Responsabilidades:</h3>
 * <ul>
 *   <li><strong>Detecção Automática:</strong> Determina tipos de controle baseados em
schemas</li>
 *   <li><strong>População Consistente:</strong> Métodos padronizados para metadados x-ui</li>
 *   <li><strong>Formatação:</strong> Transformação de dados para UI (labels, tamanhos,
etc.)</li>
 *   <li><strong>Validação:</strong> Geração automática de mensagens de erro</li>
 * </ul>
 *
 * <h3> Fluxo de Uso na Arquitetura:</h3>
 * <pre>
 *   Annotation → CustomOpenApiResolver → OpenApiUiUtils → x-ui Extensions
 * </pre>
 */
public class OpenApiUiUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiUiUtils.class);

    private OpenApiUiUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * <h3>Formata um nome de campo técnico (camelCase ou snake_case) em um label legível para humanos.</h3>
     *
     * <p>Este método é crucial para a experiência do usuário, pois converte nomes de variáveis
     * como "userName" ou "user_name" em "User Name", que é muito mais apresentável em
     * formulários e tabelas da UI.</p>
     *
     * <h4>Lógica de Transformação:</h4>
     * <ol>
     *   <li>Substitui underscores (_) por espaços.</li>
     *   <li>Insere um espaço antes de cada letra maiúscula (exceto a primeira) para separar palavras em camelCase.</li>
     *   <li>Converte a primeira letra de cada palavra para maiúscula.</li>
     * </ol>
     *
     * @param fieldName O nome do campo a ser formatado (ex: "firstName", "last_name", "userAddress").
     * @return Uma string formatada como label (ex: "First Name", "Last Name", "User Address"). Retorna uma string vazia se a entrada for nula ou vazia.
     *
     * <h4>Exemplos de Uso:</h4>
     * <pre>{@code
     * formatFieldNameAsLabel("nomeCompletoDoUsuario") // Retorna "Nome Completo Do Usuario"
     * formatFieldNameAsLabel("user_email_address")    // Retorna "User Email Address"
     * formatFieldNameAsLabel("id")                    // Retorna "Id"
     * }</pre>
     *
     * <h4>Considerações Técnicas:</h4>
     * <p>O método é projetado para ser robusto e lidar com diferentes convenções de nomenclatura de forma consistente.
     * É um dos métodos mais utilizados em toda a aplicação para garantir a padronização de labels.</p>
     */
    public static String formatFieldNameAsLabel(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "";
        }
        String label = fieldName.replace('_', ' ');
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isWhitespace(label.charAt(i - 1))) {
                result.append(' ');
            }
            if (i == 0 || Character.isWhitespace(label.charAt(Math.max(0, i - 1)))) {
                result.append(Character.toUpperCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Determina um tipo de controle de UI básico com base nas propriedades do schema OpenAPI.
     *
     * <p>Este método serve como a primeira camada de detecção, mapeando diretamente os tipos e formatos
     * do OpenAPI para os tipos de controle de UI mais comuns. A lógica aqui não considera o nome do campo,
     * focando apenas na estrutura do schema.</p>
     *
     * @param openApiType O tipo do schema OpenAPI (ex: "string", "number", "boolean").
     * @param openApiFormat O formato do schema (ex: "date", "email", "password").
     * @param hasEnum Verdadeiro se a propriedade tiver uma lista de valores `enum`.
     * @param maxLength O `maxLength` para campos do tipo string, usado para decidir entre `INPUT` e `TEXTAREA`.
     * @param isArrayType Verdadeiro se o tipo do schema for "array".
     * @param isArrayItemsHaveEnum Verdadeiro se os itens de um array tiverem uma lista `enum`.
     * @return O valor string de um {@link FieldControlType} adequado, ou `null` se nenhum tipo específico for determinado.
     */
    public static String determineBasicControlType(
            String openApiType,
            String openApiFormat,
            boolean hasEnum,
            Integer maxLength,
            boolean isArrayType,
            boolean isArrayItemsHaveEnum) {

        if (openApiType == null) {
            return null;
        }

        switch (openApiType) {
            case "string":
                if (hasEnum) {
                    return FieldControlType.SELECT.getValue();
                }
                if (openApiFormat != null) {
                    switch (openApiFormat) {
                        case "date":
                            return FieldControlType.DATE_PICKER.getValue();
                        case "date-time":
                            return FieldControlType.DATE_TIME_PICKER.getValue();
                        case "time":
                            return FieldControlType.TIME_PICKER.getValue();
                        case "email":
                            return FieldControlType.EMAIL_INPUT.getValue(); // Was FieldDataType.EMAIL, but maps to EMAIL_INPUT
                        case "password":
                            return FieldControlType.PASSWORD.getValue();
                        case "binary": // Typically for file uploads
                        case "byte":
                            return FieldControlType.FILE_UPLOAD.getValue();
                        case "uri":
                        case "url":
                        case "uri-reference": // Added from ApiDocsController
                            return FieldControlType.URL_INPUT.getValue();
                        case "color":
                            return FieldControlType.COLOR_PICKER.getValue();
                        case "phone":
                            return FieldControlType.PHONE.getValue();
                        // case "json": // ApiDocsController maps this to FieldDataType.JSON, not a specific control type here.
                        // Let specific handlers decide for json.
                        default:
                            // Fall through to general string handling
                            break;
                    }
                }
                // General string handling (after specific formats)
                // Heurística de tamanho: considerar textarea apenas para textos realmente longos
                if (maxLength != null && maxLength > 300) {
                    return FieldControlType.TEXTAREA.getValue();
                }
                return FieldControlType.INPUT.getValue(); // Default for string

            case "number":
            case "integer":
                if (hasEnum) { // enum numérico raramente é usado; manter SELECT por compatibilidade
                    return FieldControlType.SELECT.getValue();
                }
                // Specific formats for numbers can override default
                if (openApiFormat != null) {
                    if ("currency".equals(openApiFormat)) { // From CustomOpenApiResolver
                        return FieldControlType.CURRENCY_INPUT.getValue();
                    }
                    // "percent" format from CustomOpenApiResolver leads to NUMERIC_TEXT_BOX, which is the default.
                }
                return FieldControlType.NUMERIC_TEXT_BOX.getValue(); // Default for number/integer

            case "boolean":
                // Evitar SELECT como padrão para boolean
                return FieldControlType.CHECKBOX.getValue();

            case "array":
                if (isArrayItemsHaveEnum) {
                    return FieldControlType.MULTI_SELECT.getValue();
                }
                // Default for array if items don't have enum or specific handling
                return FieldControlType.ARRAY_INPUT.getValue(); // From CustomOpenApiResolver

            case "object":
                // Objects might be represented in various ways, EXPANSION_PANEL is one option.
                // Or could be handled by custom components based on schema.
                return FieldControlType.EXPANSION_PANEL.getValue(); // From CustomOpenApiResolver

            default:
                return null; // Or a very generic default if appropriate
        }
    }

    // -----------------------------------------------------------------------------------------
    // Família de Métodos `populateUi*`
    //
    // A seguir, uma série de métodos utilitários com a responsabilidade de popular o mapa
    // `x-ui` com propriedades específicas. Cada método segue um padrão defensivo: ele
    // apenas adiciona a propriedade se ela ainda não existir no mapa, garantindo que
    // configurações explícitas (ex: de anotações) tenham precedência.
    // -----------------------------------------------------------------------------------------

    public static void populateUiGroup(Map<String, Object> xUiMap, String group) {
        if (group != null && !group.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.GROUP.getValue())) {
            xUiMap.put(FieldConfigProperties.GROUP.getValue(), group);
        }
    }

    public static void populateUiOrder(Map<String, Object> xUiMap, int order) {
        if (order != 0 && !xUiMap.containsKey(FieldConfigProperties.ORDER.getValue())) {
            xUiMap.put(FieldConfigProperties.ORDER.getValue(), String.valueOf(order));
        }
    }

    public static void populateUiWidth(Map<String, Object> xUiMap, String width) {
        if (width != null && !width.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.WIDTH.getValue())) {
            xUiMap.put(FieldConfigProperties.WIDTH.getValue(), width);
        }
    }

    public static void populateUiIcon(Map<String, Object> xUiMap, String icon) {
        if (icon != null && !icon.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.ICON.getValue())) {
            xUiMap.put(FieldConfigProperties.ICON.getValue(), icon);
        }
    }

    public static void populateUiDisabled(Map<String, Object> xUiMap, boolean disabled) {
        if (disabled && !xUiMap.containsKey(FieldConfigProperties.DISABLED.getValue())) {
            xUiMap.put(FieldConfigProperties.DISABLED.getValue(), Boolean.TRUE);
        }
    }

    public static void populateUiHidden(Map<String, Object> xUiMap, boolean hidden) {
        if (hidden && !xUiMap.containsKey(FieldConfigProperties.HIDDEN.getValue())) {
            xUiMap.put(FieldConfigProperties.HIDDEN.getValue(), Boolean.TRUE);
        }
    }

    public static void populateUiEditable(Map<String, Object> xUiMap, boolean editable) {
        if (!editable && !xUiMap.containsKey(FieldConfigProperties.EDITABLE.getValue())) {
            xUiMap.put(FieldConfigProperties.EDITABLE.getValue(), Boolean.FALSE);
        }
    }

    public static void populateUiSortable(Map<String, Object> xUiMap, boolean sortable) {
        if (!sortable && !xUiMap.containsKey(FieldConfigProperties.SORTABLE.getValue())) {
            xUiMap.put(FieldConfigProperties.SORTABLE.getValue(), Boolean.FALSE);
        }
    }

    public static void populateUiFilterable(Map<String, Object> xUiMap, boolean filterable) {
        if (filterable && !xUiMap.containsKey(FieldConfigProperties.FILTERABLE.getValue())) {
            xUiMap.put(FieldConfigProperties.FILTERABLE.getValue(), Boolean.TRUE);
        }
    }

    public static void populateUiHelpText(Map<String, Object> xUiMap, String description) {
        if (description != null && !description.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.HELP_TEXT.getValue())) {
            xUiMap.put(FieldConfigProperties.HELP_TEXT.getValue(), description);
        }
    }

    public static void populateUiDefaultValue(Map<String, Object> xUiMap, Object example) {
        if (example != null && !xUiMap.containsKey(FieldConfigProperties.DEFAULT_VALUE.getValue())) {
            // Preserve the original type of the example object
            xUiMap.put(FieldConfigProperties.DEFAULT_VALUE.getValue(), example);
        }
    }

    public static void populateUiReadOnly(Map<String, Object> xUiMap, Boolean readOnly) {
        if (Boolean.TRUE.equals(readOnly) && !xUiMap.containsKey(FieldConfigProperties.READ_ONLY.getValue())) {
            xUiMap.put(FieldConfigProperties.READ_ONLY.getValue(), Boolean.TRUE); // Storing as boolean true
        }
    }

    /**
     * Popula a propriedade `options` no mapa `x-ui` a partir de uma lista de valores `enum` do schema.
     *
     * <p>Este método converte uma lista de valores (geralmente strings) em uma estrutura JSON
     * de `[{label: "...", value: "..."}]`, que é o formato esperado por componentes de UI
     * como Selects, Radio Buttons ou Checkbox Groups.</p>
     *
     * @param xUiMap O mapa de metadados `x-ui`.
     * @param enumValues A lista de valores extraída da propriedade `enum` do schema OpenAPI.
     * @param objectMapper Uma instância de {@link ObjectMapper} para construir os nós JSON.
     */
    public static void populateUiOptionsFromEnum(Map<String, Object> xUiMap, List<?> enumValues, ObjectMapper objectMapper) {
        if (enumValues != null && !enumValues.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.OPTIONS.getValue())) {
            ArrayNode optionsNode = objectMapper.createArrayNode();
            for (Object enumValue : enumValues) {
                ObjectNode optionNode = objectMapper.createObjectNode();
                // Assuming enumValue can be reasonably converted to string for value and label
                // For more complex objects, specific handling for value/label might be needed.
                if (enumValue instanceof String) {
                    optionNode.put("value", (String) enumValue);
                    optionNode.put("label", (String) enumValue);
                } else if (enumValue instanceof Number) {
                    optionNode.putPOJO("value", enumValue); // Store numbers as numbers
                    optionNode.put("label", enumValue.toString());
                } else if (enumValue instanceof Boolean) {
                    optionNode.putPOJO("value", enumValue); // Store booleans as booleans
                    optionNode.put("label", enumValue.toString());
                }
                else {
                    // Default to string representation if type is not directly handled
                    // Consider if specific .toString() or a getter is more appropriate for other types
                    String stringValue = enumValue.toString();
                    optionNode.put("value", stringValue);
                    optionNode.put("label", stringValue);
                }
                optionsNode.add(optionNode);
            }
            xUiMap.put(FieldConfigProperties.OPTIONS.getValue(), optionsNode);
        }
    }

    public static void populateUiOptionsFromString(Map<String, Object> xUiMap,
                                                  String options,
                                                  ObjectMapper objectMapper) {
        if (options == null || options.isEmpty() || xUiMap.containsKey(FieldConfigProperties.OPTIONS.getValue())) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(options);
            if (!node.isArray()) {
                LOGGER.warn("Options JSON is not an array: {}", options);
                return;
            }

            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                if (element.isObject()) {
                    arrayNode.add(element);
                } else {
                    ObjectNode optionNode = objectMapper.createObjectNode();
                    String text = element.asText();
                    optionNode.put("label", text);
                    optionNode.put("value", text);
                    arrayNode.add(optionNode);
                }
            }
            xUiMap.put(FieldConfigProperties.OPTIONS.getValue(), arrayNode);
        } catch (Exception e) {
            // Fallback: options pode estar "duplamente escapado" (ex.: [{\"label\":\"...\"}])
            // Estratégia: tratar como string JSON literal para desserializar escapes e tentar novamente
            try {
                String normalized = objectMapper.readValue("\"" + options + "\"", String.class);
                JsonNode node = objectMapper.readTree(normalized);
                if (!node.isArray()) {
                    LOGGER.warn("Options JSON (normalized) is not an array: {}", normalized);
                    return;
                }

                ArrayNode arrayNode = objectMapper.createArrayNode();
                for (JsonNode element : node) {
                    if (element.isObject()) {
                        arrayNode.add(element);
                    } else {
                        ObjectNode optionNode = objectMapper.createObjectNode();
                        String text = element.asText();
                        optionNode.put("label", text);
                        optionNode.put("value", text);
                        arrayNode.add(optionNode);
                    }
                }
                xUiMap.put(FieldConfigProperties.OPTIONS.getValue(), arrayNode);
            } catch (Exception ex) {
                LOGGER.warn("Invalid options JSON: {}", options, ex);
            }
        }
    }

    public static void populateUiMinLength(Map<String, Object> xUiMap, Integer minLength, String message) {
        if (minLength != null && minLength > 0 && !xUiMap.containsKey(ValidationProperties.MIN_LENGTH.getValue())) {
            xUiMap.put(ValidationProperties.MIN_LENGTH.getValue(), minLength.toString());
        }
        if (message != null && !message.isEmpty() && !xUiMap.containsKey(ValidationProperties.MIN_LENGTH_MESSAGE.getValue())) {
            xUiMap.put(ValidationProperties.MIN_LENGTH_MESSAGE.getValue(), message);
        }
    }

    public static void populateUiMaxLength(Map<String, Object> xUiMap, Integer maxLength, String message) {
        if (maxLength != null && !xUiMap.containsKey(ValidationProperties.MAX_LENGTH.getValue())) {
            xUiMap.put(ValidationProperties.MAX_LENGTH.getValue(), maxLength.toString());
        }
        if (message != null && !message.isEmpty() && !xUiMap.containsKey(ValidationProperties.MAX_LENGTH_MESSAGE.getValue())) {
            xUiMap.put(ValidationProperties.MAX_LENGTH_MESSAGE.getValue(), message);
        }
    }

    public static void populateUiMinimum(Map<String, Object> xUiMap, Number minimum, String message) {
        if (minimum != null) {
            if (!xUiMap.containsKey(ValidationProperties.MIN.getValue())) {
                 xUiMap.put(ValidationProperties.MIN.getValue(), minimum.toString());
            }
            // For numeric inputs, FieldConfigProperties.NUMERIC_MIN is also used
            if (!xUiMap.containsKey(FieldConfigProperties.NUMERIC_MIN.getValue())) {
                 xUiMap.put(FieldConfigProperties.NUMERIC_MIN.getValue(), minimum.toString());
            }
        }
        if (message != null && !message.isEmpty() && !xUiMap.containsKey(ValidationProperties.RANGE_MESSAGE.getValue())) {
            xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(), message);
        }
    }

    public static void populateUiMaximum(Map<String, Object> xUiMap, Number maximum, String message) {
        if (maximum != null) {
            if (!xUiMap.containsKey(ValidationProperties.MAX.getValue())) {
                xUiMap.put(ValidationProperties.MAX.getValue(), maximum.toString());
            }
            // For numeric inputs, FieldConfigProperties.NUMERIC_MAX is also used
            if (!xUiMap.containsKey(FieldConfigProperties.NUMERIC_MAX.getValue())) {
                xUiMap.put(FieldConfigProperties.NUMERIC_MAX.getValue(), maximum.toString());
            }
        }
        // Note: This might overwrite a message set by populateUiMinimum if both are called with messages.
        // Caller should be aware or have specific logic if combined messages are needed.
        if (message != null && !message.isEmpty() && !xUiMap.containsKey(ValidationProperties.RANGE_MESSAGE.getValue())) {
            xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(), message);
        }
    }

    public static void populateUiPattern(Map<String, Object> xUiMap, String pattern, String message) {
        if (pattern != null && !pattern.isEmpty() && !xUiMap.containsKey(ValidationProperties.PATTERN.getValue())) {
            xUiMap.put(ValidationProperties.PATTERN.getValue(), pattern);
        }
        if (message != null && !message.isEmpty() && !xUiMap.containsKey(ValidationProperties.PATTERN_MESSAGE.getValue())) {
            xUiMap.put(ValidationProperties.PATTERN_MESSAGE.getValue(), message);
        }
    }

    public static void populateUiRequired(Map<String, Object> xUiMap, Boolean required) {
        if (Boolean.TRUE.equals(required) && !xUiMap.containsKey(ValidationProperties.REQUIRED.getValue())) {
            xUiMap.put(ValidationProperties.REQUIRED.getValue(), Boolean.TRUE); // Storing as boolean true
        }
    }

    public static void populateUiAllowedFileTypes(Map<String, Object> xUiMap, String contentMediaType) {
        if (!xUiMap.containsKey(ValidationProperties.ALLOWED_FILE_TYPES.getValue())) {
            if (contentMediaType != null && !contentMediaType.isEmpty()) {
                xUiMap.put(ValidationProperties.ALLOWED_FILE_TYPES.getValue(), contentMediaType);
            } else {
                xUiMap.put(ValidationProperties.ALLOWED_FILE_TYPES.getValue(), "*/*");
            }
        }
    }

    public static void populateUiMaxFileSize(Map<String, Object> xUiMap, Long maxFileSize) {
        if (maxFileSize != null && !xUiMap.containsKey(ValidationProperties.MAX_FILE_SIZE.getValue())) {
            // Storing as Long, consistent with how it might be used/parsed later.
            // Previous direct puts in controllers might have used asLong() or similar.
            xUiMap.put(ValidationProperties.MAX_FILE_SIZE.getValue(), maxFileSize);
        }
    }

    public static void populateUiPlaceholder(Map<String, Object> xUiMap, String titleOrPlaceholder) {
        if (titleOrPlaceholder != null && !titleOrPlaceholder.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.PLACEHOLDER.getValue())) {
            xUiMap.put(FieldConfigProperties.PLACEHOLDER.getValue(), titleOrPlaceholder);
        }
    }

    public static void populateUiName(Map<String, Object> xUiMap, String fieldName) {
        if (fieldName != null && !fieldName.isEmpty() && !xUiMap.containsKey(FieldConfigProperties.NAME.getValue())) {
            xUiMap.put(FieldConfigProperties.NAME.getValue(), fieldName);
        }
    }

    /**
     * Popula o label do campo no mapa `x-ui`, usando um label explícito ou formatando o nome do campo.
     *
     * @param xUiMap O mapa de metadados `x-ui`.
     * @param labelText O texto do label explícito (vindo de uma anotação, por exemplo). Tem prioridade.
     * @param fieldName O nome técnico do campo, usado como fallback para gerar um label legível.
     */
    public static void populateUiLabel(Map<String, Object> xUiMap, String labelText, String fieldName) {
        if (!xUiMap.containsKey(FieldConfigProperties.LABEL.getValue())) {
            if (labelText != null && !labelText.isEmpty()) {
                xUiMap.put(FieldConfigProperties.LABEL.getValue(), labelText);
            } else if (fieldName != null && !fieldName.isEmpty()) {
                xUiMap.put(FieldConfigProperties.LABEL.getValue(), formatFieldNameAsLabel(fieldName));
            }
        }
    }

    /**
     * Formata um tamanho de arquivo em bytes para um formato legível (KB, MB, GB).
     *
     * @param size O tamanho do arquivo, geralmente um Long ou String representando bytes.
     * @return Uma string formatada e legível (ex: "1.25 MB").
     */
    public static String formatFileSize(Object size) {
        try {
            long sizeInBytes = Long.parseLong(String.valueOf(size));
            if (sizeInBytes < 1024) {
                return sizeInBytes + " bytes";
            } else if (sizeInBytes < 1024 * 1024) {
                return String.format("%.2f KB", sizeInBytes / 1024.0);
            } else if (sizeInBytes < 1024 * 1024 * 1024) {
                return String.format("%.2f MB", sizeInBytes / (1024.0 * 1024.0));
            } else {
                return String.format("%.2f GB", sizeInBytes / (1024.0 * 1024.0 * 1024.0));
            }
        } catch (NumberFormatException e) {
            return String.valueOf(size) + " bytes";
        }
    }

    /**
     * Formata uma string de mimetypes em uma lista legível para o usuário.
     *
     * <p>Converte mimetypes comuns como "application/pdf" para nomes amigáveis como "PDF".</p>
     *
     * @param allowedTypes A string de tipos permitidos (ex: "image/jpeg", "image/png").
     * @return Uma string formatada para exibição (ex: "JPEG, JPG").
     */
    public static String formatAllowedTypes(String allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return "*/*";
        }

        // If it's already a formatted list, return as is
        if (allowedTypes.contains(",")) {
            return allowedTypes;
        }

        // Handle common mimetypes for more user-friendly display
        switch (allowedTypes) {
            case "application/pdf":
                return "PDF";
            case "image/jpeg":
                return "JPEG, JPG";
            case "image/png":
                return "PNG";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
                return "XLSX (Excel)";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return "DOCX (Word)";
            case "application/zip":
                return "ZIP";
            case "image/*":
                return "Any image type";
            case "application/json":
                return "JSON";
            case "text/plain":
                return "TXT";
            case "text/csv":
                return "CSV";
            default:
                return allowedTypes;
        }
    }

    /**
     * Determina o tipo de dado semântico de um campo com base no tipo e formato do schema OpenAPI.
     *
     * <p>Este método mapeia os tipos primitivos do OpenAPI para um conjunto de tipos de dados
     * semânticos definidos no enum {@link FieldDataType}, como TEXT, NUMBER, DATE, etc.
     * Isso ajuda o frontend a entender como processar e validar o valor do campo.</p>
     *
     * @param openApiType O tipo do schema OpenAPI (ex: "string", "integer").
     * @param openApiFormat O formato do schema OpenAPI (ex: "date-time", "email").
     * @return O valor string de um {@link FieldDataType} correspondente, ou `null` se não houver um mapeamento direto.
     */
    public static String determineFieldDataType(String openApiType, String openApiFormat) {
        if (openApiType == null) {
            return null; // Or a default type if applicable
        }

        switch (openApiType) {
            case "string":
                if (openApiFormat != null) {
                    switch (openApiFormat) {
                        case "date":
                        case "date-time": // Both map to FieldDataType.DATE
                            return FieldDataType.DATE.getValue();
                        case "email":
                            return FieldDataType.EMAIL.getValue();
                        case "password":
                            return FieldDataType.PASSWORD.getValue();
                        case "uri":
                        case "url":
                        case "uri-reference": // From ApiDocsController
                            return FieldDataType.URL.getValue();
                        case "binary":
                        case "byte": // Typically for file uploads
                            return FieldDataType.FILE.getValue();
                        case "json": // As defined in ApiDocsController's processTypeAndFormat
                            return FieldDataType.JSON.getValue();
                        default:
                            return FieldDataType.TEXT.getValue();
                    }
                } else {
                    return FieldDataType.TEXT.getValue();
                }
            case "integer":
            case "number":
                return FieldDataType.NUMBER.getValue();
            case "boolean":
                return FieldDataType.BOOLEAN.getValue();
            case "array":
            case "object":
                // ApiDocsController does not set a specific FieldDataType for array/object.
                // They are often handled by specific control types (MULTI_SELECT, EXPANSION_PANEL)
                // or custom components. Returning null means no specific FieldDataType is assigned here.
                return null;
            default:
                return null; // Or a default type if applicable
        }
    }

    public static void populateUiDataType(Map<String, Object> xUiMap, String openApiType, String openApiFormat) {
        String dataType = determineFieldDataType(openApiType, openApiFormat);
        if (dataType != null && !xUiMap.containsKey(FieldConfigProperties.TYPE.getValue())) {
            xUiMap.put(FieldConfigProperties.TYPE.getValue(), dataType);
        }
    }

    /**
     * <h3>Determina um tipo de controle de UI "inteligente" baseado em convenções de nomenclatura de campos.</h3>
     *
     * <p>Este método implementa a lógica de <strong>convenção sobre configuração</strong>. Ele analisa o nome de um campo
     * para inferir um tipo de controle de UI mais específico do que o determinado pelas propriedades do schema.
     * Por exemplo, um campo `string` chamado "descricao" é melhor representado como `TEXTAREA` do que um `INPUT` padrão.</p>
     *
     * <p>A detecção não é sensível a maiúsculas/minúsculas.</p>
     *
     * @param fieldName O nome do campo a ser analisado (ex: "descricao", "valorUnitario", "userPassword").
     * @return O valor string de um {@link FieldControlType} sugerido pela convenção, ou `null` se nenhuma convenção for encontrada.
     *
     * <h4>Exemplos de Mapeamento por Convenção:</h4>
     * <ul>
     *   <li>Nomes contendo "descricao", "observacao" &rarr; {@link FieldControlType#TEXTAREA}</li>
     *   <li>Nomes contendo "valor", "preco", "salario" &rarr; {@link FieldControlType#CURRENCY_INPUT}</li>
     *   <li>Nomes contendo "url", "link", "site" &rarr; {@link FieldControlType#URL_INPUT}</li>
     *   <li>Nomes contendo "cor", "color" &rarr; {@link FieldControlType#COLOR_PICKER}</li>
     *   <li>Nomes contendo "senha", "password" &rarr; {@link FieldControlType#PASSWORD}</li>
     *   <li>Nomes contendo "imagem", "foto", "arquivo" &rarr; {@link FieldControlType#FILE_UPLOAD}</li>
     * </ul>
     */
    public static String determineSmartControlTypeByFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        String normalizedFieldName = fieldName.toLowerCase();

        // Campos que devem permanecer single-line (INPUT), mesmo com maxLength alto
        if (normalizedFieldName.contains("nome") || normalizedFieldName.contains("name") ||
                normalizedFieldName.contains("titulo") || normalizedFieldName.contains("title") ||
                normalizedFieldName.contains("assunto") || normalizedFieldName.contains("subject")) {
            return FieldControlType.INPUT.getValue();
        }

        if (normalizedFieldName.contains("descricao") || normalizedFieldName.contains("observacao") ||
                normalizedFieldName.contains("description") || normalizedFieldName.contains("comment")) {
            return FieldControlType.TEXTAREA.getValue();
        } else if (normalizedFieldName.contains("valor") || normalizedFieldName.contains("preco") ||
                normalizedFieldName.contains("price") || normalizedFieldName.contains("amount") ||
                normalizedFieldName.contains("salario") || normalizedFieldName.contains("salary")) {
            return FieldControlType.CURRENCY_INPUT.getValue();
        } else if (normalizedFieldName.contains("url") || normalizedFieldName.contains("link") ||
                normalizedFieldName.contains("website") || normalizedFieldName.contains("site")) {
            // Note: This also implies FieldDataType.URL, which should be handled by populateUiDataType
            return FieldControlType.URL_INPUT.getValue();
        } else if (normalizedFieldName.contains("cor") || normalizedFieldName.contains("color")) {
            return FieldControlType.COLOR_PICKER.getValue();
        } else if (normalizedFieldName.contains("senha") || normalizedFieldName.contains("password")) {
            // Note: This also implies FieldDataType.PASSWORD
            return FieldControlType.PASSWORD.getValue();
        } else if (normalizedFieldName.contains("email")) {
            // Note: This also implies FieldDataType.EMAIL
            return FieldControlType.EMAIL_INPUT.getValue(); // Smart control might be just EMAIL_INPUT or a specific smart email
        } else if (normalizedFieldName.contains("data") || normalizedFieldName.contains("date")) {
            // Note: This also implies FieldDataType.DATE
            return FieldControlType.DATE_PICKER.getValue();
        } else if (normalizedFieldName.contains("imagem") || normalizedFieldName.contains("foto") ||
                normalizedFieldName.contains("image") || normalizedFieldName.contains("photo")) {
            // Note: This also implies FieldDataType.FILE
            return FieldControlType.FILE_UPLOAD.getValue();
        } else if (normalizedFieldName.contains("arquivo") || normalizedFieldName.contains("file")) {
            // Note: This also implies FieldDataType.FILE
            return FieldControlType.FILE_UPLOAD.getValue();
        }
        return null; // No specific smart control type found by name
    }

    public static String determineArrayItemFilterControlType(String fieldName, String itemType, String itemFormat) {
        if (fieldName != null && fieldName.toLowerCase().endsWith("filtro")) {
            if ("string".equals(itemType)) {
                if ("date".equals(itemFormat)) {
                    return FieldControlType.DATE_RANGE.getValue();
                } else if ("date-time".equals(itemFormat)) {
                    return FieldControlType.DATE_TIME_RANGE.getValue();
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------
    // Helpers para enums/arrays/percent e numeric step
    // -----------------------------------------------------------------------------------------

    private static final int ENUM_SMALL_MAX = 5;   // pode ser configurável futuramente
    private static final int ENUM_MEDIUM_MAX = 25; // pode ser configurável futuramente

    /**
     * Determina controle ideal para enums de string por cardinalidade.
     * Pequeno (≤5): radio; Médio (≤25): select; Grande (>25): autoComplete.
     */
    public static String determineEnumControlBySize(int count) {
        if (count <= ENUM_SMALL_MAX) return FieldControlType.RADIO.getValue();
        if (count <= ENUM_MEDIUM_MAX) return FieldControlType.SELECT.getValue();
        return FieldControlType.AUTO_COMPLETE.getValue();
    }

    /**
     * Determina controle para arrays de enums: pequeno → chipInput; demais → multiSelect.
     */
    public static String determineArrayEnumControlBySize(int count) {
        if (count <= ENUM_SMALL_MAX) return FieldControlType.CHIP_INPUT.getValue();
        return FieldControlType.MULTI_SELECT.getValue();
    }

    /**
     * Aplica defaults amigáveis para percent (0–100%). Apenas define se ausentes.
     */
    public static void applyPercentDefaults(Map<String, Object> xUiMap) {
        if (!xUiMap.containsKey(FieldConfigProperties.NUMERIC_STEP.getValue())) {
            xUiMap.put(FieldConfigProperties.NUMERIC_STEP.getValue(), "0.01");
        }
        if (!xUiMap.containsKey(FieldConfigProperties.PLACEHOLDER.getValue())) {
            xUiMap.put(FieldConfigProperties.PLACEHOLDER.getValue(), "0–100%");
        }
        if (!xUiMap.containsKey(FieldConfigProperties.NUMERIC_MIN.getValue())) {
            xUiMap.put(FieldConfigProperties.NUMERIC_MIN.getValue(), "0");
        }
        if (!xUiMap.containsKey(FieldConfigProperties.NUMERIC_MAX.getValue())) {
            xUiMap.put(FieldConfigProperties.NUMERIC_MAX.getValue(), "100");
        }
    }

    /**
     * Retorna step numérico como string a partir do número de casas decimais.
     * Ex.: fraction=2 → "0.01".
     */
    public static String numericStepFromDigits(int fraction) {
        if (fraction <= 0) return "1";
        StringBuilder sb = new StringBuilder("0.");
        for (int i = 1; i < fraction; i++) sb.append('0');
        sb.append('1');
        return sb.toString();
    }

/**
 * <h2>Algoritmo Principal de Detecção de Tipo de Controle de UI</h2>
 *
 * <p>Este é o método central que determina o tipo de controle de UI mais apropriado
 * para um campo, orquestrando várias estratégias de detecção em uma ordem de
 * precedência específica.</p>
 *
 * <h3>Lógica de Precedência:</h3>
 * <ol>
 *   <li><strong>Detecção Básica:</strong> Primeiro, {@link #determineBasicControlType} é chamado, usando
 *       propriedades do schema OpenAPI (type, format, enum) para uma determinação inicial.</li>
 *   <li><strong>Sobrescrita por Filtro de Array:</strong> Se o campo for um array e o nome
 *       sugerir um filtro (ex: "dataFiltro"), o tipo de controle pode ser especializado
 *       para um controle de range (ex: "date-range").</li>
 *   <li><strong>Sobrescrita por Detecção Inteligente (Convenção):</strong> Finalmente,
 *       {@link #determineSmartControlTypeByFieldName} é chamado. Se encontrar uma correspondência
 *       baseada no nome do campo (ex: "descricao" -> "textarea"), este resultado terá a
 *       <strong>maior precedência</strong> e sobrescreverá os anteriores.</li>
 * </ol>
 *
 * @param openApiType O tipo do schema OpenAPI (ex: "string").
 * @param openApiFormat O formato do schema OpenAPI (ex: "date-time").
 * @param hasEnum Flag indicando se o schema possui uma lista de valores `enum`.
 * @param maxLengthSchema O valor de `maxLength` definido no schema.
 * @param isArrayType Flag indicando se o tipo do schema é "array".
 * @param itemType O tipo dos itens, caso o schema seja um array.
 * @param itemFormat O formato dos itens, caso o schema seja um array.
 * @param isArrayItemsHaveEnum Flag indicando se os itens do array possuem `enum`.
 * @param fieldName O nome do campo (ex: "observacao", "dataAdmissao").
 * @return O valor string do {@link FieldControlType} efetivo (ex: "textarea", "date-picker").
 *
 * <h4>Exemplo de Fluxo:</h4>
 * <pre>{@code
 * // Campo: "observacao", Tipo: "string", maxLength: 500
 *
 * // 1. determineBasicControlType("string", null, false, 500, ...) -> "textarea" (devido ao maxLength)
 * // 2. (Não é um filtro de array)
 * // 3. determineSmartControlTypeByFieldName("observacao") -> "textarea"
 *
 * // Resultado Final: "textarea"
 *
 * // ---
 *
 * // Campo: "status", Tipo: "string", Enum: ["ATIVO", "INATIVO"]
 *
 * // 1. determineBasicControlType("string", null, true, ...) -> "select"
 * // 2. (Não é um filtro de array)
 * // 3. determineSmartControlTypeByFieldName("status") -> null
 *
 * // Resultado Final: "select"
 * }</pre>
 *
 * @see #determineBasicControlType(String, String, boolean, Integer, boolean, boolean)
 * @see #determineSmartControlTypeByFieldName(String)
 * @see #determineArrayItemFilterControlType(String, String, String)
 */
public static String determineEffectiveControlType(
        String openApiType, String openApiFormat, boolean hasEnum, Integer maxLengthSchema,
        boolean isArrayType, String itemType, String itemFormat, boolean isArrayItemsHaveEnum,
        String fieldName
) {
    String controlType = null;

    // 1. Basic determination
    controlType = determineBasicControlType(openApiType, openApiFormat, hasEnum, maxLengthSchema, isArrayType, isArrayItemsHaveEnum);

    // 2. Array filter override (only if it's an array and a filter control type is found)
    // Ensure itemType and itemFormat are passed to determineArrayItemFilterControlType
    if (isArrayType) {
        String arrayFilterControlType = determineArrayItemFilterControlType(fieldName, itemType, itemFormat);
        if (arrayFilterControlType != null) {
            controlType = arrayFilterControlType;
        }
    }

    // 3. Smart control type override (based on field name, can be a smart type or a more specific basic type)
    String smartControlType = determineSmartControlTypeByFieldName(fieldName);
    if (smartControlType != null) {
        controlType = smartControlType;
    }

    return controlType;
}

    /**
     * Popula o tipo de controle de UI no mapa `x-ui`.
     *
     * @param xUiMap O mapa de metadados `x-ui` a ser populado.
     * @param controlType O valor do {@link FieldControlType} a ser definido.
     */
    public static void populateUiControlType(Map<String, Object> xUiMap, String controlType) {
        if (controlType != null && !xUiMap.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
            xUiMap.put(FieldConfigProperties.CONTROL_TYPE.getValue(), controlType);
        }
    }

    /**
     * Gets the field label from the UI map for use in validation messages.
     * @param xUiMap The UI map.
     * @return The label or a default string "O campo".
     */
    public static String getFieldLabel(Map<String, Object> xUiMap) {
        Object labelObj = xUiMap.get(FieldConfigProperties.LABEL.getValue());
        if (labelObj instanceof String) {
            String label = (String) labelObj;
            if (label != null && !label.trim().isEmpty()) {
                return label;
            }
        }
        return "O campo"; // Default label
    }

    /**
     * Popula o mapa `x-ui` com mensagens de validação padrão e amigáveis.
     *
     * <p>Este método gera mensagens de erro para validações comuns (required, minLength, pattern, etc.)
     * caso nenhuma mensagem customizada tenha sido fornecida. Ele utiliza o label do campo
     * para criar mensagens contextuais, como "Nome é obrigatório".</p>
     *
     * @param xUiMap O mapa de metadados `x-ui` que contém as propriedades de validação e onde as mensagens serão inseridas.
     */
    public static void populateDefaultValidationMessages(Map<String, Object> xUiMap) {
        // Required message
        if (Boolean.TRUE.equals(xUiMap.get(ValidationProperties.REQUIRED.getValue())) &&
            !xUiMap.containsKey(ValidationProperties.REQUIRED_MESSAGE.getValue())) {
            String fieldLabel = getFieldLabel(xUiMap);
            xUiMap.put(ValidationProperties.REQUIRED_MESSAGE.getValue(), fieldLabel + " é obrigatório");
        }

        // Min length message
        if (xUiMap.containsKey(ValidationProperties.MIN_LENGTH.getValue()) &&
            !xUiMap.containsKey(ValidationProperties.MIN_LENGTH_MESSAGE.getValue())) {
            String fieldLabel = getFieldLabel(xUiMap);
            String minLength = String.valueOf(xUiMap.get(ValidationProperties.MIN_LENGTH.getValue()));
            xUiMap.put(ValidationProperties.MIN_LENGTH_MESSAGE.getValue(),
                       fieldLabel + " deve ter no mínimo " + minLength + " caracteres");
        }

        // Max length message
        if (xUiMap.containsKey(ValidationProperties.MAX_LENGTH.getValue()) &&
            !xUiMap.containsKey(ValidationProperties.MAX_LENGTH_MESSAGE.getValue())) {
            String fieldLabel = getFieldLabel(xUiMap);
            String maxLength = String.valueOf(xUiMap.get(ValidationProperties.MAX_LENGTH.getValue()));
            xUiMap.put(ValidationProperties.MAX_LENGTH_MESSAGE.getValue(),
                       fieldLabel + " deve ter no máximo " + maxLength + " caracteres");
        }

        // Range message (min/max)
        // This logic tries to create a combined message if both min and max are present,
        // or individual messages if only one is present.
        // It assumes that if a RANGE_MESSAGE is already there, it's been set intentionally (e.g., by annotation).
        if (!xUiMap.containsKey(ValidationProperties.RANGE_MESSAGE.getValue())) {
            String fieldLabel = getFieldLabel(xUiMap);
            boolean hasMin = xUiMap.containsKey(ValidationProperties.MIN.getValue());
            boolean hasMax = xUiMap.containsKey(ValidationProperties.MAX.getValue());
            String minVal = hasMin ? String.valueOf(xUiMap.get(ValidationProperties.MIN.getValue())) : null;
            String maxVal = hasMax ? String.valueOf(xUiMap.get(ValidationProperties.MAX.getValue())) : null;

            if (hasMin && hasMax) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve estar entre " + minVal + " e " + maxVal);
            } else if (hasMin) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve ser maior ou igual a " + minVal);
            } else if (hasMax) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve ser menor ou igual a " + maxVal);
            }
        }

        // Pattern message
        if (xUiMap.containsKey(ValidationProperties.PATTERN.getValue()) &&
            !xUiMap.containsKey(ValidationProperties.PATTERN_MESSAGE.getValue())) {
            String fieldLabel = getFieldLabel(xUiMap);
            xUiMap.put(ValidationProperties.PATTERN_MESSAGE.getValue(),
                       "Formato inválido para " + fieldLabel);
        }

        // Max file size message (uses MAX_LENGTH_MESSAGE key from original resolver logic for this specific message)
        // This is a bit of an oddity from the original code, where MAX_LENGTH_MESSAGE was used for file size.
        // We keep it for now to match behavior but ideally, MAX_FILE_SIZE_MESSAGE would be distinct.
        if (xUiMap.containsKey(ValidationProperties.MAX_FILE_SIZE.getValue()) &&
            !xUiMap.containsKey(ValidationProperties.MAX_LENGTH_MESSAGE.getValue())) { // Checking MAX_LENGTH_MESSAGE
            // We also need to ensure this applies only if it's a file type,
            // but this method doesn't have direct access to schema.format.
            // The caller (CustomOpenApiResolver) would have set MAX_FILE_SIZE only for binary types.
            String fieldLabel = getFieldLabel(xUiMap);
            String maxSize = formatFileSize(xUiMap.get(ValidationProperties.MAX_FILE_SIZE.getValue()));
            xUiMap.put(ValidationProperties.MAX_LENGTH_MESSAGE.getValue(), // Using MAX_LENGTH_MESSAGE
                       "O tamanho máximo do arquivo para " + fieldLabel + " é " + maxSize);
        }

        // Allowed file types message
        if (xUiMap.containsKey(ValidationProperties.ALLOWED_FILE_TYPES.getValue()) &&
            !xUiMap.containsKey(ValidationProperties.FILE_TYPE_MESSAGE.getValue())) {
            // Similar to above, this assumes ALLOWED_FILE_TYPES is set meaningfully by the caller.
            String fieldLabel = getFieldLabel(xUiMap);
            String allowedTypes = String.valueOf(xUiMap.get(ValidationProperties.ALLOWED_FILE_TYPES.getValue()));
            if (!"*/*".equals(allowedTypes) && !"".equals(allowedTypes)) { // Added empty check
                 xUiMap.put(ValidationProperties.FILE_TYPE_MESSAGE.getValue(), "Tipos de arquivo permitidos para " +
                           fieldLabel + ": " + formatAllowedTypes(allowedTypes));
            }
        }
         // --- numericMin/numericMax (UI config) ---
        // This part was in CustomOpenApiResolver's processValidationMessages for x-ui specific numeric range.
        // Replicating it here if those specific x-ui config props (NUMERIC_MIN/MAX) are present and no general RANGE_MESSAGE is set.
        if (!xUiMap.containsKey(ValidationProperties.RANGE_MESSAGE.getValue())) { // Check again if general range message was set above
            String fieldLabel = getFieldLabel(xUiMap);
            boolean hasNumericMin = xUiMap.containsKey(FieldConfigProperties.NUMERIC_MIN.getValue());
            boolean hasNumericMax = xUiMap.containsKey(FieldConfigProperties.NUMERIC_MAX.getValue());
            String numericMinVal = hasNumericMin ? String.valueOf(xUiMap.get(FieldConfigProperties.NUMERIC_MIN.getValue())) : null;
            String numericMaxVal = hasNumericMax ? String.valueOf(xUiMap.get(FieldConfigProperties.NUMERIC_MAX.getValue())) : null;

            if (hasNumericMin && hasNumericMax) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve estar entre " + numericMinVal + " e " + numericMaxVal);
            } else if (hasNumericMin) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve ser maior ou igual a " + numericMinVal);
            } else if (hasNumericMax) {
                xUiMap.put(ValidationProperties.RANGE_MESSAGE.getValue(),
                           fieldLabel + " deve ser menor ou igual a " + numericMaxVal);
            }
        }
    }
}
