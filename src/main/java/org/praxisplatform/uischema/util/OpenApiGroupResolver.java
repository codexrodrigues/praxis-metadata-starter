package org.praxisplatform.uischema.util;

import org.springdoc.core.models.GroupedOpenApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Resolvedor de grupos OpenAPI com estrategia de {@code best match}.
 *
 * <p>
 * Esta classe decide qual grupo OpenAPI melhor representa um determinado path HTTP. Em vez de
 * retornar o primeiro match encontrado, ela percorre todos os grupos registrados e prioriza o
 * padrao mais especifico, o que evita cair em grupos genericos como {@code application} quando
 * existe um grupo mais aderente ao recurso.
 * </p>
 *
 * <p>
 * Esse comportamento e central para a superficie documental do starter, especialmente para
 * {@code /schemas/filtered} e {@code /schemas/catalog}, que dependem de uma resolucao confiavel
 * do documento OpenAPI fonte.
 * </p>
 *
 * @since 1.0.0
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 */
public class OpenApiGroupResolver {

    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/+");

    private final Supplier<List<GroupedOpenApi>> groupedOpenApisSupplier;
    private final Object indexMonitor = new Object();
    private volatile GroupIndex cachedIndex;

    public OpenApiGroupResolver(List<GroupedOpenApi> groupedOpenApis) {
        this(() -> groupedOpenApis == null ? Collections.emptyList() : groupedOpenApis);
    }

    public OpenApiGroupResolver(Supplier<List<GroupedOpenApi>> groupedOpenApisSupplier) {
        this.groupedOpenApisSupplier = groupedOpenApisSupplier == null
                ? Collections::emptyList
                : groupedOpenApisSupplier;
    }

    /**
     * Resolve o grupo OpenAPI mais especifico para o path informado.
     *
     * <p>
     * O algoritmo percorre todos os {@link GroupedOpenApi} registrados, normaliza os
     * {@code pathsToMatch} removendo wildcards e escolhe o match de maior comprimento.
     * Assim, paths especificos vencem padroes genericos.
     * </p>
     * <p>
     * Inclui tolerancia a paths sem barra inicial e a presenca ou ausencia do prefixo {@code /api}.
     * </p>
     *
     * @param requestPath path da requisicao HTTP
     * @return nome do grupo com melhor match ou {@code null} quando nenhum padrao se aplicar
     */
    public String resolveGroup(String requestPath) {
        if (requestPath == null) {
            return null;
        }

        GroupIndex index = currentIndex();
        if (!index.hasGroups()) {
            return null;
        }

        String normalizedPath = requestPath.trim();
        if (normalizedPath.contains("//")) {
            normalizedPath = MULTIPLE_SLASHES.matcher(normalizedPath).replaceAll("/");
        }
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        // 1. Tentar correspondencia direta
        String match = findBestMatch(index, normalizedPath);
        if (match != null) {
            return match;
        }

        // 2. Tentar alternar o prefixo /api/
        if (normalizedPath.startsWith("/api/")) {
            match = findBestMatch(index, normalizedPath.substring(4));
            if (match != null) {
                return match;
            }
        } else {
            match = findBestMatch(index, "/api" + normalizedPath);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    /**
     * Indica se um grupo OpenAPI com o nome exato informado esta registrado nos {@link GroupedOpenApi}s ativos.
     *
     * @param groupName nome do grupo (ex.: {@code "application"})
     * @return {@code true} quando o grupo existe na configuracao atual
     */
    public boolean hasRegisteredGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return false;
        }
        return currentIndex().registeredGroups().contains(groupName);
    }

    /**
     * Indica se ha ao menos um {@link GroupedOpenApi} registrado neste resolvedor.
     */
    public boolean hasRegisteredGroups() {
        return currentIndex().hasGroups();
    }

    private GroupIndex currentIndex() {
        List<GroupedOpenApi> groupedOpenApis = groupedOpenApisSupplier.get();
        if (groupedOpenApis == null || groupedOpenApis.isEmpty()) {
            return GroupIndex.EMPTY;
        }
        GroupIndex current = this.cachedIndex;
        if (matchesSnapshot(current, groupedOpenApis)) {
            return current;
        }
        synchronized (indexMonitor) {
            current = this.cachedIndex;
            if (matchesSnapshot(current, groupedOpenApis)) {
                return current;
            }
            GroupIndex rebuilt = buildIndex(groupedOpenApis);
            this.cachedIndex = rebuilt;
            return rebuilt;
        }
    }

