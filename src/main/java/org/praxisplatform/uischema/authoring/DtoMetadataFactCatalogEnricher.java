package org.praxisplatform.uischema.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.praxisplatform.rules.facts.RuleFactOperationCatalog;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.extension.ResolverUtils;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.schema.DomainSchemaGovernance;
import org.praxisplatform.uischema.util.OpenApiUiUtils;

/**
 * Enriquecedor canônico (`PLAT-EVO-06`) que conecta os metadados declarativos dos DTOs
 * ({@link Schema}, {@link UISchema}, {@link DomainGovernance} e {@link DomainLineage}) e os
 * catálogos curados {@link RuleFactCatalogDocument} ({@code /rules/facts/*.json}) aos catálogos de
 * fatos consumidos por LLMs, Rules Studio (`DomainRuleService`) e motores de governança.
 *
 * <p>Elimina heurísticas frágeis baseadas em nomes de variáveis (camelCase / substrings) ao:
 * <ul>
 *   <li>Inspecionar POJOs (com herança {@code *Dados} e override em subclasses) e Java
 *       {@code record}s com cache thread-safe;</li>
 *   <li>Inferir deterministicamente {@code valueType} e {@code nullable} a partir do
 *       {@code snapshotSchema} compilado e/ou da assinatura Java + Bean Validation do DTO;</li>
 *   <li>Combinar descrições ontológicas e documentação estatutária/negocial de {@code @Schema} /
 *       {@code @UISchema}, unificando {@code evidenceRefs} (PL/SQL + linhagem + tags LGPD) e
 *       aplicando política fail-closed de maior restritividade em {@code sensitivity} e
 *       {@code redaction};</li>
 *   <li>Garantir 100% de conformidade com os invariantes de ingestão do {@code DomainRuleService}
 *       (taxonomia canônica {@code NON_SENSITIVE | PERSONAL | SENSITIVE | SECRET}, unicidade de
 *       {@code path}, padrão {@code STABLE_FACT_PATH} e teto de 256 fatos por definição).</li>
 * </ul>
 */
public class DtoMetadataFactCatalogEnricher {
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    /** Limite máximo de entradas imposto por {@code DomainRuleService.validateFactCatalog}. */
    public static final int MAX_STUDIO_FACTS = 256;

    /** Limite máximo fail-closed de valores permitidos estáticos em um fato. */
    public static final int MAX_ALLOWED_VALUES = 64;

    public static final int MAX_LABEL_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_HINT_LENGTH = 500;
    private static final int MAX_GROUP_LABEL_LENGTH = 120;
    private static final int MAX_GROUP_KEY_LENGTH = 64;
    private static final int MAX_UNIT_LENGTH = 32;
    private static final int MAX_MASK_LENGTH = 64;
    private static final int MAX_EXAMPLE_LENGTH = 256;
    private static final int MAX_PATTERN_LENGTH = 256;
    private static final int MAX_OPTION_ICON_LENGTH = 64;
    private static final int MAX_OPTION_TONE_LENGTH = 32;
    private static final int MAX_OPTION_DESCRIPTION_LENGTH = 256;
    private static final int MAX_DATA_CATEGORY_LENGTH = 64;
    private static final int MAX_COMPLIANCE_TAGS = 32;
    private static final int MAX_ATTRIBUTE_ID_LENGTH = 255;
    private static final int MAX_RESOURCE_KEY_LENGTH = 255;
    private static final int MAX_TRANSFORMATION_LENGTH = 64;

    private final ConcurrentMap<Class<?>, Map<String, DtoFactPropertyMetadata>> dtoCache =
            new ConcurrentHashMap<>();

    /** Opção estática de domínio fechado extraída de {@code @UiOption}, enum ou {@code @Schema}. */
    public record DtoFactOption(
            Object value,
            String labelPtBr,
            String icon,
            String tone,
            String descriptionPtBr) {}

    /** Referência de lookup dinâmico ({@code ENTITY_LOOKUP}) sem materializar registros de negócio. */
    public record DtoFactOptionSourceRef(String sourceKey, String resourceKey) {}

    /** Restrições estruturais e de validação extraídas do DTO para formulários e Sandbox. */
    public record DtoFactConstraints(
            BigDecimal min,
            BigDecimal max,
            BigDecimal step,
            Integer minLength,
            Integer maxLength,
            String pattern) {

        public boolean isEmpty() {
            return min == null
                    && max == null
                    && step == null
                    && minLength == null
                    && maxLength == null
                    && (pattern == null || pattern.isBlank());
        }
    }

    /**
     * Metadado semântico, tipado e de governança extraído de uma propriedade de DTO.
     *
     * @param propertyName nome da propriedade Java no DTO
     * @param valueType tipo canônico para Rules Studio ({@code boolean}, {@code string},
     *     {@code number}, {@code date}, {@code string-array}, {@code number-array},
     *     {@code date-array})
     * @param nullable indica se a propriedade aceita {@code null}
     * @param labelPtBr rótulo curto de UI em português ({@code @UISchema.label} ou {@code @Schema.title})
     * @param descriptionPtBr descrição semântica de negócio em português ({@code @Schema.description})
     * @param helpTextPtBr orientação operacional complementar ({@code @UISchema.helpText} / {@code @UISchema.hint})
     * @param explicitGovernanceDeclared indica se possui {@code @DomainGovernance} ou {@code @DomainLineage} explícito
     * @param classification classificação de segurança ({@code public}, {@code internal}, {@code confidential}, {@code restricted})
     * @param dataCategory categoria do dado ({@code operational}, {@code personal}, {@code sensitive_personal}, {@code financial}, {@code credential}, {@code audit})
     * @param sensitivity sensibilidade canônica da plataforma ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE}, {@code SECRET})
     * @param redaction política de redação canônica ({@code NONE}, {@code MASK}, {@code HASH}, {@code OMIT})
     * @param exportable elegibilidade para exportação governada
     * @param aiVisibility política de visibilidade para IA ({@code allow}, {@code summarize_only}, {@code mask}, {@code deny})
     * @param complianceTags tags regulatórias e de conformidade (ex.: {@code LGPD_ART_5_I})
     * @param lineageAttributeId identificador semântico canônico de linhagem ({@code @DomainLineage.attributeId})
     * @param lineageSourceResourceKey recurso proprietário da origem ({@code @DomainLineage.sourceResourceKey})
     * @param lineageSourcePaths caminhos de origem na entidade/domínio ({@code @DomainLineage.sourcePaths})
     * @param lineageTransformation tipo de transformação da linhagem ({@code DIRECT}, {@code DERIVED}, etc.)
     * @param groupKey chave normalizada de agrupamento semântico derivado de {@code @UISchema.group}
     * @param groupLabelPtBr rótulo humano do agrupamento semântico
     * @param valuePresentation apresentação semântica canônica em minúsculas ({@code currency}, {@code percentage}, {@code date}, {@code datetime}, etc.)
     * @param unit unidade semântica explícita (ex.: {@code %})
     * @param mask máscara declarativa de entrada/exibição
     * @param example exemplo válido para preenchimento assistido (omitido se {@code SECRET}/{@code OMIT})
     * @param allowedValues valores permitidos de domínio fechado (omitidos se {@code SECRET}/{@code OMIT})
     * @param optionSourceRef referência de lookup dinâmico ({@code ENTITY_LOOKUP})
     * @param constraints limites numéricos, de tamanho e regex
     */
    public record DtoFactPropertyMetadata(
            String propertyName,
            String valueType,
            boolean nullable,
            String labelPtBr,
            String descriptionPtBr,
            String helpTextPtBr,
            boolean explicitGovernanceDeclared,
            String classification,
            String dataCategory,
            String sensitivity,
            String redaction,
            boolean exportable,
            String aiVisibility,
            List<String> complianceTags,
            String lineageAttributeId,
            String lineageSourceResourceKey,
            List<String> lineageSourcePaths,
            String lineageTransformation,
            String groupKey,
            String groupLabelPtBr,
            String valuePresentation,
            String unit,
            String mask,
            String example,
            List<DtoFactOption> allowedValues,
            DtoFactOptionSourceRef optionSourceRef,
            DtoFactConstraints constraints) {

        public DtoFactPropertyMetadata {
            complianceTags = complianceTags == null ? List.of() : List.copyOf(complianceTags);
            lineageSourcePaths =
                    lineageSourcePaths == null ? List.of() : List.copyOf(lineageSourcePaths);
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }

        /** Construtor compatível legado de 18 componentes sem metadados estendidos de apresentação. */
        public DtoFactPropertyMetadata(
                String propertyName,
                String valueType,
                boolean nullable,
                String labelPtBr,
                String descriptionPtBr,
                String helpTextPtBr,
                boolean explicitGovernanceDeclared,
                String classification,
                String dataCategory,
                String sensitivity,
                String redaction,
                boolean exportable,
                String aiVisibility,
                List<String> complianceTags,
                String lineageAttributeId,
                String lineageSourceResourceKey,
                List<String> lineageSourcePaths,
                String lineageTransformation) {
            this(
                    propertyName,
                    valueType,
                    nullable,
                    labelPtBr,
                    descriptionPtBr,
                    helpTextPtBr,
                    explicitGovernanceDeclared,
                    classification,
                    dataCategory,
                    sensitivity,
                    redaction,
                    exportable,
                    aiVisibility,
                    complianceTags,
                    lineageAttributeId,
                    lineageSourceResourceKey,
                    lineageSourcePaths,
                    lineageTransformation,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null);
        }

        /**
         * Constrói a lista consolidada de referências de evidência ({@code evidenceRefs})
         * combinando {@code lineageAttributeId}, {@code lineageSourceResourceKey} e
         * {@code complianceTags}.
         *
         * @param fallbackRef referência padrão caso nenhuma tag ou linhagem esteja presente
         * @return lista imutável de referências de evidência
         */
        public List<String> buildEvidenceRefs(String fallbackRef) {
            Set<String> refs = new LinkedHashSet<>();
            if (lineageAttributeId != null && !lineageAttributeId.isBlank()) {
                refs.add(lineageAttributeId);
            }
            if (lineageSourceResourceKey != null && !lineageSourceResourceKey.isBlank()) {
                refs.add(lineageSourceResourceKey);
            }
            if (complianceTags != null) {
                for (String tag : complianceTags) {
                    if (tag != null && !tag.isBlank()) {
                        refs.add(tag);
                    }
                }
            }
            if (refs.isEmpty() && fallbackRef != null && !fallbackRef.isBlank()) {
                refs.add(fallbackRef.trim());
            }
            return List.copyOf(refs);
        }

        /**
         * Retorna a descrição negocial limpa para consumo humano/LLM sem concatenar {@code helpText}
         * (que trafega estruturadamente em {@code hints}).
         */
        public String richDescriptionPtBr() {
            if (descriptionPtBr != null && !descriptionPtBr.isBlank()) {
                return descriptionPtBr;
            }
            if (labelPtBr != null && !labelPtBr.isBlank()) {
                return labelPtBr;
            }
            if (helpTextPtBr != null && !helpTextPtBr.isBlank()) {
                return helpTextPtBr;
            }
            return propertyName;
        }
    }

