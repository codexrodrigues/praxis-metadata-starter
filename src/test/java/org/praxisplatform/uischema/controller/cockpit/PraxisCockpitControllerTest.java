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
    void bundledCockpitEntryPointExposesElementsExpectedByScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("/praxis/cockpit/assets/brand/praxis-mark-light.png")
                .contains("id=\"domainTitle\"")
                .contains("id=\"domainAreaCount\"")
                .contains("id=\"domainResourceCount\"")
                .contains("Cobertura por domínio")
                .contains("Camadas por recurso")
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
                .contains("function renderHostScorecards")
                .contains("function renderHostAttention")
                .contains("function catalogDiagnostics")
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
                .contains("resourceKey = canonicalResourceKey(resource, resourcePath);")
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