    private static boolean matchesSnapshot(GroupIndex index, List<GroupedOpenApi> current) {
        if (index == null || index.sourceRefs().length != current.size()) {
            return false;
        }
        GroupedOpenApi[] refs = index.sourceRefs();
        List<?>[] patternRefs = index.patternListRefs();
        int[] patternSizes = index.patternListSizes();
        for (int i = 0; i < refs.length; i++) {
            GroupedOpenApi candidate = current.get(i);
            if (candidate != refs[i]) {
                return false;
            }
            if (candidate != null) {
                List<String> patterns = candidate.getPathsToMatch();
                if (patterns != patternRefs[i] || (patterns != null && patterns.size() != patternSizes[i])) {
                    return false;
                }
            }
        }
        return true;
    }

    private GroupIndex buildIndex(List<GroupedOpenApi> groupedOpenApis) {
        int size = groupedOpenApis.size();
        GroupedOpenApi[] sourceRefs = new GroupedOpenApi[size];
        List<?>[] patternListRefs = new List<?>[size];
        int[] patternListSizes = new int[size];
        Set<String> registeredGroups = new HashSet<>();
        List<PatternRule> rules = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            GroupedOpenApi groupedOpenApi = groupedOpenApis.get(i);
            sourceRefs[i] = groupedOpenApi;
            if (groupedOpenApi == null) {
                continue;
            }
            String groupName = groupedOpenApi.getGroup();
            if (groupName != null) {
                registeredGroups.add(groupName);
            }
            List<String> patterns = groupedOpenApi.getPathsToMatch();
            patternListRefs[i] = patterns;
            if (patterns == null) {
                continue;
            }
            patternListSizes[i] = patterns.size();
            for (String pattern : patterns) {
                String normalized = normalize(pattern);
                if (!normalized.isEmpty()) {
                    rules.add(new PatternRule(groupName, normalized));
                }
            }
        }
        // Stable descending length sort preserves first-registered precedence on equal-length ties.
        rules.sort(Comparator.comparingInt((PatternRule rule) -> rule.normalizedPattern().length()).reversed());
        return new GroupIndex(
                true,
                sourceRefs,
                patternListRefs,
                patternListSizes,
                Set.copyOf(registeredGroups),
                rules.toArray(PatternRule[]::new)
        );
    }

    private String findBestMatch(GroupIndex index, String candidatePath) {
        for (PatternRule rule : index.rules()) {
            if (matchesPathBoundary(candidatePath, rule.normalizedPattern())) {
                return rule.groupName();
            }
        }
        return null;
    }

    /**
     * Normaliza um padrao removendo wildcards finais para comparacao de especificidade.
     *
     * @param pattern padrao original com wildcards
     * @return padrao normalizado
     */
    private String normalize(String pattern) {
        if (pattern == null) {
            return "";
        }

        if (pattern.endsWith("/**")) {
            return pattern.substring(0, pattern.length() - 3);
        }

        if (pattern.endsWith("/*")) {
            return pattern.substring(0, pattern.length() - 2);
        }

        return pattern;
    }

    private boolean matchesPathBoundary(String requestPath, String normalizedPattern) {
        if (normalizedPattern == null || normalizedPattern.isEmpty()) {
            return false;
        }
        if (requestPath.equals(normalizedPattern)) {
            return true;
        }
        return requestPath.startsWith(normalizedPattern + "/");
    }

    private record PatternRule(String groupName, String normalizedPattern) {
    }

    private record GroupIndex(
            boolean hasGroups,
            GroupedOpenApi[] sourceRefs,
            List<?>[] patternListRefs,
            int[] patternListSizes,
            Set<String> registeredGroups,
            PatternRule[] rules
    ) {
        private static final GroupIndex EMPTY = new GroupIndex(
                false,
                new GroupedOpenApi[0],
                new List<?>[0],
                new int[0],
                Set.of(),
                new PatternRule[0]
        );
    }
}