    /**
     * Inspeciona uma classe de DTO (POJO com hierarquia de superclasses como {@code *Dados} ou
     * Java {@code record}) e retorna os metadados semânticos, estruturais e de governança indexados
     * pelo nome da propriedade.
     *
     * @param dtoType classe do DTO
     * @return mapa imutável de metadados por nome de propriedade
     */
    public Map<String, DtoFactPropertyMetadata> inspectDto(Class<?> dtoType) {
        Objects.requireNonNull(dtoType, "dtoType");
        return dtoCache.computeIfAbsent(dtoType, this::computeDtoMetadata);
    }

    /**
     * Inspeciona um DTO de escrita ({@code CreateDTO} ou {@code UpdateDTO}) herdando a linhagem
     * semântica ({@code @DomainLineage}) e descrições/governança de fallback do
     * {@code fallbackResponseDtoType} quando omitidas na classe de escrita (alinhado à regra
     * canônica {@code P10.8-R01} do {@code praxis-authz-starter}).
     *
     * @param writeDtoType classe de {@code CreateDTO} ou {@code UpdateDTO}
     * @param fallbackResponseDtoType classe opcional de {@code ResponseDTO} do mesmo recurso
     * @return mapa imutável de metadados por nome de propriedade
     */
    public Map<String, DtoFactPropertyMetadata> inspectWriteDto(
            Class<?> writeDtoType, Class<?> fallbackResponseDtoType) {
        Objects.requireNonNull(writeDtoType, "writeDtoType");
        Map<String, DtoFactPropertyMetadata> writeProps = inspectDto(writeDtoType);
        if (fallbackResponseDtoType == null || fallbackResponseDtoType == writeDtoType) {
            return writeProps;
        }
        Map<String, DtoFactPropertyMetadata> readProps = inspectDto(fallbackResponseDtoType);
        if (readProps.isEmpty()) {
            return writeProps;
        }
        Map<String, DtoFactPropertyMetadata> merged = new LinkedHashMap<>(writeProps.size());
        for (Map.Entry<String, DtoFactPropertyMetadata> entry : writeProps.entrySet()) {
            DtoFactPropertyMetadata w = entry.getValue();
            DtoFactPropertyMetadata r = readProps.get(entry.getKey());
            if (r == null) {
                merged.put(entry.getKey(), w);
                continue;
            }
            String lineageAttr =
                    w.lineageAttributeId() != null
                            ? w.lineageAttributeId()
                            : r.lineageAttributeId();
            String lineageRes =
                    w.lineageSourceResourceKey() != null
                            ? w.lineageSourceResourceKey()
                            : r.lineageSourceResourceKey();
            List<String> lineagePaths =
                    !w.lineageSourcePaths().isEmpty()
                            ? w.lineageSourcePaths()
                            : r.lineageSourcePaths();
            String lineageTrans =
                    w.lineageTransformation() != null
                            ? w.lineageTransformation()
                            : r.lineageTransformation();
            boolean explicitGov = w.explicitGovernanceDeclared() || r.explicitGovernanceDeclared();
            String classification;
            String dataCategory;
            String sensitivity;
            String redaction;
            boolean exportable;
            String aiVisibility;
            if (w.explicitGovernanceDeclared() && r.explicitGovernanceDeclared()) {
                classification = strictestClassification(w.classification(), r.classification());
                dataCategory = w.dataCategory() != null ? w.dataCategory() : r.dataCategory();
                sensitivity = strictestSensitivity(w.sensitivity(), r.sensitivity());
                redaction = strictestRedaction(w.redaction(), r.redaction());
                exportable = w.exportable() && r.exportable();
                aiVisibility = strictestAiVisibility(w.aiVisibility(), r.aiVisibility());
            } else if (w.explicitGovernanceDeclared()) {
                classification = strictestClassification(w.classification(), r.classification());
                dataCategory = w.dataCategory();
                sensitivity = strictestSensitivity(w.sensitivity(), r.sensitivity());
                redaction = strictestRedaction(w.redaction(), r.redaction());
                exportable = w.exportable() && r.exportable();
                aiVisibility = strictestAiVisibility(w.aiVisibility(), r.aiVisibility());
            } else {
                classification = r.classification();
                dataCategory = r.dataCategory();
                sensitivity = strictestSensitivity(w.sensitivity(), r.sensitivity());
                redaction = strictestRedaction(w.redaction(), r.redaction());
                exportable = r.exportable();
                aiVisibility = r.aiVisibility();
            }
            Set<String> mergedTags = new LinkedHashSet<>();
            for (String tag : w.complianceTags()) {
                if (tag != null && !tag.isBlank() && mergedTags.size() < MAX_COMPLIANCE_TAGS) {
                    mergedTags.add(tag.trim());
                }
            }
            for (String tag : r.complianceTags()) {
                if (tag != null && !tag.isBlank() && mergedTags.size() < MAX_COMPLIANCE_TAGS) {
                    mergedTags.add(tag.trim());
                }
            }
            List<String> tags = List.copyOf(mergedTags);
            boolean restricted = isRestrictedSecret(sensitivity, redaction);
            if (restricted) {
                aiVisibility = "deny";
                exportable = false;
            }
            String groupKey = w.groupKey() != null ? w.groupKey() : r.groupKey();
            String groupLabel =
                    w.groupLabelPtBr() != null ? w.groupLabelPtBr() : r.groupLabelPtBr();
            String valuePres =
                    w.valuePresentation() != null ? w.valuePresentation() : r.valuePresentation();
            String unit = w.unit() != null ? w.unit() : r.unit();
            String mask = w.mask() != null ? w.mask() : r.mask();
            String example =
                    restricted
                            ? null
                            : (w.example() != null ? w.example() : r.example());
            List<DtoFactOption> allowedValues =
                    restricted
                            ? List.of()
                            : (!w.allowedValues().isEmpty()
                                    ? w.allowedValues()
                                    : r.allowedValues());
            DtoFactOptionSourceRef optionSourceRef =
                    w.optionSourceRef() != null ? w.optionSourceRef() : r.optionSourceRef();
            DtoFactConstraints constraints =
                    mergeConstraints(w.constraints(), r.constraints());

            merged.put(
                    entry.getKey(),
                    new DtoFactPropertyMetadata(
                            w.propertyName(),
                            w.valueType(),
                            w.nullable(),
                            w.labelPtBr() != null ? w.labelPtBr() : r.labelPtBr(),
                            w.descriptionPtBr() != null ? w.descriptionPtBr() : r.descriptionPtBr(),
                            w.helpTextPtBr() != null ? w.helpTextPtBr() : r.helpTextPtBr(),
                            explicitGov,
                            classification,
                            dataCategory,
                            sensitivity,
                            redaction,
                            exportable,
                            aiVisibility,
                            tags,
                            lineageAttr,
                            lineageRes,
                            lineagePaths,
                            lineageTrans,
                            groupKey,
                            groupLabel,
                            valuePres,
                            unit,
                            mask,
                            example,
                            allowedValues,
                            optionSourceRef,
                            constraints));
        }
        return Map.copyOf(merged);
    }

    private Map<String, DtoFactPropertyMetadata> computeDtoMetadata(Class<?> dtoType) {
        ObjectNode ownerTypeGov = DomainLineageResolver.resolveTypeGovernance(dtoType);
        Map<String, DtoFactPropertyMetadata> result = new LinkedHashMap<>();

        if (dtoType.isRecord()) {
            RecordComponent[] components = dtoType.getRecordComponents();
            if (components != null) {
                for (RecordComponent rc : components) {
                    Field backingField = findDeclaredFieldQuietly(dtoType, rc.getName());
                    Method accessor = rc.getAccessor();
                    result.put(
                            rc.getName(),
                            extractMetadata(
                                    rc.getName(),
                                    rc.getType(),
                                    rc.getGenericType(),
                                    rc,
                                    backingField,
                                    accessor,
                                    ownerTypeGov));
                }
            }
            return Map.copyOf(result);
        }

        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = dtoType;
                c != null && c != Object.class && c != Record.class;
                c = c.getSuperclass()) {
            hierarchy.add(c);
        }
        Collections.reverse(hierarchy);

