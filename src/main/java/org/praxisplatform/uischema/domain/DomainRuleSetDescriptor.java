package org.praxisplatform.uischema.domain;

import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Descritor imutável que projeta um {@link RuleSetDefinition} governado do {@code praxis-rules-engine}
 * e seus metadados de razão/campo-alvo no catálogo semântico {@code /schemas/domain}.
 *
 * @param resourceKey chave canônica do recurso governado (ex.: {@code ergon.designacoes-chefia})
 * @param definition contrato compilável do conjunto de regras (incluindo {@code availableRoots} e {@code referencedFactPaths})
 * @param label rótulo legível para exibição em catálogo e cockpit
 * @param description descrição funcional do conjunto de regras
 * @param conceptRootBindings explicit root-to-role/owner identity for this RuleSet operation
 * @param reasonDescriptors mapa opcional indexado pelo {@code reasonCode} (ex.: {@code ERG-01151}) com campo-alvo e linhagem
 */
public record DomainRuleSetDescriptor(
        String resourceKey,
        RuleSetDefinition definition,
        String label,
        String description,
        Map<String, RuleReasonDescriptor> reasonDescriptors,
        Map<String, ConceptRootBinding> conceptRootBindings
) {

    public DomainRuleSetDescriptor {
        definition = Objects.requireNonNull(definition, "definition is required");
        resourceKey = normalizeNullable(resourceKey);
        if (resourceKey == null) {
            resourceKey = inferResourceKey(definition.ref());
        }
        String defaultLabel = "RuleSet " + definition.ref().ruleSetKey();
        String defaultDescription = "Conjunto de regras determinísticas ("
                + definition.ref().ruleSetKey()
                + ") para a operação "
                + definition.ref().operationKey()
                + " do recurso "
                + resourceKey
                + ".";
        label = normalizeNullable(label) != null ? normalizeNullable(label) : defaultLabel;
        description = normalizeNullable(description) != null ? normalizeNullable(description) : defaultDescription;
        reasonDescriptors = immutableReasons(reasonDescriptors);
        conceptRootBindings = validateConceptBindings(definition, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(RuleSetDefinition definition, Map<String, ConceptRootBinding> conceptRootBindings) {
        return new DomainRuleSetDescriptor(null, definition, null, null, Map.of(), conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            String resourceKey,
            RuleSetDefinition definition,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return new DomainRuleSetDescriptor(resourceKey, definition, null, null, Map.of(), conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            RuleSetDefinition definition,
            Collection<RuleReasonDescriptor> reasons,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return of(null, definition, null, null, reasons, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            String resourceKey,
            RuleSetDefinition definition,
            Collection<RuleReasonDescriptor> reasons,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return of(resourceKey, definition, null, null, reasons, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            RuleSetDefinition definition,
            String label,
            String description,
            Collection<RuleReasonDescriptor> reasons,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return of(null, definition, label, description, reasons, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            String resourceKey,
            RuleSetDefinition definition,
            String label,
            String description,
            Collection<RuleReasonDescriptor> reasons,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        Map<String, RuleReasonDescriptor> byCode = new LinkedHashMap<>();
        if (reasons != null) {
            for (RuleReasonDescriptor reason : reasons) {
                if (reason != null && reason.code() != null && !reason.code().isBlank()) {
                    byCode.put(reason.code().trim(), reason);
                }
            }
        }
        return new DomainRuleSetDescriptor(resourceKey, definition, label, description, byCode, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            RuleSetDefinition definition,
            RuleErrorCatalog errorCatalog,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return of(null, definition, null, null, errorCatalog, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            String resourceKey,
            RuleSetDefinition definition,
            RuleErrorCatalog errorCatalog,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        return of(resourceKey, definition, null, null, errorCatalog, conceptRootBindings);
    }

    public static DomainRuleSetDescriptor of(
            String resourceKey,
            RuleSetDefinition definition,
            String label,
            String description,
            RuleErrorCatalog errorCatalog,
            Map<String, ConceptRootBinding> conceptRootBindings
    ) {
        Objects.requireNonNull(definition, "definition is required");
        if (errorCatalog == null) {
            return new DomainRuleSetDescriptor(resourceKey, definition, label, description, Map.of(), conceptRootBindings);
        }
        Map<String, RuleReasonDescriptor> byCode = new LinkedHashMap<>();
        if (definition.slots() != null) {
            for (DecisionSlot slot : definition.slots()) {
                if (slot != null && slot.parameterTemplate() != null) {
                    String templateCode = normalizeNullable(slot.parameterTemplate().falseReasonCode());
                    if (templateCode != null) {
                        errorCatalog.find(templateCode).ifPresent(desc ->
                                byCode.putIfAbsent(templateCode, toReasonDescriptor(desc, slot.slotKey())));
                    }
                }
            }
        }
        if (definition.bindings() != null) {
            for (DecisionBinding binding : definition.bindings()) {
                if (binding == null || !binding.enabled()) {
                    continue;
                }
                String reasonCode = normalizeNullable(binding.falseReasonCode());
                if (reasonCode != null) {
                    errorCatalog.find(reasonCode).ifPresent(desc ->
                            byCode.put(reasonCode, toReasonDescriptor(desc, binding.slotKey())));
                }
                String bindingKey = normalizeNullable(binding.bindingKey());
                if (bindingKey != null && !byCode.containsKey(bindingKey)) {
                    errorCatalog.find(bindingKey).ifPresent(desc ->
                            byCode.put(bindingKey, toReasonDescriptor(desc, binding.slotKey())));
                }
            }
        }
        return new DomainRuleSetDescriptor(resourceKey, definition, label, description, byCode, conceptRootBindings);
    }

    private static Map<String, ConceptRootBinding> validateConceptBindings(
            RuleSetDefinition definition, Map<String, ConceptRootBinding> input) {
        Objects.requireNonNull(input, "conceptRootBindings is required; use an explicit empty map only without concepts");
        var result = new java.util.TreeMap<String, ConceptRootBinding>();
        var roles = new java.util.HashSet<String>();
        var structural = java.util.Set.of("draft", "current", "neighborhood", "context", "tenant", "facts", "derived", "scope");
        input.forEach((root, binding) -> {
            if (root == null || !root.matches("[A-Za-z_][A-Za-z0-9_]*") || structural.contains(root)
                    || !definition.availableRoots().contains(root) || binding == null) {
                throw new IllegalArgumentException("Invalid concept root binding: " + root);
            }
            if (!roles.add(binding.role())) throw new IllegalArgumentException("Ambiguous concept role: " + binding.role());
            result.put(root, binding);
        });
        var missing = new java.util.TreeSet<>(definition.conceptRoots());
        missing.removeAll(result.keySet());
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing concept root bindings: " + missing);
        // Engine's legacy collection classification remains until the governed provider cut in G3.
        // Explicit bindings are never filtered by name, including legitimate active*/existing* concepts.
        return Collections.unmodifiableMap(result);
    }

    private static RuleReasonDescriptor toReasonDescriptor(RuleErrorDescriptor desc, String slotKey) {
        return new RuleReasonDescriptor(
                desc.code(),
                slotKey,
                desc.targetField(),
                desc.messagePtBr(),
                desc.category(),
                desc.lineage()
        );
    }

    public RuleSetDefinition ruleSetDefinition() {
        return definition;
    }

    public String ruleSetKey() {
        return definition.ref().ruleSetKey();
    }

    public String operationKey() {
        return definition.ref().operationKey();
    }

    public List<String> availableRoots() {
        return definition.availableRoots();
    }

    public List<String> conceptRoots() {
        return List.copyOf(conceptRootBindings.keySet());
    }

    public List<String> referencedFactPaths() {
        return definition.referencedFactPaths();
    }

    public Map<String, List<String>> factPathsByRoot() {
        return definition.factPathsByRoot();
    }

    private static String inferResourceKey(RuleSetRef ref) {
        String boundedContext = ref.boundedContextKey();
        if (boundedContext.contains(".")) {
            return boundedContext;
        }
        String ruleSetKey = ref.ruleSetKey();
        String marker = "." + boundedContext + ".";
        int idx = ruleSetKey.indexOf(marker);
        if (idx > 0) {
            return ruleSetKey.substring(0, idx + 1 + boundedContext.length());
        }
        return ref.domainKey() + "." + boundedContext;
    }

    /**
     * Metadado público de um código de razão determinístico ({@code falseReasonCode}) associado a um
     * {@code DecisionBinding}, conectando a violação de regra ao atributo do contrato ({@code targetField})
     * e à linhagem normativa/legada.
     *
     * @param code código canônico da decisão (ex.: {@code ERG-01151})
     * @param slotKey chave do {@code DecisionSlot} associado
     * @param targetField campo principal do DTO/contrato governado pela regra
     * @param message mensagem oficial de negócio
     * @param origin rotina ou política de origem
     * @param lineage predicado ou fundamentação original da regra
     */
    public record RuleReasonDescriptor(
            String code,
            String slotKey,
            String targetField,
            String message,
            String origin,
            String lineage
    ) {
        public RuleReasonDescriptor {
            code = Objects.requireNonNull(code, "code is required").trim();
            if (code.isEmpty()) {
                throw new IllegalArgumentException("code must not be blank");
            }
            slotKey = normalizeNullable(slotKey);
            targetField = normalizeNullable(targetField);
            message = normalizeNullable(message);
            origin = normalizeNullable(origin);
            lineage = normalizeNullable(lineage);
        }
    }

    private static Map<String, RuleReasonDescriptor> immutableReasons(Map<String, RuleReasonDescriptor> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, RuleReasonDescriptor> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                copy.put(key.trim(), value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
