package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.praxisplatform.uischema.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.numeric.NumberFormatStyle;
import org.praxisplatform.uischema.util.OpenApiUiUtils;
import org.praxisplatform.uischema.filter.annotation.Filterable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolver OpenAPI que enriquece schemas com a extensao {@code x-ui}.
 *
 * <p>
 * Esta e uma das classes mais centrais do starter: ela transforma anotacoes Java, tipos OpenAPI,
 * validacoes Jakarta e heuristicas de renderizacao em metadados estruturados que alimentam o
 * contrato metadata-driven consumido por {@code /schemas/filtered} e pelos frontends Praxis.
 * </p>
 *
 * <p>
 * A precedencia aplicada segue a semantica canônica da plataforma: defaults da anotacao
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema}, deteccao heuristica baseada
 * no schema, overrides explicitos da anotacao, validacoes Jakarta e por fim {@code extraProperties}.
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

        // === ETAPA 4.5: Semântica canônica de apresentação de valor ===
        // Publica `x-ui.valuePresentation` a partir do contrato efetivo resolvido.
        applyValuePresentation(property, annotation, uiExtension);

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
                                    schemaBasedControlType = FieldControlType.COLOR_INPUT.getValue();
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
                            schemaBasedControlType = FieldControlType.CHIP_INPUT.getValue();
                        }
                        break;
                        
                    case "object":
                        schemaBasedControlType = null;
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
                            detectedControlType = FieldControlType.COLOR_INPUT.getValue();
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
                } else {
                    detectedControlType = FieldControlType.CHIP_INPUT.getValue();
                }
                break;
                
            case "object":
                detectedControlType = null;
                break;
        }

        // Extra detection based on @Filterable annotation for array fields
        Filterable filterable = ResolverUtils.getAnnotation(Filterable.class, annotations);
        if (filterable != null
                && isRangeOperation(filterable.operation())
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
            } else if (isNumericItemType(itemType)) {
                boolean monetaryRange = isMonetaryRange(fieldName, itemFormat, annotations);
                boolean percentRange = !monetaryRange && isPercentRange(itemFormat, annotations);
                detectedControlType = monetaryRange
                        ? FieldControlType.PRICE_RANGE.getValue()
                        : FieldControlType.RANGE_SLIDER.getValue();
                if (!monetaryRange) {
                    // Numeric range filters should default to dual-thumb mode in consumers.
                    uiExtension.putIfAbsent("mode", "range");
                }
                if (percentRange) {
                    uiExtension.putIfAbsent(
                            FieldConfigProperties.NUMERIC_FORMAT.getValue(),
                            NumberFormatStyle.PERCENT.getValue()
                    );
                    OpenApiUiUtils.applyPercentDefaults(uiExtension);
                }
            }
            applyRangeOneOfContract(property, fieldName, itemType, itemFormat, annotations, filterable.operation());
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

    private boolean isNumericItemType(String itemType) {
        if (itemType == null) return false;
        String normalized = itemType.toLowerCase(Locale.ROOT);
        return "number".equals(normalized) || "integer".equals(normalized);
    }

    private void applyRangeOneOfContract(
            Schema<?> property,
            String fieldName,
            String itemType,
            String itemFormat,
            Annotation[] annotations,
            Filterable.FilterOperation operation
    ) {
        if (property == null || property.getItems() == null) {
            return;
        }
        if (property.getOneOf() != null && !property.getOneOf().isEmpty()) {
            return;
        }

        String normalizedItemType = itemType == null ? "" : itemType.toLowerCase(Locale.ROOT);
        boolean isDateLike = "string".equals(normalizedItemType)
                && ("date".equals(itemFormat) || "date-time".equals(itemFormat));
        boolean isNumeric = isNumericItemType(normalizedItemType);
        if (!isDateLike && !isNumeric) {
            return;
        }

        boolean requireTwoBounds = operation == Filterable.FilterOperation.BETWEEN_EXCLUSIVE;

        ArraySchema arrayVariant = new ArraySchema();
        Schema<?> arrayItem = copyRangeItemSchema(property.getItems());
        applyArrayRangeBoundContract(arrayVariant, arrayItem, requireTwoBounds);

        ObjectSchema objectVariant = new ObjectSchema();
        Map<String, Schema> properties = new LinkedHashMap<>();
        Schema<?> lowerBoundSchema;
        Schema<?> upperBoundSchema;
        String lowerBoundName;
        String upperBoundName;

        if (isDateLike) {
            StringSchema lower = new StringSchema();
            lower.setFormat(itemFormat);
            StringSchema upper = new StringSchema();
            upper.setFormat(itemFormat);
            markRangeBoundNullable(lower, !requireTwoBounds);
            markRangeBoundNullable(upper, !requireTwoBounds);
            lowerBoundName = "startDate";
            upperBoundName = "endDate";
            lowerBoundSchema = lower;
            upperBoundSchema = upper;
            properties.put(lowerBoundName, lower);
            properties.put(upperBoundName, upper);
        } else {
            boolean monetary = isMonetaryRange(fieldName, itemFormat, annotations);
            Schema<?> lower = copyRangeItemSchema(property.getItems());
            Schema<?> upper = copyRangeItemSchema(property.getItems());
            markRangeBoundNullable(lower, !requireTwoBounds);
            markRangeBoundNullable(upper, !requireTwoBounds);
            if (monetary) {
                lowerBoundName = "minPrice";
                upperBoundName = "maxPrice";
                properties.put(lowerBoundName, lower);
                properties.put(upperBoundName, upper);
                properties.put("currency", new StringSchema());
            } else {
                lowerBoundName = "min";
                upperBoundName = "max";
                properties.put(lowerBoundName, lower);
                properties.put(upperBoundName, upper);
            }
            lowerBoundSchema = lower;
            upperBoundSchema = upper;
        }

        objectVariant.setProperties(properties);
        applyObjectRangeBoundContract(
                objectVariant,
                lowerBoundName,
                lowerBoundSchema,
                upperBoundName,
                upperBoundSchema,
                requireTwoBounds
        );

        List<Schema> oneOf = new ArrayList<>(2);
        oneOf.add(arrayVariant);
        oneOf.add(objectVariant);
        property.setOneOf(oneOf);
        // Remove tipo base fixo para que o oneOf permita array e objeto no mesmo contrato.
        property.setType(null);
        property.setFormat(null);
        property.setItems(null);
    }

    private Schema<?> copyRangeItemSchema(Schema<?> source) {
        if (source == null) {
            return new Schema<>();
        }
        Schema<?> copy = new Schema<>();
        copy.setType(source.getType());
        copy.setFormat(source.getFormat());
        copy.setPattern(source.getPattern());
        copy.setMinimum(source.getMinimum());
        copy.setMaximum(source.getMaximum());
        copy.setNullable(source.getNullable());
        copy.setExample(source.getExample());
        return copy;
    }

    private void markRangeBoundNullable(Schema<?> schema, boolean allowNull) {
        if (schema == null) {
            return;
        }
        schema.setNullable(allowNull);
    }

    private void applyArrayRangeBoundContract(
            ArraySchema arrayVariant,
            Schema<?> arrayItem,
            boolean requireTwoBounds
    ) {
        if (arrayVariant == null || arrayItem == null) {
            return;
        }

        if (requireTwoBounds) {
            Schema<?> strictItem = copyRangeItemSchema(arrayItem);
            markRangeBoundNullable(strictItem, false);
            arrayVariant.setItems(strictItem);
            arrayVariant.setMinItems(2);
            arrayVariant.setMaxItems(2);
            return;
        }

        Schema<?> nullableItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(nullableItem, true);
        arrayVariant.setItems(nullableItem);
        arrayVariant.setMinItems(1);
        arrayVariant.setMaxItems(2);

        ArraySchema oneNonNullBound = new ArraySchema();
        Schema<?> oneNonNullItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(oneNonNullItem, false);
        oneNonNullBound.setItems(oneNonNullItem);
        oneNonNullBound.setMinItems(1);
        oneNonNullBound.setMaxItems(1);

        ArraySchema twoBounds = new ArraySchema();
        Schema<?> twoBoundsItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(twoBoundsItem, true);
        twoBounds.setItems(twoBoundsItem);
        twoBounds.setMinItems(2);
        twoBounds.setMaxItems(2);

        ArraySchema allNullTwoBounds = new ArraySchema();
        Schema<?> allNullItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(allNullItem, true);
        allNullTwoBounds.setItems(allNullItem);
        allNullTwoBounds.setMinItems(2);
        allNullTwoBounds.setMaxItems(2);
        allNullTwoBounds.setEnum(List.of(Arrays.asList((Object) null, null)));

        arrayVariant.setAnyOf(List.of(oneNonNullBound, twoBounds));
        arrayVariant.setNot(allNullTwoBounds);
    }

    private void applyObjectRangeBoundContract(
            ObjectSchema objectVariant,
            String lowerBoundName,
            Schema<?> lowerBoundSchema,
            String upperBoundName,
            Schema<?> upperBoundSchema,
            boolean requireTwoBounds
    ) {
        if (objectVariant == null) {
            return;
        }

        if (requireTwoBounds) {
            objectVariant.setRequired(List.of(lowerBoundName, upperBoundName));
            objectVariant.setMinProperties(2);
            return;
        }

        objectVariant.setMinProperties(1);

        ObjectSchema requiresLower = new ObjectSchema();
        Map<String, Schema> lowerProperties = new LinkedHashMap<>();
        Schema<?> nonNullLower = copyRangeItemSchema(lowerBoundSchema);
        markRangeBoundNullable(nonNullLower, false);
        lowerProperties.put(lowerBoundName, nonNullLower);
        requiresLower.setProperties(lowerProperties);
        requiresLower.setRequired(List.of(lowerBoundName));

        ObjectSchema requiresUpper = new ObjectSchema();
        Map<String, Schema> upperProperties = new LinkedHashMap<>();
        Schema<?> nonNullUpper = copyRangeItemSchema(upperBoundSchema);
        markRangeBoundNullable(nonNullUpper, false);
        upperProperties.put(upperBoundName, nonNullUpper);
        requiresUpper.setProperties(upperProperties);
        requiresUpper.setRequired(List.of(upperBoundName));

        objectVariant.setAnyOf(List.of(requiresLower, requiresUpper));
    }

    private boolean isRangeOperation(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.BETWEEN
                || operation == Filterable.FilterOperation.BETWEEN_EXCLUSIVE
                || operation == Filterable.FilterOperation.NOT_BETWEEN
                || operation == Filterable.FilterOperation.OUTSIDE_RANGE;
    }

    private boolean isMonetaryRange(String fieldName, String itemFormat, Annotation[] annotations) {
        if ("currency".equalsIgnoreCase(itemFormat)) {
            return true;
        }
        UISchema uiSchema = ResolverUtils.getAnnotation(UISchema.class, annotations);
        if (uiSchema != null && uiSchema.numericFormat() == NumericFormat.CURRENCY) {
            return true;
        }
        String smart = OpenApiUiUtils.determineSmartControlTypeByFieldName(fieldName);
        return FieldControlType.CURRENCY_INPUT.getValue().equals(smart);
    }

    private boolean isPercentRange(String itemFormat, Annotation[] annotations) {
        if ("percent".equalsIgnoreCase(itemFormat)) {
            return true;
        }
        UISchema uiSchema = ResolverUtils.getAnnotation(UISchema.class, annotations);
        return uiSchema != null && uiSchema.numericFormat() == NumericFormat.PERCENT;
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

    private void applyValuePresentation(
            Schema<?> property,
            UISchema annotation,
            Map<String, Object> uiExtension
    ) {
        if (uiExtension.containsKey(FieldConfigProperties.VALUE_PRESENTATION.getValue())) {
            return;
        }

        String resolvedType = inferValuePresentationType(property, annotation, uiExtension);
        if (resolvedType == null || resolvedType.isBlank()) {
            return;
        }

        Map<String, Object> valuePresentation = new LinkedHashMap<>();
        valuePresentation.put(FieldConfigProperties.TYPE.getValue(), resolvedType);
        uiExtension.put(FieldConfigProperties.VALUE_PRESENTATION.getValue(), valuePresentation);
    }

    private String inferValuePresentationType(
            Schema<?> property,
            UISchema annotation,
            Map<String, Object> uiExtension
    ) {
        String controlType = asTrimmedString(uiExtension.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        if (controlType != null && !isScalarPresentationControlType(controlType)) {
            return null;
        }

        String openApiType = property != null ? asTrimmedString(property.getType()) : null;
        if ("array".equalsIgnoreCase(openApiType) || "object".equalsIgnoreCase(openApiType)) {
            return null;
        }

        String numericFormat = asTrimmedString(uiExtension.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
        if ("percent".equalsIgnoreCase(numericFormat)) {
            return "percentage";
        }
        if ("currency".equalsIgnoreCase(numericFormat)) {
            return "currency";
        }

        if (isCurrencyControlType(controlType)) {
            return "currency";
        }
        if (isDateTimeControlType(controlType)) {
            return "datetime";
        }
        if (isDateControlType(controlType)) {
            return "date";
        }
        if (isTimeControlType(controlType)) {
            return "time";
        }

        String openApiFormat = property != null ? asTrimmedString(property.getFormat()) : null;
        if ("date-time".equalsIgnoreCase(openApiFormat)) {
            return "datetime";
        }
        if ("date".equalsIgnoreCase(openApiFormat)) {
            return "date";
        }
        if ("time".equalsIgnoreCase(openApiFormat)) {
            return "time";
        }
        if ("currency".equalsIgnoreCase(openApiFormat)) {
            return "currency";
        }
        if ("percent".equalsIgnoreCase(openApiFormat)) {
            return "percentage";
        }

        String dataType = asTrimmedString(uiExtension.get(FieldConfigProperties.TYPE.getValue()));
        if ("boolean".equalsIgnoreCase(dataType)) {
            return "boolean";
        }
        if ("number".equalsIgnoreCase(dataType)) {
            return "number";
        }
        if ("date".equalsIgnoreCase(dataType)) {
            return "date";
        }

        if ("boolean".equalsIgnoreCase(openApiType)) {
            return "boolean";
        }
        if ("number".equalsIgnoreCase(openApiType) || "integer".equalsIgnoreCase(openApiType)) {
            return "number";
        }

        if (annotation != null && annotation.numericFormat() == NumericFormat.PERCENT) {
            return "percentage";
        }
        if (annotation != null && annotation.numericFormat() == NumericFormat.CURRENCY) {
            return "currency";
        }

        return null;
    }

    private String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String token = String.valueOf(value).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean isCurrencyControlType(String controlType) {
        return FieldControlType.CURRENCY_INPUT.getValue().equals(controlType)
                || FieldControlType.PRICE_RANGE.getValue().equals(controlType);
    }

    private boolean isDateControlType(String controlType) {
        return FieldControlType.DATE_PICKER.getValue().equals(controlType)
                || "dateInput".equals(controlType)
                || "date".equals(controlType)
                || FieldControlType.DATE_RANGE.getValue().equals(controlType)
                || "month".equals(controlType)
                || "week".equals(controlType)
                || "year".equals(controlType);
    }

    private boolean isDateTimeControlType(String controlType) {
        return FieldControlType.DATE_TIME_PICKER.getValue().equals(controlType)
                || "dateTime".equals(controlType)
                || "dateTimeLocal".equals(controlType)
                || FieldControlType.DATE_TIME_RANGE.getValue().equals(controlType);
    }

    private boolean isTimeControlType(String controlType) {
        return FieldControlType.TIME_PICKER.getValue().equals(controlType)
                || "time".equals(controlType)
                || "timeRange".equals(controlType);
    }

    private boolean isScalarPresentationControlType(String controlType) {
        if (controlType == null) {
            return true;
        }

        return FieldControlType.INPUT.getValue().equals(controlType)
                || FieldControlType.TEXTAREA.getValue().equals(controlType)
                || FieldControlType.NUMERIC_TEXT_BOX.getValue().equals(controlType)
                || FieldControlType.CURRENCY_INPUT.getValue().equals(controlType)
                || FieldControlType.DATE_INPUT.getValue().equals(controlType)
                || FieldControlType.DATE_PICKER.getValue().equals(controlType)
                || FieldControlType.DATE_TIME_PICKER.getValue().equals(controlType)
                || FieldControlType.DATETIME_LOCAL_INPUT.getValue().equals(controlType)
                || FieldControlType.TIME_PICKER.getValue().equals(controlType)
                || FieldControlType.MONTH_INPUT.getValue().equals(controlType)
                || FieldControlType.WEEK_INPUT.getValue().equals(controlType)
                || FieldControlType.YEAR_INPUT.getValue().equals(controlType)
                || FieldControlType.EMAIL_INPUT.getValue().equals(controlType)
                || FieldControlType.URL_INPUT.getValue().equals(controlType)
                || FieldControlType.PASSWORD.getValue().equals(controlType)
                || FieldControlType.PHONE.getValue().equals(controlType)
                || FieldControlType.CPF_CNPJ_INPUT.getValue().equals(controlType)
                || FieldControlType.COLOR_INPUT.getValue().equals(controlType)
                || FieldControlType.COLOR_PICKER.getValue().equals(controlType)
                || FieldControlType.CHECKBOX.getValue().equals(controlType)
                || FieldControlType.TOGGLE.getValue().equals(controlType)
                || FieldControlType.INLINE_INPUT.getValue().equals(controlType)
                || FieldControlType.INLINE_NUMBER.getValue().equals(controlType)
                || FieldControlType.INLINE_CURRENCY.getValue().equals(controlType)
                || FieldControlType.INLINE_DATE.getValue().equals(controlType)
                || FieldControlType.INLINE_TIME.getValue().equals(controlType)
                || FieldControlType.INLINE_TOGGLE.getValue().equals(controlType);
    }

    /**
     * ETAPA 5: Aplica extraProperties (precedência MÁXIMA)
     */
    private void applyExtraProperties(UISchema annotation, Map<String, Object> uiExtension) {
        if (annotation.extraProperties() != null && annotation.extraProperties().length > 0) {
            for (ExtensionProperty p : annotation.extraProperties()) {
                // extraProperties sobrescreve TUDO (precedência máxima)
                if (p.name() != null && p.name().contains(".")) {
                    putNestedExtraProperty(uiExtension, p.name(), parseNestedExtraPropertyValue(p.value()));
                    continue;
                }
                uiExtension.put(p.name(), p.value());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void putNestedExtraProperty(Map<String, Object> target, String dottedName, Object value) {
        String[] parts = dottedName.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object existing = current.get(part);
            if (!(existing instanceof Map<?, ?> existingMap)) {
                Map<String, Object> nested = new LinkedHashMap<>();
                current.put(part, nested);
                current = nested;
                continue;
            }
            current = (Map<String, Object>) existingMap;
        }
        current.put(parts[parts.length - 1], value);
    }

    private Object parseNestedExtraPropertyValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (looksLikeStructuredLiteral(trimmed)) {
            try {
                return _mapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                // Fallback to the raw string when the literal is not valid JSON.
            }
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            try {
                return Integer.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                try {
                    return Long.valueOf(trimmed);
                } catch (NumberFormatException ignoredAgain) {
                    // Fallback to raw string below.
                }
            }
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.valueOf(trimmed);
            } catch (NumberFormatException ignored) {
                // Fallback to raw string below.
            }
        }
        return trimmed;
    }

    private boolean looksLikeStructuredLiteral(String value) {
        return (value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"))
                || (value.startsWith("\"") && value.endsWith("\""));
    }
}