        Map<String, Field> fieldsByName = new LinkedHashMap<>();
        for (Class<?> currentClass : hierarchy) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fieldsByName.put(field.getName(), field);
                Method getter = findGetter(dtoType, field.getName());
                DtoFactPropertyMetadata meta =
                        extractMetadata(
                                field.getName(),
                                field.getType(),
                                field.getGenericType(),
                                null,
                                field,
                                getter,
                                ownerTypeGov);
                result.put(field.getName(), meta);
            }
            for (Method method : currentClass.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.isSynthetic()
                        || method.getParameterCount() > 0
                        || method.getReturnType() == void.class) {
                    continue;
                }
                String propName = getterPropertyName(method.getName());
                if (propName == null) {
                    continue;
                }
                if (hasAnyMetadataAnnotation(method)) {
                    Field inheritedField = fieldsByName.get(propName);
                    Class<?> rawType =
                            inheritedField != null
                                    ? inheritedField.getType()
                                    : method.getReturnType();
                    Type genericType =
                            inheritedField != null
                                    ? inheritedField.getGenericType()
                                    : method.getGenericReturnType();
                    result.put(
                            propName,
                            extractMetadata(
                                    propName,
                                    rawType,
                                    genericType,
                                    null,
                                    inheritedField,
                                    method,
                                    ownerTypeGov));
                }
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Resolve o metadado de um caminho pontilhado de fato (ex.: {@code "draft.cpf"},
     * {@code "current.nome"}, {@code "person.dtnasc"}, {@code "bond.regimejur"}) a partir do
     * mapeamento entre raízes ontológicas e classes de DTO ({@code rootDtoBindings}).
     *
     * @param factPath caminho pontilhado do fato
     * @param rootDtoBindings mapa associando o nome da raiz ontológica (ex.: {@code "draft"},
     *     {@code "current"}, {@code "person"}) à respectiva classe de DTO
     * @return metadado da propriedade correspondente quando encontrado
     */
    public Optional<DtoFactPropertyMetadata> resolveFactPath(
            String factPath, Map<String, Class<?>> rootDtoBindings) {
        if (factPath == null
                || factPath.isBlank()
                || rootDtoBindings == null
                || rootDtoBindings.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = factPath.trim();
        boolean elementIndexed =
                trimmed.endsWith(":items") || trimmed.endsWith(":any") || trimmed.endsWith("[]");
        String normalized = trimmed.replace(":items", "").replace(":any", "").replace("[]", "");
        int dot = normalized.indexOf('.');
        if (dot <= 0 || dot >= normalized.length() - 1) {
            return Optional.empty();
        }
        String root = normalized.substring(0, dot);
        String remainder = normalized.substring(dot + 1);
        Class<?> boundDto = rootDtoBindings.get(root);
        Class<?> fallbackDto = "draft".equals(root) ? rootDtoBindings.get("current") : null;
        if (boundDto == null) {
            boundDto = fallbackDto;
            fallbackDto = null;
        }
        if (boundDto == null) {
            return Optional.empty();
        }

        String[] segments = remainder.split("\\.");
        if (segments.length == 0 || segments.length > 16) {
            return Optional.empty();
        }
        Optional<DtoFactPropertyMetadata> resolved =
                resolveFactSegments(boundDto, fallbackDto, segments);
        if (resolved.isEmpty() && fallbackDto != null && fallbackDto != boundDto) {
            resolved = resolveFactSegments(fallbackDto, null, segments);
        }
        if (elementIndexed) {
            return resolved.map(DtoMetadataFactCatalogEnricher::unwrapArrayMetadataIfNeeded);
        }
        return resolved;
    }

    private static DtoFactPropertyMetadata unwrapArrayMetadataIfNeeded(
            DtoFactPropertyMetadata meta) {
        if (meta == null) {
            return null;
        }
        String elemType =
                switch (meta.valueType()) {
                    case "number-array", "array<number>" -> "number";
                    case "string-array" -> "string";
                    case "date-array" -> "date";
                    default -> null;
                };
        if (elemType == null) {
            return meta;
        }
        return new DtoFactPropertyMetadata(
                meta.propertyName(),
                elemType,
                meta.nullable(),
                meta.labelPtBr(),
                meta.descriptionPtBr(),
                meta.helpTextPtBr(),
                meta.explicitGovernanceDeclared(),
                meta.classification(),
                meta.dataCategory(),
                meta.sensitivity(),
                meta.redaction(),
                meta.exportable(),
                meta.aiVisibility(),
                meta.complianceTags(),
                meta.lineageAttributeId(),
                meta.lineageSourceResourceKey(),
                meta.lineageSourcePaths(),
                meta.lineageTransformation(),
                meta.groupKey(),
                meta.groupLabelPtBr(),
                meta.valuePresentation(),
                meta.unit(),
                meta.mask(),
                meta.example(),
                meta.allowedValues(),
                meta.optionSourceRef(),
                meta.constraints());
    }

    private Optional<DtoFactPropertyMetadata> resolveFactSegments(
            Class<?> primaryDto, Class<?> fallbackDto, String[] segments) {
        Class<?> currentType = primaryDto;
        Class<?> currentFallback =
                fallbackDto != null && fallbackDto != primaryDto ? fallbackDto : null;
        Set<Class<?>> visited = new LinkedHashSet<>();
        visited.add(currentType);

        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i].trim();
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            Class<?> nextType = findNestedPropertyClass(currentType, segment);
            if (nextType == null || isScalarOrJdkLeaf(nextType) || !visited.add(nextType)) {
                return Optional.empty();
            }
            currentType = nextType;
            if (currentFallback != null) {
                Class<?> nextFallback = findNestedPropertyClass(currentFallback, segment);
                currentFallback =
                        nextFallback != null && !isScalarOrJdkLeaf(nextFallback)
                                ? nextFallback
                                : null;
            }
        }

        String leafProperty = segments[segments.length - 1].trim();
        if (leafProperty.isEmpty()) {
            return Optional.empty();
        }
        Map<String, DtoFactPropertyMetadata> dtoProps =
                currentFallback != null && currentFallback != currentType
                        ? inspectWriteDto(currentType, currentFallback)
                        : inspectDto(currentType);
        return findPropertyMatch(dtoProps, leafProperty);
    }

    private static Optional<DtoFactPropertyMetadata> findPropertyMatch(
            Map<String, DtoFactPropertyMetadata> dtoProps, String leafProperty) {
        DtoFactPropertyMetadata exact = dtoProps.get(leafProperty);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, DtoFactPropertyMetadata> entry : dtoProps.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(leafProperty)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Constrói um nó {@code factCatalog} (`praxis.domain-rule-fact-catalog.v1`) enriquecido
     * inferindo automaticamente {@code valueType} e {@code nullable} a partir do catálogo compilado
     * da operação e das classes de DTO vinculadas.
     */
    public ObjectNode buildEnrichedFactCatalog(
            ObjectMapper objectMapper,
            String schemaVersion,
            String ruleSetKey,
            String operationKey,
            String providerPrefix,
            List<String> requiredFactPaths,
            Map<String, Class<?>> rootDtoBindings,
            RuleFactCatalogDocument optionalCuratedCatalog) {
        return buildEnrichedFactCatalog(
                objectMapper,
                schemaVersion,
                ruleSetKey,
                operationKey,
                providerPrefix,
                requiredFactPaths,
                rootDtoBindings,
                optionalCuratedCatalog,
                null);
    }

    /**
     * Sobrecarga compatível sem {@code operationKey} explícita (pesquisa em todas as operações do
     * catálogo curado quando fornecido).
     */
    public ObjectNode buildEnrichedFactCatalog(
            ObjectMapper objectMapper,
            String schemaVersion,
            String ruleSetKey,
            String providerPrefix,
            List<String> requiredFactPaths,
            Map<String, Class<?>> rootDtoBindings,
            RuleFactCatalogDocument optionalCuratedCatalog,
            Function<String, String> valueTypeResolver) {
        return buildEnrichedFactCatalog(
                objectMapper,
                schemaVersion,
                ruleSetKey,
                null,
                providerPrefix,
                requiredFactPaths,
                rootDtoBindings,
                optionalCuratedCatalog,
                valueTypeResolver);
    }

    /**
     * Constrói um nó {@code factCatalog} (`praxis.domain-rule-fact-catalog.v1`) enriquecido para o
     * Rules Studio / autoria assistida por LLM, compatível com todos os invariantes de ingestão de
     * {@code DomainRuleService.validateFactCatalog}.
     *
     * @param objectMapper mapper Jackson
     * @param schemaVersion versão do schema do catálogo do Studio (ex.: {@code "praxis.domain-rule-fact-catalog.v1"})
     * @param ruleSetKey chave do RuleSet
     * @param operationKey chave opcional da operação (ex.: {@code "CREATE"}, {@code "UPDATE"}) para resolução exata no {@code snapshotSchema}
     * @param providerPrefix prefixo do provider para fallback
     * @param requiredFactPaths caminhos de fatos requeridos pelo slot/regra
     * @param rootDtoBindings mapeamento entre raiz ontológica e classe de DTO
     * @param optionalCuratedCatalog catálogo JSON curado opcional ({@link RuleFactCatalogDocument})
     * @param valueTypeResolver override opcional de {@code valueType}; quando {@code null}, inferido automaticamente do schema compilado ou do tipo Java do DTO
     * @return {@link ObjectNode} representando o {@code factCatalog} enriquecido
     */
    public ObjectNode buildEnrichedFactCatalog(
            ObjectMapper objectMapper,
            String schemaVersion,
            String ruleSetKey,
            String operationKey,
            String providerPrefix,
            List<String> requiredFactPaths,
            Map<String, Class<?>> rootDtoBindings,
            RuleFactCatalogDocument optionalCuratedCatalog,
            Function<String, String> valueTypeResolver) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : DEFAULT_MAPPER;
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("schemaVersion", schemaVersion);
        ArrayNode facts = catalog.putArray("facts");
        if (requiredFactPaths == null || requiredFactPaths.isEmpty()) {
            return catalog;
        }

        Optional<RuleFactOperationCatalog> opCatalog =
                (optionalCuratedCatalog != null
                                && operationKey != null
                                && !operationKey.isBlank())
                        ? optionalCuratedCatalog.findOperation(operationKey)
                        : Optional.empty();

        Set<String> emittedPaths = new LinkedHashSet<>();
        for (String rawPath : requiredFactPaths) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }
            if (facts.size() >= MAX_STUDIO_FACTS) {
                break;
            }
            String stablePath = RuleFactDescriptor.toStableFactPath(rawPath);
            if (!RuleFactDescriptor.STABLE_FACT_PATH_PATTERN.matcher(stablePath).matches()
                    || !emittedPaths.add(stablePath)) {
                continue;
            }

            Optional<RuleFactDescriptor> curated = Optional.empty();
            if (opCatalog.isPresent()) {
                curated = opCatalog.get().findFact(rawPath);
            }
            if (curated.isEmpty() && optionalCuratedCatalog != null) {
                curated = optionalCuratedCatalog.findFactAcrossOperations(rawPath);
            }
            Optional<DtoFactPropertyMetadata> dtoMeta = resolveFactPath(rawPath, rootDtoBindings);

            String resolvedValueType =
                    resolveStudioValueType(
                            rawPath, valueTypeResolver, curated, opCatalog, dtoMeta);
            boolean resolvedNullable = resolveStudioNullable(rawPath, curated, opCatalog, dtoMeta);

            ObjectNode node = mapper.createObjectNode();
            node.put("path", stablePath);
            node.put("valueType", resolvedValueType);
            node.put("nullable", resolvedNullable);

            if (curated.isPresent() && dtoMeta.isPresent()) {
                RuleFactDescriptor desc = curated.get();
                DtoFactPropertyMetadata meta = dtoMeta.get();

                String ptLabel =
                        mergeEditorialLabelPtBr(stablePath, desc.label("pt-BR"), meta);
                ObjectNode labelsNode = node.putObject("labels").put("pt-BR", ptLabel);
                String curatedEnLabel = desc.labels().get("en-US");
                if (curatedEnLabel != null
                        && !curatedEnLabel.isBlank()
                        && !isRawPathOrLeafLabel(curatedEnLabel, stablePath, meta)) {
                    labelsNode.put(
                            "en-US", truncateText(curatedEnLabel.trim(), MAX_LABEL_LENGTH));
                }

                String curatedPtDesc = desc.description("pt-BR");
                String ptDesc = mergeEditorialDescriptionPtBr(curatedPtDesc, meta);
                ObjectNode descriptionsNode = node.putObject("descriptions").put("pt-BR", ptDesc);
                String curatedEnDesc = desc.descriptions().get("en-US");
                if (curatedEnDesc != null
                        && !curatedEnDesc.isBlank()
                        && !isGenericTemplateDescription(curatedEnDesc)) {
                    descriptionsNode.put(
                            "en-US", truncateText(curatedEnDesc.trim(), MAX_DESCRIPTION_LENGTH));
                }
                node.put("providerRef", desc.providerRef());

                Set<String> mergedEvidence = new LinkedHashSet<>(desc.evidenceRefs());
                mergedEvidence.addAll(meta.buildEvidenceRefs(null));
                ArrayNode ev = node.putArray("evidenceRefs");
                mergedEvidence.forEach(ev::add);

                String effSensitivity =
                        strictestSensitivity(desc.canonicalSensitivity(), meta.sensitivity());
                String effRedaction = strictestRedaction(desc.redaction(), meta.redaction());
                node.put("sensitivity", effSensitivity);
                node.put("redaction", effRedaction);
                populateFactNodeMetadata(mapper, node, meta, effSensitivity, effRedaction);
            } else if (curated.isPresent()) {
                RuleFactDescriptor desc = curated.get();
                String ptLabel =
                        mergeEditorialLabelPtBr(stablePath, desc.label("pt-BR"), null);
                ObjectNode labelsNode = node.putObject("labels").put("pt-BR", ptLabel);
                String curatedEnLabel = desc.labels().get("en-US");
                if (curatedEnLabel != null
                        && !curatedEnLabel.isBlank()
                        && !isRawPathOrLeafLabel(curatedEnLabel, stablePath, null)) {
                    labelsNode.put(
                            "en-US", truncateText(curatedEnLabel.trim(), MAX_LABEL_LENGTH));
                }
                String ptDesc =
                        mergeEditorialDescriptionPtBr(desc.description("pt-BR"), null);
                ObjectNode descriptionsNode = node.putObject("descriptions").put("pt-BR", ptDesc);
                String curatedEnDesc = desc.descriptions().get("en-US");
                if (curatedEnDesc != null
                        && !curatedEnDesc.isBlank()
                        && !isGenericTemplateDescription(curatedEnDesc)) {
                    descriptionsNode.put(
                            "en-US", truncateText(curatedEnDesc.trim(), MAX_DESCRIPTION_LENGTH));
                }
                node.put("providerRef", desc.providerRef());
                ArrayNode ev = node.putArray("evidenceRefs");
                desc.evidenceRefs().forEach(ev::add);
                node.put("sensitivity", desc.canonicalSensitivity());
                node.put("redaction", desc.redaction());
            } else if (dtoMeta.isPresent()) {
                DtoFactPropertyMetadata meta = dtoMeta.get();
                String labelPt = mergeEditorialLabelPtBr(stablePath, null, meta);
                String descPt = mergeEditorialDescriptionPtBr(null, meta);
                node.putObject("labels").put("pt-BR", labelPt);
                node.putObject("descriptions").put("pt-BR", descPt);
                node.put("providerRef", providerPrefix + "." + stablePath);
                ArrayNode ev = node.putArray("evidenceRefs");
                for (String ref : meta.buildEvidenceRefs(ruleSetKey)) {
                    ev.add(ref);
                }
                node.put("sensitivity", meta.sensitivity());
                node.put("redaction", meta.redaction());
                populateFactNodeMetadata(mapper, node, meta, meta.sensitivity(), meta.redaction());
            } else {
                node.putObject("labels").put("pt-BR", truncateText(stablePath, MAX_LABEL_LENGTH));
                node.putObject("descriptions")
                        .put(
                                "pt-BR",
                                truncateText(
                                        "Fato de avaliação do RuleSet "
                                                + ruleSetKey
                                                + " ("
                                                + stablePath
                                                + ").",
                                        MAX_DESCRIPTION_LENGTH));
                node.put("providerRef", providerPrefix + "." + stablePath);
                node.putArray("evidenceRefs").add(ruleSetKey);
                node.put("sensitivity", "NON_SENSITIVE");
                node.put("redaction", "NONE");
            }
            facts.add(node);
        }
        return catalog;
    }

    private static String resolveStudioValueType(
            String rawPath,
            Function<String, String> valueTypeResolver,
            Optional<RuleFactDescriptor> curated,
            Optional<RuleFactOperationCatalog> opCatalog,
            Optional<DtoFactPropertyMetadata> dtoMeta) {
        if (curated.isPresent()) {
            return curated.get().valueType();
        }
        if (opCatalog.isPresent() && hasSchemaNode(opCatalog.get(), rawPath)) {
            return opCatalog.get().inferValueType(rawPath);
        }
        if (dtoMeta.isPresent()) {
            return dtoMeta.get().valueType();
        }
        if (valueTypeResolver != null) {
            String custom = valueTypeResolver.apply(rawPath);
            if (custom != null && RuleFactDescriptor.ALLOWED_VALUE_TYPES.contains(custom.trim())) {
                return custom.trim();
            }
        }
        return "string";
    }

    private static boolean resolveStudioNullable(
            String rawPath,
            Optional<RuleFactDescriptor> curated,
            Optional<RuleFactOperationCatalog> opCatalog,
            Optional<DtoFactPropertyMetadata> dtoMeta) {
        if (curated.isPresent()) {
            return curated.get().nullable();
        }
        if (opCatalog.isPresent() && hasSchemaNode(opCatalog.get(), rawPath)) {
            return opCatalog.get().isNullable(rawPath);
        }
        if (dtoMeta.isPresent()) {
            return dtoMeta.get().nullable();
        }
        return true;
    }

    /**
     * Popula os metadados estruturados canônicos de {@link DtoFactPropertyMetadata} em um nó JSON de
     * {@code factCatalog}, aplicando contenção fail-closed para fatos {@code SECRET}/{@code OMIT} e
     * omitindo caminhos internos de persistência da linhagem.
     */
    public static void populateFactNodeMetadata(
            ObjectMapper mapper,
            ObjectNode node,
            DtoFactPropertyMetadata meta,
            String effectiveSensitivity,
            String effectiveRedaction) {
        if (node == null || meta == null) {
            return;
        }
        ObjectMapper effectiveMapper = mapper != null ? mapper : DEFAULT_MAPPER;
        boolean restricted = isRestrictedSecret(effectiveSensitivity, effectiveRedaction);

        if (meta.helpTextPtBr() != null && !meta.helpTextPtBr().isBlank()) {
            node.putObject("hints")
                    .put("pt-BR", truncateText(meta.helpTextPtBr().trim(), MAX_HINT_LENGTH));
        }
        if (meta.groupKey() != null && !meta.groupKey().isBlank()) {
            node.put("groupKey", meta.groupKey());
        }
        if (meta.groupLabelPtBr() != null && !meta.groupLabelPtBr().isBlank()) {
            node.putObject("groupLabels")
                    .put(
                            "pt-BR",
                            truncateText(meta.groupLabelPtBr().trim(), MAX_GROUP_LABEL_LENGTH));
        }
        if (meta.valuePresentation() != null && !meta.valuePresentation().isBlank()) {
            node.put("valuePresentation", meta.valuePresentation());
        }
        if (meta.unit() != null && !meta.unit().isBlank()) {
            node.put("unit", truncateText(meta.unit().trim(), MAX_UNIT_LENGTH));
        }
        if (meta.mask() != null && !meta.mask().isBlank()) {
            node.put("mask", truncateText(meta.mask().trim(), MAX_MASK_LENGTH));
        }
        if (!restricted && !meta.allowedValues().isEmpty()) {
            ArrayNode allowedArray = node.putArray("allowedValues");
            int limit = Math.min(meta.allowedValues().size(), MAX_ALLOWED_VALUES);
            for (int i = 0; i < limit; i++) {
                DtoFactOption opt = meta.allowedValues().get(i);
                ObjectNode optNode = allowedArray.addObject();
                optNode.set("value", effectiveMapper.valueToTree(opt.value()));
                String optLabel =
                        opt.labelPtBr() != null && !opt.labelPtBr().isBlank()
                                ? truncateText(opt.labelPtBr().trim(), MAX_LABEL_LENGTH)
                                : truncateText(String.valueOf(opt.value()), MAX_LABEL_LENGTH);
                optNode.putObject("labels").put("pt-BR", optLabel);
                if (opt.icon() != null && !opt.icon().isBlank()) {
                    optNode.put("icon", truncateText(opt.icon().trim(), MAX_OPTION_ICON_LENGTH));
                }
                if (opt.tone() != null && !opt.tone().isBlank()) {
                    optNode.put("tone", truncateText(opt.tone().trim(), MAX_OPTION_TONE_LENGTH));
                }
                if (opt.descriptionPtBr() != null && !opt.descriptionPtBr().isBlank()) {
                    optNode.put(
                            "description",
                            truncateText(
                                    opt.descriptionPtBr().trim(), MAX_OPTION_DESCRIPTION_LENGTH));
                }
            }
        }
        if (meta.optionSourceRef() != null
                && meta.optionSourceRef().sourceKey() != null
                && !meta.optionSourceRef().sourceKey().isBlank()) {
            ObjectNode refNode = node.putObject("optionSourceRef");
            refNode.put("sourceKey", meta.optionSourceRef().sourceKey());
            if (meta.optionSourceRef().resourceKey() != null
                    && !meta.optionSourceRef().resourceKey().isBlank()) {
                refNode.put(
                        "resourceKey",
                        truncateText(
                                meta.optionSourceRef().resourceKey(), MAX_RESOURCE_KEY_LENGTH));
            }
        }
        if (!restricted && meta.example() != null && !meta.example().isBlank()) {
            node.put("example", truncateText(meta.example().trim(), MAX_EXAMPLE_LENGTH));
        }
        if (meta.constraints() != null && !meta.constraints().isEmpty()) {
            DtoFactConstraints c = meta.constraints();
            ObjectNode constraintsNode = node.putObject("constraints");
            if (c.min() != null) {
                constraintsNode.put("min", c.min());
            }
            if (c.max() != null) {
                constraintsNode.put("max", c.max());
            }
            if (c.step() != null) {
                constraintsNode.put("step", c.step());
            }
            if (c.minLength() != null) {
                constraintsNode.put("minLength", c.minLength());
            }
            if (c.maxLength() != null) {
                constraintsNode.put("maxLength", c.maxLength());
            }
            if (c.pattern() != null && !c.pattern().isBlank()) {
                constraintsNode.put("pattern", truncateText(c.pattern(), MAX_PATTERN_LENGTH));
            }
        }
        if (meta.explicitGovernanceDeclared() || restricted) {
            ObjectNode govNode = node.putObject("governance");
            govNode.put("classification", meta.classification());
            if (meta.dataCategory() != null && !meta.dataCategory().isBlank()) {
                govNode.put(
                        "dataCategory",
                        truncateText(meta.dataCategory().trim(), MAX_DATA_CATEGORY_LENGTH));
            }
            govNode.put("exportable", !restricted && meta.exportable());
            govNode.put("aiVisibility", restricted ? "deny" : meta.aiVisibility());
            if (!meta.complianceTags().isEmpty()) {
                ArrayNode tags = govNode.putArray("complianceTags");
                int tagCount = 0;
                for (String tag : meta.complianceTags()) {
                    if (tag != null && !tag.isBlank()) {
                        tags.add(tag.trim());
                        if (++tagCount >= MAX_COMPLIANCE_TAGS) {
                            break;
                        }
                    }
                }
            }
        }
        if (meta.lineageAttributeId() != null
                || meta.lineageSourceResourceKey() != null
                || meta.lineageTransformation() != null) {
            ObjectNode lineageNode = node.putObject("lineage");
            if (meta.lineageAttributeId() != null && !meta.lineageAttributeId().isBlank()) {
                lineageNode.put(
                        "attributeId",
                        truncateText(meta.lineageAttributeId().trim(), MAX_ATTRIBUTE_ID_LENGTH));
            }
            if (meta.lineageSourceResourceKey() != null
                    && !meta.lineageSourceResourceKey().isBlank()) {
                lineageNode.put(
                        "sourceResourceKey",
                        truncateText(
                                meta.lineageSourceResourceKey().trim(), MAX_RESOURCE_KEY_LENGTH));
            }
            if (meta.lineageTransformation() != null
                    && !meta.lineageTransformation().isBlank()) {
                lineageNode.put(
                        "transformation",
                        truncateText(
                                meta.lineageTransformation().trim(), MAX_TRANSFORMATION_LENGTH));
            }
        }
    }

    private static boolean hasSchemaNode(RuleFactOperationCatalog opCatalog, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String ptr =
                RuleFactDescriptor.factPathToSchemaPointer(rawPath, opCatalog.schema().schema());
        JsonNode target = opCatalog.schema().schema().at(ptr.substring(1));
        return target != null && !target.isMissingNode() && !target.isNull();
    }

    /**
     * Resolve o rótulo editorial canônico em {@code pt-BR} unificando {@code @UISchema.label} do
     * DTO e o catálogo curado, garantindo que rótulos explícitos do DTO prevaleçam sobre o JSON
     * curado e o JSON curado rico prevaleça sobre identificadores crus.
     */
    public static String mergeEditorialLabelPtBr(
            String factPath, String curatedPtLabel, DtoFactPropertyMetadata dtoMeta) {
        if (dtoMeta != null && dtoMeta.labelPtBr() != null && !dtoMeta.labelPtBr().isBlank()) {
            return truncateText(dtoMeta.labelPtBr().trim(), MAX_LABEL_LENGTH);
        }
        if (curatedPtLabel != null
                && !curatedPtLabel.isBlank()
                && !isRawPathOrLeafLabel(curatedPtLabel, factPath, dtoMeta)) {
            return truncateText(curatedPtLabel.trim(), MAX_LABEL_LENGTH);
        }
        if (curatedPtLabel != null && !curatedPtLabel.isBlank()) {
            return truncateText(curatedPtLabel.trim(), MAX_LABEL_LENGTH);
        }
        if (dtoMeta != null
                && dtoMeta.propertyName() != null
                && !dtoMeta.propertyName().isBlank()) {
            return truncateText(dtoMeta.propertyName().trim(), MAX_LABEL_LENGTH);
        }
        return factPath != null ? truncateText(factPath.trim(), MAX_LABEL_LENGTH) : null;
    }

    /**
     * Resolve a descrição editorial canônica em {@code pt-BR} sem sobrescrever descrições curadas
     * ricas com nomes crus de propriedade e mantendo {@code helpText} separado em {@code hints}.
     */
    public static String mergeEditorialDescriptionPtBr(
            String curatedPtDesc, DtoFactPropertyMetadata dtoMeta) {
        String dtoDesc =
                dtoMeta != null
                                && dtoMeta.descriptionPtBr() != null
                                && !dtoMeta.descriptionPtBr().isBlank()
                        ? dtoMeta.descriptionPtBr().trim()
                        : null;
        String curatedTrim =
                curatedPtDesc != null && !curatedPtDesc.isBlank() ? curatedPtDesc.trim() : null;
        if (dtoDesc != null) {
            if (curatedTrim == null || isGenericTemplateDescription(curatedTrim)) {
                return truncateText(dtoDesc, MAX_DESCRIPTION_LENGTH);
            }
            if (curatedTrim.contains(dtoDesc)) {
                return truncateText(curatedTrim, MAX_DESCRIPTION_LENGTH);
            }
            if (dtoDesc.contains(curatedTrim)) {
                return truncateText(dtoDesc, MAX_DESCRIPTION_LENGTH);
            }
            return truncateText(dtoDesc + " Contexto no RuleSet: " + curatedTrim, MAX_DESCRIPTION_LENGTH);
        }
        if (curatedTrim != null && !isGenericTemplateDescription(curatedTrim)) {
            return truncateText(curatedTrim, MAX_DESCRIPTION_LENGTH);
        }
        if (dtoMeta != null) {
            if (dtoMeta.labelPtBr() != null && !dtoMeta.labelPtBr().isBlank()) {
                return truncateText(dtoMeta.labelPtBr().trim(), MAX_DESCRIPTION_LENGTH);
            }
            if (curatedTrim != null) {
                return truncateText(curatedTrim, MAX_DESCRIPTION_LENGTH);
            }
            if (dtoMeta.helpTextPtBr() != null && !dtoMeta.helpTextPtBr().isBlank()) {
                return truncateText(dtoMeta.helpTextPtBr().trim(), MAX_DESCRIPTION_LENGTH);
            }
            if (dtoMeta.propertyName() != null && !dtoMeta.propertyName().isBlank()) {
                return truncateText(dtoMeta.propertyName().trim(), MAX_DESCRIPTION_LENGTH);
            }
        }
        return curatedTrim != null ? truncateText(curatedTrim, MAX_DESCRIPTION_LENGTH) : null;
    }

    public static boolean isRawPathOrLeafLabel(
            String label, String factPath, DtoFactPropertyMetadata dtoMeta) {
        if (label == null || label.isBlank()) {
            return true;
        }
        String trimmed = label.trim();
        if (dtoMeta != null
                && dtoMeta.propertyName() != null
                && trimmed.equalsIgnoreCase(dtoMeta.propertyName())) {
            return true;
        }
        if (factPath == null || factPath.isBlank()) {
            return false;
        }
        String stable = RuleFactDescriptor.toStableFactPath(factPath);
        if (trimmed.equals(factPath)
                || trimmed.equals(stable)
                || trimmed.equals(stable.replace('.', ' '))) {
            return true;
        }
        int lastDot = stable.lastIndexOf('.');
        if (lastDot < 0) {
            return trimmed.equalsIgnoreCase(stable) || trimmed.equals("fato: " + stable);
        }
        String root = stable.substring(0, lastDot);
        String leaf = stable.substring(lastDot + 1);
        return trimmed.equalsIgnoreCase(leaf) || trimmed.equals(root + ": " + leaf);
    }

    public static boolean isGenericTemplateDescription(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("Projeção primitiva de ")
                || trimmed.startsWith("Fato de avaliação do RuleSet ")
                || trimmed.startsWith("Fato governado do RuleSet ")
                || trimmed.startsWith("Governed fact for RuleSet ")
                || trimmed.startsWith("Governed evaluation fact for RuleSet ")
                || trimmed.startsWith("Evaluation fact for RuleSet ")
                || trimmed.startsWith("Identificador persistente do registro no recurso indicado");
    }

    private DtoFactPropertyMetadata extractMetadata(
            String propertyName,
            Class<?> rawType,
            Type genericType,
            RecordComponent recordComponent,
            Field field,
            Method getter,
            ObjectNode ownerTypeGov) {
        // Precedence: getter/accessor override -> recordComponent -> field
        Schema schema = findAnnotation(Schema.class, getter, recordComponent, field);
        UISchema uiSchema = findAnnotation(UISchema.class, getter, recordComponent, field);
        DomainGovernance gov =
                findAnnotation(DomainGovernance.class, getter, recordComponent, field);
        DomainLineage lineage =
                findAnnotation(DomainLineage.class, getter, recordComponent, field);

        String valueType = inferJavaValueType(rawType, genericType);
        boolean nullable = inferJavaNullable(rawType, schema, getter, recordComponent, field);

        String labelPtBr = null;
        if (uiSchema != null && !uiSchema.label().isBlank()) {
            labelPtBr = uiSchema.label().trim();
        } else if (schema != null && !schema.title().isBlank()) {
            labelPtBr = schema.title().trim();
        }

        String helpTextPtBr = null;
        if (uiSchema != null && !uiSchema.helpText().isBlank()) {
            helpTextPtBr = truncateText(uiSchema.helpText().trim(), MAX_HINT_LENGTH);
        } else if (uiSchema != null && !uiSchema.hint().isBlank()) {
            helpTextPtBr = truncateText(uiSchema.hint().trim(), MAX_HINT_LENGTH);
        }

        String descriptionPtBr = null;
        if (schema != null && !schema.description().isBlank()) {
            descriptionPtBr = schema.description().trim();
        } else if (uiSchema != null && !uiSchema.description().isBlank()) {
            descriptionPtBr = uiSchema.description().trim();
        }

        ObjectNode effectiveGov = null;
        String lineageAttributeId = null;
        String lineageSourceResourceKey = null;
        List<String> lineageSourcePaths = List.of();
        String lineageTransformation = null;

        if (lineage != null) {
            DomainLineageResolver.ResolvedLineage resolved =
                    DomainLineageResolver.resolve(lineage, gov, ownerTypeGov);
            effectiveGov = resolved.effectiveGovernance();
            lineageAttributeId = resolved.attributeId();
            lineageSourceResourceKey = resolved.sourceResourceKey();
            lineageSourcePaths = resolved.sourcePaths();
            lineageTransformation =
                    resolved.transformation() != null ? resolved.transformation().name() : null;
        } else if (gov != null || ownerTypeGov != null) {
            effectiveGov =
                    DomainSchemaGovernance.mergeOrCopy(
                            ownerTypeGov, DomainLineageResolver.toGovernanceNode(gov));
        }

        boolean explicitGov = effectiveGov != null;
        String classification =
                explicitGov
                        ? effectiveGov.path("classification").asText("internal")
                        : "internal";
        String dataCategory =
                explicitGov
                        ? effectiveGov.path("dataCategory").asText("operational")
                        : "operational";
        boolean exportable = !explicitGov || DomainSchemaGovernance.isExportable(effectiveGov);
        String aiVisibility =
                explicitGov
                        ? effectiveGov.path("aiUsage").path("visibility").asText("allow")
                        : "allow";

        List<String> complianceTags = new ArrayList<>();
        if (explicitGov && effectiveGov.path("complianceTags").isArray()) {
            for (JsonNode tag : effectiveGov.path("complianceTags")) {
                if (complianceTags.size() >= MAX_COMPLIANCE_TAGS) {
                    break;
                }
                if (tag.isTextual() && !tag.asText().isBlank()) {
                    complianceTags.add(tag.asText().trim());
                }
            }
        }

        String sensitivity = mapSensitivity(dataCategory, classification);
        String redaction = mapRedaction(classification, dataCategory, aiVisibility, exportable);
        boolean restricted = isRestrictedSecret(sensitivity, redaction);
        if (restricted) {
            aiVisibility = "deny";
            exportable = false;
        }

        String groupLabelPtBr =
                uiSchema != null && !uiSchema.group().isBlank()
                        ? truncateText(uiSchema.group().trim(), MAX_GROUP_LABEL_LENGTH)
                        : null;
        String groupKey = normalizeGroupKey(groupLabelPtBr);

        String valuePresentation = resolveValuePresentation(rawType, schema, uiSchema);
        String unit = resolveUnit(uiSchema, valuePresentation);
        String mask =
                uiSchema != null && !uiSchema.mask().isBlank()
                        ? truncateText(uiSchema.mask().trim(), MAX_MASK_LENGTH)
                        : null;

        String rawExample =
                firstNonBlank(
                        uiSchema != null ? uiSchema.example() : null,
                        schema != null ? schema.example() : null);
        String example = restricted || rawExample == null
                ? null
                : truncateText(rawExample, MAX_EXAMPLE_LENGTH);

        List<DtoFactOption> allowedValues =
                restricted
                        ? List.of()
                        : extractAllowedValues(rawType, valueType, schema, uiSchema);

        DtoFactOptionSourceRef optionSourceRef =
                extractOptionSourceRef(propertyName, uiSchema, lineageSourceResourceKey);

        DtoFactConstraints constraints =
                extractConstraints(schema, uiSchema, getter, recordComponent, field);

        return new DtoFactPropertyMetadata(
                propertyName,
                valueType,
                nullable,
                labelPtBr,
                descriptionPtBr,
                helpTextPtBr,
                explicitGov,
                classification,
                dataCategory,
                sensitivity,
                redaction,
                exportable,
                aiVisibility,
                List.copyOf(complianceTags),
                lineageAttributeId,
                lineageSourceResourceKey,
                lineageSourcePaths,
                lineageTransformation,
                groupKey,
                groupLabelPtBr,
                valuePresentation,
                unit,
                mask,
                example,
                allowedValues,
                optionSourceRef,
                constraints);
    }

    private static boolean isRestrictedSecret(String sensitivity, String redaction) {
        return "SECRET".equalsIgnoreCase(sensitivity) || "OMIT".equalsIgnoreCase(redaction);
    }

    private static DtoFactConstraints mergeConstraints(
            DtoFactConstraints primary, DtoFactConstraints fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }
        BigDecimal min = primary.min() != null ? primary.min() : fallback.min();
        BigDecimal max = primary.max() != null ? primary.max() : fallback.max();
        if (min != null && max != null && min.compareTo(max) > 0) {
            if (primary.min() != null && primary.max() == null) {
                max = null;
            } else if (primary.max() != null && primary.min() == null) {
                min = null;
            } else {
                min = null;
                max = null;
            }
        }
        BigDecimal step = primary.step() != null ? primary.step() : fallback.step();
        Integer minLength =
                primary.minLength() != null ? primary.minLength() : fallback.minLength();
        Integer maxLength =
                primary.maxLength() != null ? primary.maxLength() : fallback.maxLength();
        if (minLength != null && maxLength != null && minLength > maxLength) {
            if (primary.minLength() != null && primary.maxLength() == null) {
                maxLength = null;
            } else if (primary.maxLength() != null && primary.minLength() == null) {
                minLength = null;
            } else {
                minLength = null;
                maxLength = null;
            }
        }
        String pattern =
                primary.pattern() != null && !primary.pattern().isBlank()
                        ? primary.pattern()
                        : fallback.pattern();
        DtoFactConstraints merged =
                new DtoFactConstraints(min, max, step, minLength, maxLength, pattern);
        return merged.isEmpty() ? null : merged;
    }

    private static Class<?> findNestedPropertyClass(Class<?> ownerType, String segment) {
        if (ownerType == null || segment == null || segment.isBlank()) {
            return null;
        }
        if (ownerType.isRecord()) {
            RecordComponent[] components = ownerType.getRecordComponents();
            if (components != null) {
                for (RecordComponent rc : components) {
                    if (rc.getName().equals(segment) || rc.getName().equalsIgnoreCase(segment)) {
                        return unwrapElementClass(rc.getType(), rc.getGenericType());
                    }
                }
            }
            return null;
        }

        Class<?> fallbackCaseInsensitive = null;
        for (Class<?> c = ownerType;
                c != null && c != Object.class && c != Record.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (f.getName().equals(segment)) {
                    return unwrapElementClass(f.getType(), f.getGenericType());
                }
                if (fallbackCaseInsensitive == null && f.getName().equalsIgnoreCase(segment)) {
                    fallbackCaseInsensitive = unwrapElementClass(f.getType(), f.getGenericType());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        || m.isSynthetic()
                        || m.getParameterCount() > 0
                        || m.getReturnType() == void.class) {
                    continue;
                }
                String propName = getterPropertyName(m.getName());
                if (propName == null) {
                    continue;
                }
                if (propName.equals(segment)) {
                    return unwrapElementClass(m.getReturnType(), m.getGenericReturnType());
                }
                if (fallbackCaseInsensitive == null && propName.equalsIgnoreCase(segment)) {
                    fallbackCaseInsensitive =
                            unwrapElementClass(m.getReturnType(), m.getGenericReturnType());
                }
            }
        }
        return fallbackCaseInsensitive;
    }

    private static Class<?> unwrapElementClass(Class<?> rawType, Type genericType) {
        if (rawType == null) {
            return null;
        }
        if (rawType.isArray()) {
            return rawType.getComponentType();
        }
        if (Collection.class.isAssignableFrom(rawType) || Optional.class.isAssignableFrom(rawType)) {
            if (genericType instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 1) {
                return resolveTypeArgumentClass(pt.getActualTypeArguments()[0]);
            }
            return null;
        }
        return rawType;
    }

    private static Class<?> resolveTypeArgumentClass(Type typeArg) {
        if (typeArg instanceof Class<?> elem) {
            return elem;
        }
        if (typeArg instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (typeArg instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            if (upper != null && upper.length > 0 && upper[0] != Object.class) {
                return resolveTypeArgumentClass(upper[0]);
            }
            Type[] lower = wt.getLowerBounds();
            if (lower != null && lower.length > 0) {
                return resolveTypeArgumentClass(lower[0]);
            }
        }
        return null;
    }

    private static boolean isScalarOrJdkLeaf(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Temporal.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type)
                || type.getName().startsWith("java.");
    }

    private static String normalizeGroupKey(String groupLabel) {
        if (groupLabel == null || groupLabel.isBlank()) {
            return null;
        }
        String decomposed =
                Normalizer.normalize(groupLabel.trim(), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                        .toLowerCase(Locale.ROOT);
        String slug =
                decomposed
                        .replaceAll("[^a-z0-9._-]+", "-")
                        .replaceAll("^[._-]+|[._-]+$", "");
        if (slug.isEmpty() || !Character.isLetterOrDigit(slug.charAt(0))) {
            return null;
        }
        return truncateText(slug, MAX_GROUP_KEY_LENGTH);
    }

    private static String resolveValuePresentation(
            Class<?> rawType, Schema schema, UISchema uiSchema) {
        if (uiSchema != null && uiSchema.numericFormat() != NumericFormat.AUTO) {
            return switch (uiSchema.numericFormat()) {
                case CURRENCY -> "currency";
                case PERCENT -> "percentage";
                case INTEGER -> "integer";
                case TIME -> "time";
                case DATE -> "date";
                case DATE_TIME -> "datetime";
                case DURATION -> "duration";
                case DECIMAL, NUMBER, FRACTION, SCIENTIFIC -> "number";
                default -> null;
            };
        }
        if (uiSchema != null && uiSchema.preset() != UISchemaPreset.NONE) {
            String fromPreset =
                    switch (uiSchema.preset()) {
                        case MONETARY_AMOUNT -> "currency";
                        case PERCENTAGE_RATE -> "percentage";
                        case INTEGER_COUNTER -> "integer";
                        case BOOLEAN_FLAG -> "boolean";
                        case LOCAL_DATE -> "date";
                        case LOCAL_DATE_TIME, AUDIT_TIMESTAMP -> "datetime";
                        case ENTERPRISE_ID, ENTERPRISE_CODE, CPF_CNPJ -> "identifier";
                        default -> null;
                    };
            if (fromPreset != null) {
                return fromPreset;
            }
        }
        if (uiSchema != null && uiSchema.controlType() != FieldControlType.AUTO) {
            String ctrl = uiSchema.controlType().getValue();
            if (FieldControlType.CURRENCY_INPUT.getValue().equals(ctrl)
                    || FieldControlType.PRICE_RANGE.getValue().equals(ctrl)
                    || FieldControlType.INLINE_CURRENCY.getValue().equals(ctrl)
                    || FieldControlType.INLINE_CURRENCY_RANGE.getValue().equals(ctrl)) {
                return "currency";
            }
            if (FieldControlType.DATE_TIME_PICKER.getValue().equals(ctrl)
                    || FieldControlType.DATE_TIME_RANGE.getValue().equals(ctrl)
                    || FieldControlType.DATETIME_LOCAL_INPUT.getValue().equals(ctrl)
                    || "datetimeInput".equals(ctrl)
                    || "datetime".equals(ctrl)) {
                return "datetime";
            }
            if (FieldControlType.DATE_PICKER.getValue().equals(ctrl)
                    || FieldControlType.DATE_INPUT.getValue().equals(ctrl)
                    || FieldControlType.DATE_RANGE.getValue().equals(ctrl)
                    || FieldControlType.INLINE_DATE.getValue().equals(ctrl)
                    || FieldControlType.INLINE_DATE_RANGE.getValue().equals(ctrl)) {
                return "date";
            }
            if (FieldControlType.TIME_PICKER.getValue().equals(ctrl)
                    || FieldControlType.TIME_INPUT.getValue().equals(ctrl)
                    || FieldControlType.TIME_RANGE.getValue().equals(ctrl)
                    || FieldControlType.INLINE_TIME.getValue().equals(ctrl)
                    || FieldControlType.INLINE_TIME_RANGE.getValue().equals(ctrl)) {
                return "time";
            }
        }
        if (schema != null && !schema.format().isBlank()) {
            String fmt = schema.format().trim().toLowerCase(Locale.ROOT);
            switch (fmt) {
                case "date-time", "datetime" -> {
                    return "datetime";
                }
                case "date" -> {
                    return "date";
                }
                case "time" -> {
                    return "time";
                }
                case "currency" -> {
                    return "currency";
                }
                case "percent", "percentage" -> {
                    return "percentage";
                }
                case "duration" -> {
                    return "duration";
                }
                default -> {
                    // Continue to Java type fallback.
                }
            }
        }
        if (rawType != null) {
            if (rawType == boolean.class || rawType == Boolean.class) {
                return "boolean";
            }
            if (LocalDateTime.class.isAssignableFrom(rawType)
                    || Instant.class.isAssignableFrom(rawType)
                    || OffsetDateTime.class.isAssignableFrom(rawType)
                    || ZonedDateTime.class.isAssignableFrom(rawType)) {
                return "datetime";
            }
            if (LocalTime.class.isAssignableFrom(rawType)
                    || OffsetTime.class.isAssignableFrom(rawType)) {
                return "time";
            }
            if (Temporal.class.isAssignableFrom(rawType) || Date.class.isAssignableFrom(rawType)) {
                return "date";
            }
            if (rawType == int.class
                    || rawType == Integer.class
                    || rawType == long.class
                    || rawType == Long.class
                    || rawType == short.class
                    || rawType == Short.class
                    || rawType == byte.class
                    || rawType == Byte.class
                    || java.math.BigInteger.class.isAssignableFrom(rawType)) {
                return "integer";
            }
            if (rawType.isPrimitive() || Number.class.isAssignableFrom(rawType)) {
                return "number";
            }
        }
        return null;
    }

    private static String resolveUnit(UISchema uiSchema, String valuePresentation) {
        String explicit = extractExtraProperty(uiSchema, "unit");
        if (explicit != null) {
            return truncateText(explicit, MAX_UNIT_LENGTH);
        }
        if ("percentage".equals(valuePresentation)) {
            return "%";
        }
        return null;
    }

    private static List<DtoFactOption> extractAllowedValues(
            Class<?> rawType, String valueType, Schema schema, UISchema uiSchema) {
        Map<String, Object> tempUiMap = new LinkedHashMap<>();
        String targetSchemaType = openApiScalarTypeForValueType(rawType, valueType);

        if (uiSchema != null && uiSchema.optionItems().length > 0) {
            OpenApiUiUtils.populateUiOptionsFromAnnotationItems(
                    tempUiMap, uiSchema.optionItems(), targetSchemaType, DEFAULT_MAPPER);
        } else if (uiSchema != null
                && uiSchema.optionEnum() != Void.class
                && uiSchema.optionEnum().isEnum()) {
            OpenApiUiUtils.populateUiOptionsFromEnumClass(
                    tempUiMap, uiSchema.optionEnum(), DEFAULT_MAPPER);
        } else if (rawType != null && rawType.isEnum()) {
            OpenApiUiUtils.populateUiOptionsFromEnumClass(tempUiMap, rawType, DEFAULT_MAPPER);
        }

        Object rawOptions = tempUiMap.get("options");
        if (rawOptions != null) {
            JsonNode optionsTree = DEFAULT_MAPPER.valueToTree(rawOptions);
            if (optionsTree != null && optionsTree.isArray()) {
                List<DtoFactOption> parsed = new ArrayList<>();
                Set<String> seenValues = new LinkedHashSet<>();
                for (JsonNode item : optionsTree) {
                    if (parsed.size() >= MAX_ALLOWED_VALUES) {
                        break;
                    }
                    JsonNode valNode = item.get("value");
                    if (valNode == null || valNode.isNull()) {
                        continue;
                    }
                    Object scalar = extractTypedOptionValue(valNode, valueType);
                    if (scalar == null || !seenValues.add(allowedValueDedupKey(scalar))) {
                        continue;
                    }
                    String label =
                            truncateText(
                                    firstNonBlank(
                                            item.path("label").asText(null),
                                            String.valueOf(scalar)),
                                    MAX_LABEL_LENGTH);
                    String icon =
                            truncateText(
                                    firstNonBlank(item.path("icon").asText(null), null),
                                    MAX_OPTION_ICON_LENGTH);
                    String tone =
                            truncateText(
                                    firstNonBlank(item.path("tone").asText(null), null),
                                    MAX_OPTION_TONE_LENGTH);
                    String desc =
                            truncateText(
                                    firstNonBlank(item.path("description").asText(null), null),
                                    MAX_OPTION_DESCRIPTION_LENGTH);
                    parsed.add(new DtoFactOption(scalar, label, icon, tone, desc));
                }
                if (!parsed.isEmpty()) {
                    return List.copyOf(parsed);
                }
            }
        }

        if (schema != null && schema.allowableValues().length > 0) {
            List<DtoFactOption> parsed = new ArrayList<>();
            Set<String> seenValues = new LinkedHashSet<>();
            for (String rawVal : schema.allowableValues()) {
                if (parsed.size() >= MAX_ALLOWED_VALUES) {
                    break;
                }
                if (rawVal == null || rawVal.isBlank()) {
                    continue;
                }
                Object scalar = coerceAllowableString(rawVal.trim(), valueType);
                if (scalar == null || !seenValues.add(allowedValueDedupKey(scalar))) {
                    continue;
                }
                parsed.add(
                        new DtoFactOption(
                                scalar,
                                truncateText(String.valueOf(scalar), MAX_LABEL_LENGTH),
                                null,
                                null,
                                null));
            }
            return List.copyOf(parsed);
        }

        return List.of();
    }

    private static String allowedValueDedupKey(Object scalar) {
        if (scalar instanceof BigDecimal bd) {
            return "N:" + bd.stripTrailingZeros().toPlainString();
        }
        if (scalar instanceof Number num) {
            try {
                return "N:" + new BigDecimal(num.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return scalar.getClass().getName() + ":" + num;
            }
        }
        return scalar.getClass().getName() + ":" + scalar;
    }

    private static String openApiScalarTypeForValueType(Class<?> rawType, String valueType) {
        if ("boolean".equals(valueType)) {
            return "boolean";
        }
        if ("number".equals(valueType) || "number-array".equals(valueType)) {
            if (rawType == int.class
                    || rawType == Integer.class
                    || rawType == long.class
                    || rawType == Long.class) {
                return "integer";
            }
            return "number";
        }
        return "string";
    }

    private static Object extractTypedOptionValue(JsonNode valNode, String valueType) {
        if ("number".equals(valueType)
                || "number-array".equals(valueType)
                || "array<number>".equals(valueType)) {
            if (valNode.isNumber()) {
                return valNode.decimalValue();
            }
            return parseBigDecimal(valNode.asText(null));
        }
        if ("boolean".equals(valueType)) {
            if (valNode.isBoolean()) {
                return valNode.booleanValue();
            }
            String txt = valNode.asText("");
            if ("true".equalsIgnoreCase(txt)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(txt)) {
                return Boolean.FALSE;
            }
            return null;
        }
        String text = valNode.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Object coerceAllowableString(String raw, String valueType) {
        if ("number".equals(valueType)
                || "number-array".equals(valueType)
                || "array<number>".equals(valueType)) {
            return parseBigDecimal(raw);
        }
        if ("boolean".equals(valueType)) {
            if ("true".equalsIgnoreCase(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return Boolean.FALSE;
            }
            return null;
        }
        return raw;
    }

    private static DtoFactOptionSourceRef extractOptionSourceRef(
            String propertyName, UISchema uiSchema, String lineageSourceResourceKey) {
        String explicitSourceKey = extractExtraProperty(uiSchema, "optionSourceKey");
        String explicitResourceKey = extractExtraProperty(uiSchema, "optionSourceResourceKey");
        boolean isEntityLookup =
                uiSchema != null
                        && (uiSchema.controlType() == FieldControlType.ENTITY_LOOKUP
                                || uiSchema.preset() == UISchemaPreset.ENTITY_LOOKUP);
        if (!isEntityLookup && explicitSourceKey == null) {
            return null;
        }
        String sourceKey = explicitSourceKey;
        if (sourceKey == null && uiSchema != null && !uiSchema.endpoint().isBlank()) {
            sourceKey = deriveSourceKeyFromEndpoint(uiSchema.endpoint());
        }
        if (sourceKey == null) {
            sourceKey = propertyName;
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            return null;
        }
        String resourceKey = firstNonBlank(explicitResourceKey, lineageSourceResourceKey);
        return new DtoFactOptionSourceRef(
                truncateText(sourceKey.trim(), 128),
                resourceKey != null ? truncateText(resourceKey.trim(), 255) : null);
    }

    private static String deriveSourceKeyFromEndpoint(String endpoint) {
        String cleaned = endpoint.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.endsWith("/options/filter")) {
            cleaned = cleaned.substring(0, cleaned.length() - "/options/filter".length());
        } else if (cleaned.endsWith("/filter")) {
            cleaned = cleaned.substring(0, cleaned.length() - "/filter".length());
        } else if (cleaned.endsWith("/options")) {
            cleaned = cleaned.substring(0, cleaned.length() - "/options".length());
        }
        int slash = cleaned.lastIndexOf('/');
        String candidate = slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
        return candidate.isBlank() ? null : candidate;
    }

    private static DtoFactConstraints extractConstraints(
            Schema schema, UISchema uiSchema, AnnotatedElement... elements) {
        Min minAnn = findAnnotation(Min.class, elements);
        Max maxAnn = findAnnotation(Max.class, elements);
        DecimalMin decMinAnn = findAnnotation(DecimalMin.class, elements);
        DecimalMax decMaxAnn = findAnnotation(DecimalMax.class, elements);
        Size sizeAnn = findAnnotation(Size.class, elements);
        Pattern patternAnn = findAnnotation(Pattern.class, elements);

        BigDecimal min =
                firstNonNull(
                        uiSchema != null ? parseBigDecimal(uiSchema.numericMin()) : null,
                        uiSchema != null ? parseBigDecimal(uiSchema.min()) : null,
                        decMinAnn != null ? parseBigDecimal(decMinAnn.value()) : null,
                        minAnn != null ? BigDecimal.valueOf(minAnn.value()) : null,
                        schema != null ? parseBigDecimal(schema.minimum()) : null);
        BigDecimal max =
                firstNonNull(
                        uiSchema != null ? parseBigDecimal(uiSchema.numericMax()) : null,
                        uiSchema != null ? parseBigDecimal(uiSchema.max()) : null,
                        decMaxAnn != null ? parseBigDecimal(decMaxAnn.value()) : null,
                        maxAnn != null ? BigDecimal.valueOf(maxAnn.value()) : null,
                        schema != null ? parseBigDecimal(schema.maximum()) : null);
        if (min != null && max != null && min.compareTo(max) > 0) {
            min = null;
            max = null;
        }

        BigDecimal step = uiSchema != null ? parseBigDecimal(uiSchema.numericStep()) : null;
        if (step != null && step.compareTo(BigDecimal.ZERO) <= 0) {
            step = null;
        }

        Integer minLength = null;
        if (uiSchema != null && uiSchema.minLength() > 0) {
            minLength = uiSchema.minLength();
        } else if (sizeAnn != null && sizeAnn.min() > 0) {
            minLength = sizeAnn.min();
        } else if (schema != null && schema.minLength() > 0) {
            minLength = schema.minLength();
        }

        Integer maxLength = null;
        if (uiSchema != null && uiSchema.maxLength() > 0) {
            maxLength = uiSchema.maxLength();
        } else if (sizeAnn != null && sizeAnn.max() > 0 && sizeAnn.max() < Integer.MAX_VALUE) {
            maxLength = sizeAnn.max();
        } else if (schema != null && schema.maxLength() > 0 && schema.maxLength() < Integer.MAX_VALUE) {
            maxLength = schema.maxLength();
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            minLength = null;
            maxLength = null;
        }

        String rawPattern =
                firstNonBlank(
                        uiSchema != null && uiSchema.pattern() != null
                                ? uiSchema.pattern().getPattern()
                                : null,
                        patternAnn != null ? patternAnn.regexp() : null,
                        schema != null ? schema.pattern() : null);
        String validPattern = null;
        if (rawPattern != null && rawPattern.length() <= MAX_PATTERN_LENGTH) {
            try {
                java.util.regex.Pattern.compile(rawPattern);
                validPattern = rawPattern;
            } catch (IllegalArgumentException ignored) {
                // Skip invalid regex annotations cleanly.
            }
        }

        DtoFactConstraints constraints =
                new DtoFactConstraints(min, max, step, minLength, maxLength, validPattern);
        return constraints.isEmpty() ? null : constraints;
    }

    private static BigDecimal parseBigDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal firstNonNull(BigDecimal... candidates) {
        for (BigDecimal candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String extractExtraProperty(UISchema uiSchema, String key) {
        if (uiSchema == null || uiSchema.extraProperties() == null || key == null) {
            return null;
        }
        for (ExtensionProperty prop : uiSchema.extraProperties()) {
            if (prop != null && key.equalsIgnoreCase(prop.name()) && !prop.value().isBlank()) {
                return prop.value().trim();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String truncateText(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    /**
     * Mapeia a categoria semântica de {@link DomainGovernance} para os 4 tokens canônicos de
     * {@code sensitivity} exigidos pelo {@code DomainRuleService}
     * ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE}, {@code SECRET}).
     */
    public static String mapSensitivity(String dataCategory) {
        return mapSensitivity(dataCategory, null);
    }

    /**
     * Mapeia categoria e classificação de segurança de {@link DomainGovernance} para a taxonomia
     * canônica de {@code sensitivity} ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE},
     * {@code SECRET}).
     */
    public static String mapSensitivity(String dataCategory, String classification) {
        String cat = dataCategory == null ? "" : dataCategory.trim().toLowerCase(Locale.ROOT);
        String cls = classification == null ? "" : classification.trim().toLowerCase(Locale.ROOT);
        return switch (cat) {
            case "credential" -> "SECRET";
            case "sensitive_personal" -> "SENSITIVE";
            case "financial" -> "public".equals(cls) ? "NON_SENSITIVE" : "SENSITIVE";
            case "personal" -> "restricted".equals(cls) ? "SENSITIVE" : "PERSONAL";
            default -> "restricted".equals(cls) ? "SENSITIVE" : "NON_SENSITIVE";
        };
    }

    /**
     * Mapeia a classificação, categoria, visibilidade de IA e exportabilidade de
     * {@link DomainGovernance} para a política de redação canônica ({@code NONE}, {@code MASK},
     * {@code HASH}, {@code OMIT}).
     */
    public static String mapRedaction(
            String classification, String dataCategory, String aiVisibility, boolean exportable) {
        String cat = dataCategory == null ? "" : dataCategory.toLowerCase(Locale.ROOT);
        String vis = aiVisibility == null ? "allow" : aiVisibility.toLowerCase(Locale.ROOT);
        String cls = classification == null ? "internal" : classification.toLowerCase(Locale.ROOT);

        if ("credential".equals(cat) || "deny".equals(vis)) {
            return "OMIT";
        }
        if ("mask".equals(vis)
                || "sensitive_personal".equals(cat)
                || "personal".equals(cat)
                || ("financial".equals(cat)
                        && ("confidential".equals(cls) || "restricted".equals(cls)))
                || !exportable) {
            return "MASK";
        }
        return "NONE";
    }

    static String strictestSensitivity(String first, String second) {
        String n1 = RuleFactDescriptor.normalizeSensitivity(first);
        String n2 = RuleFactDescriptor.normalizeSensitivity(second);
        return sensitivityRank(n1) >= sensitivityRank(n2) ? n1 : n2;
    }

    static String strictestRedaction(String first, String second) {
        String r1 = first == null ? "NONE" : first.trim().toUpperCase(Locale.ROOT);
        String r2 = second == null ? "NONE" : second.trim().toUpperCase(Locale.ROOT);
        return redactionRank(r1) >= redactionRank(r2) ? r1 : r2;
    }

    private static int sensitivityRank(String canonical) {
        return switch (canonical) {
            case "SECRET" -> 3;
            case "SENSITIVE" -> 2;
            case "PERSONAL" -> 1;
            default -> 0;
        };
    }

    private static int redactionRank(String redaction) {
        return switch (redaction) {
            case "OMIT" -> 3;
            case "HASH" -> 2;
            case "MASK" -> 1;
            default -> 0;
        };
    }

    static String strictestClassification(String first, String second) {
        String c1 =
                first == null || first.isBlank()
                        ? "internal"
                        : first.trim().toLowerCase(Locale.ROOT);
        String c2 =
                second == null || second.isBlank()
                        ? "internal"
                        : second.trim().toLowerCase(Locale.ROOT);
        return classificationRank(c1) >= classificationRank(c2) ? c1 : c2;
    }

    private static int classificationRank(String classification) {
        return switch (classification) {
            case "restricted" -> 3;
            case "confidential" -> 2;
            case "internal" -> 1;
            case "public" -> 0;
            default -> -1;
        };
    }

    static String strictestAiVisibility(String first, String second) {
        String a1 =
                first == null || first.isBlank()
                        ? "allow"
                        : first.trim().toLowerCase(Locale.ROOT);
        String a2 =
                second == null || second.isBlank()
                        ? "allow"
                        : second.trim().toLowerCase(Locale.ROOT);
        return aiVisibilityRank(a1) >= aiVisibilityRank(a2) ? a1 : a2;
    }

    private static int aiVisibilityRank(String aiVisibility) {
        return switch (aiVisibility) {
            case "deny" -> 3;
            case "mask" -> 2;
            case "summarize_only" -> 1;
            case "allow" -> 0;
            default -> -1;
        };
    }

    private static String inferJavaValueType(Class<?> rawType, Type genericType) {
        if (rawType == null) {
            return "string";
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return "boolean";
        }
        if (rawType.isPrimitive() && rawType != char.class) {
            return "number";
        }
        if (Number.class.isAssignableFrom(rawType)) {
            return "number";
        }
        if (Temporal.class.isAssignableFrom(rawType) || Date.class.isAssignableFrom(rawType)) {
            return "date";
        }
        if (rawType.isArray()) {
            return inferArrayElementValueType(rawType.getComponentType());
        }
        if (Collection.class.isAssignableFrom(rawType)) {
            if (genericType instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 1) {
                return inferArrayElementValueType(
                        resolveTypeArgumentClass(pt.getActualTypeArguments()[0]));
            }
            return "string-array";
        }
        return "string";
    }

    private static String inferArrayElementValueType(Class<?> elemType) {
        if (elemType == null) {
            return "string-array";
        }
        if ((elemType.isPrimitive() && elemType != boolean.class && elemType != char.class)
                || Number.class.isAssignableFrom(elemType)) {
            return "number-array";
        }
        if (Temporal.class.isAssignableFrom(elemType) || Date.class.isAssignableFrom(elemType)) {
            return "date-array";
        }
        return "string-array";
    }

    private static boolean inferJavaNullable(
            Class<?> rawType, Schema schema, AnnotatedElement... elements) {
        if (rawType != null && rawType.isPrimitive()) {
            return false;
        }
        if (findAnnotation(NotNull.class, elements) != null
                || findAnnotation(NotBlank.class, elements) != null
                || findAnnotation(NotEmpty.class, elements) != null) {
            return false;
        }
        if (schema != null
                && schema.requiredMode() == Schema.RequiredMode.REQUIRED
                && !schema.nullable()) {
            return false;
        }
        return true;
    }

    private static <A extends java.lang.annotation.Annotation> A findAnnotation(
            Class<A> annotationType, AnnotatedElement... elements) {
        if (elements == null) {
            return null;
        }
        for (AnnotatedElement el : elements) {
            if (el != null) {
                A found = ResolverUtils.getAnnotation(annotationType, el.getAnnotations());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasAnyMetadataAnnotation(AnnotatedElement element) {
        return ResolverUtils.getAnnotation(Schema.class, element.getAnnotations()) != null
                || ResolverUtils.getAnnotation(UISchema.class, element.getAnnotations()) != null
                || ResolverUtils.getAnnotation(DomainGovernance.class, element.getAnnotations())
                        != null
                || ResolverUtils.getAnnotation(DomainLineage.class, element.getAnnotations())
                        != null;
    }

    private static Field findDeclaredFieldQuietly(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Method findGetter(Class<?> type, String fieldName) {
        String capitalized =
                Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (String candidate : List.of("get" + capitalized, "is" + capitalized, fieldName)) {
            try {
                Method m = type.getMethod(candidate);
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static String getterPropertyName(String methodName) {
        if (methodName.startsWith("get")
                && methodName.length() > 3
                && !"getClass".equals(methodName)) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return null;
    }
}
