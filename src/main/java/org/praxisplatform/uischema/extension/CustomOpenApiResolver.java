package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.numeric.NumberFormatStyle;
import org.praxisplatform.uischema.util.OpenApiUiUtils;
import org.praxisplatform.uischema.filter.annotation.Filterable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolve modelos OpenAPI enriquecendo-os com a extensão {@code x-ui} usada
 * pelos frontends Praxis.
 *
 * <p>
 * A ordem de precedência aplicada segue o fluxo descrito em
 * {@code docs/architecture-overview.md}: valores padrão de
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema},
 * detecção heurística baseada no schema, overrides explícitos, validações
 * Jakarta e, por fim, {@code extraProperties}. As chaves utilizadas são
 * definidas em {@link org.praxisplatform.uischema.FieldConfigProperties} e
 * {@link org.praxisplatform.uischema.ValidationProperties}.
 * </p>
 *
 * <h3>Exemplo</h3>
 * <pre>{@code
 * public class PessoaDTO {
 *     @UISchema(label = "Nome", placeholder = "Informe o nome")
 *     @Size(min = 3, max = 100)
 *     private String nome;
 * }
 * }</pre>
 *
 * <p>
 * O resultado é uma documentação OpenAPI com dicas ricas de renderização
 * (placeholders, controlType, mensagens de validação) consumidas pelo endpoint
 * {@code /schemas/filtered} controlado por
 * {@link org.praxisplatform.uischema.controller.docs.ApiDocsController}.
 * </p>
 *
 * @since 1.0.0
 */
public class CustomOpenApiResolver extends ModelResolver {

