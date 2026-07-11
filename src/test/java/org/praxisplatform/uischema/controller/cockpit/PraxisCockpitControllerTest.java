package org.praxisplatform.uischema.controller.cockpit;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.constants.ApiPaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        String style = new String(styleResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

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
                .contains(".release-marker")
                .contains(".resource-brief")
                .contains(".resource-brief-grid button::after")
                .contains("display: none")
                .contains(".resource-brief-grid button:hover::after")
                .contains("display: block")
                .contains(".contract-links")
                .contains("align-items: start")
                .contains(".contract-group")
                .contains(".contract-group-heading")
                .contains(".contract-link")
                .contains(".exploration-journey")
                .contains(".contract-loading-progress")
                .contains(".contract-loading-progress.is-complete .contract-loading-steps")
                .contains(".contract-loading-steps li.is-running")
                .contains(".contract-loading-bar span")
                .contains(".decision-actions")
                .contains(".domain-heatmap")
                .contains(".domain-heatmap-cell")
                .contains(".experience-resource-summary")
                .contains(".detail-module > summary")
                .contains("width: 100%;")
                .contains(".workspace-toolbar")
                .contains(".cockpit-shell.is-domain-map-collapsed")
                .contains(".cockpit-shell.is-resource-panel-collapsed")
                .contains(".cockpit-shell.is-domain-map-collapsed .workbench {\n  grid-column: 1;\n}")
                .contains(".cockpit-shell.is-domain-map-collapsed .domain-map { display: block; }")
                .contains(".cockpit-shell.is-resource-panel-collapsed .resource-panel { display: grid; }")
                .contains("@media (min-width: 1760px)")
                .contains("@media (min-width: 1181px)")
                .contains(".exploration-journey {\n    border: 1px solid rgba(154, 172, 194, .16);\n    background: rgba(6, 13, 19, .94);\n    position: sticky;")
                .contains(".exploration-journey a.is-active")
                .contains(".cockpit-view-switcher")
                .contains(".cockpit-shell.is-cockpit-view-overview .workbench")
                .contains(".cockpit-shell.is-cockpit-view-explore .detail-panel")
                .contains(".cockpit-shell.is-cockpit-view-detail .resource-panel")
                .contains("button.domain-heatmap-cell")
                .contains("scroll-margin-top: 82px;")
                .contains(".cockpit-view-switcher { grid-column: 1 / -1; grid-row: 5; }")
                .contains(".control-plane { grid-row: 7; }")
                .contains(".workspace-toolbar {\n  grid-column: 1 / -1;\n  grid-row: 8;")
                .contains(".domain-map {\n  grid-column: 1;\n  grid-row: 9;")
                .contains(".workbench {\n  grid-column: 2;\n  grid-row: 9;")
                .contains("grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);")
                .contains(".exploration-journey { order: 4; }")
                .contains(".intelligence-strip { order: 6; }")
                .contains(".control-plane { order: 7; }")
                .contains(".domain-map { order: 8; }")
                .contains(".workbench { order: 9; }")
                .contains(".semantic-graph-routes p {\n    display: none;")
                .contains(".endpoint-row {\n    gap: 2px;\n    grid-template-columns: 1fr;");
    }

    @Test
    void bundledCockpitEntryPointExposesElementsExpectedByScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(html)
                .contains("/praxis/cockpit/assets/brand/praxis-mark-light.png")
                .contains("/praxis/cockpit/assets/cockpit.css?v=20260711-contract-feedback")
                .contains("/praxis/cockpit/assets/cockpit.js?v=20260711-contract-feedback")
                .contains("href=\"/v3/api-docs\"")
                .contains("id=\"domainTitle\"")
                .contains("id=\"releaseMarker\"")
                .contains("id=\"hostDecisionTitle\"")
                .contains("id=\"contractLoadingProgress\"")
                .contains("id=\"contractLoadingPhase\"")
                .contains("id=\"contractLoadingDetail\"")
                .contains("id=\"contractLoadingBar\"")
                .contains("id=\"contractLoadingSteps\"")
                .contains("id=\"decisionResourceCount\"")
                .contains("id=\"hostDecisionAttention\"")
                .contains("id=\"domainAreaCount\"")
                .contains("id=\"domainResourceCount\"")
                .contains("class=\"decision-actions\"")
                .contains("Explorar recursos")
                .contains("Ver lacunas")
                .contains("class=\"exploration-journey\"")
                .contains("href=\"#controlPlane\"")
                .contains("href=\"#domainMap\"")
                .contains("href=\"#resourceWorkbench\"")
                .contains("id=\"workspaceControls\"")
                .contains("data-workspace-toggle=\"domain\"")
                .contains("data-workspace-toggle=\"resources\"")
                .contains("data-workspace-toggle=\"focus\"")
                .contains("id=\"resourcePanel\"")
                .contains("class=\"detail-module attention-panel\"")
                .contains("class=\"detail-module evidence-panel\"")
                .contains("Heatmap domínio x capacidade")
                .contains("Onde o host está pronto e onde falta evidência")
                .contains("Distribuição de capacidades")
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
                .contains("id=\"contractLinks\"")
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
                .contains("function renderExperienceResourceSummary")
                .contains("function layerEvidenceSummary")
                .doesNotContain("function renderExperienceResourceChips")
                .contains("function statusClassFromCount")
                .contains("function renderHostScorecards")
                .contains("function renderHostAttention")
                .contains("function toggleWorkspace")
                .contains("function renderWorkspaceControls")
                .contains("function syncCompactWorkspace")
                .contains("function wireExplorationJourney")
                .contains("function setCockpitView")
                .contains("function renderCockpitView")
                .contains("function heatmapFilter")
                .contains("setCockpitView('explore')")
                .contains("data-area=\"${escapeAttr(areaKey || '')}\"")
                .contains("aria-current', 'location'")
                .contains("window.matchMedia('(max-width: 1180px)')")
                .contains("window.addEventListener('resize', () => syncCompactWorkspace(compactWorkspace))")
                .contains("state.workspace.focus = false;\n    renderWorkspaceControls();")
                .contains("function catalogDiagnostics")
                .contains("function expectsFormMaterialization")
                .contains("function expectsWorkflowAction")
                .contains("formExpectedResources")
                .contains("Apto para UI operacional metadata-driven")
                .contains("Prontidão metadata-driven")
                .contains("O health não entra no score semântico")
                .contains("resource.schemaLinks.length || resource.fieldList.length")
                .contains("Identidade publicada")
                .contains("Identidade verificada")
                .contains("formulários esperados")
                .contains("const readySentence")
                .doesNotContain("resource-brief-label")
                .contains("os demais ficam como CRUD, consulta ou analytics sem bloquear")
                .contains("trackContractRead('health', '/actuator/health', { timeoutMs: 10000 })")
                .contains("trackContractRead('build', '/actuator/info', { timeoutMs: 10000 })")
                .contains("trackContractRead('catalog', '/schemas/catalog', { timeoutMs: 15000 })")
                .contains("trackContractRead('openApi', '/v3/api-docs/swagger-config', { timeoutMs: 10000 })")
                .contains("const catalogs = await discoverCatalogs(valueOrNull(catalog), valueOrNull(swaggerConfig));")
                .contains("function discoverCatalogs")
                .contains("function openApiGroupNames")
                .contains("fetchJson(`/schemas/catalog?group=${encodeURIComponent(group)}`, { timeoutMs: 15000 })")
                .contains("return [...catalogs, ...groupCatalogs];")
                .contains("const aggregateGroups = groups.filter((name) => !name.startsWith('api-'));")
                .contains("return aggregateGroups.length ? aggregateGroups : groups;")
                .contains("function catalogEndpoints(catalog)")
                .contains("return catalog.flatMap((item) => catalogEndpoints(item));")
                .contains("catálogos de domínio")
                .contains("function hasPublishedActionSignal")
                .contains("function knownActionsForResource")
                .contains("function endpointKey")
                .contains("function startCapabilityVerification")
                .contains("function beginContractLoadingProgress")
                .contains("function trackContractRead")
                .contains("function updateBackgroundLoadingProgress")
                .contains("function renderContractLoadingProgress")
                .contains("classList.toggle('is-complete', isComplete)")
                .contains("Lendo contratos:")
                .contains("Leitura dos contratos concluída")
                .contains("function shouldVerifyCapabilities")
                .contains("return Boolean(resource?.resourceKey)")
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
                .contains("function renderResourceExecutiveSummary")
                .contains("function renderReleaseMarker")
                .contains("function formatBuildTime")
                .contains("data-resource-filter-shortcut")
                .contains("data-tooltip=\"${escapeAttr(shortcut.label)}\"")
                .contains("aria-label=\"${escapeAttr(`${shortcut.label}: ${shortcut.value}`)}\"")
                .contains("resource-brief-icon")
                .contains("function cockpitIcon")
                .contains("Leitura rápida")
                .contains("Investigar agora")
                .contains("function matchesResourceFilter")
                .contains("function resourceListSignal")
                .contains("function resourceRenderability")
                .contains("const actions = knownActionsForResource(resource)")
                .contains("const hasCapabilitySnapshot = Boolean(resource.capability);")
                .contains("analytics: capabilitySource(hasStatsOperation, false, !hasCapabilitySnapshot && Boolean(summary.stats))")
                .contains("function areaLayerCoverage")
                .contains("function renderDomainLayerCoverage")
                .contains("function topologyNodeTheme")
                .contains("function topologyEdgeColor")
                .contains("function capabilitySource")
                .contains("function evidenceStatusLabel")
                .contains("function renderRenderability")
                .contains("function renderResourceAnalytics")
                .contains("if (profile.evidence.analytics === 'confirmed' && dimension && resource.resourcePath)")
                .contains("function renderContractLinks")
                .contains("function contractLinkGroups")
                .contains("function resourceContractLinks")
                .contains("state.swaggerConfig = valueOrNull(swaggerConfig);")
                .contains("function openApiGroupForResource")
                .contains("function openApiGroups")
                .contains("/swagger-ui/index.html?urls.primaryName=")
                .contains("areaOpenApi?.url || '/v3/api-docs'")
                .contains("/schemas/domain?resourceKey=")
                .contains("title: 'Explorar API'")
                .contains("title: 'Auditar contrato'")
                .contains("title: 'Entender semântica'")
                .contains("function groupEndpointsByOperation")
                .contains("function classifyEndpointFromCapabilities")
                .contains("function classifyEndpointFallback")
                .contains("function isActionDiscoveryEndpoint")
                .contains("function isWorkflowActionEndpoint")
                .contains("duplicate-draft")
                .contains("function sourceLabelList")
                .contains("function groupIntent")
                .contains("function canonicalResourceKey")
                .contains("function semanticCacheKey")
                .contains("function canonicalAreaKey")
                .contains("if (!endpoint.resourceKey)")
                .contains("sourceConfidence: 'resourceKey'")
                .contains("function nonGenericGroup")
                .contains("group: canonicalAreaKey(endpoint.resourceKey, endpoint.group, resourcePath, endpoint.resourceVisual, endpoint.groupVisual)")
                .contains("groupVisual: endpoint.groupVisual || catalog.groupVisual || null")
                .contains("resource.groupVisual?.title || labelForArea(resource.group)")
                .contains("domainFromResourceKey(resourceKey)")
                .contains("'application', 'default', 'api', 'apis', 'openapi'")
                .contains("graph-label-muted")
                .contains("{ selector: '.graph-label-muted', style: { label: '' } }")
                .contains("Rótulos secundários entram quando você busca")
                .contains("resourceKey = canonicalResourceKey(resource, resourcePath);")
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
                .contains("data-graph-filter=\"journey\"")
                .contains("function domainJourneyForResource")
                .contains("business_journey")
                .contains("current?.group !== nextArea")
                .contains("selectResource(firstResourceInArea.key, { view: 'explore' })")
                .doesNotContain("fetchJson('/schemas/domain')")
                .doesNotContain("fetchJson('/schemas/surfaces')")
                .doesNotContain("fetchJson('/schemas/actions')")
                .doesNotContain("state.resources = composeResources(valueOrNull(catalog))")
                .doesNotContain("if (groupCatalogs.length) return groupCatalogs;")
                .doesNotContain("group: endpoint.group || domainFromResourceKey(endpoint.resourceKey)")
                .doesNotContain("resource.resourceKey = resource.resourceKey || resourceKeyFromPath(resource.resourcePath)")
                .doesNotContain("actions: capabilitySource(Boolean((resource.actions || []).length), false, Boolean(summary.actions))")
                .doesNotContain("const workflowComplete = hasResources && metrics.actionResources === metrics.resources")
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

    @Test
    void cockpitReadinessUsesMaterializedFieldsAsSemanticBaseEvidence() throws IOException {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/praxis/cockpit/assets/cockpit.js");
        String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("function computeHostReadiness")
                .contains("resources.some((resource) => resource.schemaLinks.length || resource.fieldList.length)")
                .doesNotContain("resources.some((resource) => resource.schemaLinks.length),");

        HostReadiness readiness = hostReadinessFor(
                true,
                List.of(new ReadinessResource(false, true, true)),
                "UP");

        assertThat(readiness.score()).isEqualTo(100);
        assertThat(readiness.label()).isEqualTo("base semântica operacional");
        assertThat(readiness.hint())
                .isEqualTo("Prontidão metadata-driven; Saúde operacional do host: UP. "
                        + "O health não entra no score semântico.");
    }

    private HostReadiness hostReadinessFor(
            boolean catalogOk,
            List<ReadinessResource> resources,
            String healthStatus) {
        List<Boolean> semanticChecks = List.of(
                catalogOk,
                !resources.isEmpty(),
                resources.stream().anyMatch(resource -> resource.hasSchemaLinks() || resource.hasFieldList()),
                resources.stream().anyMatch(ReadinessResource::hasResourceKey));
        long passedChecks = semanticChecks.stream().filter(Boolean::booleanValue).count();
        int score = Math.round((passedChecks / (float) semanticChecks.size()) * 100);
        String healthHint = healthStatus == null || healthStatus.isBlank()
                ? "Saúde operacional do host indisponível."
                : "Saúde operacional do host: " + healthStatus + ".";
        return new HostReadiness(
                score,
                score == 100 ? "base semântica operacional" : score + "%",
                "Prontidão metadata-driven; " + healthHint + " O health não entra no score semântico.");
    }

    private record ReadinessResource(boolean hasSchemaLinks, boolean hasFieldList, boolean hasResourceKey) {
    }

    private record HostReadiness(int score, String label, String hint) {
    }
}
