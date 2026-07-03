package org.praxisplatform.uischema.controller.cockpit;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.constants.ApiPaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PraxisCockpitControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PraxisCockpitController())
            .build();

    @Test
    void redirectsCockpitBasePathToBundledStaticPage() throws Exception {
        mockMvc.perform(get(ApiPaths.Framework.COCKPIT))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ApiPaths.Framework.COCKPIT_INDEX));
    }

    @Test
    void redirectsCockpitSlashPathToBundledStaticPage() throws Exception {
        mockMvc.perform(get(ApiPaths.Framework.COCKPIT + "/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ApiPaths.Framework.COCKPIT_INDEX));
    }

    @Test
    void bundlesCockpitStaticEntryPointInStarterJarResources() {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");

        assertThat(resource.exists()).isTrue();
    }

    @Test
    void bundlesPraxisBrandAssetsInStarterJarResources() {
        ClassPathResource lightMark = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/brand/praxis-mark-light.png");
        ClassPathResource darkMark = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/brand/praxis-mark-dark.png");

        assertThat(lightMark.exists()).isTrue();
        assertThat(darkMark.exists()).isTrue();
    }

    @Test
    void bundlesCockpitGraphRuntimeWithoutExternalNetworkDependency() throws IOException {
        ClassPathResource cytoscape = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/vendor/cytoscape/cytoscape.min.js");
        ClassPathResource cytoscapeLicense = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/vendor/cytoscape/LICENSE.txt");
        ClassPathResource scriptResource = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/cockpit.js");
        ClassPathResource styleResource = new ClassPathResource(
                "META-INF/resources/praxis/cockpit/assets/cockpit.css");
        String script = new String(scriptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String style = new String(styleResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(cytoscape.exists()).isTrue();
        assertThat(cytoscapeLicense.exists()).isTrue();
        assertThat(script)
                .contains("./assets/vendor/cytoscape/cytoscape.min.js")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(style)
                .doesNotContain("fonts.googleapis.com")
                .doesNotContain("fonts.gstatic.com")
                .doesNotContain("@import url(")
                .contains(".endpoint-row {\n    gap: 2px;\n    grid-template-columns: 1fr;");
    }

    @Test
    void bundledCockpitEntryPointExposesElementsExpectedByScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("/praxis/cockpit/assets/brand/praxis-mark-light.png")
                .contains("/praxis/cockpit/assets/cockpit.js?v=20260702-decision-summary")
                .contains("id=\"domainTitle\"")
                .contains("id=\"hostDecisionTitle\"")
                .contains("id=\"decisionResourceCount\"")
                .contains("id=\"hostDecisionAttention\"")
                .contains("id=\"domainAreaCount\"")
                .contains("id=\"domainResourceCount\"")
                .contains("Materialização por domínio")
                .contains("Onde a UI pode ser gerada")
                .contains("id=\"domainCoverageChart\"")
                .contains("id=\"renderableStackChart\"")
                .contains("id=\"filterPower\"")
                .contains("id=\"hostScorecards\"")
                .contains("id=\"hostAttention\"")
                .contains("id=\"domainAreas\"")
                .contains("id=\"resourceFilterChips\"")
                .contains("id=\"attentionNow\"")
                .contains("id=\"domainTopology\"")
                .contains("id=\"semanticReadiness\"")
                .contains("id=\"renderabilityMatrix\"")
                .contains("id=\"resourceChart\"")
                .contains("id=\"filterInsights\"")
                .contains("id=\"workflowRail\"")
                .contains("id=\"endpointMatrix\"")
                .contains("id=\"fieldSummary\"")
                .contains("id=\"diagnosticsList\"");
    }

    @Test
    void bundledCockpitScriptUsesScopedSemanticDiscoveryAndDecisionPanels() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/assets/cockpit.js");
        String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("/schemas/domain?resourceKey=")
                .contains("/schemas/surfaces?resource=")
                .contains("/schemas/actions?resource=")
                .contains("function renderAttentionNow")
                .contains("function renderSemanticReadiness")
                .contains("function renderPlatformIntelligence")
                .contains("function renderHostDecision")
                .contains("function renderRenderableStack")
                .contains("function layerEvidenceSummary")
                .contains("function renderExperienceResourceChips")
                .contains("function statusClassFromCount")
                .contains("function renderHostScorecards")
                .contains("function renderHostAttention")
                .contains("function catalogDiagnostics")
                .contains("fetchJson('/actuator/health', { timeoutMs: 10000 })")
                .contains("fetchJson('/schemas/catalog', { timeoutMs: 15000 })")
                .contains("fetchJson('/v3/api-docs/swagger-config', { timeoutMs: 10000 })")
                .contains("const catalogs = await discoverCatalogs(valueOrNull(catalog), valueOrNull(swaggerConfig));")
                .contains("function discoverCatalogs")
                .contains("function openApiGroupNames")
                .contains("fetchJson(`/schemas/catalog?group=${encodeURIComponent(group)}`, { timeoutMs: 5000 })")
                .contains("return [...catalogs, ...groupCatalogs];")
                .contains("const aggregateGroups = groups.filter((name) => !name.startsWith('api-'));")
                .contains("return aggregateGroups.length ? aggregateGroups : groups;")
                .contains("function catalogEndpoints(catalog)")
                .contains("return catalog.flatMap((item) => catalogEndpoints(item));")
                .contains("catálogo(s) de domínio")
                .contains("function hasPublishedActionSignal")
                .contains("function endpointKey")
                .contains("function startCapabilityVerification")
                .contains("function verifyResourceCapability")
                .contains("function startActionVerification")
                .contains("function verifyResourceActions")
                .contains("data-graph-zoom=\"out\"")
                .contains("data-graph-fullscreen")
                .contains("data-graph-mode=\"clean\"")
                .contains("data-graph-search")
                .contains("data-graph-search-status")
                .contains("data-node-priority")
                .contains("function bindTopologyFullscreenControl")
                .contains("function bindTopologySearchControl")
                .contains("function updateTopologySearchStatus")
                .contains("function topologyModeSummary")
                .contains("function hasConfirmedIdentity")
                .contains("capabilityErrors")
                .contains("Capabilities retornaram erro")
                .contains("function renderResourceFilters")
                .contains("function matchesResourceFilter")
                .contains("function resourceListSignal")
                .contains("function resourceRenderability")
                .contains("function areaLayerCoverage")
                .contains("function renderDomainLayerCoverage")
                .contains("function topologyNodeTheme")
                .contains("function topologyEdgeColor")
                .contains("function capabilitySource")
                .contains("function evidenceStatusLabel")
                .contains("function renderRenderability")
                .contains("function renderResourceAnalytics")
                .contains("function groupEndpointsByOperation")
                .contains("function classifyEndpointFromCapabilities")
                .contains("function classifyEndpointFallback")
                .contains("function isActionDiscoveryEndpoint")
                .contains("function isWorkflowActionEndpoint")
                .contains("function sourceLabelList")
                .contains("function groupIntent")
                .contains("function canonicalResourceKey")
                .contains("function semanticCacheKey")
                .contains("function canonicalAreaKey")
                .contains("function nonGenericGroup")
                .contains("group: canonicalAreaKey(endpoint.resourceKey, endpoint.group, resourcePath, endpoint.resourceVisual)")
                .contains("domainFromResourceKey(resourceKey)")
                .contains("'application', 'default', 'api', 'apis', 'openapi'")
                .contains("graph-label-muted")
                .contains("{ selector: '.graph-label-muted', style: { label: '' } }")
                .contains("Rótulos secundários entram quando você busca")
                .contains("resourceKey = canonicalResourceKey(resource, resourcePath);")
                .contains("sourceConfidence: endpoint.resourceKey ? 'resourceKey' : 'path-fallback'")
                .contains("resource.inferredResourceKey = resource.resourceKey ? null : resourceKeyFromPath(resource.resourcePath);")
                .contains("return resource.resourceKey || null;")
                .contains("Identidade inferida por path")
                .contains("state.selectionToken")
                .contains("endpoint.operation.sourceLabel")
                .contains("fallback inferido")
                .contains("enrichmentState = 'loading'")
                .contains("schema de request")
                .contains("semantic-graph-open-resource")
                .contains("data-graph-resource-key")
                .doesNotContain("fetchJson('/schemas/domain')")
                .doesNotContain("fetchJson('/schemas/surfaces')")
                .doesNotContain("fetchJson('/schemas/actions')")
                .doesNotContain("state.resources = composeResources(valueOrNull(catalog))")
                .doesNotContain("if (groupCatalogs.length) return groupCatalogs;")
                .doesNotContain("group: endpoint.group || domainFromResourceKey(endpoint.resourceKey)")
                .doesNotContain("resource.resourceKey = resource.resourceKey || resourceKeyFromPath(resource.resourcePath)")
                .doesNotContain("actions: capabilitySource(Boolean((resource.actions || []).length), false, Boolean(summary.actions))")
                .doesNotContain("por cento das camadas materializáveis disponíveis em média")
                .doesNotContain("""
                        const linkedResource = resourceFromTopologyNode(data);
                                  if (linkedResource && linkedResource.key !== state.selectedKey) {
                                    selectResource(linkedResource.key);
                                  }
                        """);

        assertThat(script.indexOf("const fromCapability = classifyEndpointFromCapabilities(resource, endpoint);"))
                .isLessThan(script.indexOf("const fromSurface = classifyEndpointFromMaterialization(resource.surfaces, endpoint, 'surface');"));
    }
}
