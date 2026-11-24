package org.praxisplatform.uischema.util;

import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void invalidatesCachedIndexWhenSupplierAddsReplacesOrRemovesGroupsAndPreservesTieOrder() {
        GroupedOpenApi firstShared = GroupedOpenApi.builder()
                .group("first-shared")
                .pathsToMatch("/api/shared/**")
                .build();
        GroupedOpenApi secondShared = GroupedOpenApi.builder()
                .group("second-shared")
                .pathsToMatch("/api/shared/**")
                .build();
        GroupedOpenApi broad = GroupedOpenApi.builder()
                .group("broad")
                .pathsToMatch("/api/**")
                .build();

        AtomicReference<List<GroupedOpenApi>> ref = new AtomicReference<>(new ArrayList<>(List.of(broad, firstShared, secondShared)));
        OpenApiGroupResolver resolver = new OpenApiGroupResolver(ref::get);

        // Most specific wins over broad; tie preserves registration order (first-shared before second-shared)
        assertEquals("first-shared", resolver.resolveGroup("/api/shared/items"));
        assertEquals("broad", resolver.resolveGroup("/api/other/items"));
        assertTrue(resolver.hasRegisteredGroup("first-shared"));
        assertTrue(resolver.hasRegisteredGroups());

        // Dynamic registration of an even more specific group invalidates cache immediately
        GroupedOpenApi specific = GroupedOpenApi.builder()
                .group("specific-items")
                .pathsToMatch("/api/shared/items/**")
                .build();
        ref.set(List.of(broad, firstShared, secondShared, specific));

        assertEquals("specific-items", resolver.resolveGroup("/api/shared/items/filter"));
        assertTrue(resolver.hasRegisteredGroup("specific-items"));

        // Dynamic replacement/removal invalidates cache immediately
        ref.set(List.of(broad, secondShared));
        assertEquals("second-shared", resolver.resolveGroup("/api/shared/items"));
        assertFalse(resolver.hasRegisteredGroup("first-shared"));
        assertFalse(resolver.hasRegisteredGroup("specific-items"));

        // Empty list updates hasRegisteredGroups immediately
        ref.set(List.of());
        assertNull(resolver.resolveGroup("/api/shared/items"));
        assertFalse(resolver.hasRegisteredGroups());
    }
}
