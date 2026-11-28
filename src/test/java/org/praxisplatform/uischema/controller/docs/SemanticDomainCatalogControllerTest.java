package org.praxisplatform.uischema.controller.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.uischema.domain.DomainCatalogResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SemanticDomainCatalogControllerTest {

    @Mock
    private org.praxisplatform.uischema.domain.SemanticDomainCatalogService semanticDomainCatalogService;

    private SemanticDomainCatalogController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SemanticDomainCatalogController(semanticDomainCatalogService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private DomainCatalogResponse sampleResponse(String resourceKey) {
        return new DomainCatalogResponse(
                "0.2",
                new DomainCatalogResponse.DomainServiceInfo("ergon-showcase", "Ergon Showcase", "1.0.0"),
                new DomainCatalogResponse.DomainReleaseInfo("dev", "2026-11-27T00:00:00Z", "sha256:test"),
                resourceKey,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Test
    void getDomainByResourceReturnsPrivateNoCacheAndFullTenantVaryHeaders() throws Exception {
        String resourceKey = "administracao-pessoal.ferias-gozo";
        DomainCatalogResponse expected = sampleResponse(resourceKey);
        when(semanticDomainCatalogService.findByResourceKey(resourceKey)).thenReturn(expected);

        mockMvc.perform(get("/schemas/domain").param("resource", resourceKey))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(
                        HttpHeaders.VARY,
                        "X-Tenant, X-Tenant-ID, X-Tenant-Realm, X-Organization-ID"
                ));

        var direct = controller.getDomain(resourceKey, null, null, false);
        assertEquals(HttpStatus.OK, direct.getStatusCode());
        assertEquals("no-cache, private", direct.getHeaders().getCacheControl());
        assertIterableEquals(
                List.of("X-Tenant", "X-Tenant-ID", "X-Tenant-Realm", "X-Organization-ID"),
                direct.getHeaders().getVary()
        );
        assertNotNull(direct.getBody());
        assertEquals(resourceKey, direct.getBody().resourceKey());
        verify(semanticDomainCatalogService, times(2)).findByResourceKey(resourceKey);
    }

    @Test
    void getDomainByGroupAndStrictResourceAliasEmitFullVaryHeaders() throws Exception {
        String group = "administracao-pessoal";
        String resourceKey = "administracao-pessoal.vinculos";
        when(semanticDomainCatalogService.findByGroup(group)).thenReturn(sampleResponse(null));
        when(semanticDomainCatalogService.findByGroupStrict(group)).thenReturn(sampleResponse(null));
        when(semanticDomainCatalogService.findByResourceKeyStrict(resourceKey)).thenReturn(sampleResponse(resourceKey));

        mockMvc.perform(get("/schemas/domain").param("group", group))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(
                        HttpHeaders.VARY,
                        "X-Tenant, X-Tenant-ID, X-Tenant-Realm, X-Organization-ID"
                ));

        mockMvc.perform(get("/schemas/domain")
                        .param("group", group)
                        .param("strict", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(
                        HttpHeaders.VARY,
                        "X-Tenant, X-Tenant-ID, X-Tenant-Realm, X-Organization-ID"
                ));

        mockMvc.perform(get("/schemas/domain")
                        .param("resourceKey", resourceKey)
                        .param("strict", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.VARY,
                        "X-Tenant, X-Tenant-ID, X-Tenant-Realm, X-Organization-ID"
                ));

        verify(semanticDomainCatalogService).findByGroup(group);
        verify(semanticDomainCatalogService).findByGroupStrict(group);
        verify(semanticDomainCatalogService).findByResourceKeyStrict(resourceKey);
    }

    @Test
    void getDomainAcceptsMatchingResourceAndResourceKeyAliasWithVaryHeaders() throws Exception {
        String resourceKey = "administracao-pessoal.cargos";
        when(semanticDomainCatalogService.findByResourceKey(resourceKey)).thenReturn(sampleResponse(resourceKey));

        mockMvc.perform(get("/schemas/domain")
                        .param("resource", resourceKey)
                        .param("resourceKey", resourceKey))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(
                        HttpHeaders.VARY,
                        "X-Tenant, X-Tenant-ID, X-Tenant-Realm, X-Organization-ID"
                ));

        verify(semanticDomainCatalogService).findByResourceKey(resourceKey);
    }

    @Test
    void getDomainRejectsConflictingOrAmbiguousParametersWithBadRequest() throws Exception {
        mockMvc.perform(get("/schemas/domain")
                        .param("resource", "administracao-pessoal.ferias-gozo")
                        .param("group", "administracao-pessoal"))
                .andExpect(status().isBadRequest());

        ResponseStatusException mismatch = assertThrows(
                ResponseStatusException.class,
                () -> controller.getDomain("a", "b", null, false)
        );
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatusCode());

        ResponseStatusException missingBoth = assertThrows(
                ResponseStatusException.class,
                () -> controller.getDomain(null, null, null, false)
        );
        assertEquals(HttpStatus.BAD_REQUEST, missingBoth.getStatusCode());
    }
}
