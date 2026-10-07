package org.praxisplatform.uischema.util;

import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OpenApiGroupResolverTest {

    @Test
    void resolvesGroupByPrefixMatch() {
        GroupedOpenApi funcionarios = GroupedOpenApi.builder()
                .group("funcionarios")
                .pathsToMatch("/api/human-resources/funcionarios/**")
                .build();

        OpenApiGroupResolver resolver = new OpenApiGroupResolver(List.of(funcionarios));

        String group = resolver.resolveGroup("/api/human-resources/funcionarios/filter");
        assertEquals("funcionarios", group);
    }

    @Test
    void doesNotMatchSiblingResourceWithSharedPrefix() {
        GroupedOpenApi vinculos = GroupedOpenApi.builder()
                .group("vinculos")
                .pathsToMatch("/api/administracao-pessoal/vinculos/**")
                .build();

        OpenApiGroupResolver resolver = new OpenApiGroupResolver(List.of(vinculos));

        assertNull(resolver.resolveGroup("/api/administracao-pessoal/vinculos-funcionais/{id}/documentos-legais"));
    }

    @Test
    void exactBasePathStillMatches() {
        GroupedOpenApi employees = GroupedOpenApi.builder()
                .group("employees")
                .pathsToMatch("/employees", "/employees/**")
                .build();

        OpenApiGroupResolver resolver = new OpenApiGroupResolver(List.of(employees));

        assertEquals("employees", resolver.resolveGroup("/employees"));
    }

    @Test
    void resolvesGroupWhenPathLacksLeadingSlashAndApiPrefix() {
        GroupedOpenApi funcionarios = GroupedOpenApi.builder()
                .group("funcionarios")
                .pathsToMatch("/api/human-resources/funcionarios/**")
                .build();

        OpenApiGroupResolver resolver = new OpenApiGroupResolver(List.of(funcionarios));

        // Sem barra inicial e sem /api
        String group = resolver.resolveGroup("human-resources/funcionarios/filter");
        assertEquals("funcionarios", group);
    }

    @Test
    void resolvesGroupWhenGroupPatternLacksApiPrefixAndRequestHasIt() {
        GroupedOpenApi assets = GroupedOpenApi.builder()
                .group("assets")
                .pathsToMatch("/assets/**")
                .build();

        OpenApiGroupResolver resolver = new OpenApiGroupResolver(List.of(assets));

        // Request com /api/ mas grupo definido sem /api/
        String group = resolver.resolveGroup("/api/assets/equipamentos/filter");
        assertEquals("assets", group);
    }
}
