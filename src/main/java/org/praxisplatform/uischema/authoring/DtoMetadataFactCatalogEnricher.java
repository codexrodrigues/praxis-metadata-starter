package org.praxisplatform.uischema.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
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
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.extension.ResolverUtils;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.schema.DomainSchemaGovernance;

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

    private final ConcurrentMap<Class<?>, Map<String, DtoFactPropertyMetadata>> dtoCache =
            new ConcurrentHashMap<>();

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
     * @param helpTextPtBr orientação operacional complementar ({@code @UISchema.helpText})
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
            String lineageTransformation) {

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
         * Retorna a descrição negocial enriquecida para consumo humano/LLM, incorporando
         * {@code helpTextPtBr} quando distinto de {@code descriptionPtBr}.
         */
        public String richDescriptionPtBr() {
            if (descriptionPtBr != null && !descriptionPtBr.isBlank()) {
                if (helpTextPtBr != null
                        && !helpTextPtBr.isBlank()
                        && !descriptionPtBr.contains(helpTextPtBr)) {
                    return descriptionPtBr + " Orientação operacional: " + helpTextPtBr;
                }
                return descriptionPtBr;
            }
            if (helpTextPtBr != null && !helpTextPtBr.isBlank()) {
                return helpTextPtBr;
            }
            return labelPtBr != null && !labelPtBr.isBlank() ? labelPtBr : propertyName;
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
            String classification =
                    w.explicitGovernanceDeclared() ? w.classification() : r.classification();
            String dataCategory =
                    w.explicitGovernanceDeclared() ? w.dataCategory() : r.dataCategory();
            String sensitivity =
                    w.explicitGovernanceDeclared() ? w.sensitivity() : r.sensitivity();
            String redaction = w.explicitGovernanceDeclared() ? w.redaction() : r.redaction();
            boolean exportable = w.explicitGovernanceDeclared() ? w.exportable() : r.exportable();
            String aiVisibility =
                    w.explicitGovernanceDeclared() ? w.aiVisibility() : r.aiVisibility();
            List<String> tags =
                    !w.complianceTags().isEmpty() ? w.complianceTags() : r.complianceTags();

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
                            lineageTrans));
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
        String normalized = factPath.trim().replace(":items", "").replace("[]", "");
        int dot = normalized.indexOf('.');
        if (dot <= 0 || dot >= normalized.length() - 1) {
            return Optional.empty();
        }
        String root = normalized.substring(0, dot);
        String remainder = normalized.substring(dot + 1);
        String leafProperty =
                remainder.contains(".")
                        ? remainder.substring(remainder.lastIndexOf('.') + 1)
                        : remainder;

        Class<?> boundDto = rootDtoBindings.get(root);
        if (boundDto == null) {
            return Optional.empty();
        }
        Map<String, DtoFactPropertyMetadata> dtoProps = inspectDto(boundDto);
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

                String ptLabel = desc.label("pt-BR");
                if ((ptLabel.equals(desc.factPath()) || ptLabel.equalsIgnoreCase(meta.propertyName()))
                        && meta.labelPtBr() != null
                        && !meta.labelPtBr().isBlank()) {
                    ptLabel = meta.labelPtBr();
                }
                String enLabel = desc.labels().getOrDefault("en-US", ptLabel);

                String curatedPtDesc = desc.description("pt-BR");
                String ptDesc = mergeDescriptionsPtBr(curatedPtDesc, meta);
                String enDesc = desc.descriptions().getOrDefault("en-US", ptDesc);

                node.putObject("labels").put("pt-BR", ptLabel).put("en-US", enLabel);
                node.putObject("descriptions").put("pt-BR", ptDesc).put("en-US", enDesc);
                node.put("providerRef", desc.providerRef());

                Set<String> mergedEvidence = new LinkedHashSet<>(desc.evidenceRefs());
                mergedEvidence.addAll(meta.buildEvidenceRefs(null));
                ArrayNode ev = node.putArray("evidenceRefs");
                mergedEvidence.forEach(ev::add);

                node.put(
                        "sensitivity",
                        strictestSensitivity(desc.canonicalSensitivity(), meta.sensitivity()));
                node.put("redaction", strictestRedaction(desc.redaction(), meta.redaction()));
            } else if (curated.isPresent()) {
                RuleFactDescriptor desc = curated.get();
                String ptLabel = desc.label("pt-BR");
                String enLabel = desc.labels().getOrDefault("en-US", ptLabel);
                String ptDesc = desc.description("pt-BR");
                String enDesc = desc.descriptions().getOrDefault("en-US", ptDesc);
                node.putObject("labels").put("pt-BR", ptLabel).put("en-US", enLabel);
                node.putObject("descriptions").put("pt-BR", ptDesc).put("en-US", enDesc);
                node.put("providerRef", desc.providerRef());
                ArrayNode ev = node.putArray("evidenceRefs");
                desc.evidenceRefs().forEach(ev::add);
                node.put("sensitivity", desc.canonicalSensitivity());
                node.put("redaction", desc.redaction());
            } else if (dtoMeta.isPresent()) {
                DtoFactPropertyMetadata meta = dtoMeta.get();
                String labelPt =
                        meta.labelPtBr() != null && !meta.labelPtBr().isBlank()
                                ? meta.labelPtBr()
                                : meta.propertyName();
                String descPt = meta.richDescriptionPtBr();
                node.putObject("labels").put("pt-BR", labelPt).put("en-US", labelPt);
                node.putObject("descriptions").put("pt-BR", descPt).put("en-US", descPt);
                node.put("providerRef", providerPrefix + "." + stablePath);
                ArrayNode ev = node.putArray("evidenceRefs");
                for (String ref : meta.buildEvidenceRefs(ruleSetKey)) {
                    ev.add(ref);
                }
                node.put("sensitivity", meta.sensitivity());
                node.put("redaction", meta.redaction());
            } else {
                node.putObject("labels").put("pt-BR", stablePath).put("en-US", stablePath);
                node.putObject("descriptions")
                        .put(
                                "pt-BR",
                                "Fato de avaliação do RuleSet "
                                        + ruleSetKey
                                        + " ("
                                        + stablePath
                                        + ").")
                        .put(
                                "en-US",
                                "Evaluation fact for RuleSet "
                                        + ruleSetKey
                                        + " ("
                                        + stablePath
                                        + ").");
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
        if (valueTypeResolver != null) {
            String custom = valueTypeResolver.apply(rawPath);
            if (custom != null && RuleFactDescriptor.ALLOWED_VALUE_TYPES.contains(custom.trim())) {
                return custom.trim();
            }
        }
        if (curated.isPresent()) {
            return curated.get().valueType();
        }
        if (opCatalog.isPresent() && hasSchemaNode(opCatalog.get(), rawPath)) {
            return opCatalog.get().inferValueType(rawPath);
        }
        if (dtoMeta.isPresent()) {
            return dtoMeta.get().valueType();
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

    private static boolean hasSchemaNode(RuleFactOperationCatalog opCatalog, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String ptr =
                RuleFactDescriptor.factPathToSchemaPointer(rawPath, opCatalog.schema().schema());
        JsonNode target = opCatalog.schema().schema().at(ptr.substring(1));
        return target != null && !target.isMissingNode() && !target.isNull();
    }

    private static String mergeDescriptionsPtBr(
            String curatedPtDesc, DtoFactPropertyMetadata dtoMeta) {
        String dtoRich = dtoMeta.descriptionPtBr();
        if (dtoRich == null || dtoRich.isBlank()) {
            return curatedPtDesc;
        }
        if (curatedPtDesc == null
                || curatedPtDesc.isBlank()
                || isGenericTemplateDescription(curatedPtDesc)) {
            return dtoMeta.richDescriptionPtBr();
        }
        if (curatedPtDesc.contains(dtoRich)) {
            return curatedPtDesc;
        }
        if (dtoRich.contains(curatedPtDesc)) {
            return dtoRich;
        }
        return dtoRich + " Contexto no RuleSet: " + curatedPtDesc;
    }

    private static boolean isGenericTemplateDescription(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("Projeção primitiva de ")
                || trimmed.startsWith("Fato de avaliação do RuleSet ")
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

        String helpTextPtBr =
                uiSchema != null && !uiSchema.helpText().isBlank()
                        ? uiSchema.helpText().trim()
                        : null;
        String descriptionPtBr = null;
        if (schema != null && !schema.description().isBlank()) {
            descriptionPtBr = schema.description().trim();
        } else if (helpTextPtBr != null) {
            descriptionPtBr = helpTextPtBr;
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
                if (tag.isTextual() && !tag.asText().isBlank()) {
                    complianceTags.add(tag.asText().trim());
                }
            }
        }

        String sensitivity = mapSensitivity(dataCategory, classification);
        String redaction = mapRedaction(classification, dataCategory, aiVisibility, exportable);

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
                lineageTransformation);
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
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> elemClass) {
                return inferArrayElementValueType(elemClass);
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