    // Cache para armazenar campos constantes das interfaces
    private static final Map<String, String> FIELD_PROPERTIES_MAP = new HashMap<>();
    private static final Map<String, String> VALIDATION_PROPERTIES_MAP = new HashMap<>();
    // Constante para o nome da extensão UI
    private static final String UI_EXTENSION_NAME = "x-ui";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CustomOpenApiResolver.class);


    // Inicializar o cache estaticamente
    static {
        initializePropertiesMap(FieldConfigProperties.class, FIELD_PROPERTIES_MAP);
        initializePropertiesMap(ValidationProperties.class, VALIDATION_PROPERTIES_MAP);
    }

    public CustomOpenApiResolver(ObjectMapper mapper) {
        super(mapper);
    }


    @Override
    protected void applyBeanValidatorAnnotations(Schema property, Annotation[] annotations, Schema parent, boolean applyNotNullAnnotations) {
        super.applyBeanValidatorAnnotations(property, annotations, parent, applyNotNullAnnotations);

        if (annotations != null && ResolverUtils.getAnnotation(UISchema.class, annotations) != null) {
            // NOVA ORDEM DE PRECEDÊNCIA (do menor para o maior):
            // 1. Valores padrão da anotação @UISchema (base)
            // 2. Detecção automática baseada no OpenAPI Schema (sobrescreve padrões)
            // 3. Valores explícitos da anotação @UISchema (sobrescreve detecção automática)
            // 4. extraProperties (sobrescreve tudo)
            
            resolveSchemaWithPrecedence(property, annotations);

            // Centralized validation message population
            OpenApiUiUtils.populateDefaultValidationMessages(getUIExtensionMap(property));
        }
    }

    /**
     * Resolve UI Schema com ordem de precedência clara e bem definida
     */
    private void resolveSchemaWithPrecedence(Schema<?> property, Annotation[] annotations) {
        UISchema annotation = ResolverUtils.getAnnotation(UISchema.class, annotations);
        if (annotation == null) return;
        
        Map<String, Object> uiExtension = getUIExtensionMap(property);
        String fieldName = property.getName(); // Nome do campo para detecção inteligente

        // === ETAPA 1: Valores PADRÃO da anotação @UISchema ===
        // Aplica apenas valores padrão (não explícitos) da anotação
        applyUISchemaDefaults(annotation, uiExtension);

        // === ETAPA 2: Detecção AUTOMÁTICA baseada no OpenAPI Schema ===
        // Sobrescreve padrões com detecção inteligente baseada em type/format
        applySchemaBasedDetection(property, uiExtension, fieldName, annotations);

        // === ETAPA 3: Valores EXPLÍCITOS da anotação @UISchema ===
        // Sobrescreve detecção automática com valores explicitamente definidos
        applyUISchemaExplicitValues(property, annotation, uiExtension);

        // === ETAPA 4: Anotações Jakarta Validation ===
        // Adiciona validações baseadas em @NotNull, @Size, etc.
        processJakartaValidationAnnotations(property, annotations, uiExtension);

        // === ETAPA 5: extraProperties (precedência MÁXIMA) ===
        // Sobrescreve tudo com propriedades customizadas
        applyExtraProperties(annotation, uiExtension);
    }



    private String getExtensionPropertyName(String methodName) {
        // Primeiro verificar se o método corresponde a uma propriedade em FieldConfigProperties
        for (Map.Entry<String, String> entry : FIELD_PROPERTIES_MAP.entrySet()) {
            String constName = entry.getKey();
            String propertyValue = entry.getValue();

            // Converter de camelCase para CONSTANT_CASE para comparação aproximada
            String camelCasePropertyName = toCamelCase(constName);

            if (methodName.equalsIgnoreCase(camelCasePropertyName)) {
                return propertyValue;
            }
        }

        // Em seguida, verificar ValidationProperties
        for (Map.Entry<String, String> entry : VALIDATION_PROPERTIES_MAP.entrySet()) {
            String constName = entry.getKey();
            String propertyValue = entry.getValue();

            // Converter de camelCase para CONSTANT_CASE para comparação aproximada
            String camelCasePropertyName = toCamelCase(constName);

            if (methodName.equalsIgnoreCase(camelCasePropertyName)) {
                return propertyValue;
            }
        }

        // Se não encontrado, usar o próprio nome do método
        return methodName;
    }

    private static String toCamelCase(String constCase) {
        if (constCase == null || constCase.isEmpty()) {
            return constCase;
        }

        String[] parts = constCase.toLowerCase().split("_");
        StringBuilder camelCase = new StringBuilder(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                camelCase.append(Character.toUpperCase(parts[i].charAt(0)))
                        .append(parts[i].substring(1));
            }
        }

        return camelCase.toString();
    }

    /**
     * Inicializa dinamicamente o mapa de propriedades a partir de uma interface de constantes
     */
    private static void initializePropertiesMap(Class<?> interfaceClass, Map<String, String> propertiesMap) {
        try {
            Field[] fields = interfaceClass.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    String constName = field.getName();
                    String constValue = (String) field.get(null);
                    propertiesMap.put(constName, constValue);
                }
            }
        } catch (Exception e) {
            // Log error if needed
        }
    }

    private void resolveExtension(Schema<?> property, Annotation[] annotations) {
        UISchema annotation = ResolverUtils.getAnnotation(UISchema.class, annotations);
        if (annotation != null && annotation.extraProperties() != null) {
            Map<String, Object> uiExtension = getUIExtensionMap(property);
            setProperties(annotation, uiExtension);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getUIExtensionMap(Schema<?> property) {
        if (property.getExtensions() == null) {
            property.setExtensions(new HashMap<>());
        }
        return (Map<String, Object>) property.getExtensions()
                .computeIfAbsent(UI_EXTENSION_NAME, k -> new HashMap<>());
    }

    private void setProperties(UISchema annotation, Map<String, Object> uiExtension) {
        Arrays.stream(annotation.extraProperties())
                .forEach(extensionProperty ->
                        uiExtension.putIfAbsent(extensionProperty.name(), extensionProperty.value())
                );
    }

    /**
     * Processa anotações do Jakarta Validation e Schema OpenAPI, configurando
     * o x-ui automaticamente quando não definido explicitamente.
     */
    private void processStandardAnnotations(Schema<?> property, Annotation[] annotations) {
        Map<String, Object> uiExtension = getUIExtensionMap(property);
        String fieldName = null;

        // 1. Processar propriedades do Schema OpenAPI
        if (property != null) {
            // Tentar obter o nome do campo a partir do contexto
            try {
                // O nome do campo geralmente está disponível no contexto onde o schema é construído
                fieldName = property.getName();
            } catch (Exception e) {
                // Ignora erro se não conseguir obter o nome do campo
            }

            // Utilizar métodos centralizados de OpenApiUiUtils
            OpenApiUiUtils.populateUiName(uiExtension, fieldName); // Though not strictly used before, good for consistency
            OpenApiUiUtils.populateUiLabel(uiExtension, property.getTitle(), fieldName);
            OpenApiUiUtils.populateUiPlaceholder(uiExtension, property.getTitle());
            OpenApiUiUtils.populateUiHelpText(uiExtension, property.getDescription());
            OpenApiUiUtils.populateUiDefaultValue(uiExtension, property.getExample()); // Already uses util, ensures it uses updated one
            OpenApiUiUtils.populateUiReadOnly(uiExtension, property.getReadOnly()); // Already uses util, ensures it uses updated one

            // ControlType e DataType baseados no OpenAPI Schema - priorizar sobre valores padrão da anotação
            String openApiType = property.getType();
            String openApiFormat = property.getFormat();
            boolean hasEnum = property.getEnum() != null && !property.getEnum().isEmpty();
            
            // Priorizar OpenAPI Schema para determinar controlType e dataType corretos
            String schemaBasedControlType = null;
            String schemaBasedDataType = null;
            
            if (openApiType != null) {
                switch (openApiType) {
                    case "string":
                        schemaBasedDataType = FieldDataType.TEXT.getValue();
                        schemaBasedControlType = FieldControlType.INPUT.getValue();
                        
                        if (hasEnum) {
                            schemaBasedControlType = FieldControlType.SELECT.getValue();
                        } else if (openApiFormat != null) {
                            switch (openApiFormat) {
                                case "date":
                                    schemaBasedControlType = FieldControlType.DATE_PICKER.getValue();
                                    schemaBasedDataType = FieldDataType.DATE.getValue();
                                    break;
                                case "date-time":
                                    schemaBasedControlType = FieldControlType.DATE_TIME_PICKER.getValue();
                                    schemaBasedDataType = FieldDataType.DATE.getValue();
                                    break;
                                case "time":
                                    schemaBasedControlType = FieldControlType.TIME_PICKER.getValue();
                                    schemaBasedDataType = FieldDataType.DATE.getValue();
                                    break;
                                case "email":
                                    schemaBasedControlType = FieldControlType.EMAIL_INPUT.getValue();
                                    schemaBasedDataType = FieldDataType.EMAIL.getValue();
                                    break;
                                case "password":
                                    schemaBasedControlType = FieldControlType.PASSWORD.getValue();
                                    schemaBasedDataType = FieldDataType.PASSWORD.getValue();
                                    break;
                                case "uri":
                                case "url":
                                    schemaBasedControlType = FieldControlType.URL_INPUT.getValue();
                                    schemaBasedDataType = FieldDataType.URL.getValue();
                                    break;
                                case "binary":
                                case "byte":
                                    schemaBasedControlType = FieldControlType.FILE_UPLOAD.getValue();
                                    schemaBasedDataType = FieldDataType.FILE.getValue();
                                    break;
                                case "color":
                                    schemaBasedControlType = FieldControlType.COLOR_PICKER.getValue();
                                    break;
                                case "phone":
                                    schemaBasedControlType = FieldControlType.PHONE.getValue();
                                    break;
                                case "json":
                                    schemaBasedDataType = FieldDataType.JSON.getValue();
                                    break;
                            }
                        }
                        
                        // Verificar maxLength para textarea
                        Integer maxLength = property.getMaxLength();
                        if (maxLength != null && maxLength > 100 && 
                            FieldControlType.INPUT.getValue().equals(schemaBasedControlType)) {
                            schemaBasedControlType = FieldControlType.TEXTAREA.getValue();
                        }
                        break;
                        
                    case "number":
                    case "integer":
                        schemaBasedDataType = FieldDataType.NUMBER.getValue();
                        schemaBasedControlType = FieldControlType.NUMERIC_TEXT_BOX.getValue();
                        
                        if (hasEnum) {
                            schemaBasedControlType = FieldControlType.SELECT.getValue();
                        } else if (openApiFormat != null) {
                            switch (openApiFormat) {
                                case "currency":
                                    schemaBasedControlType = FieldControlType.CURRENCY_INPUT.getValue();
                                    break;
                            }
                        }
                        break;
                        
                    case "boolean":
                        schemaBasedDataType = FieldDataType.BOOLEAN.getValue();
                        schemaBasedControlType = hasEnum ? FieldControlType.SELECT.getValue() : FieldControlType.CHECKBOX.getValue();
                        break;
                        
                    case "array":
                        // Verificar se items têm enum para multi-select
                        if (property.getItems() != null && property.getItems().getEnum() != null && 
                            !property.getItems().getEnum().isEmpty()) {
                            schemaBasedControlType = FieldControlType.MULTI_SELECT.getValue();
                        } else {
                            schemaBasedControlType = FieldControlType.ARRAY_INPUT.getValue();
                        }
                        break;
                        
                    case "object":
                        schemaBasedControlType = FieldControlType.EXPANSION_PANEL.getValue();
                        break;
                }
                
                // Aplicar controlType baseado no schema (sobrescreve valor padrão da anotação)
                if (schemaBasedControlType != null) {
                    uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), schemaBasedControlType);
                }
                
                // Aplicar dataType baseado no schema (sobrescreve valor padrão da anotação)
                if (schemaBasedDataType != null) {
                    uiExtension.put(FieldConfigProperties.TYPE.getValue(), schemaBasedDataType);
                }
            }
            
            // Fallback: usar lógica de detecção inteligente baseada no nome do campo se ainda não definido
            if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
                String smartControlType = OpenApiUiUtils.determineSmartControlTypeByFieldName(fieldName);
                if (smartControlType != null) {
                    uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), smartControlType);
                }
            }
            // Adicionar NUMERIC_FORMAT se o formato for "percent"
            // This specific logic might remain if not covered by a general numeric format population
            if ("number".equals(property.getType()) && "percent".equals(property.getFormat())) {
                if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_FORMAT.getValue())) {
                    uiExtension.put(FieldConfigProperties.NUMERIC_FORMAT.getValue(), NumberFormatStyle.PERCENT.getValue());
                }
            }

            OpenApiUiUtils.populateUiMinimum(uiExtension, property.getMinimum(), null);
            OpenApiUiUtils.populateUiMaximum(uiExtension, property.getMaximum(), null);
            OpenApiUiUtils.populateUiMinimum(uiExtension, property.getMinimum(), null); // Pass null for message
            OpenApiUiUtils.populateUiMaximum(uiExtension, property.getMaximum(), null); // Pass null for message
            OpenApiUiUtils.populateUiMinLength(uiExtension, property.getMinLength(), null); // Pass null for message
            OpenApiUiUtils.populateUiMaxLength(uiExtension, property.getMaxLength(), null); // Pass null for message
            OpenApiUiUtils.populateUiPattern(uiExtension, property.getPattern(), null); // Pass null for message
            // Ensure 'this.mapper' (ObjectMapper from ModelResolver) is passed to the updated method
            OpenApiUiUtils.populateUiOptionsFromEnum(uiExtension, property.getEnum(), this._mapper);
            OpenApiUiUtils.populateUiRequired(uiExtension, property.getRequired() != null && !property.getRequired().isEmpty());
        }

        // 2. Processar anotações Jakarta Validation
        for (Annotation annotation : annotations) {
            String annotationType = annotation.annotationType().getSimpleName();

            switch (annotationType) {
                // Validações de obrigatoriedade
                case "NotNull":
                case "NotEmpty":
                case "NotBlank":
                    // Use the utility method. Message will be handled by processValidationMessages if not set by another source.
                    OpenApiUiUtils.populateUiRequired(uiExtension, true);
                    break;

                // Validações de tamanho
                case "Size":
                    processSizeAnnotation(annotation, uiExtension);
                    break;

                // Validações numéricas
                case "Min":
                    processMinAnnotation(annotation, uiExtension);
                    break;

                case "Max":
                    processMaxAnnotation(annotation, uiExtension);
                    break;

                case "DecimalMin":
                    processDecimalMinAnnotation(annotation, uiExtension);
                    break;

                case "DecimalMax":
                    processDecimalMaxAnnotation(annotation, uiExtension);
                    break;

                // Validações de padrão
                case "Pattern":
                    processPatternAnnotation(annotation, uiExtension);
                    break;

                // Validações de email
                case "Email":
                    if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
                        // Assuming FieldControlType.EMAIL_INPUT is now available
                        uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.EMAIL_INPUT.getValue());
                    }
                    break;

                // Validações booleanas
                case "AssertTrue":
                case "AssertFalse":
                    if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
                        uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.CHECKBOX.getValue());
                    }
                    break;

                // Validações de data
                case "Past":
                case "PastOrPresent":
                case "Future":
                case "FutureOrPresent":
                    processTemporal(annotation, annotationType, uiExtension);
                    break;

                // Validações numéricas adicionais
                case "Positive":
                case "PositiveOrZero":
                    processPositiveAnnotation(annotationType, uiExtension); // This method itself sets CONTROL_TYPE to "numeric"
                    break;

                case "Negative":
                case "NegativeOrZero":
                    processNegativeAnnotation(annotationType, uiExtension);
                    break;

                case "Digits":
                    processDigitsAnnotation(annotation, uiExtension);
                    break;

                default:
                    // Outras anotações não processadas
                    break;
            }
        }
    }

    // The determineControlType method is removed as its functionality is replaced by determineEffectiveControlType.

    /**
     * Processa a anotação @Size para definir minLength e maxLength
     */
    private void processSizeAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method minMethod = annotation.annotationType().getMethod("min");
            Integer min = (Integer) minMethod.invoke(annotation);

            Method maxMethod = annotation.annotationType().getMethod("max");
            Integer max = (Integer) maxMethod.invoke(annotation);

            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation); // Generic message for @Size

            // populateUiMinLength and populateUiMaxLength will handle the Integer.MAX_VALUE check for max
            // Pass the generic @Size message; specific min/max messages will be generated by processValidationMessages if needed and if this message is not set
            OpenApiUiUtils.populateUiMinLength(uiExtension, (min > 0 ? min : null), !message.startsWith("{") ? message : null);
            OpenApiUiUtils.populateUiMaxLength(uiExtension, (max < Integer.MAX_VALUE ? max : null), !message.startsWith("{") ? message : null);

        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa a anotação @Min para definir min
     */
    private void processMinAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            Long value = (Long) valueMethod.invoke(annotation);
            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation);

            OpenApiUiUtils.populateUiMinimum(uiExtension, value, !message.startsWith("{") ? message : null);
        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa a anotação @Max para definir max
     */
    private void processMaxAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            Long value = (Long) valueMethod.invoke(annotation);
            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation);

            OpenApiUiUtils.populateUiMaximum(uiExtension, value, !message.startsWith("{") ? message : null);
        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa a anotação @DecimalMin
     */
    private void processDecimalMinAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            String stringValue = (String) valueMethod.invoke(annotation);
            java.math.BigDecimal value = new java.math.BigDecimal(stringValue);
            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation);

            OpenApiUiUtils.populateUiMinimum(uiExtension, value, !message.startsWith("{") ? message : null);
        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa a anotação @DecimalMax
     */
    private void processDecimalMaxAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            String stringValue = (String) valueMethod.invoke(annotation);
            java.math.BigDecimal value = new java.math.BigDecimal(stringValue);
            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation);

            OpenApiUiUtils.populateUiMaximum(uiExtension, value, !message.startsWith("{") ? message : null);
        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa a anotação @Pattern
     */
    private void processPatternAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method regexpMethod = annotation.annotationType().getMethod("regexp");
            String patternValue = (String) regexpMethod.invoke(annotation);
            Method messageMethod = annotation.annotationType().getMethod("message");
            String message = (String) messageMethod.invoke(annotation);

            OpenApiUiUtils.populateUiPattern(uiExtension, patternValue, !message.startsWith("{") ? message : null);
        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    /**
     * Processa anotações temporais (Past, Future, etc)
     */
    private void processTemporal(Annotation annotation, String annotationType, Map<String, Object> uiExtension) {
        if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
            uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.DATE_PICKER.getValue());
        }

        // Configura validações adicionais com base no tipo de anotação
        switch (annotationType) {
            case "Past":
                if (!uiExtension.containsKey(ValidationProperties.MAX.getValue())) {
                    uiExtension.put(ValidationProperties.MAX.getValue(), "today");
                }
                break;
            case "Future":
                if (!uiExtension.containsKey(ValidationProperties.MIN.getValue())) {
                    uiExtension.put(ValidationProperties.MIN.getValue(), "tomorrow");
                }
                break;
            case "PastOrPresent":
                if (!uiExtension.containsKey(ValidationProperties.MAX.getValue())) {
                    uiExtension.put(ValidationProperties.MAX.getValue(), "today");
                }
                break;
            case "FutureOrPresent":
                if (!uiExtension.containsKey(ValidationProperties.MIN.getValue())) {
                    uiExtension.put(ValidationProperties.MIN.getValue(), "today");
                }
                break;
        }
    }

    /**
     * Processa anotações de positividade (@Positive, @PositiveOrZero)
     */
    private void processPositiveAnnotation(String annotationType, Map<String, Object> uiExtension) {
        if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
            uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.NUMERIC_TEXT_BOX.getValue());
        }

        if (!uiExtension.containsKey(ValidationProperties.MIN.getValue())) {
            String minValue = "PositiveOrZero".equals(annotationType) ? "0" : "0.000001";
            uiExtension.put(ValidationProperties.MIN.getValue(), minValue);

            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_MIN.getValue())) {
                uiExtension.put(FieldConfigProperties.NUMERIC_MIN.getValue(), minValue);
            }
        }
    }

    /**
     * Processa anotações de negatividade (@Negative, @NegativeOrZero)
     */
    private void processNegativeAnnotation(String annotationType, Map<String, Object> uiExtension) {
        if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
            uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.NUMERIC_TEXT_BOX.getValue());
        }

        if (!uiExtension.containsKey(ValidationProperties.MAX.getValue())) {
            String maxValue = "NegativeOrZero".equals(annotationType) ? "0" : "-0.000001";
            uiExtension.put(ValidationProperties.MAX.getValue(), maxValue);

            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_MAX.getValue())) {
                uiExtension.put(FieldConfigProperties.NUMERIC_MAX.getValue(), maxValue);
            }
        }
    }

    /**
     * Processa a anotação @Digits
     */
    private void processDigitsAnnotation(Annotation annotation, Map<String, Object> uiExtension) {
        try {
            Method integerMethod = annotation.annotationType().getMethod("integer");
            int integerDigits = (int) integerMethod.invoke(annotation);

            Method fractionMethod = annotation.annotationType().getMethod("fraction");
            int fractionDigits = (int) fractionMethod.invoke(annotation);

            // Configura formato numérico baseado nos dígitos inteiros e fracionários
            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_FORMAT.getValue())) {
                StringBuilder format = new StringBuilder();
                format.append("#");

                if (integerDigits > 1) {
                    format.append(",".repeat(integerDigits - 1));
                }

                if (fractionDigits > 0) {
                    format.append(".");
                    format.append("#".repeat(fractionDigits));
                }

                uiExtension.put(FieldConfigProperties.NUMERIC_FORMAT.getValue(), format.toString());
            }

            // Configura step para garantir precisão adequada (usar helper centralizado)
            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_STEP.getValue())) {
                String step = org.praxisplatform.uischema.util.OpenApiUiUtils.numericStepFromDigits(fractionDigits);
                uiExtension.put(FieldConfigProperties.NUMERIC_STEP.getValue(), step);
            }

        } catch (Exception e) {
            // Ignorar erros de reflexão
        }
    }

    // ============================================================================
    // NOVOS MÉTODOS PARA PRECEDÊNCIA CLARA
    // ============================================================================

    /**
     * ETAPA 1: Aplica apenas valores PADRÃO da anotação @UISchema
     * (não valores explicitamente definidos pelo desenvolvedor)
     */
    private void applyUISchemaDefaults(UISchema annotation, Map<String, Object> uiExtension) {
        // Aplicar apenas os valores padrão que são "genéricos"
        // Valores específicos serão aplicados na ETAPA 3
        
        // Não define controlType quando no modo AUTO (sentinela)
        // controlType explícito é tratado na ETAPA 3
        
        if (annotation.type() == FieldDataType.TEXT) {
            uiExtension.put(FieldConfigProperties.TYPE.getValue(), FieldDataType.TEXT.getValue());
        }
        
        // Outros valores padrão seguros
        if (annotation.iconPosition() == IconPosition.LEFT) {
            uiExtension.put(FieldConfigProperties.ICON_POSITION.getValue(), IconPosition.LEFT.getValue());
        }
        
        // Valores booleanos padrão
        if (annotation.sortable()) {
            uiExtension.put(FieldConfigProperties.SORTABLE.getValue(), true);
        }
        
        if (annotation.editable()) {
            uiExtension.put(FieldConfigProperties.EDITABLE.getValue(), true);
        }
    }

    /**
     * ETAPA 2: Detecção AUTOMÁTICA baseada no OpenAPI Schema
     * Esta é nossa lógica principal de detecção inteligente
     */
    private void applySchemaBasedDetection(
            Schema<?> property,
            Map<String, Object> uiExtension,
            String fieldName,
            Annotation[] annotations) {
        String openApiType = property.getType();
        String openApiFormat = property.getFormat();
        boolean hasEnum = property.getEnum() != null && !property.getEnum().isEmpty();
        
        if (openApiType == null) return;
        
        // Determinar controlType e dataType baseado no schema
        String detectedControlType = null;
        String detectedDataType = null;
        
        switch (openApiType) {
            case "string":
                detectedDataType = FieldDataType.TEXT.getValue();
                detectedControlType = FieldControlType.INPUT.getValue();
                
                if (hasEnum) {
                    int count = property.getEnum().size();
                    detectedControlType = OpenApiUiUtils.determineEnumControlBySize(count);
                } else if (openApiFormat != null) {
                    switch (openApiFormat) {
                        case "date":
                            detectedControlType = FieldControlType.DATE_PICKER.getValue();
                            detectedDataType = FieldDataType.DATE.getValue();
                            break;
                        case "date-time":
                            detectedControlType = FieldControlType.DATE_TIME_PICKER.getValue();
                            detectedDataType = FieldDataType.DATE.getValue();
                            break;
                        case "time":
                            detectedControlType = FieldControlType.TIME_PICKER.getValue();
                            detectedDataType = FieldDataType.DATE.getValue();
                            break;
                        case "email":
                            detectedControlType = FieldControlType.EMAIL_INPUT.getValue();
                            detectedDataType = FieldDataType.EMAIL.getValue();
                            break;
                        case "password":
                            detectedControlType = FieldControlType.PASSWORD.getValue();
                            detectedDataType = FieldDataType.PASSWORD.getValue();
                            break;
                        case "uri":
                        case "url":
                            detectedControlType = FieldControlType.URL_INPUT.getValue();
                            detectedDataType = FieldDataType.URL.getValue();
                            break;
                        case "binary":
                        case "byte":
                            detectedControlType = FieldControlType.FILE_UPLOAD.getValue();
                            detectedDataType = FieldDataType.FILE.getValue();
                            break;
                        case "color":
                            detectedControlType = FieldControlType.COLOR_PICKER.getValue();
                            break;
                        case "phone":
                            detectedControlType = FieldControlType.PHONE.getValue();
                            break;
                        case "json":
                            detectedDataType = FieldDataType.JSON.getValue();
                            break;
                    }
                }
                
                // Verificar maxLength para textarea (threshold ajustado)
                Integer maxLength = property.getMaxLength();
                if (maxLength != null && maxLength > 300 &&
                    FieldControlType.INPUT.getValue().equals(detectedControlType)) {
                    detectedControlType = FieldControlType.TEXTAREA.getValue();
                }
                break;
                
            case "number":
            case "integer":
                detectedDataType = FieldDataType.NUMBER.getValue();
                detectedControlType = FieldControlType.NUMERIC_TEXT_BOX.getValue();
                
                if (hasEnum) {
                    detectedControlType = FieldControlType.SELECT.getValue();
                } else if ("currency".equals(openApiFormat)) {
                    detectedControlType = FieldControlType.CURRENCY_INPUT.getValue();
                }
                break;
                
            case "boolean":
                detectedDataType = FieldDataType.BOOLEAN.getValue();
                // Evitar SELECT por padrão. Se enum binário estiver presente, preferir RADIO; caso contrário, CHECKBOX.
                if (hasEnum) {
                    int size = property.getEnum().size();
                    detectedControlType = (size == 2) ? FieldControlType.RADIO.getValue() : FieldControlType.CHECKBOX.getValue();
                } else {
                    detectedControlType = FieldControlType.CHECKBOX.getValue();
                }
                break;
                
            case "array":
                if (property.getItems() != null && property.getItems().getEnum() != null &&
                    !property.getItems().getEnum().isEmpty()) {
                    int c = property.getItems().getEnum().size();
                    detectedControlType = OpenApiUiUtils.determineArrayEnumControlBySize(c);
                    if (c > 5) {
                        // Em listas maiores, indicar controle adequado para filtros
                        uiExtension.putIfAbsent(FieldConfigProperties.FILTER_CONTROL_TYPE.getValue(), "multiColumnComboBox");
                    }
                } else {
                    detectedControlType = FieldControlType.ARRAY_INPUT.getValue();
                }
                break;
                
            case "object":
                detectedControlType = FieldControlType.EXPANSION_PANEL.getValue();
                break;
        }

        // Extra detection based on @Filterable annotation for array fields
        Filterable filterable = ResolverUtils.getAnnotation(Filterable.class, annotations);
        if (filterable != null
                && filterable.operation() == Filterable.FilterOperation.BETWEEN
                && "array".equals(openApiType)
                && property.getItems() != null) {
            String itemType = property.getItems().getType();
            String itemFormat = property.getItems().getFormat();
            if ("string".equals(itemType)) {
                if ("date".equals(itemFormat)) {
                    detectedControlType = FieldControlType.DATE_RANGE.getValue();
                } else if ("date-time".equals(itemFormat)) {
                    detectedControlType = FieldControlType.DATE_TIME_RANGE.getValue();                    
                }
            }
        }
        
        // Aplicar detecções (sobrescreve valores padrão)
        if (detectedControlType != null) {
            uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), detectedControlType);
        }
        
        if (detectedDataType != null) {
            uiExtension.put(FieldConfigProperties.TYPE.getValue(), detectedDataType);
        }
        
        // Detecção inteligente por nome do campo (precedência sobre INPUT/TEXTAREA gerados por schema)
        String smartControlType = OpenApiUiUtils.determineSmartControlTypeByFieldName(fieldName);
        if (smartControlType != null) {
            Object current = uiExtension.get(FieldConfigProperties.CONTROL_TYPE.getValue());
            if (current == null || FieldControlType.INPUT.getValue().equals(current) || FieldControlType.TEXTAREA.getValue().equals(current)) {
                uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), smartControlType);
            }
        }
        
        // Aplicar outras propriedades do schema
        OpenApiUiUtils.populateUiName(uiExtension, fieldName);
        OpenApiUiUtils.populateUiLabel(uiExtension, property.getTitle(), fieldName);
        OpenApiUiUtils.populateUiPlaceholder(uiExtension, property.getTitle());
        OpenApiUiUtils.populateUiHelpText(uiExtension, property.getDescription());
        OpenApiUiUtils.populateUiDefaultValue(uiExtension, property.getExample());
        OpenApiUiUtils.populateUiReadOnly(uiExtension, property.getReadOnly());
        OpenApiUiUtils.populateUiMinLength(uiExtension, property.getMinLength(), null);
        OpenApiUiUtils.populateUiMaxLength(uiExtension, property.getMaxLength(), null);
        OpenApiUiUtils.populateUiMinimum(uiExtension, property.getMinimum(), null);
        OpenApiUiUtils.populateUiMaximum(uiExtension, property.getMaximum(), null);
        OpenApiUiUtils.populateUiPattern(uiExtension, property.getPattern(), null);
        OpenApiUiUtils.populateUiOptionsFromEnum(uiExtension, property.getEnum(), this._mapper);
        OpenApiUiUtils.populateUiRequired(uiExtension, property.getRequired() != null && !property.getRequired().isEmpty());
        
        // Adicionar NUMERIC_FORMAT e defaults se o formato for "percent"
        if ("number".equals(openApiType) && "percent".equals(openApiFormat)) {
            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_FORMAT.getValue())) {
                uiExtension.put(FieldConfigProperties.NUMERIC_FORMAT.getValue(), NumberFormatStyle.PERCENT.getValue());
            }
            OpenApiUiUtils.applyPercentDefaults(uiExtension);
        }
    }

    /**
     * ETAPA 3: Aplica valores EXPLÍCITOS da anotação @UISchema
     * (valores definidos explicitamente pelo desenvolvedor)
     */
    private void applyUISchemaExplicitValues(Schema<?> property, UISchema annotation, Map<String, Object> uiExtension) {
        // Processar apenas valores NÃO padrão (explicitamente definidos)
        
        // ControlType explícito (diferente do padrão)
        if (annotation.controlType() != FieldControlType.AUTO) {
            uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), annotation.controlType().getValue());
        }
        
        // DataType explícito (diferente do padrão)
        if (annotation.type() != FieldDataType.TEXT) {
            uiExtension.put(FieldConfigProperties.TYPE.getValue(), annotation.type().getValue());
        }
        
        // Strings não vazias
        if (!annotation.name().isEmpty()) {
            uiExtension.put(FieldConfigProperties.NAME.getValue(), annotation.name());
        }
        if (!annotation.label().isEmpty()) {
            uiExtension.put(FieldConfigProperties.LABEL.getValue(), annotation.label());
        }
        if (!annotation.description().isEmpty()) {
            // Expõe também no x-ui, além do Schema description
            uiExtension.put(FieldConfigProperties.DESCRIPTION.getValue(), annotation.description());
            try { if (property.getDescription() == null || property.getDescription().isEmpty()) property.setDescription(annotation.description()); } catch (Exception ignored) {}
        }
        if (!annotation.example().isEmpty()) {
            try { if (property.getExample() == null) property.setExample(annotation.example()); } catch (Exception ignored) {}
        }
        if (!annotation.placeholder().isEmpty()) {
            uiExtension.put(FieldConfigProperties.PLACEHOLDER.getValue(), annotation.placeholder());
        }
        if (!annotation.defaultValue().isEmpty()) {
            uiExtension.put(FieldConfigProperties.DEFAULT_VALUE.getValue(), annotation.defaultValue());
        }
        if (!annotation.group().isEmpty()) {
            uiExtension.put(FieldConfigProperties.GROUP.getValue(), annotation.group());
        }
        if (!annotation.width().isEmpty()) {
            uiExtension.put(FieldConfigProperties.WIDTH.getValue(), annotation.width());
        }
        if (annotation.isFlex()) {
            uiExtension.put(FieldConfigProperties.IS_FLEX.getValue(), true);
        }
        if (!annotation.displayOrientation().isEmpty()) {
            uiExtension.put(FieldConfigProperties.DISPLAY_ORIENTATION.getValue(), annotation.displayOrientation());
        }
        if (!annotation.icon().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON.getValue(), annotation.icon());
        }
        if (!annotation.iconSize().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON_SIZE.getValue(), annotation.iconSize());
        }
        if (!annotation.iconColor().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON_COLOR.getValue(), annotation.iconColor());
        }
        if (!annotation.iconClass().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON_CLASS.getValue(), annotation.iconClass());
        }
        if (!annotation.iconStyle().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON_STYLE.getValue(), annotation.iconStyle());
        }
        if (!annotation.iconFontSize().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ICON_FONT_SIZE.getValue(), annotation.iconFontSize());
        }
        if (!annotation.helpText().isEmpty()) {
            uiExtension.put(FieldConfigProperties.HELP_TEXT.getValue(), annotation.helpText());
        }
        if (!annotation.hint().isEmpty()) {
            uiExtension.put(FieldConfigProperties.HINT.getValue(), annotation.hint());
        }
        if (!annotation.hiddenCondition().isEmpty()) {
            uiExtension.put(FieldConfigProperties.HIDDEN_CONDITION.getValue(), annotation.hiddenCondition());
        }
        if (!annotation.tooltipOnHover().isEmpty()) {
            uiExtension.put(FieldConfigProperties.TOOLTIP_ON_HOVER.getValue(), annotation.tooltipOnHover());
        }
        if (!annotation.valueField().isEmpty()) {
            uiExtension.put(FieldConfigProperties.VALUE_FIELD.getValue(), annotation.valueField());
        }
        if (!annotation.displayField().isEmpty()) {
            uiExtension.put(FieldConfigProperties.DISPLAY_FIELD.getValue(), annotation.displayField());
        }
        if (!annotation.endpoint().isEmpty()) {
            uiExtension.put(FieldConfigProperties.ENDPOINT.getValue(), annotation.endpoint());
        }
        if (!annotation.emptyOptionText().isEmpty()) {
            uiExtension.put(FieldConfigProperties.EMPTY_OPTION_TEXT.getValue(), annotation.emptyOptionText());
        }
        if (!annotation.options().isEmpty()) {
            OpenApiUiUtils.populateUiOptionsFromString(uiExtension, annotation.options(), this._mapper);
        }
        if (!annotation.filter().isEmpty()) {
            uiExtension.put(FieldConfigProperties.FILTER.getValue(), annotation.filter());
        }
        if (!annotation.filterOptions().isEmpty()) {
            // Sem parser dedicado, manter string bruta (frontend pode interpretar)
            uiExtension.put(FieldConfigProperties.FILTER_OPTIONS.getValue(), annotation.filterOptions());
        }
        if (!annotation.filterControlType().isEmpty()) {
            uiExtension.put(FieldConfigProperties.FILTER_CONTROL_TYPE.getValue(), annotation.filterControlType());
        }
        
        // Inteiros não zero
        if (annotation.order() != 0) {
            uiExtension.put(FieldConfigProperties.ORDER.getValue(), String.valueOf(annotation.order()));
        }
        if (annotation.debounceTime() != 0) {
            uiExtension.put(FieldConfigProperties.DEBOUNCE_TIME.getValue(), String.valueOf(annotation.debounceTime()));
        }
        if (annotation.minLength() != 0) {
            uiExtension.put(ValidationProperties.MIN_LENGTH.getValue(), String.valueOf(annotation.minLength()));
        }
        if (annotation.maxLength() != 0) {
            uiExtension.put(ValidationProperties.MAX_LENGTH.getValue(), String.valueOf(annotation.maxLength()));
        }
        
        // Booleanos explícitos (diferentes do padrão)
        if (annotation.disabled()) {
            uiExtension.put(FieldConfigProperties.DISABLED.getValue(), true);
        }
        if (annotation.readOnly()) {
            uiExtension.put(FieldConfigProperties.READ_ONLY.getValue(), true);
        }
        if (annotation.hidden()) {
            uiExtension.put(FieldConfigProperties.HIDDEN.getValue(), true);
        }
        // Context-specific visibility flags
        if (annotation.tableHidden()) {
            uiExtension.put(FieldConfigProperties.TABLE_HIDDEN.getValue(), true);
        }
        if (annotation.formHidden()) {
            uiExtension.put(FieldConfigProperties.FORM_HIDDEN.getValue(), true);
        }
        if (!annotation.editable()) { // padrão é true
            uiExtension.put(FieldConfigProperties.EDITABLE.getValue(), false);
        }
        if (!annotation.sortable()) { // padrão é true
            uiExtension.put(FieldConfigProperties.SORTABLE.getValue(), false);
        }
        if (annotation.multiple()) {
            uiExtension.put(FieldConfigProperties.MULTIPLE.getValue(), true);
        }
        if (annotation.filterable()) { // padrão é false
            uiExtension.put(FieldConfigProperties.FILTERABLE.getValue(), true);
        }
        if (annotation.inlineEditing()) {
            uiExtension.put(FieldConfigProperties.INLINE_EDITING.getValue(), true);
        }
        if (!annotation.validationMode().isEmpty()) {
            uiExtension.put(FieldConfigProperties.VALIDATION_MODE.getValue(), annotation.validationMode());
        }
        if (annotation.unique()) {
            uiExtension.put(FieldConfigProperties.UNIQUE.getValue(), true);
        }
        if (!annotation.mask().isEmpty()) {
            uiExtension.put(FieldConfigProperties.MASK.getValue(), annotation.mask());
        }
        if (!annotation.conditionalRequired().isEmpty()) {
            // Grava nos dois espaços para compatibilidade (top-level e validation)
            uiExtension.put(FieldConfigProperties.CONDITIONAL_REQUIRED.getValue(), annotation.conditionalRequired());
            uiExtension.put(ValidationProperties.CONDITIONAL_REQUIRED.getValue(), annotation.conditionalRequired());
        }
        if (!annotation.viewOnlyStyle().isEmpty()) {
            uiExtension.put(FieldConfigProperties.VIEW_ONLY_STYLE.getValue(), annotation.viewOnlyStyle());
        }
        if (!annotation.validationTriggers().isEmpty()) {
            uiExtension.put(FieldConfigProperties.VALIDATION_TRIGGERS.getValue(), annotation.validationTriggers());
        }
        if (!annotation.conditionalDisplay().isEmpty()) {
            uiExtension.put(FieldConfigProperties.CONDITIONAL_DISPLAY.getValue(), annotation.conditionalDisplay());
        }
        if (!annotation.dependentField().isEmpty()) {
            uiExtension.put(FieldConfigProperties.DEPENDENT_FIELD.getValue(), annotation.dependentField());
        }
        if (annotation.resetOnDependentChange()) {
            uiExtension.put(FieldConfigProperties.RESET_ON_DEPENDENT_CHANGE.getValue(), true);
        }
        if (!annotation.transformValueFunction().isEmpty()) {
            uiExtension.put(FieldConfigProperties.TRANSFORM_VALUE_FUNCTION.getValue(), annotation.transformValueFunction());
        }
        if (annotation.required()) {
            uiExtension.put(ValidationProperties.REQUIRED.getValue(), true);
        }
        // Mensagens explícitas de validação (bloqueiam as default)
        if (!annotation.requiredMessage().isEmpty()) {
            uiExtension.put(ValidationProperties.REQUIRED_MESSAGE.getValue(), annotation.requiredMessage());
        }
        if (!annotation.minLengthMessage().isEmpty()) {
            uiExtension.put(ValidationProperties.MIN_LENGTH_MESSAGE.getValue(), annotation.minLengthMessage());
        }
        if (!annotation.maxLengthMessage().isEmpty()) {
            uiExtension.put(ValidationProperties.MAX_LENGTH_MESSAGE.getValue(), annotation.maxLengthMessage());
        }
        
        // Pattern explícito (diferente do padrão CUSTOM)
        if (annotation.pattern() != ValidationPattern.CUSTOM) {
            String patternValue = annotation.pattern().getPattern();
            if (patternValue != null && !patternValue.isEmpty()) {
                uiExtension.put(ValidationProperties.PATTERN.getValue(), patternValue);
            }
        }
        if (!annotation.patternMessage().isEmpty()) {
            uiExtension.put(ValidationProperties.PATTERN_MESSAGE.getValue(), annotation.patternMessage());
        }
        if (!annotation.rangeMessage().isEmpty()) {
            uiExtension.put(ValidationProperties.RANGE_MESSAGE.getValue(), annotation.rangeMessage());
        }
        if (!annotation.customValidator().isEmpty()) {
            uiExtension.put(ValidationProperties.CUSTOM_VALIDATOR.getValue(), annotation.customValidator());
        }
        if (!annotation.asyncValidator().isEmpty()) {
            uiExtension.put(ValidationProperties.ASYNC_VALIDATOR.getValue(), annotation.asyncValidator());
        }
        if (annotation.minWords() > 0) {
            uiExtension.put(ValidationProperties.MIN_WORDS.getValue(), String.valueOf(annotation.minWords()));
        }
        // min/max explícitos da anotação
        if (!annotation.min().isEmpty()) {
            uiExtension.put(ValidationProperties.MIN.getValue(), annotation.min());
            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_MIN.getValue())) {
                uiExtension.put(FieldConfigProperties.NUMERIC_MIN.getValue(), annotation.min());
            }
        }
        if (!annotation.max().isEmpty()) {
            uiExtension.put(ValidationProperties.MAX.getValue(), annotation.max());
            if (!uiExtension.containsKey(FieldConfigProperties.NUMERIC_MAX.getValue())) {
                uiExtension.put(FieldConfigProperties.NUMERIC_MAX.getValue(), annotation.max());
            }
        }
        
        // IconPosition explícito (diferente do padrão LEFT)
        if (annotation.iconPosition() != IconPosition.LEFT) {
            uiExtension.put(FieldConfigProperties.ICON_POSITION.getValue(), annotation.iconPosition().getValue());
        }
        
        // NumericFormat explícito (diferente do padrão INTEGER)
        if (annotation.numericFormat() != NumericFormat.INTEGER) {
            uiExtension.put(FieldConfigProperties.NUMERIC_FORMAT.getValue(), annotation.numericFormat().getValue());
        }
        if (!annotation.numericStep().isEmpty()) {
            uiExtension.put(FieldConfigProperties.NUMERIC_STEP.getValue(), annotation.numericStep());
        }
        if (!annotation.numericMin().isEmpty()) {
            uiExtension.put(FieldConfigProperties.NUMERIC_MIN.getValue(), annotation.numericMin());
        }
        if (!annotation.numericMax().isEmpty()) {
            uiExtension.put(FieldConfigProperties.NUMERIC_MAX.getValue(), annotation.numericMax());
        }
        if (!annotation.numericMaxLength().isEmpty()) {
            uiExtension.put(FieldConfigProperties.NUMERIC_MAX_LENGTH.getValue(), annotation.numericMaxLength());
        }

        // Arquivos: tipos permitidos e tamanho máximo
        if (annotation.allowedFileTypes() != null && annotation.allowedFileTypes() != AllowedFileTypes.ALL) {
            uiExtension.put(ValidationProperties.ALLOWED_FILE_TYPES.getValue(), annotation.allowedFileTypes().getValue());
        }
        if (!annotation.maxFileSize().isEmpty()) {
            // Tentar parsear para Long; se falhar, armazenar como string
            try {
                Long size = Long.parseLong(annotation.maxFileSize());
                uiExtension.put(ValidationProperties.MAX_FILE_SIZE.getValue(), size);
            } catch (NumberFormatException nfe) {
                uiExtension.put(ValidationProperties.MAX_FILE_SIZE.getValue(), annotation.maxFileSize());
            }
        }
    }

    /**
     * ETAPA 4: Processa anotações Jakarta Validation
     */
    private void processJakartaValidationAnnotations(Schema<?> property, Annotation[] annotations, Map<String, Object> uiExtension) {
        // Reutilizar a lógica existente do processStandardAnnotations
        for (Annotation annotation : annotations) {
            String annotationType = annotation.annotationType().getSimpleName();

            switch (annotationType) {
                case "NotNull":
                case "NotEmpty":
                case "NotBlank":
                    OpenApiUiUtils.populateUiRequired(uiExtension, true);
                    break;

                case "Size":
                    processSizeAnnotation(annotation, uiExtension);
                    break;

                case "Min":
                    processMinAnnotation(annotation, uiExtension);
                    break;

                case "Max":
                    processMaxAnnotation(annotation, uiExtension);
                    break;

                case "DecimalMin":
                    processDecimalMinAnnotation(annotation, uiExtension);
                    break;

                case "DecimalMax":
                    processDecimalMaxAnnotation(annotation, uiExtension);
                    break;

                case "Pattern":
                    processPatternAnnotation(annotation, uiExtension);
                    break;

                case "Email":
                    if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
                        uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.EMAIL_INPUT.getValue());
                    }
                    break;

                case "AssertTrue":
                case "AssertFalse":
                    if (!uiExtension.containsKey(FieldConfigProperties.CONTROL_TYPE.getValue())) {
                        uiExtension.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.CHECKBOX.getValue());
                    }
                    break;

                case "Past":
                case "PastOrPresent":
                case "Future":
                case "FutureOrPresent":
                    processTemporal(annotation, annotationType, uiExtension);
                    break;

                case "Positive":
                case "PositiveOrZero":
                    processPositiveAnnotation(annotationType, uiExtension);
                    break;

                case "Negative":
                case "NegativeOrZero":
                    processNegativeAnnotation(annotationType, uiExtension);
                    break;

                case "Digits":
                    processDigitsAnnotation(annotation, uiExtension);
                    break;
            }
        }
    }

    /**
     * ETAPA 5: Aplica extraProperties (precedência MÁXIMA)
     */
    private void applyExtraProperties(UISchema annotation, Map<String, Object> uiExtension) {
        if (annotation.extraProperties() != null && annotation.extraProperties().length > 0) {
            for (ExtensionProperty p : annotation.extraProperties()) {
                // extraProperties sobrescreve TUDO (precedência máxima)
                uiExtension.put(p.name(), p.value());
            }
        }
    }

}
