package org.praxisplatform.uischema.util;

import org.springdoc.core.models.GroupedOpenApi;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

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

    private final Supplier<List<GroupedOpenApi>> groupedOpenApisSupplier;

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

        List<GroupedOpenApi> groupedOpenApis = groupedOpenApisSupplier.get();
        if (groupedOpenApis == null || groupedOpenApis.isEmpty()) {
            return null;
        }

        String normalizedPath = requestPath.trim().replaceAll("/+", "/");
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        // 1. Tentar correspondencia direta
        String match = findBestMatch(groupedOpenApis, normalizedPath);
        if (match != null) {
            return match;
        }

        // 2. Tentar alternar o prefixo /api/
        if (normalizedPath.startsWith("/api/")) {
            match = findBestMatch(groupedOpenApis, normalizedPath.substring(4));
            if (match != null) {
                return match;
            }
        } else {
            match = findBestMatch(groupedOpenApis, "/api" + normalizedPath);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    private String findBestMatch(List<GroupedOpenApi> groupedOpenApis, String candidatePath) {
        String bestMatch = null;
        int bestMatchLength = 0;

        for (GroupedOpenApi groupedOpenApi : groupedOpenApis) {
            List<String> patterns = groupedOpenApi.getPathsToMatch();
            if (patterns == null) {
                continue;
            }

            for (String pattern : patterns) {
                String normalized = normalize(pattern);

                if (matchesPathBoundary(candidatePath, normalized)) {
                    if (normalized.length() > bestMatchLength) {
                        bestMatch = groupedOpenApi.getGroup();
                        bestMatchLength = normalized.length();
                    }
                }
            }
        }

        return bestMatch;
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
}
