(function () {
  const state = {
    resources: [],
    areas: [],
    selectedKey: null,
    selectedArea: null,
    resourceFilter: 'all',
    topologyGraph: null,
    cytoscapeLoader: null,
    backgroundCapabilities: {
      running: false,
      checked: 0,
      total: 0
    },
    backgroundActions: {
      running: false,
      checked: 0,
      total: 0
    },
    health: null,
    info: null,
    endpointStatus: {},
    capabilities: new Map(),
    capabilityErrors: new Map(),
    filteredSchemas: new Map(),
    statsPreviews: new Map(),
    optionLabelCache: new Map(),
    domains: new Map(),
    surfaces: new Map(),
    actions: new Map(),
    frontendResources: new Map(),
    discovery: {
      frameworkEndpoints: [],
      derivedEndpoints: [],
      partialEndpoints: []
    }
  };

  const els = {
    hostStatusDot: document.getElementById('hostStatusDot'),
    hostStatusText: document.getElementById('hostStatusText'),
    hostStatusDetail: document.getElementById('hostStatusDetail'),
    releaseMarker: document.getElementById('releaseMarker'),
    domainTitle: document.getElementById('domainTitle'),
    domainSummary: document.getElementById('domainSummary'),
    hostDecisionTitle: document.getElementById('hostDecisionTitle'),
    hostDecisionSummary: document.getElementById('hostDecisionSummary'),
    decisionResourceCount: document.getElementById('decisionResourceCount'),
    decisionFormCount: document.getElementById('decisionFormCount'),
    decisionFilterCount: document.getElementById('decisionFilterCount'),
    decisionWorkflowCount: document.getElementById('decisionWorkflowCount'),
    hostDecisionAttention: document.getElementById('hostDecisionAttention'),
    domainAreaCount: document.getElementById('domainAreaCount'),
    domainAreaHint: document.getElementById('domainAreaHint'),
    domainResourceCount: document.getElementById('domainResourceCount'),
    operationCount: document.getElementById('operationCount'),
    fieldCount: document.getElementById('fieldCount'),
    readinessScore: document.getElementById('readinessScore'),
    readinessHint: document.getElementById('readinessHint'),
    capabilityMode: document.getElementById('capabilityMode'),
    domainCoverageChart: document.getElementById('domainCoverageChart'),
    renderableStackChart: document.getElementById('renderableStackChart'),
    filterPower: document.getElementById('filterPower'),
    filterPowerDetail: document.getElementById('filterPowerDetail'),
    hostScorecards: document.getElementById('hostScorecards'),
    hostAttention: document.getElementById('hostAttention'),
    sourceMode: document.getElementById('sourceMode'),
    domainAreas: document.getElementById('domainAreas'),
    resourceSearch: document.getElementById('resourceSearch'),
    resourceFilterChips: document.getElementById('resourceFilterChips'),
    resourceList: document.getElementById('resourceList'),
    selectedDomain: document.getElementById('selectedDomain'),
    selectedTitle: document.getElementById('selectedTitle'),
    selectedSubtitle: document.getElementById('selectedSubtitle'),
    resourceReadiness: document.getElementById('resourceReadiness'),
    attentionNow: document.getElementById('attentionNow'),
    businessMeaning: document.getElementById('businessMeaning'),
    integrationMeaning: document.getElementById('integrationMeaning'),
    gapMeaning: document.getElementById('gapMeaning'),
    domainTopology: document.getElementById('domainTopology'),
    semanticReadiness: document.getElementById('semanticReadiness'),
    renderabilityMatrix: document.getElementById('renderabilityMatrix'),
    resourceChart: document.getElementById('resourceChart'),
    filterInsights: document.getElementById('filterInsights'),
    workflowRail: document.getElementById('workflowRail'),
    endpointMatrix: document.getElementById('endpointMatrix'),
    fieldSummary: document.getElementById('fieldSummary'),
    diagnosticsList: document.getElementById('diagnosticsList'),
    refreshResourceButton: document.getElementById('refreshResourceButton')
  };

  const areaLabels = {
    'human-resources': 'Pessoas e RH',
    operations: 'Operações',
    assets: 'Ativos Operacionais',
    'risk-intelligence': 'Inteligência de Risco',
    procurement: 'Suprimentos',
    praxis: 'Plataforma Praxis'
  };

  const areaDescriptions = {
    'human-resources': 'Pessoas, perfis, reputação, folha e vínculos que sustentam a operação.',
    operations: 'Missões, bases, equipes, incidentes, eventos e acordos regulatórios.',
    assets: 'Veículos, equipamentos e alocações necessários para executar missões.',
    'risk-intelligence': 'Ameaças, incidentes e sinais analíticos para tomada de decisão.',
    procurement: 'Fornecedores, contratos, empresas, produtos e ordens de compra.',
    praxis: 'Runtime e configuração da própria plataforma Praxis.'
  };

  const areaThemes = {
    'human-resources': { a: '#73efb5', b: '#62d8ff', icon: 'people' },
    operations: { a: '#62d8ff', b: '#b7a5ff', icon: 'route' },
    procurement: { a: '#ffd166', b: '#73efb5', icon: 'chain' },
    assets: { a: '#5ee7df', b: '#7aa7ff', icon: 'box' },
    'risk-intelligence': { a: '#ff6d91', b: '#ffd166', icon: 'radar' },
    praxis: { a: '#b7a5ff', b: '#62d8ff', icon: 'spark' },
    default: { a: '#9eacbd', b: '#62d8ff', icon: 'node' }
  };

  document.addEventListener('DOMContentLoaded', init);

  async function init() {
    try {
      wireEvents();
      await loadHost();
    } catch (error) {
      const detail = error?.message || 'erro inesperado durante a leitura do host';
      console.error('[Praxis Cockpit] Falha ao carregar host', error);
      setHostStatus('error', 'Falha ao carregar contratos do host.', detail);
      els.domainTitle.textContent = 'Não foi possível materializar o domínio do host';
      els.domainSummary.textContent = 'O cockpit recebeu uma falha durante a leitura ou renderização dos contratos publicados.';
    }
  }

  function wireEvents() {
    els.resourceSearch.addEventListener('input', renderResourceList);
    els.hostAttention.addEventListener('click', (event) => {
      const button = event.target.closest('button[data-key]');
      if (button) {
        selectResource(button.dataset.key);
      }
    });
    els.renderableStackChart.addEventListener('click', (event) => {
      const button = event.target.closest('button[data-key]');
      if (button) {
        selectResource(button.dataset.key);
      }
    });
    els.resourceFilterChips.querySelectorAll('button[data-filter]').forEach((button) => {
      button.addEventListener('click', () => {
        state.resourceFilter = button.dataset.filter || 'all';
        renderResourceFilters();
        renderResourceList();
      });
    });
    els.refreshResourceButton.addEventListener('click', async () => {
      const resource = selectedResource();
      if (resource) {
        await enrichResource(resource, true);
        renderDetail(resource);
      }
    });
  }

  async function loadHost() {
    setHostStatus('loading', 'Lendo contratos publicados pelo host...', 'Catálogo, health e endpoints metadata-driven.');
    renderReleaseMarker();
    const [health, info, catalog, swaggerConfig, frontendResources] = await Promise.allSettled([
      fetchJson('/actuator/health', { timeoutMs: 10000 }),
      fetchJson('/actuator/info', { timeoutMs: 10000 }),
      fetchJson('/schemas/catalog', { timeoutMs: 15000 }),
      fetchJson('/v3/api-docs/swagger-config', { timeoutMs: 10000 }),
      fetchJson('/assets/frontend-resources.json', { timeoutMs: 10000 })
    ]);
    const catalogs = await discoverCatalogs(valueOrNull(catalog), valueOrNull(swaggerConfig));
    const groupCatalogCount = catalogs.filter((item) => item?.group && item.group !== 'application').length;

    state.health = valueOrNull(health);
    state.info = valueOrNull(info);
    renderReleaseMarker(state.info);
    state.frontendResources = frontendResourceIndex(valueOrNull(frontendResources));
    state.endpointStatus = {
      health: endpointStatus(health),
      catalog: endpointStatus(catalog),
      openApiGroups: endpointStatus(swaggerConfig),
      groupCatalogs: {
        ok: groupCatalogCount > 0 || endpointStatus(catalog).ok,
        message: groupCatalogCount > 0 ? `${groupCatalogCount} catálogo(s) por grupo lido(s)` : 'catálogo default'
      }
    };
    state.resources = composeResources(catalogs);
    state.areas = composeAreas(state.resources);

    renderOverview();
    renderAreas();
    renderResourceFilters();
    renderResourceList();

    if (state.resources.length) {
      selectResource(state.resources[0].key);
      setHostStatus('ok', 'Host conectado ao cockpit.', 'O starter está lendo contratos reais deste serviço.');
      startCapabilityVerification();
      startActionVerification();
    } else {
      setHostStatus('error', 'Nenhum recurso de domínio encontrado.', 'Verifique /schemas/catalog e os grupos publicados pelo host.');
      renderEmptyDetail();
    }
  }

  async function startCapabilityVerification() {
    if (state.backgroundCapabilities.running) return;
    const candidates = state.resources.filter((resource) => resource.resourcePath || resource.paths?.[0]);
    state.backgroundCapabilities = {
      running: true,
      checked: candidates.filter((resource) => state.capabilities.has(resource.key)).length,
      total: candidates.length
    };
    renderOverview();
    const queue = candidates.slice();
    const workers = Array.from({ length: Math.min(4, queue.length) }, () => verifyCapabilityQueue(queue));
    await Promise.all(workers);
    state.backgroundCapabilities.running = false;
    renderOverview();
    renderResourceList();
    const resource = selectedResource();
    if (resource) {
      renderDetail(resource);
    }
  }

  async function verifyCapabilityQueue(queue) {
    while (queue.length) {
      const resource = queue.shift();
      if (!resource || state.capabilities.has(resource.key)) {
        updateCapabilityProgress();
        continue;
      }
      await verifyResourceCapability(resource);
      updateCapabilityProgress();
      renderOverview();
      renderResourceList();
    }
  }

  async function verifyResourceCapability(resource) {
    const resourcePath = resource.resourcePath || resource.paths?.[0];
    if (!resourcePath) return;
    try {
      const capability = await fetchJson(`${resourcePath}/capabilities`);
      state.capabilities.set(resource.key, capability);
      state.capabilityErrors.delete(resource.key);
      resource.capability = capability;
      resource.capabilityError = null;
      promoteCanonicalIdentity(resource, capability, 'capabilities');
    } catch (error) {
      state.capabilities.set(resource.key, null);
      state.capabilityErrors.set(resource.key, error?.message || 'capabilities indisponíveis');
      resource.capabilityError = error?.message || 'capabilities indisponíveis';
      if (!resource.capability) {
        resource.capability = null;
      }
    }
  }

  async function startActionVerification() {
    if (state.backgroundActions.running) return;
    const candidates = state.resources.filter((resource) => canonicalResourceKey(resource, resource.resourcePath || resource.paths?.[0]));
    state.backgroundActions = {
      running: true,
      checked: candidates.filter((resource) => state.actions.has(semanticCacheKey(resource, canonicalResourceKey(resource, resource.resourcePath || resource.paths?.[0])))).length,
      total: candidates.length
    };
    renderOverview();
    const queue = candidates.slice();
    const workers = Array.from({ length: Math.min(4, queue.length) }, () => verifyActionQueue(queue));
    await Promise.all(workers);
    state.backgroundActions.running = false;
    renderOverview();
    renderResourceList();
    const resource = selectedResource();
    if (resource) {
      renderDetail(resource);
    }
  }

  async function verifyActionQueue(queue) {
    while (queue.length) {
      const resource = queue.shift();
      if (!resource) {
        updateActionProgress();
        continue;
      }
      const resourcePath = resource.resourcePath || resource.paths?.[0];
      const resourceKey = canonicalResourceKey(resource, resourcePath);
      const cacheKey = semanticCacheKey(resource, resourceKey);
      if (state.actions.has(cacheKey)) {
        updateActionProgress();
        continue;
      }
      await verifyResourceActions(resource);
      updateActionProgress();
      renderOverview();
      renderResourceList();
    }
  }

  async function verifyResourceActions(resource) {
    const resourcePath = resource.resourcePath || resource.paths?.[0];
    const resourceKey = canonicalResourceKey(resource, resourcePath);
    const cacheKey = semanticCacheKey(resource, resourceKey);
    if (!resourceKey) return;
    if (!hasPublishedActionSignal(resource)) {
      const fallbackActions = uniqueBySurface(resource.capability?.actions || []);
      state.actions.set(cacheKey, fallbackActions);
      resource.actions = fallbackActions;
      promoteCanonicalIdentity(resource, fallbackActions.find((action) => action?.resourceKey), 'capabilities.actions');
      return;
    }
    try {
      const payload = await fetchJson(`/schemas/actions?resource=${encodeURIComponent(resourceKey)}`);
      const scopedActions = uniqueBySurface([...actionItems(payload), ...(resource.capability?.actions || [])]);
      state.actions.set(cacheKey, scopedActions);
      resource.actions = scopedActions;
      promoteCanonicalIdentity(resource, payload, 'actions');
      promoteCanonicalIdentity(resource, scopedActions.find((action) => action?.resourceKey), 'actions');
    } catch (error) {
      const fallbackActions = uniqueBySurface(resource.capability?.actions || []);
      state.actions.set(cacheKey, fallbackActions);
      resource.actions = fallbackActions;
      promoteCanonicalIdentity(resource, fallbackActions.find((action) => action?.resourceKey), 'capabilities.actions');
    }
  }

  function updateActionProgress() {
    const total = state.backgroundActions.total || state.resources.length;
    state.backgroundActions.checked = state.resources.filter((resource) => {
      const resourcePath = resource.resourcePath || resource.paths?.[0];
      const resourceKey = canonicalResourceKey(resource, resourcePath);
      return state.actions.has(semanticCacheKey(resource, resourceKey));
    }).length;
    state.backgroundActions.total = total;
  }

  function updateCapabilityProgress() {
    const total = state.backgroundCapabilities.total || state.resources.length;
    state.backgroundCapabilities.checked = state.resources.filter((resource) => state.capabilities.has(resource.key)).length;
    state.backgroundCapabilities.total = total;
  }

  async function fetchJson(url, options = {}) {
    const controller = options.timeoutMs ? new AbortController() : null;
    const timeoutId = controller ? window.setTimeout(() => controller.abort(), options.timeoutMs) : null;
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    if (options.body !== undefined && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    }
    try {
      const response = await fetch(url, {
        method: options.method || 'GET',
        headers,
        body: options.body === undefined ? undefined : (typeof options.body === 'string' ? options.body : JSON.stringify(options.body)),
        credentials: 'same-origin',
        signal: controller?.signal
      });
      if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}`);
      }
      return response.json();
    } finally {
      if (timeoutId) window.clearTimeout(timeoutId);
    }
  }

  function valueOrNull(result) {
    return result.status === 'fulfilled' ? result.value : null;
  }

  function endpointStatus(result) {
    if (result.status === 'fulfilled') {
      return { ok: true, message: 'ok' };
    }
    return { ok: false, message: result.reason?.message || 'indisponível' };
  }

  async function discoverCatalogs(defaultCatalog, swaggerConfig) {
    const catalogs = [defaultCatalog].filter(Boolean);
    const groups = openApiGroupNames(swaggerConfig);
    if (!groups.length) return catalogs;

    const groupResults = await Promise.allSettled(groups.map((group) =>
      fetchJson(`/schemas/catalog?group=${encodeURIComponent(group)}`, { timeoutMs: 5000 })
    ));
    const groupCatalogs = [];
    for (const result of groupResults) {
      const catalog = valueOrNull(result);
      if (catalog) groupCatalogs.push(catalog);
    }
    if (groupCatalogs.length) {
      return [...catalogs, ...groupCatalogs];
    }
    return catalogs;
  }

  function openApiGroupNames(swaggerConfig) {
    const entries = Array.isArray(swaggerConfig?.urls) ? swaggerConfig.urls : [];
    const groups = Array.from(new Set(entries
      .map((entry) => entry?.name || groupNameFromOpenApiUrl(entry?.url))
      .filter((name) => name && name !== 'application')
    ));
    const aggregateGroups = groups.filter((name) => !name.startsWith('api-'));
    return aggregateGroups.length ? aggregateGroups : groups;
  }

  function groupNameFromOpenApiUrl(url) {
    const match = String(url || '').match(/\/v3\/api-docs\/([^/?#]+)/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  function composeResources(catalogs) {
    const index = new Map();
    state.discovery = { frameworkEndpoints: [], derivedEndpoints: [], partialEndpoints: [], catalogGroups: [] };

    for (const endpoint of catalogEndpoints(catalogs)) {
      const path = endpoint.path || '';
      if (isFrameworkEndpoint(path)) {
        state.discovery.frameworkEndpoints.push(endpoint);
        continue;
      }
      const resourcePath = normalizeResourcePath(path);
      if (!resourcePath) {
        state.discovery.partialEndpoints.push(endpoint);
        continue;
      }
      if (resourcePath !== path) {
        state.discovery.derivedEndpoints.push(endpoint);
      }
      const resource = ensureResource(index, resourcePath, {
        resourcePath,
        resourceKey: endpoint.resourceKey || null,
        catalogVisual: endpoint.resourceVisual || null,
        group: canonicalAreaKey(endpoint.resourceKey, endpoint.group, resourcePath, endpoint.resourceVisual),
        frontendResource: frontendResourceForPath(resourcePath),
        icon: endpoint.resourceVisual?.icon || null,
        sourceConfidence: endpoint.resourceKey ? 'resourceKey' : 'path-fallback'
      });
      if (!resource.endpoints.some((current) => endpointKey(current) === endpointKey(endpoint))) {
        resource.endpoints.push(endpoint);
      }
      if (endpoint.schemaLinks) {
        resource.schemaLinks.push(endpoint.schemaLinks);
      }
      mergeSchemaFields(resource, endpoint.requestSchema, 'request');
      mergeSchemaFields(resource, endpoint.responseSchema, 'response');
    }

    return Array.from(new Set(index.values()))
      .map(finalizeResource)
      .sort((a, b) => a.group.localeCompare(b.group) || a.label.localeCompare(b.label));
  }

  function ensureResource(index, key, patch) {
    const stableKey = patch.resourceKey || key;
    const existing = index.get(stableKey) || index.get(key) || findByResourcePath(index, patch.resourcePath);
    if (existing) {
      mergeResource(existing, patch);
      index.set(stableKey, existing);
      if (key) index.set(key, existing);
      if (patch.resourcePath) index.set(patch.resourcePath, existing);
      return existing;
    }
    const resource = {
      key: stableKey,
      resourceKey: patch.resourceKey || null,
      resourcePath: patch.resourcePath || null,
      group: patch.group || null,
      sourceConfidence: patch.sourceConfidence || 'parcial',
      endpoints: [],
      schemaLinks: [],
      surfaces: [],
      actions: [],
      domainItems: [],
      catalogVisual: patch.catalogVisual || null,
      frontendResource: patch.frontendResource || null,
      icon: patch.icon || null,
      fields: new Map(),
      capability: null,
      filteredSchema: null,
      domainGraph: null,
      enrichmentState: 'idle'
    };
    index.set(stableKey, resource);
    if (key !== stableKey) index.set(key, resource);
    if (patch.resourcePath) index.set(patch.resourcePath, resource);
    return resource;
  }

  function findByResourcePath(index, resourcePath) {
    if (!resourcePath) return null;
    for (const resource of index.values()) {
      if (resource.resourcePath === resourcePath || resource.paths?.includes(resourcePath)) return resource;
    }
    return null;
  }

  function mergeResource(resource, patch) {
    resource.resourceKey = resource.resourceKey || patch.resourceKey || null;
    resource.resourcePath = resource.resourcePath || patch.resourcePath || null;
    resource.group = canonicalAreaKey(resource.resourceKey, resource.group, resource.resourcePath, resource.catalogVisual)
      || canonicalAreaKey(patch.resourceKey, patch.group, patch.resourcePath, patch.catalogVisual)
      || resource.group
      || null;
    resource.catalogVisual = resource.catalogVisual || patch.catalogVisual || null;
    resource.frontendResource = resource.frontendResource || patch.frontendResource || null;
    resource.icon = resource.icon || patch.icon || patch.catalogVisual?.icon || patch.frontendResource?.icon || null;
    if (resource.sourceConfidence !== 'resourceKey' && patch.sourceConfidence) {
      resource.sourceConfidence = patch.sourceConfidence;
    }
  }

  function mergeSchemaFields(resource, schema, direction) {
    for (const field of schema?.fields || []) {
      if (!field?.name) continue;
      const current = resource.fields.get(field.name) || {
        name: field.name,
        type: field.type || field.format || 'objeto',
        required: false,
        descriptions: new Set(),
        enumValues: new Set(),
        directions: new Set(),
        xui: field['x-ui'] || field.xui || field.xUi || null,
        icon: field.icon || field['x-ui']?.icon || field.xui?.icon || field.xUi?.icon || null
      };
      current.required = current.required || Boolean(field.required);
      current.type = current.type || field.type || field.format || 'objeto';
      current.directions.add(direction);
      current.xui = current.xui || field['x-ui'] || field.xui || field.xUi || null;
      current.icon = current.icon || field.icon || field['x-ui']?.icon || field.xui?.icon || field.xUi?.icon || null;
      if (field.description) current.descriptions.add(field.description);
      for (const value of field.enumValues || []) current.enumValues.add(value);
      resource.fields.set(field.name, current);
    }
  }

  function finalizeResource(resource) {
    const visual = resource.catalogVisual || {};
    resource.inferredResourceKey = resource.resourceKey ? null : resourceKeyFromPath(resource.resourcePath);
    resource.key = resource.resourceKey || resource.key;
    resource.group = canonicalAreaKey(resource.resourceKey, resource.group, resource.resourcePath, visual) || 'domínio';
    resource.domain = labelForArea(resource.group);
    resource.icon = visual.icon || resource.icon || resource.frontendResource?.icon || iconFromResourceFields(resource);
    resource.label = visual.title || resource.frontendResource?.title || readableResourceName(resource);
    resource.description = visual.description || resource.frontendResource?.description || describeResource(resource);
    resource.paths = Array.from(new Set([
      resource.resourcePath,
      ...resource.endpoints.map((endpoint) => normalizeResourcePath(endpoint.path)),
      ...resource.surfaces.map((surface) => surface.path && normalizeResourcePath(surface.path)),
      ...resource.actions.map((action) => action.path && normalizeResourcePath(action.path))
    ].filter(Boolean)));
    resource.operationSummary = summarizeOperations(resource.endpoints);
    resource.fieldList = Array.from(resource.fields.values());
    return resource;
  }

  function composeAreas(resources) {
    const byGroup = new Map();
    for (const resource of resources) {
      const area = byGroup.get(resource.group) || {
        key: resource.group,
        label: labelForArea(resource.group),
        description: areaDescriptions[resource.group] || 'Área publicada pelo catálogo metadata-driven do host.',
        icon: null,
        resources: [],
        endpoints: 0,
        fields: 0
      };
      area.resources.push(resource);
      area.endpoints += resource.endpoints.length;
      area.fields += resource.fieldList.length;
      area.icon = area.icon || dominantAreaIcon(area.resources);
      byGroup.set(resource.group, area);
    }
    return Array.from(byGroup.values())
      .map((area) => ({ ...area, icon: dominantAreaIcon(area.resources) || area.icon }))
      .sort((a, b) => b.resources.length - a.resources.length);
  }

  function frontendResourceIndex(items) {
    const index = new Map();
    if (!Array.isArray(items)) return index;
    for (const item of items) {
      const key = resourceManifestKey(item?.path);
      if (key) index.set(key, item);
    }
    return index;
  }

  function frontendResourceForPath(path) {
    const key = resourceManifestKey(path);
    return key ? state.frontendResources.get(key) || null : null;
  }

  function resourceManifestKey(path) {
    const normalized = normalizeResourcePath(path);
    if (!normalized) return null;
    return normalized.replace(/^\/api\//, '/');
  }

  function iconFromResourceFields(resource) {
    const counts = new Map();
    for (const field of resource.fields.values()) {
      const icon = firstText(field.xui?.icon, field.xUi?.icon, field.icon);
      if (icon) counts.set(icon, (counts.get(icon) || 0) + 1);
    }
    return mostFrequent(counts);
  }

  function dominantAreaIcon(resources) {
    const counts = new Map();
    for (const resource of resources || []) {
      const icon = resource.icon;
      if (icon) counts.set(icon, (counts.get(icon) || 0) + 1);
    }
    return mostFrequent(counts);
  }

  function mostFrequent(counts) {
    let winner = null;
    let winnerCount = 0;
    for (const [value, count] of counts.entries()) {
      if (count > winnerCount) {
        winner = value;
        winnerCount = count;
      }
    }
    return winner;
  }

  function catalogEndpoints(catalog) {
    if (Array.isArray(catalog)) {
      return catalog.flatMap((item) => catalogEndpoints(item));
    }
    if (!catalog) return [];
    if (catalog.group && !state.discovery.catalogGroups.includes(catalog.group)) {
      state.discovery.catalogGroups.push(catalog.group);
    }
    if (Array.isArray(catalog.endpoints)) {
      return catalog.endpoints.map((endpoint) => ({
        ...endpoint,
        group: endpoint.group || catalog.group
      }));
    }
    if (Array.isArray(catalog.groups)) {
      return catalog.groups.flatMap((group) => (group.endpoints || []).map((endpoint) => ({
        ...endpoint,
        group: endpoint.group || group.group || group.name || group.id
      })));
    }
    return [];
  }

  function endpointKey(endpoint) {
    return `${endpoint?.method || 'GET'} ${endpoint?.path || ''}`;
  }

  function surfaceItems(payload) {
    if (!payload) return [];
    if (Array.isArray(payload.surfaces)) return payload.surfaces.map((item) => ({ ...item, resourcePath: payload.resourcePath, group: payload.group }));
    if (Array.isArray(payload.resources)) {
      return payload.resources.flatMap((resource) => (resource.surfaces || []).map((item) => ({ ...item, resourcePath: resource.resourcePath, group: resource.group })));
    }
    if (Array.isArray(payload)) return payload.flatMap(surfaceItems);
    return [];
  }

  function actionItems(payload) {
    if (!payload) return [];
    if (Array.isArray(payload.actions)) return payload.actions.map((item) => ({ ...item, resourcePath: payload.resourcePath, group: payload.group }));
    if (Array.isArray(payload.resources)) {
      return payload.resources.flatMap((resource) => (resource.actions || []).map((item) => ({ ...item, resourcePath: resource.resourcePath, group: resource.group })));
    }
    if (Array.isArray(payload)) return payload.flatMap(actionItems);
    return [];
  }

  function hasPublishedActionSignal(resource) {
    if ((resource.capability?.actions || []).length) return true;
    if ((resource.actions || []).length) return true;
    if ((resource.operationSummary?.actions || 0) > 0) return true;
    return (resource.endpoints || []).some((endpoint) => isWorkflowActionEndpoint(endpoint.path));
  }

  function domainItems(payload) {
    if (!payload) return [];
    if (Array.isArray(payload.resources)) return payload.resources;
    if (Array.isArray(payload.items)) return payload.items;
    if (Array.isArray(payload)) return payload;
    return [];
  }

  function graphToDomainItems(graph) {
    if (!graph) return [];
    return [
      ...(Array.isArray(graph.contexts) ? graph.contexts : []),
      ...(Array.isArray(graph.nodes) ? graph.nodes : []),
      ...(Array.isArray(graph.edges) ? graph.edges : []),
      ...(Array.isArray(graph.evidence) ? graph.evidence : [])
    ];
  }

  function normalizeResourcePath(path) {
    if (!path || isFrameworkEndpoint(path) || !path.startsWith('/api/')) return null;
    const parts = path.split('/').filter(Boolean);
    if (parts.length < 3) return null;
    return `/${parts.slice(0, 3).join('/')}`;
  }

  function isFrameworkEndpoint(path) {
    return !path
      || path === '/'
      || path === '/index.html'
      || path === '/favicon.ico'
      || path.startsWith('/auth/')
      || path.startsWith('/actuator/')
      || path.startsWith('/assets/')
      || path === '/swagger-ui.html'
      || path.startsWith('/swagger-ui/')
      || path.startsWith('/v3/api-docs')
      || path === '/schemas'
      || path.startsWith('/schemas/')
      || path.startsWith('/praxis/cockpit')
      || path === '/api/praxis/config'
      || path.startsWith('/api/praxis/config/');
  }

  function domainFromPath(path) {
    const parts = (path || '').split('/').filter(Boolean);
    return parts[0] === 'api' ? parts[1] : null;
  }

  function domainFromResourceKey(resourceKey) {
    return resourceKey ? resourceKey.split('.')[0] : null;
  }

  function canonicalAreaKey(resourceKey, group, path, visual) {
    return domainFromResourceKey(resourceKey)
      || nonGenericGroup(group)
      || nonGenericGroup(visual?.tone)
      || domainFromPath(path)
      || null;
  }

  function nonGenericGroup(group) {
    const normalized = String(group || '').trim();
    if (!normalized || ['application', 'default', 'api', 'apis', 'openapi'].includes(normalized.toLowerCase())) {
      return null;
    }
    return normalized;
  }

  function resourceKeyFromPath(path) {
    const parts = (path || '').split('/').filter(Boolean);
    return parts[0] === 'api' && parts[1] && parts[2] ? `${parts[1]}.${parts[2]}` : null;
  }

  function labelForArea(group) {
    return areaLabels[group] || titleize(group || 'domínio');
  }

  function readableResourceName(resource) {
    const source = resource.resourceKey?.split('.').slice(1).join('.') || resource.resourcePath?.split('/').pop() || resource.key;
    return titleize(source);
  }

  function titleize(value) {
    const label = String(value || '')
      .replace(/^vw[-.]/, 'Visão ')
      .replace(/[-_.]+/g, ' ')
      .replace(/\b\w/g, (letter) => letter.toUpperCase());
    return normalizePortugueseLabel(label);
  }

  function normalizePortugueseLabel(label) {
    return label
      .replace(/\bAlocacoes\b/g, 'Alocações')
      .replace(/\balocacoes\b/g, 'alocações')
      .replace(/\bAlocacao\b/g, 'Alocação')
      .replace(/\balocacao\b/g, 'alocação')
      .replace(/\bAcoes\b/g, 'Ações')
      .replace(/\bOperacoes\b/g, 'Operações')
      .replace(/\bMissoes\b/g, 'Missões')
      .replace(/\bMencoes\b/g, 'Menções')
      .replace(/\bReputacoes\b/g, 'Reputações')
      .replace(/\bLicencas\b/g, 'Licenças')
      .replace(/\blicencas\b/g, 'licenças')
      .replace(/\bFuncionarios\b/g, 'Funcionários')
      .replace(/\bFuncionario\b/g, 'Funcionário')
      .replace(/\bfuncionario\b/g, 'funcionário')
      .replace(/\bHistoricos\b/g, 'Históricos')
      .replace(/\bFerias\b/g, 'Férias')
      .replace(/\bMidia\b/g, 'Mídia')
      .replace(/\bVeiculos\b/g, 'Veículos')
      .replace(/\bAnaliticos\b/g, 'Analíticos')
      .replace(/\banalitica\b/g, 'analítica')
      .replace(/\bInicio\b/g, 'Início')
      .replace(/\binicio\b/g, 'início')
      .replace(/\bvigencia\b/g, 'vigência')
      .replace(/\bcustodia\b/g, 'custódia')
      .replace(/\bdetem\b/g, 'detém')
      .replace(/\bcessao\b/g, 'cessão');
  }

  function readableText(value) {
    const pathTokens = [];
    const protectedText = String(value || '').replace(/\/[A-Za-z0-9._~:/?#[\]@!$&'()*+,;=%-]+/g, (match) => {
      const token = `__PATH_TOKEN_${pathTokens.length}__`;
      pathTokens.push(match);
      return token;
    });
    return normalizePortugueseLabel(protectedText).replace(/__PATH_TOKEN_(\d+)__/g, (_, index) => pathTokens[Number(index)] || '');
  }

  function describeResource(resource) {
    const endpoint = resource.endpoints.find((item) => usefulText(item.description)) || resource.endpoints.find((item) => usefulText(item.summary));
    const text = endpoint?.description || endpoint?.summary;
    return usefulText(text) ? text : `Recurso publicado na área ${labelForArea(resource.group)}.`;
  }

  function usefulText(text) {
    return text && !/^Endpoint para /i.test(text);
  }

  function summarizeOperations(endpoints) {
    const summary = { read: 0, write: 0, filter: 0, stats: 0, options: 0, actions: 0, export: 0 };
    for (const endpoint of endpoints) {
      const method = String(endpoint.method || '').toUpperCase();
      const path = endpoint.path || '';
      if (method === 'GET') summary.read += 1;
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) summary.write += 1;
      if (path.includes('/filter') || path.endsWith('/filtered')) summary.filter += 1;
      if (path.includes('/stats/')) summary.stats += 1;
      if (path.includes('/options') || path.includes('/option-sources')) summary.options += 1;
      if (isWorkflowActionEndpoint(path)) summary.actions += 1;
      if (path.endsWith('/export')) summary.export += 1;
    }
    return summary;
  }

  function renderOverview() {
    const businessAreas = state.areas.filter((area) => area.key !== 'praxis');
    const businessResources = state.resources.filter((resource) => resource.group !== 'praxis');
    const resourcesForMetrics = businessResources.length ? businessResources : state.resources;
    const totalOperations = resourcesForMetrics.reduce((sum, resource) => sum + resource.endpoints.length, 0);
    const fieldCount = resourcesForMetrics.reduce((sum, resource) => sum + resource.fieldList.length, 0);
    const readiness = computeHostReadiness(resourcesForMetrics);
    els.domainTitle.textContent = hostTitle(businessAreas);
    els.domainSummary.textContent = hostSummary(businessAreas);
    els.domainAreaCount.textContent = String(businessAreas.length || state.areas.length);
    els.domainAreaHint.textContent = state.areas.some((area) => area.key === 'praxis') ? 'negócio + plataforma' : 'grupos de negócio';
    els.domainResourceCount.textContent = String(resourcesForMetrics.length);
    els.operationCount.textContent = String(totalOperations);
    els.fieldCount.textContent = String(fieldCount || '--');
    els.readinessScore.textContent = readiness.label;
    els.readinessHint.textContent = readiness.hint;
    const domainCatalogCount = state.discovery.catalogGroups.filter((group) => group !== 'application').length;
    els.sourceMode.textContent = domainCatalogCount
      ? `${domainCatalogCount} catálogo(s) de domínio · ${state.discovery.frameworkEndpoints.length} endpoints técnicos isolados`
      : `${state.discovery.frameworkEndpoints.length} endpoints técnicos isolados da leitura de domínio`;
    renderPlatformIntelligence(resourcesForMetrics);
  }

  function hostTitle(areas) {
    const labels = areas.slice(0, 4).map((area) => area.label);
    if (!labels.length) return 'Serviço metadata-driven ainda sem domínio publicado';
    return `Serviço de ${joinHuman(labels)}`;
  }

  function hostSummary(areas) {
    if (!areas.length) {
      return 'O host publicou contratos técnicos, mas o cockpit ainda não encontrou áreas de negócio suficientes para explicar o domínio.';
    }
    const resources = areas.reduce((sum, area) => sum + area.resources.length, 0);
    const endpoints = areas.reduce((sum, area) => sum + area.endpoints, 0);
    return `Praxis leu ${resources} recursos de domínio em ${areas.length} área(s), com ${endpoints} operações que podem sustentar consulta, escrita, filtros, estatísticas, opções e automações.`;
  }

  function joinHuman(values) {
    if (values.length <= 1) return values[0] || '';
    return `${values.slice(0, -1).join(', ')} e ${values[values.length - 1]}`;
  }

  function computeHostReadiness(resources) {
    const checks = [
      state.endpointStatus.health?.ok,
      state.endpointStatus.catalog?.ok,
      resources.length > 0,
      resources.some((resource) => resource.schemaLinks.length),
      resources.some((resource) => resource.resourceKey)
    ];
    const score = Math.round((checks.filter(Boolean).length / checks.length) * 100);
    return {
      score,
      label: score === 100 ? 'base semântica operacional' : `${score}%`,
      hint: state.health?.status ? `health ${state.health.status}; detalhe semântico por recurso` : 'health indisponível'
    };
  }

  function renderPlatformIntelligence(resources) {
    const metrics = platformMetrics(resources);
    els.capabilityMode.textContent = `${metrics.renderableResources} experiências possíveis · ${metrics.capabilityChecked}/${metrics.resources} capabilities verificadas`;
    els.filterPower.textContent = `${metrics.filterResources}/${metrics.resources}`;
    els.filterPowerDetail.textContent = `${metrics.filterResources} recurso(s) com consulta, ${metrics.statsResources} com analytics e ${metrics.optionResources} com fontes de opção. Sinais confirmados vêm de capabilities, surfaces, schemas e x-ui; o catálogo entra como possibilidade estimada.`;
    els.domainCoverageChart.innerHTML = renderDomainCoverageChart(metrics);
    els.renderableStackChart.innerHTML = renderRenderableStack(metrics);
    els.hostScorecards.innerHTML = renderHostScorecards(metrics);
    els.hostAttention.innerHTML = renderHostAttention(resources);
    renderHostDecision(metrics);
  }

  function platformMetrics(resources) {
    const areaRows = state.areas
      .map((area) => {
        const areaResources = area.resources.filter((resource) => resources.includes(resource));
        const total = areaResources.length;
        const renderability = areaResources.map((resource) => resourceRenderability(resource));
        const totalLayers = renderability.reduce((sum, resource) => sum + resource.score, 0);
        const averageLayers = total ? totalLayers / total : 0;
        const score = Math.round((Math.min(6, averageLayers) / 6) * 100);
        const maxLayers = total * 6;
        return { ...area, total, score, averageLayers, totalLayers, maxLayers, layerCoverage: areaLayerCoverage(renderability) };
      })
      .filter((area) => area.total > 0);
    const renderableProfiles = resources.map((resource) => ({ resource, profile: resourceRenderability(resource) }));
    const renderable = renderableProfiles.map((item) => item.profile);
    const capabilityChecked = resources.filter((resource) => state.capabilities.has(resource.key)).length;
    const capabilityFailures = resources.filter((resource) => state.capabilityErrors.has(resource.key)).length;
    const identityConfirmed = resources.filter((resource) => hasConfirmedIdentity(resource)).length;
    const identityPending = resources.filter((resource) => !state.capabilities.has(resource.key)).length;
    return {
      resources: resources.length,
      areas: areaRows,
      renderableProfiles,
      capabilityChecked,
      capabilityFailures,
      identityConfirmed,
      identityPending,
      filterResources: renderable.filter((item) => item.canFilter).length,
      tableResources: renderable.filter((item) => item.canTable).length,
      formResources: renderable.filter((item) => item.canForm).length,
      statsResources: renderable.filter((item) => item.canAnalytics).length,
      optionResources: renderable.filter((item) => item.canOptions).length,
      actionResources: renderable.filter((item) => item.canActions).length,
      renderableResources: renderable.filter((item) => item.score >= 2).length,
      confirmedLayers: renderable.reduce((sum, item) => sum + item.confirmedScore, 0),
      estimatedLayers: renderable.reduce((sum, item) => sum + Object.values(item.evidence).filter((source) => source === 'catalog').length, 0),
      schemaGaps: resources.filter((resource) => !resource.fieldList.length && !resource.schemaLinks.length).length,
      identityGaps: resources.filter((resource) => state.capabilities.has(resource.key) && !hasConfirmedIdentity(resource)).length,
      actionGaps: renderable.filter((item) => !item.canActions).length
    };
  }

  function renderHostDecision(metrics) {
    const hasResources = metrics.resources > 0;
    const formComplete = hasResources && metrics.formResources === metrics.resources;
    const filterComplete = hasResources && metrics.filterResources === metrics.resources;
    const tableComplete = hasResources && metrics.tableResources === metrics.resources;
    const analyticsComplete = hasResources && metrics.statsResources === metrics.resources;
    const workflowComplete = hasResources && metrics.actionResources === metrics.resources;
    const uiOperational = hasResources && tableComplete && filterComplete && metrics.formResources > 0;
    const decisionTone = !hasResources || metrics.schemaGaps ? 'danger' : workflowComplete && formComplete ? 'success' : 'warning';
    const decisionLabel = !hasResources
      ? 'Sem domínio materializável'
      : uiOperational
        ? workflowComplete
          ? 'Apto para UI operacional e automação'
          : 'Apto para UI operacional com ressalva em automação'
        : 'Materialização parcial exige atenção';
    const readyParts = [];
    if (tableComplete) readyParts.push('tabelas');
    if (filterComplete) readyParts.push('consultas e filtros');
    if (analyticsComplete) readyParts.push('analytics');
    if (metrics.formResources) readyParts.push(`${metrics.formResources}/${metrics.resources} formulários`);
    const readyText = readyParts.length ? `${joinHuman(readyParts)} disponíveis.` : 'Ainda não há experiência de UI suficiente para uma conclusão operacional.';
    const workflowText = workflowComplete
      ? 'Workflows acionáveis publicados para todos os recursos avaliados.'
      : `${metrics.actionResources}/${metrics.resources || 0} workflows acionáveis publicados; CRUD e exploração não são bloqueados, mas automações governadas ainda não ficam disponíveis.`;
    els.hostDecisionTitle.textContent = decisionLabel;
    els.hostDecisionSummary.textContent = hasResources
      ? `${metrics.resources} recurso(s) analisado(s). ${readyText}`
      : 'O host ainda não publicou recursos de domínio suficientes para auditoria de materialização.';
    els.decisionResourceCount.textContent = String(metrics.resources || '--');
    els.decisionFormCount.textContent = `${metrics.formResources}/${metrics.resources || 0}`;
    els.decisionFilterCount.textContent = `${metrics.filterResources}/${metrics.resources || 0}`;
    els.decisionWorkflowCount.textContent = `${metrics.actionResources}/${metrics.resources || 0}`;
    els.hostDecisionAttention.innerHTML = `
      <span class="status-token status-${escapeAttr(decisionTone)}">${escapeHtml(statusDecisionLabel(decisionTone))}</span>
      <p><strong>Próxima atenção:</strong> ${escapeHtml(workflowText)}</p>
      <small>Base: schemas, capabilities, surfaces, actions, x-ui e catálogo HTTP do host.</small>
    `;
  }

  function statusDecisionLabel(tone) {
    if (tone === 'success') return 'Pronto';
    if (tone === 'danger') return 'Bloqueio';
    if (tone === 'warning') return 'Atenção';
    return 'Informativo';
  }

  function hasConfirmedIdentity(resource) {
    return Boolean(resource.capability?.resourceKey)
      || ['resourceKey', 'capabilities', 'surfaces', 'actions', 'capabilities.surfaces', 'capabilities.actions'].includes(resource.sourceConfidence);
  }

  function renderHostScorecards(metrics) {
    const cards = [
      {
        tone: metrics.schemaGaps ? 'blocking' : 'ok',
        label: 'Contratos explicáveis',
        value: `${metrics.resources - metrics.schemaGaps}/${metrics.resources || 0}`,
        detail: metrics.schemaGaps ? `${metrics.schemaGaps} recurso(s) sem campos ou schema link.` : 'Todos os recursos têm campos ou referência de schema.'
      },
      {
        tone: metrics.capabilityFailures ? 'blocking' : metrics.identityGaps ? 'attention' : metrics.identityPending ? 'optional' : 'ok',
        label: 'Identidade semântica',
        value: `${metrics.identityConfirmed}/${metrics.resources || 0}`,
        detail: metrics.capabilityFailures
          ? `${metrics.capabilityFailures} recurso(s) falharam ao consultar capabilities.`
          : metrics.identityGaps
          ? `${metrics.identityGaps} recurso(s) verificados ainda dependem de evidência derivada.`
          : metrics.identityPending
            ? `${metrics.identityPending} recurso(s) aguardam verificação em segundo plano.`
            : 'resourceKey confirmado no conjunto visível.'
      },
      {
        tone: metrics.estimatedLayers ? 'attention' : 'ok',
        label: 'Evidência das camadas',
        value: String(metrics.confirmedLayers),
        detail: metrics.estimatedLayers ? `${metrics.estimatedLayers} camada(s) são possibilidade estimada pelo catálogo.` : 'Capacidades visíveis estão confirmadas por fonte forte.'
      },
      {
        tone: metrics.actionGaps ? 'optional' : 'ok',
        label: 'Workflows acionáveis',
        value: `${metrics.actionResources}/${metrics.resources || 0}`,
        detail: metrics.actionGaps ? `${metrics.actionGaps} recurso(s) sem workflow publicado; CRUD não é bloqueado.` : 'Recursos visíveis publicam ações de workflow.'
      }
    ];
    return cards.map((card) => `
      <article class="host-scorecard ${escapeAttr(card.tone)}">
        <em class="status-token ${escapeAttr(statusClassForTone(card.tone))}">${escapeHtml(statusTextForTone(card.tone))}</em>
        <span>${escapeHtml(card.label)}</span>
        <strong>${escapeHtml(card.value)}</strong>
        <small>${escapeHtml(card.detail)}</small>
      </article>
    `).join('');
  }

  function renderHostAttention(resources) {
    const items = resources
      .map((resource) => ({ resource, diagnostic: hostPriorityDiagnostic(resource) }))
      .filter((item) => item.diagnostic)
      .sort((a, b) => severityRank(a.diagnostic.level) - severityRank(b.diagnostic.level)
        || a.resource.label.localeCompare(b.resource.label))
      .slice(0, 5);
    if (!items.length) {
      return '<article class="host-attention-item ok"><strong>Nenhuma prioridade global crítica</strong><span>Os recursos visíveis têm sinais suficientes para inspeção inicial.</span></article>';
    }
    return items.map(({ resource, diagnostic }) => `
      <button class="host-attention-item ${escapeAttr(diagnostic.level)}" type="button" data-key="${escapeAttr(resource.key)}">
        <strong>${escapeHtml(resource.label)}</strong>
        <span>${escapeHtml(diagnostic.title)}</span>
        <small>${escapeHtml(diagnostic.impact)}</small>
      </button>
    `).join('');
  }

  function hostPriorityDiagnostic(resource) {
    const diagnostics = catalogDiagnostics(resource);
    return diagnostics.sort((a, b) => severityRank(a.level) - severityRank(b.level))[0] || null;
  }

  function catalogDiagnostics(resource) {
    const profile = resourceRenderability(resource);
    const diagnostics = [];
    if (!resource.fieldList.length && !resource.schemaLinks.length) {
      diagnostics.push({
        level: 'blocking',
        title: 'Sem contrato materializável',
        impact: 'Não há campos nem link de schema para explicar payloads.'
      });
    }
    if (!resource.resourceKey && resource.inferredResourceKey) {
      diagnostics.push({
        level: 'attention',
        title: 'Identidade inferida por path',
        impact: `O cockpit inferiu ${resource.inferredResourceKey} apenas como diagnóstico; publique resourceKey para confirmar a identidade canônica.`
      });
    }
    if (state.capabilityErrors.has(resource.key)) {
      diagnostics.push({
        level: 'blocking',
        title: 'Capabilities retornaram erro',
        impact: `O endpoint /capabilities falhou: ${state.capabilityErrors.get(resource.key)}.`
      });
    } else if (state.capabilities.has(resource.key) && !hasConfirmedIdentity(resource)) {
      diagnostics.push({
        level: 'attention',
        title: 'Identidade ainda não canônica',
        impact: 'Capabilities foram lidas, mas não confirmaram resourceKey canônico.'
      });
    } else if (!state.capabilities.has(resource.key)) {
      diagnostics.push({
        level: 'optional',
        title: 'Capabilities aguardando verificação',
        impact: 'O cockpit ainda está confirmando a identidade e as operações deste recurso em segundo plano.'
      });
    }
    if (profile.canFilter && profile.evidence.filter === 'catalog') {
      diagnostics.push({
        level: 'attention',
        title: 'Filtro estimado pelo catálogo',
        impact: 'Há endpoint de filtro, mas falta confirmação por capabilities.'
      });
    }
    if (profile.canAnalytics && profile.evidence.analytics === 'catalog') {
      diagnostics.push({
        level: 'optional',
        title: 'Chart possível, não confirmado',
        impact: 'Stats existem no catálogo, mas ainda não foram confirmadas como capability.'
      });
    }
    if (!profile.canActions) {
      diagnostics.push({
        level: 'optional',
        title: 'Sem action de workflow',
        impact: 'CRUD pode existir, mas não há comando de negócio publicado.'
      });
    }
    return diagnostics;
  }

  function renderDomainCoverageChart(metrics) {
    if (!metrics.areas.length) {
      return '<div class="empty-state">Nenhuma área de domínio para calcular cobertura.</div>';
    }
    return metrics.areas.slice(0, 6).map((area) => `
      <div class="domain-bar-row">
        <span class="domain-bar-label">
          <strong>${escapeHtml(area.label)}</strong>
          <small>${escapeHtml(area.total)} recurso(s) · ${escapeHtml(area.totalLayers)}/${escapeHtml(area.maxLayers)} experiências · média ${escapeHtml(formatDecimal(area.averageLayers))}/6</small>
        </span>
        <div class="domain-layer-grid" aria-label="${escapeAttr(area.totalLayers)} de ${escapeAttr(area.maxLayers)} camadas materializáveis em ${escapeAttr(area.label)}">
          ${renderDomainLayerCoverage(area.layerCoverage, area.total)}
        </div>
      </div>
    `).join('');
  }

  function areaLayerCoverage(renderability) {
    const layers = [
      { key: 'canTable', label: 'Tabela' },
      { key: 'canForm', label: 'Form' },
      { key: 'canFilter', label: 'Filtro' },
      { key: 'canAnalytics', label: 'Chart' },
      { key: 'canOptions', label: 'Lookup' },
      { key: 'canActions', label: 'Workflow' }
    ];
    return layers.map((layer) => ({
      ...layer,
      count: renderability.filter((resource) => resource[layer.key]).length
    }));
  }

  function renderDomainLayerCoverage(layers, total) {
    return (layers || []).map((layer) => {
      const ratio = total ? layer.count / total : 0;
      const tone = ratio === 1 ? 'ok' : ratio >= .5 ? 'attention' : 'gap';
      const status = ratio === 1 ? 'Completo' : layer.count > 0 ? 'Parcial' : 'Ausente';
      return `
        <span class="domain-layer-pill ${escapeAttr(tone)}" aria-label="${escapeAttr(layer.label)}: ${escapeAttr(status)}, ${escapeAttr(layer.count)} de ${escapeAttr(total)} recursos">
          <b>${escapeHtml(layer.label)}</b>
          <em>${escapeHtml(layer.count)}/${escapeHtml(total)}</em>
          <small>${escapeHtml(status)}</small>
        </span>
      `;
    }).join('');
  }

  function renderRenderableStack(metrics) {
    const parts = [
      {
        key: 'table',
        flag: 'canTable',
        evidence: 'table',
        label: 'Tabelas',
        value: metrics.tableResources,
        tone: 'cyan',
        impact: 'Listagens, detalhes e grades operacionais para navegação dos dados.'
      },
      {
        key: 'form',
        flag: 'canForm',
        evidence: 'form',
        label: 'Formulários',
        value: metrics.formResources,
        tone: 'mint',
        impact: 'Criação e edição quando há contrato de escrita ou surface de formulário.'
      },
      {
        key: 'filter',
        flag: 'canFilter',
        evidence: 'filter',
        label: 'Filtros',
        value: metrics.filterResources,
        tone: 'amber',
        impact: 'Busca, segmentação e exploração segura dos recursos publicados.'
      },
      {
        key: 'chart',
        flag: 'canAnalytics',
        evidence: 'analytics',
        label: 'Charts',
        value: metrics.statsResources,
        tone: 'violet',
        impact: 'Indicadores, distribuições e séries para leitura executiva.'
      },
      {
        key: 'lookup',
        flag: 'canOptions',
        evidence: 'options',
        label: 'Lookups',
        value: metrics.optionResources,
        tone: 'rose',
        impact: 'Seletores e fontes de opção para compor telas relacionais.'
      }
    ];
    const total = Math.max(1, metrics.resources);
    return parts.map((part) => {
      const readyResources = metrics.renderableProfiles
        .filter((item) => item.profile[part.flag])
        .map((item) => item.resource);
      const evidence = layerEvidenceSummary(metrics.renderableProfiles, part.flag, part.evidence);
      const missing = Math.max(0, metrics.resources - part.value);
      const ratio = Math.round((part.value / total) * 100);
      const stateLabel = part.value === metrics.resources ? 'Completo' : part.value > 0 ? 'Parcial' : 'Ausente';
      const status = part.value === metrics.resources ? 'Pronto em todo o domínio' : `${missing} recurso(s) sem sinal suficiente`;
      return `
        <section class="experience-row ${escapeAttr(part.tone)}" aria-label="${escapeAttr(part.label)}: ${escapeAttr(part.value)} de ${escapeAttr(metrics.resources)} recursos geráveis">
          <div class="experience-row-main">
            <span class="experience-layer">${escapeHtml(part.label)}</span>
            <strong class="experience-score">${escapeHtml(part.value)}/${escapeHtml(metrics.resources)}</strong>
            <small class="experience-status"><span class="status-token ${escapeAttr(statusClassFromCount(part.value, metrics.resources))}">${escapeHtml(stateLabel)}</span>${escapeHtml(status)}</small>
          </div>
          <div class="experience-row-body">
            <div class="experience-meter" aria-hidden="true"><i style="--value:${escapeAttr(ratio)}%"></i></div>
            <p>${escapeHtml(part.impact)}</p>
            <div class="experience-evidence">
              <span>${escapeHtml(evidence)}</span>
              ${renderExperienceResourceChips(readyResources)}
            </div>
          </div>
        </section>
      `;
    }).join('');
  }

  function layerEvidenceSummary(items, flag, evidenceKey) {
    const scoped = items.filter((item) => item.profile[flag]);
    if (!scoped.length) return 'Sem recursos prontos nesta camada.';
    const confirmed = scoped.filter((item) => ['confirmed', 'schema'].includes(item.profile.evidence[evidenceKey])).length;
    const estimated = scoped.filter((item) => item.profile.evidence[evidenceKey] === 'catalog').length;
    if (estimated) return `${confirmed} confirmado(s) · ${estimated} estimado(s) pelo catálogo`;
    return `${confirmed} confirmado(s) por capabilities, surfaces, schema ou x-ui`;
  }

  function renderExperienceResourceChips(resources) {
    const visible = resources.slice(0, 3);
    const overflow = resources.length - visible.length;
    if (!visible.length) return '<em>Nenhum exemplo disponível.</em>';
    return `
      <div class="experience-resource-list" aria-label="Exemplos de recursos geráveis">
        ${visible.map((resource) => `
          <button class="experience-resource-chip" type="button" data-key="${escapeAttr(resource.key)}">${escapeHtml(resource.label)}</button>
        `).join('')}
        ${overflow > 0 ? `<em>+${escapeHtml(overflow)}</em>` : ''}
      </div>
    `;
  }

  function formatDecimal(value) {
    return Number(value || 0).toLocaleString('pt-BR', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1
    });
  }

  function resourceRenderability(resource) {
    const ops = resource.capability?.canonicalOperations || {};
    const summary = resource.operationSummary || {};
    const fields = filteredSchemaFields(resource);
    const fieldSource = fields.length ? fields : resource.fieldList || [];
    const ui = uiFieldSummary(resource);
    const surfaces = resource.surfaces || [];
    const evidence = {
      table: capabilitySource(Boolean(ops.all), Boolean(surfaces.some((surface) => isViewSurface(surface))), Boolean(fieldSource.length || summary.read)),
      form: capabilitySource(Boolean(ops.create || ops.update), Boolean(surfaces.some((surface) => isFormSurface(surface)) || ui.editableFields), Boolean(hasFormEndpoint(resource))),
      filter: capabilitySource(Boolean(ops.filter || ops.cursor), false, Boolean(summary.filter)),
      analytics: capabilitySource(Boolean(ops.statsGroupBy || ops.statsTimeSeries || ops.statsDistribution), false, Boolean(summary.stats)),
      options: capabilitySource(Boolean(ops.options || ops.optionSources), Boolean(ui.optionSources), Boolean(summary.options)),
      actions: capabilitySource(Boolean((resource.actions || []).length), false, false)
    };
    const scoreItems = {
      canTable: evidence.table !== 'missing',
      canForm: evidence.form !== 'missing',
      canFilter: evidence.filter !== 'missing',
      canAnalytics: evidence.analytics !== 'missing',
      canOptions: evidence.options !== 'missing',
      canActions: evidence.actions !== 'missing'
    };
    const score = Object.values(scoreItems).filter(Boolean).length;
    const confirmedScore = Object.values(evidence).filter((source) => source === 'confirmed' || source === 'schema').length;
    return {
      ...scoreItems,
      score,
      confirmedScore,
      evidence,
      fields: fieldSource.length,
      uiFields: ui.uiFields,
      editableFields: ui.editableFields,
      lookupFields: ui.lookupFields,
      controlTypes: ui.controlTypes,
      optionSources: ui.optionSources,
      filterOperators: ui.filterOperators
    };
  }

  function capabilitySource(capability, schemaOrSurface, catalog) {
    if (capability) return 'confirmed';
    if (schemaOrSurface) return 'schema';
    if (catalog) return 'catalog';
    return 'missing';
  }

  function evidenceStatusLabel(source) {
    const labels = {
      confirmed: 'confirmado',
      schema: 'por schema/x-ui',
      catalog: 'possível',
      missing: 'não publicado'
    };
    return labels[source] || 'não publicado';
  }

  function evidenceDetail(source) {
    const details = {
      confirmed: 'Evidência canônica por capabilities, surfaces ou actions.',
      schema: 'Evidência estrutural por schema filtrado ou x-ui.',
      catalog: 'Possibilidade estimada pelo catálogo HTTP; ainda não é confirmação canônica.',
      missing: 'Sem evidência publicada suficiente.'
    };
    return details[source] || details.missing;
  }

  function isFormSurface(surface) {
    const text = String([surface?.kind, surface?.intent, surface?.id, surface?.title].filter(Boolean).join(' ')).toLowerCase();
    return text.includes('form') || text.includes('create') || text.includes('edit');
  }

  function hasFormEndpoint(resource) {
    return (resource.endpoints || []).some((endpoint) => {
      const method = String(endpoint.method || '').toUpperCase();
      const path = String(endpoint.path || '');
      const collectionWrite = method === 'POST' && path === resource.resourcePath;
      const itemWrite = ['PUT', 'PATCH'].includes(method) && path.startsWith(resource.resourcePath || '') && path.includes('{');
      return (collectionWrite || itemWrite) && Boolean(endpoint.schemaLinks?.request || endpoint.requestSchema);
    });
  }

  function isViewSurface(surface) {
    const text = String([surface?.kind, surface?.intent, surface?.id, surface?.title, surface?.responseCardinality].filter(Boolean).join(' ')).toLowerCase();
    return text.includes('view') || text.includes('list') || text.includes('collection');
  }

  function uiFieldSummary(resource) {
    const fields = filteredSchemaFields(resource);
    const summary = {
      uiFields: 0,
      editableFields: 0,
      lookupFields: 0,
      optionSources: 0,
      controlTypes: new Set(),
      filterOperators: new Set()
    };
    for (const field of fields) {
      const xui = field.xui || {};
      if (!Object.keys(xui).length) continue;
      summary.uiFields += 1;
      if (xui.editable !== false) summary.editableFields += 1;
      if (xui.controlType) summary.controlTypes.add(xui.controlType);
      if (xui.optionSource || xui.endpoint) summary.optionSources += 1;
      if (String(xui.controlType || '').toLowerCase().includes('lookup')) summary.lookupFields += 1;
      const filters = xui.optionSource?.filtering?.availableFilters || [];
      for (const filter of filters) {
        for (const operator of filter.operators || [filter.defaultOperator].filter(Boolean)) {
          if (operator) summary.filterOperators.add(operator);
        }
      }
    }
    return summary;
  }

  function renderAreas() {
    const max = Math.max(1, ...state.areas.map((area) => area.resources.length));
    if (!state.areas.length) {
      els.domainAreas.innerHTML = '<div class="empty-state">Nenhuma área de domínio foi descoberta.</div>';
      return;
    }
    els.domainAreas.innerHTML = state.areas.map((area) => `
      <button class="area-button ${area.key === state.selectedArea ? 'active' : ''}" type="button" data-area="${escapeAttr(area.key)}" aria-pressed="${area.key === state.selectedArea ? 'true' : 'false'}" style="${areaStyleVars(area)}">
        <span class="area-icon" aria-hidden="true">${areaIcon(area)}</span>
        <strong>${escapeHtml(area.label)}</strong>
        <span class="area-count">${escapeHtml(area.resources.length)} recursos, ${escapeHtml(area.endpoints)} operações, ${escapeHtml(area.fields)} campos descritos</span>
        <span>${escapeHtml(area.description)}</span>
        <div class="area-meter" aria-hidden="true"><i style="--value:${Math.max(8, Math.round(area.resources.length / max * 100))}%"></i></div>
      </button>
    `).join('');
    els.domainAreas.querySelectorAll('button[data-area]').forEach((button) => {
      button.addEventListener('click', () => {
        state.selectedArea = state.selectedArea === button.dataset.area ? null : button.dataset.area;
        renderAreas();
        renderResourceList();
      });
    });
  }

  function renderResourceFilters() {
    els.resourceFilterChips.querySelectorAll('button[data-filter]').forEach((button) => {
      const active = button.dataset.filter === state.resourceFilter;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
  }

  function renderResourceList() {
    const query = els.resourceSearch.value.trim().toLowerCase();
    const scopedResources = state.resources.filter((resource) => !state.selectedArea || resource.group === state.selectedArea);
    const filtered = scopedResources.filter((resource) => {
      if (state.selectedArea && resource.group !== state.selectedArea) return false;
      if (!matchesResourceFilter(resource, state.resourceFilter)) return false;
      const haystack = [
        resource.label,
        resource.resourceKey,
        resource.resourcePath,
        resource.domain,
        resource.description
      ].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(query);
    });
    const summary = renderResourceExecutiveSummary(scopedResources, filtered);

    if (!filtered.length) {
      els.resourceList.innerHTML = `${summary}<div class="empty-state">Nenhum recurso encontrado com esse filtro.</div>`;
      bindResourceListControls();
      return;
    }

    els.resourceList.innerHTML = `${summary}${filtered.map((resource) => `
      <button class="resource-button ${resource.key === state.selectedKey ? 'active' : ''}" type="button" data-key="${escapeAttr(resource.key)}" data-area="${escapeAttr(resource.group)}" aria-current="${resource.key === state.selectedKey ? 'true' : 'false'}" style="${areaStyleVars(resource.group)}">
        <strong>${escapeHtml(resource.label)}</strong>
        <span>${escapeHtml(resource.domain)} · ${escapeHtml(resource.endpoints.length)} operações · ${escapeHtml(resource.fieldList.length)} campos</span>
        <span>${escapeHtml(resourceListSignal(resource))}</span>
        <span>${escapeHtml(resource.resourcePath || 'sem path publicado')}</span>
      </button>
    `).join('')}`;
    bindResourceListControls();
  }

  function bindResourceListControls() {
    els.resourceList.querySelectorAll('button[data-key]').forEach((button) => {
      button.addEventListener('click', () => selectResource(button.dataset.key));
    });
    els.resourceList.querySelectorAll('button[data-resource-filter-shortcut]').forEach((button) => {
      button.addEventListener('click', () => {
        state.resourceFilter = button.dataset.resourceFilterShortcut || 'all';
        renderResourceFilters();
        renderResourceList();
      });
    });
  }

  function renderResourceExecutiveSummary(scopedResources, filteredResources) {
    const total = scopedResources.length;
    const countByFilter = (filter) => scopedResources.filter((resource) => matchesResourceFilter(resource, filter)).length;
    const attentionCount = countByFilter('attention');
    const shortcuts = [
      { key: 'all', label: 'Todos', value: total },
      { key: 'attention', label: 'Atenção', value: attentionCount },
      { key: 'forms', label: 'Formulários', value: countByFilter('forms') },
      { key: 'analytics', label: 'Charts', value: countByFilter('analytics') },
      { key: 'actions', label: 'Workflow', value: countByFilter('actions') }
    ];
    const recommended = scopedResources
      .map((resource) => ({
        resource,
        attention: catalogDiagnostics(resource).filter((item) => item.level === 'blocking' || item.level === 'attention').length,
        renderability: resourceRenderability(resource)
      }))
      .sort((left, right) => {
        const leftScore = left.attention * 20 + Number(left.renderability.canActions) * 5 + Number(left.renderability.canAnalytics) * 3;
        const rightScore = right.attention * 20 + Number(right.renderability.canActions) * 5 + Number(right.renderability.canAnalytics) * 3;
        return rightScore - leftScore || right.resource.endpoints.length - left.resource.endpoints.length;
      })
      .slice(0, 3);

    return `
      <section class="resource-brief" aria-label="Leitura rápida dos recursos">
        <div class="resource-brief-header">
          <span>Leitura rápida</span>
          <strong>${escapeHtml(filteredResources.length)} de ${escapeHtml(total)} recursos neste recorte</strong>
          <p>${escapeHtml(resourceBriefText(total, attentionCount))}</p>
        </div>
        <div class="resource-brief-grid" aria-label="Atalhos por capacidade">
          ${shortcuts.map((shortcut) => `
            <button class="${shortcut.key === state.resourceFilter ? 'active' : ''}" type="button" data-resource-filter-shortcut="${escapeAttr(shortcut.key)}" aria-pressed="${shortcut.key === state.resourceFilter ? 'true' : 'false'}">
              <span>${escapeHtml(shortcut.label)}</span>
              <strong>${escapeHtml(shortcut.value)}</strong>
            </button>
          `).join('')}
        </div>
        <div class="resource-brief-focus">
          <span>Investigar agora</span>
          <div>
            ${recommended.map((item) => `
              <button type="button" data-key="${escapeAttr(item.resource.key)}" style="${areaStyleVars(item.resource.group)}">
                <strong>${escapeHtml(item.resource.label)}</strong>
                <small>${escapeHtml(resourceBriefSignal(item.resource, item.attention))}</small>
              </button>
            `).join('')}
          </div>
        </div>
      </section>
    `;
  }

  function resourceBriefText(total, attentionCount) {
    if (!total) return 'Nenhum recurso foi materializado para este recorte.';
    if (attentionCount) return `${attentionCount} recurso(s) pedem revisão de contrato, identidade ou capability antes de virarem experiência confiável.`;
    return 'O recorte atual está pronto para exploração por UI, filtros, charts e workflows publicados.';
  }

  function resourceBriefSignal(resource, attentionCount) {
    const profile = resourceRenderability(resource);
    const layers = [
      profile.canForm ? 'form' : null,
      profile.canFilter ? 'filtros' : null,
      profile.canAnalytics ? 'charts' : null,
      profile.canActions ? 'workflow' : null
    ].filter(Boolean);
    if (attentionCount) return `${attentionCount} ponto(s) de atenção · ${layers.join(', ') || 'sem camada confirmada'}`;
    return `${resource.endpoints.length} operações · ${layers.join(', ') || 'sem camada confirmada'}`;
  }

  function matchesResourceFilter(resource, filter) {
    const profile = resourceRenderability(resource);
    if (filter === 'forms') return profile.canForm;
    if (filter === 'filters') return profile.canFilter;
    if (filter === 'analytics') return profile.canAnalytics;
    if (filter === 'actions') return profile.canActions;
    if (filter === 'attention') return catalogDiagnostics(resource).some((item) => item.level === 'blocking' || item.level === 'attention');
    return true;
  }

  function resourceListSignal(resource) {
    const profile = resourceRenderability(resource);
    const parts = [];
    if (profile.canForm) parts.push(`form ${evidenceStatusLabel(profile.evidence.form)}`);
    if (profile.canFilter) parts.push(`filtro ${evidenceStatusLabel(profile.evidence.filter)}`);
    if (profile.canAnalytics) parts.push(`chart ${evidenceStatusLabel(profile.evidence.analytics)}`);
    if (profile.canActions) parts.push(`action ${evidenceStatusLabel(profile.evidence.actions)}`);
    if (!parts.length) return 'sem camada de UI confirmada';
    return parts.slice(0, 3).join(' · ');
  }

  function statusClassFromCount(value, total) {
    if (!total || !value) return 'status-danger';
    if (value === total) return 'status-success';
    return 'status-warning';
  }

  function statusClassForTone(tone) {
    if (tone === 'ok') return 'status-success';
    if (tone === 'attention' || tone === 'optional') return 'status-warning';
    if (tone === 'blocking') return 'status-danger';
    return 'status-info';
  }

  function statusTextForTone(tone) {
    if (tone === 'ok') return 'Pronto';
    if (tone === 'attention') return 'Atenção';
    if (tone === 'optional') return 'Parcial';
    if (tone === 'blocking') return 'Bloqueio';
    return 'Info';
  }

  async function selectResource(key) {
    state.selectedKey = key;
    const selectionToken = `${key}:${Date.now()}`;
    state.selectionToken = selectionToken;
    const resource = selectedResource();
    if (!resource) {
      renderResourceList();
      renderEmptyDetail();
      return;
    }
    state.selectedArea = resource.group || state.selectedArea;
    renderAreas();
    renderResourceList();
    resource.enrichmentState = 'loading';
    renderDetail(resource);
    await enrichResource(resource, false);
    if (state.selectedKey !== key || state.selectionToken !== selectionToken) {
      return;
    }
    resource.enrichmentState = 'ready';
    renderOverview();
    renderDetail(resource);
  }

  function selectedResource() {
    return state.resources.find((resource) => resource.key === state.selectedKey);
  }

  async function enrichResource(resource, force) {
    const resourcePath = resource.resourcePath || resource.paths?.[0];
    let resourceKey = canonicalResourceKey(resource, resourcePath);
    if (resourcePath && (force || !state.capabilities.has(resource.key))) {
      try {
        const capability = await fetchJson(`${resourcePath}/capabilities`);
        state.capabilities.set(resource.key, capability);
        state.capabilityErrors.delete(resource.key);
        resource.capability = capability;
        resource.capabilityError = null;
        promoteCanonicalIdentity(resource, capability, 'capabilities');
        resourceKey = canonicalResourceKey(resource, resourcePath);
      } catch (error) {
        state.capabilities.set(resource.key, null);
        state.capabilityErrors.set(resource.key, error?.message || 'capabilities indisponíveis');
        resource.capability = null;
        resource.capabilityError = error?.message || 'capabilities indisponíveis';
      }
    } else if (state.capabilities.has(resource.key)) {
      resource.capability = state.capabilities.get(resource.key);
      resourceKey = canonicalResourceKey(resource, resourcePath);
    }

    const domainCacheKey = semanticCacheKey(resource, resourceKey);
    if (resourceKey && (force || !state.domains.has(domainCacheKey))) {
      try {
        const graph = await fetchJson(`/schemas/domain?resourceKey=${encodeURIComponent(resourceKey)}`);
        state.domains.set(domainCacheKey, graph);
        resource.domainGraph = graph;
        resource.domainItems = graphToDomainItems(graph);
      } catch (error) {
        state.domains.set(domainCacheKey, null);
        resource.domainGraph = null;
        resource.domainItems = [];
      }
    } else if (state.domains.has(domainCacheKey)) {
      resource.domainGraph = state.domains.get(domainCacheKey);
      resource.domainItems = graphToDomainItems(resource.domainGraph);
    }

    const surfaceCacheKey = semanticCacheKey(resource, resourceKey);
    if (resourceKey && (force || !state.surfaces.has(surfaceCacheKey))) {
      try {
        const payload = await fetchJson(`/schemas/surfaces?resource=${encodeURIComponent(resourceKey)}`);
        const scopedSurfaces = uniqueBySurface([...surfaceItems(payload), ...(resource.capability?.surfaces || [])]);
        state.surfaces.set(surfaceCacheKey, scopedSurfaces);
        resource.surfaces = scopedSurfaces;
        promoteCanonicalIdentity(resource, payload, 'surfaces');
        promoteCanonicalIdentity(resource, scopedSurfaces.find((surface) => surface?.resourceKey), 'surfaces');
        resourceKey = canonicalResourceKey(resource, resourcePath);
      } catch (error) {
        const fallbackSurfaces = uniqueBySurface(resource.capability?.surfaces || []);
        state.surfaces.set(surfaceCacheKey, fallbackSurfaces);
        resource.surfaces = fallbackSurfaces;
        promoteCanonicalIdentity(resource, fallbackSurfaces.find((surface) => surface?.resourceKey), 'capabilities.surfaces');
        resourceKey = canonicalResourceKey(resource, resourcePath);
      }
    } else if (state.surfaces.has(surfaceCacheKey)) {
      resource.surfaces = state.surfaces.get(surfaceCacheKey) || [];
    }

    const actionCacheKey = semanticCacheKey(resource, resourceKey);
    if (resourceKey && (force || !state.actions.has(actionCacheKey))) {
      if (!hasPublishedActionSignal(resource)) {
        const fallbackActions = uniqueBySurface(resource.capability?.actions || []);
        state.actions.set(actionCacheKey, fallbackActions);
        resource.actions = fallbackActions;
        promoteCanonicalIdentity(resource, fallbackActions.find((action) => action?.resourceKey), 'capabilities.actions');
      } else {
        try {
          const payload = await fetchJson(`/schemas/actions?resource=${encodeURIComponent(resourceKey)}`);
          const scopedActions = uniqueBySurface([...actionItems(payload), ...(resource.capability?.actions || [])]);
          state.actions.set(actionCacheKey, scopedActions);
          resource.actions = scopedActions;
          promoteCanonicalIdentity(resource, payload, 'actions');
          promoteCanonicalIdentity(resource, scopedActions.find((action) => action?.resourceKey), 'actions');
        } catch (error) {
          const fallbackActions = uniqueBySurface(resource.capability?.actions || []);
          state.actions.set(actionCacheKey, fallbackActions);
          resource.actions = fallbackActions;
          promoteCanonicalIdentity(resource, fallbackActions.find((action) => action?.resourceKey), 'capabilities.actions');
        }
      }
    } else if (state.actions.has(actionCacheKey)) {
      resource.actions = state.actions.get(actionCacheKey) || [];
    }

    const schemaRef = firstSchemaReference(resource);
    resource.schemaSource = schemaRef;
    const schemaCacheKey = semanticCacheKey(resource, resourceKey);
    if (schemaRef?.url && (force || !state.filteredSchemas.has(schemaCacheKey))) {
      try {
        const schema = await fetchJson(schemaRef.url);
        state.filteredSchemas.set(schemaCacheKey, schema);
        resource.filteredSchema = schema;
      } catch (error) {
        state.filteredSchemas.set(schemaCacheKey, null);
        resource.filteredSchema = null;
      }
    } else if (state.filteredSchemas.has(schemaCacheKey)) {
      resource.filteredSchema = state.filteredSchemas.get(schemaCacheKey);
    }
  }

  function canonicalResourceKey(resource, resourcePath) {
    return resource.resourceKey || null;
  }

  function semanticCacheKey(resource, resourceKey) {
    return resourceKey || resource.resourceKey || resource.key;
  }

  function promoteCanonicalIdentity(resource, payload, source) {
    const candidate = payload?.resourceKey || payload?.resource?.resourceKey || payload?.metadata?.resourceKey;
    if (!candidate || candidate === resource.resourceKey) return;
    resource.resourceKey = candidate;
    resource.key = resource.key || candidate;
    resource.sourceConfidence = source;
    resource.group = resource.group || domainFromResourceKey(candidate);
    resource.domain = labelForArea(resource.group);
    resource.label = readableResourceName(resource);
  }

  function uniqueBySurface(items) {
    const byKey = new Map();
    for (const item of items || []) {
      const key = [item.id, item.operationId, item.method, item.path].filter(Boolean).join('|');
      byKey.set(key || JSON.stringify(item), item);
    }
    return Array.from(byKey.values());
  }

  function firstSchemaReference(resource) {
    const filteredSurfaceSchema = resource.surfaces.find((surface) => isFilteredSchemaUrl(surface?.schemaUrl))?.schemaUrl;
    if (filteredSurfaceSchema) return { url: filteredSurfaceSchema, label: 'schema filtrado', canonical: true };
    const filteredCatalog = resource.schemaLinks.find((links) => isFilteredSchemaUrl(links?.request) || isFilteredSchemaUrl(links?.response));
    if (filteredCatalog?.request && isFilteredSchemaUrl(filteredCatalog.request)) return { url: filteredCatalog.request, label: 'schema filtrado', canonical: true };
    if (filteredCatalog?.response && isFilteredSchemaUrl(filteredCatalog.response)) return { url: filteredCatalog.response, label: 'schema filtrado', canonical: true };
    const surfaceSchema = resource.surfaces.find((surface) => surface?.schemaUrl)?.schemaUrl;
    if (surfaceSchema) return { url: surfaceSchema, label: 'schema da surface', canonical: false };
    const fromCatalog = resource.schemaLinks.find((links) => links?.request || links?.response);
    if (fromCatalog?.request) return { url: fromCatalog.request, label: 'schema de request', canonical: false };
    if (fromCatalog?.response) return { url: fromCatalog.response, label: 'schema de response', canonical: false };
    return null;
  }

  function isFilteredSchemaUrl(url) {
    return String(url || '').includes('/schemas/filtered');
  }

  function renderDetail(resource) {
    const readiness = computeResourceReadiness(resource);
    els.selectedDomain.closest('.resource-header')?.setAttribute('style', areaStyleVars(resource.group));
    els.selectedDomain.textContent = resource.domain;
    els.selectedTitle.textContent = resource.label;
    els.selectedSubtitle.textContent = resource.description;
    els.resourceReadiness.querySelector('strong').textContent = readiness.label;
    els.businessMeaning.textContent = businessMeaning(resource);
    els.integrationMeaning.textContent = integrationMeaning(resource);
    els.gapMeaning.textContent = gapMeaning(resource);
    renderAttentionNow(resource);
    renderTopology(resource);
    renderSemanticReadiness(resource);
    renderRenderability(resource);
    renderResourceAnalytics(resource);
    renderWorkflow(resource);
    renderEndpoints(resource);
    renderFields(resource);
    renderDiagnostics(resource);
  }

  function computeResourceReadiness(resource) {
    const dimensions = semanticDimensions(resource);
    const score = Math.round((dimensions.reduce((sum, item) => sum + readinessWeight(item.status), 0) / dimensions.length) * 100);
    return {
      score,
      label: score === 100 ? 'Pronto' : `${score}%`,
      dimensions
    };
  }

  function semanticDimensions(resource) {
    const filteredFields = filteredSchemaFields(resource);
    const hasCanonicalSchema = Boolean(resource.schemaSource?.canonical && filteredFields.length);
    const canonicalOps = Object.values(resource.capability?.canonicalOperations || {}).filter(Boolean).length;
    const loading = resource.enrichmentState === 'loading';
    const domainGrounded = hasDomainGrounding(resource.domainGraph);
    const capabilityFailed = Boolean(resource.capabilityError || state.capabilityErrors.has(resource.key));
    const capabilityError = resource.capabilityError || state.capabilityErrors.get(resource.key);
    return [
      {
        key: 'domain',
        label: 'Domínio',
        status: loading ? 'loading' : domainGrounded ? 'ok' : 'attention',
        impact: loading ? 'Lendo grafo de conceitos, relações e evidências.' : domainGrounded ? 'IA entende o contexto semântico do recurso.' : 'A chamada pode existir, mas ainda não há grounding semântico suficiente.',
        detail: loading ? 'lendo /schemas/domain escopado' : domainGrounded ? `${countItems(resource.domainGraph.nodes)} nós e ${countItems(resource.domainGraph.evidence)} evidências` : 'grafo sem evidência suficiente ou ainda ausente'
      },
      {
        key: 'schema',
        label: 'Contrato',
        status: loading ? 'loading' : hasCanonicalSchema ? 'ok' : filteredFields.length ? 'attention' : 'blocking',
        impact: loading ? 'Lendo a melhor referência de schema publicada para o recurso.' : hasCanonicalSchema ? 'Campos vêm da fonte estrutural canônica.' : filteredFields.length ? 'Campos existem, mas a origem não comprova schema filtrado canônico.' : 'Runtime e IA não conseguem explicar campos reais deste recurso.',
        detail: loading ? 'lendo schema publicado' : filteredFields.length ? `${filteredFields.length} campos via ${resource.schemaSource?.label || 'schema'}` : 'sem campos de schema materializados'
      },
      {
        key: 'capabilities',
        label: 'Operações',
        status: loading ? 'loading' : capabilityFailed ? 'blocking' : resource.capability ? 'ok' : 'attention',
        impact: loading ? 'Lendo snapshot agregado de disponibilidade.' : capabilityFailed ? 'O endpoint de capabilities falhou; a leitura global não pode confirmar identidade e operações.' : resource.capability ? 'O runtime sabe quais operações canônicas existem.' : 'O cockpit enxerga endpoints, mas não confirma snapshot agregado de capabilities.',
        detail: loading ? 'lendo capabilities do recurso' : capabilityFailed ? capabilityError : resource.capability ? `${canonicalOps} operações canônicas suportadas` : 'capabilities não lidas'
      },
      {
        key: 'surfaces',
        label: 'Experiência',
        status: loading ? 'loading' : resource.surfaces.length ? 'ok' : 'optional',
        impact: loading ? 'Lendo experiências declaradas para este recurso.' : resource.surfaces.length ? 'Há experiências publicadas para orientar runtime e UI.' : 'Sem experiência de uso declarada; pode ser legítimo para recursos técnicos ou ainda não materializados.',
        detail: loading ? 'lendo surfaces escopadas' : resource.surfaces.length ? `${resource.surfaces.length} experiências publicadas` : 'sem surface publicada para o recurso'
      },
      {
        key: 'actions',
        label: 'Ações',
        status: loading ? 'loading' : resource.actions.length ? 'ok' : 'optional',
        impact: loading ? 'Lendo comandos de negócio declarados.' : resource.actions.length ? 'Há comandos de negócio acionáveis além de CRUD.' : 'Ausência aceitável para recursos CRUD; limita automações de negócio.',
        detail: loading ? 'lendo actions escopadas' : resource.actions.length ? `${resource.actions.length} workflow action publicada` : 'sem workflow action publicada'
      }
    ];
  }

  function readinessWeight(status) {
    if (status === 'ok') return 1;
    if (status === 'optional') return .82;
    if (status === 'attention') return .52;
    if (status === 'loading') return .5;
    return .16;
  }

  function hasDomainGrounding(graph) {
    return Boolean(graph)
      && (countItems(graph.nodes) > 0 || countItems(graph.edges) > 0 || countItems(graph.evidence) > 0);
  }

  function countItems(value) {
    return Array.isArray(value) ? value.length : 0;
  }

  function businessMeaning(resource) {
    const graph = resource.domainGraph;
    const context = Array.isArray(graph?.contexts) ? graph.contexts[0] : null;
    const confidence = graph?.governance?.confidence || context?.confidence;
    const suffix = confidence ? ` Confiança publicada: ${confidence}.` : '';
    return `${resource.label} pertence à área ${resource.domain}. ${resource.description}${suffix}`;
  }

  function integrationMeaning(resource) {
    const ops = resource.capability?.canonicalOperations || resource.operationSummary;
    const parts = [];
    if (ops.all || ops.byId || resource.operationSummary.read) parts.push('leitura');
    if (ops.create || ops.update || ops.delete || resource.operationSummary.write) parts.push('escrita governada');
    if (ops.filter || ops.cursor) parts.push('filtros');
    if (ops.statsGroupBy || ops.statsTimeSeries || ops.statsDistribution) parts.push('estatísticas');
    if (ops.options || ops.optionSources) parts.push('fontes de opção');
    return parts.length ? `O host materializa ${joinHuman(parts)} para este recurso.` : 'Não há operações canônicas suficientes para explicar a integração deste recurso.';
  }

  function gapMeaning(resource) {
    if (resource.enrichmentState === 'loading') return 'O cockpit ainda está lendo evidências escopadas deste recurso.';
    if (!hasDomainGrounding(resource.domainGraph)) return 'O domínio escopado ainda não materializou grounding suficiente; a leitura pode cair para labels derivados de path.';
    if (!filteredSchemaFields(resource).length) return 'Nenhum schema trouxe campos suficientes para explicar contratos reais.';
    if (filteredSchemaFields(resource).length && !resource.schemaSource?.canonical) return `Os campos foram lidos de ${resource.schemaSource?.label || 'schema'}, não de schema filtrado canônico.`;
    if (!resource.capability) return 'Capabilities ainda não foram lidas ou não estão disponíveis para este recurso.';
    if (!resource.surfaces.length) return 'Nenhuma surface foi publicada para este recurso; a API existe, mas a experiência metadata-driven ainda não aparece.';
    if (!resource.actions.length) return 'Não há workflow action publicada; isso não bloqueia CRUD, mas limita decisões de negócio acionáveis.';
    return 'O recurso tem domínio, schema, capabilities e materializações suficientes para inspeção semântica.';
  }

  function renderAttentionNow(resource) {
    const diagnostics = operationalDiagnostics(resource).slice(0, 4);
    if (!diagnostics.length) {
      els.attentionNow.innerHTML = '<article class="attention-item ok"><strong>Nenhuma prioridade crítica</strong><span>O recurso tem evidências suficientes para inspeção semântica inicial.</span><small>Continue refinando glossário, exemplos e decisões de negócio.</small></article>';
      return;
    }
    els.attentionNow.innerHTML = diagnostics.map((item) => `
      <article class="attention-item ${escapeAttr(item.level)}">
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.impact)}</span>
        <small>${escapeHtml(item.action)}</small>
      </article>
    `).join('');
  }

  function operationalDiagnostics(resource) {
    const fields = filteredSchemaFields(resource);
    const diagnostics = [];
    if (resource.enrichmentState === 'loading') {
      return [{
        level: 'loading',
        title: 'Lendo evidências do recurso',
        impact: 'O cockpit está consultando domínio, capabilities, surfaces, actions e schema antes de classificar lacunas.',
        action: 'Aguarde a confirmação das fontes canônicas publicadas pelo starter.'
      }];
    }
    if (!hasDomainGrounding(resource.domainGraph)) {
      diagnostics.push({
        level: 'attention',
        title: 'Domínio escopado sem grounding suficiente',
        impact: 'A IA não recebe conceitos, relações ou evidências suficientes para este recurso.',
        action: 'Verifique se o resourceKey canônico está publicado e se /schemas/domain responde para ele.'
      });
    }
    if (!fields.length) {
      diagnostics.push({
        level: 'blocking',
        title: 'Contrato sem campos explicáveis',
        impact: 'O runtime consegue listar endpoints, mas não explica payloads e propriedades do recurso.',
        action: 'Priorize /schemas/filtered ou uma fonte de schema com campos reais para este recurso.'
      });
    } else if (!resource.schemaSource?.canonical) {
      diagnostics.push({
        level: 'attention',
        title: 'Campos sem confirmação de schema filtrado',
        impact: `A UI está usando ${resource.schemaSource?.label || 'schema do catálogo'}, que pode ser request/response e não a fonte estrutural canônica.`,
        action: 'Diferencie a origem ou publique referência explícita para /schemas/filtered.'
      });
    }
    if (!resource.capability) {
      diagnostics.push({
        level: 'attention',
        title: 'Capabilities não confirmadas',
        impact: 'As operações aparecem no catálogo, mas o cockpit não tem snapshot agregado de disponibilidade.',
        action: `Verifique ${resource.resourcePath || 'o endpoint do recurso'}/capabilities.`
      });
    }
    if (!resource.surfaces.length) {
      diagnostics.push({
        level: 'optional',
        title: 'Sem experiência declarada',
        impact: 'O recurso existe como API, mas não publicou uma surface de UI ou jornada operacional.',
        action: 'Trate como lacuna apenas quando este recurso precisar ser materializado em experiência de usuário.'
      });
    }
    if (!resource.actions.length) {
      diagnostics.push({
        level: 'optional',
        title: 'Sem action de workflow',
        impact: 'Isso não bloqueia CRUD, mas reduz automações e decisões de negócio acionáveis.',
        action: 'Modele workflow actions apenas para comandos de negócio explícitos.'
      });
    }
    return diagnostics.sort((a, b) => severityRank(a.level) - severityRank(b.level));
  }

  function severityRank(level) {
    return { blocking: 0, attention: 1, optional: 2, ok: 3, good: 3, warn: 1, bad: 0 }[level] ?? 4;
  }

  function renderTopology(resource) {
    const graph = resource.domainGraph;
    if (resource.enrichmentState === 'loading') {
      els.domainTopology.innerHTML = '<div class="empty-state">Lendo topologia escopada do recurso...</div>';
      destroyTopologyGraph();
      return;
    }
    if (!graph) {
      els.domainTopology.innerHTML = '<div class="empty-state">A topologia aparece quando /schemas/domain materializa o grafo escopado deste recurso.</div>';
      destroyTopologyGraph();
      return;
    }

    const nodes = Array.isArray(graph.nodes) ? graph.nodes : [];
    const edges = Array.isArray(graph.edges) ? graph.edges : [];
    const evidence = Array.isArray(graph.evidence) ? graph.evidence : [];
    const root = nodes.find((node) => node.nodeKey === resource.resourceKey)
      || nodes.find((node) => node.metadata?.resourceKey === resource.resourceKey && node.nodeType === 'concept')
      || nodes[0];
    const rootKey = root?.nodeKey;
    const relatedEdges = edges
      .filter((edge) => edge.sourceNodeKey === rootKey || edge.targetNodeKey === rootKey)
      .slice(0, 8);
    const relatedNodeKeys = new Set(relatedEdges.map((edge) => edge.sourceNodeKey === rootKey ? edge.targetNodeKey : edge.sourceNodeKey));
    const relatedNodes = nodes
      .filter((node) => node.nodeKey !== rootKey && relatedNodeKeys.has(node.nodeKey))
      .slice(0, 9);
    const nodeGroups = groupNodesByType(relatedNodes);
    const relationEvidenceKeys = new Set(relatedEdges.flatMap((edge) => edge.evidenceKeys || []));
    const selectedEvidence = evidence
      .filter((item) => relationEvidenceKeys.has(item.evidenceKey) || item.sourceRef?.resourceKey === resource.resourceKey)
      .slice(0, 5);

    els.domainTopology.innerHTML = `
      <div class="semantic-graph-shell">
        <div class="semantic-graph-toolbar">
          <div>
            <span>Constelação navegável</span>
            <strong>Como este recurso se conecta ao domínio</strong>
          </div>
          <div class="semantic-graph-tools">
            <div class="semantic-graph-legend" aria-label="Legenda do grafo">
              <span class="concept">Conceito</span>
              <span class="field">Campo</span>
              <span class="surface">UI</span>
              <span class="action">Workflow</span>
              <span class="stats">Analytics</span>
              <span class="journey">Jornada</span>
            </div>
            <div class="semantic-graph-filters" aria-label="Filtros do grafo semântico">
              <button type="button" class="active" data-graph-filter="all">Tudo</button>
              <button type="button" data-graph-filter="journey">Jornada</button>
              <button type="button" data-graph-filter="surface">UI</button>
              <button type="button" data-graph-filter="action">Workflow</button>
              <button type="button" data-graph-filter="field">Campos</button>
              <button type="button" data-graph-filter="stats">Analytics</button>
            </div>
            <div class="semantic-graph-view-modes" aria-label="Modos do grafo semântico">
              <button type="button" class="active" data-graph-mode="clean">Limpo</button>
              <button type="button" data-graph-mode="evidence">Evidência</button>
            </div>
            <label class="semantic-graph-search">
              <span>Buscar no grafo</span>
              <input type="search" data-graph-search placeholder="Campo, UI, action..." autocomplete="off">
              <small data-graph-search-status>Digite para localizar campos, UI, workflow ou analytics.</small>
            </label>
            <div class="semantic-graph-viewport-tools" aria-label="Controles de visualização do grafo">
              <button type="button" data-graph-zoom="out" aria-label="Reduzir zoom do grafo" title="Reduzir zoom">-</button>
              <span data-graph-scale>100%</span>
              <button type="button" data-graph-zoom="in" aria-label="Aumentar zoom do grafo" title="Aumentar zoom">+</button>
              <button type="button" data-graph-fit aria-label="Ajustar grafo à tela" title="Ajustar grafo à tela">Centralizar</button>
              <button type="button" data-graph-fullscreen aria-label="Abrir grafo em tela cheia" title="Tela cheia">Expandir</button>
            </div>
            <div class="semantic-graph-summary" aria-label="Resumo do grafo"></div>
          </div>
        </div>
        <div class="semantic-graph-stage">
          <div id="semanticGraphCanvas" class="semantic-graph-canvas" role="img" aria-label="Grafo navegável de relações do recurso"></div>
          <aside id="semanticGraphInspector" class="semantic-graph-inspector">
            <span>Recurso central</span>
            <strong>${escapeHtml(normalizePortugueseLabel(root?.businessGlossary?.preferredTerm || root?.label || resource.label))}</strong>
            <p>${escapeHtml(readableText(root?.description || root?.businessGlossary?.description || resource.description || 'Conceito central do recurso selecionado.'))}</p>
          </aside>
        </div>
        <div class="semantic-graph-routes" aria-label="Trilhas de materialização do recurso"></div>
      </div>
      <div class="topology-canvas">
        ${renderRootNode(root, resource, graph)}
        <div class="topology-groups">
          ${nodeGroups.map(renderNodeGroup).join('') || '<div class="empty-state">Nenhum nó relacionado foi publicado para este recurso.</div>'}
        </div>
      </div>
      <div class="topology-evidence-grid">
        <div>
          <h3>Relações explicadas</h3>
          <div class="relation-list">
            ${relatedEdges.map((edge) => renderRelation(edge, nodes)).join('') || '<div class="empty-state">Nenhuma relação explícita publicada.</div>'}
          </div>
        </div>
        <div>
          <h3>Evidências de grounding</h3>
          <div class="relation-list">
            ${selectedEvidence.map(renderEvidence).join('') || '<div class="empty-state">Nenhuma evidência vinculada às relações principais.</div>'}
          </div>
        </div>
      </div>
    `;
    mountTopologyGraph(resource, graph, root);
  }

  function destroyTopologyGraph() {
    if (state.topologyGraph) {
      state.topologyGraph.destroy();
      state.topologyGraph = null;
    }
  }

  function mountTopologyGraph(resource, graph, root) {
    const container = document.getElementById('semanticGraphCanvas');
    const inspector = document.getElementById('semanticGraphInspector');
    const shell = container?.closest('.semantic-graph-shell');
    if (!container) return;
    const model = buildTopologyGraphModel(resource, graph, root);
    if (!model.elements.length) {
      container.innerHTML = '<div class="empty-state">Sem relações suficientes para desenhar o grafo.</div>';
      return;
    }
    renderTopologyGraphSummary(shell, model);

    destroyTopologyGraph();
    withTimeout(loadCytoscape(), 4500)
      .then((cytoscape) => {
        if (!container.isConnected) return;
        state.topologyGraph = cytoscape({
          container,
          elements: model.elements,
          minZoom: 0.55,
          maxZoom: 2.2,
          style: topologyGraphStyle(),
          layout: {
            name: 'concentric',
            animate: true,
            animationDuration: 420,
            avoidOverlap: true,
            concentric: (node) => node.data('weight') || 1,
            levelWidth: () => 1,
            minNodeSpacing: 38,
            padding: 36
          }
        });
        state.topologyGraph.on('tap', 'node', (event) => {
          const data = event.target.data();
          state.topologyGraph.elements().removeClass('graph-focus graph-muted');
          event.target.addClass('graph-focus');
          event.target.connectedEdges().addClass('graph-focus');
          event.target.union(event.target.neighborhood('node')).removeClass('graph-label-muted');
          state.topologyGraph.elements().difference(event.target.union(event.target.connectedEdges()).union(event.target.neighborhood('node'))).addClass('graph-muted');
          updateTopologyInspector(inspector, data);
        });
        state.topologyGraph.on('tap', 'edge', (event) => {
          const data = event.target.data();
          focusTopologyEdge(state.topologyGraph, data.id, inspector);
        });
        bindTopologyGraphControls(shell, state.topologyGraph, model, inspector);
        bindTopologyRelationCards(state.topologyGraph, model, inspector);
        applyTopologyGraphMode(state.topologyGraph, shell, 'clean');
        applyTopologyGraphFilter(state.topologyGraph, 'all');
        state.topologyGraph.fit(undefined, 34);
      })
      .catch(() => {
        renderTopologySvgFallback(container, model, inspector, shell);
      });
  }

  function renderTopologyGraphSummary(shell, model) {
    const summary = shell?.querySelector('.semantic-graph-summary');
    if (!summary) return;
    const nodes = model.elements.filter((item) => !item.data.source).map((item) => item.data);
    const edges = model.elements.filter((item) => item.data.source).map((item) => item.data);
    const byType = nodes.reduce((acc, node) => {
      acc[node.type] = (acc[node.type] || 0) + 1;
      return acc;
    }, {});
    summary.innerHTML = `
      <span>${nodes.length} nós</span>
      <span>${edges.length} relações</span>
      <span>${byType.journey || 0} jornada</span>
      <span>${byType.surface || 0} UI</span>
      <span>${byType.action || 0} workflow</span>
      <span>${byType.field || 0} campos</span>
    `;
    const labels = {
      all: 'Tudo',
      journey: 'Jornada',
      surface: 'UI',
      action: 'Workflow',
      field: 'Campos',
      stats: 'Analytics'
    };
    for (const button of shell.querySelectorAll('[data-graph-filter]')) {
      const filter = button.getAttribute('data-graph-filter') || 'all';
      const count = filter === 'all' ? nodes.length : (byType[filter] || 0);
      button.innerHTML = `${escapeHtml(labels[filter] || nodeTypeLabel(filter))}<small>${count}</small>`;
      button.classList.toggle('is-empty', filter !== 'all' && count === 0);
    }
    const routes = shell.querySelector('.semantic-graph-routes');
    if (routes) {
      routes.innerHTML = topologyMaterializationRoutes(byType).map((route) => `
        <button type="button" class="${route.status}" data-graph-route-filter="${escapeAttr(route.filter)}" onclick="this.closest('.semantic-graph-shell')?.querySelector('[data-graph-filter=&quot;${escapeAttr(route.filter)}&quot;]')?.click()">
          <span>${escapeHtml(route.statusLabel)}</span>
          <strong>${escapeHtml(route.title)}</strong>
          <p>${escapeHtml(route.body)}</p>
        </button>
      `).join('');
    }
  }

  function topologyMaterializationRoutes(byType) {
    const fields = byType.field || 0;
    const journey = byType.journey || 0;
    const surfaces = byType.surface || 0;
    const stats = byType.stats || 0;
    const actions = byType.action || 0;
    return [
      {
        filter: 'journey',
        title: 'Jornada do domínio',
        body: journey
          ? `${journey} recurso(s) formam uma cadeia navegável de negócio ao redor deste recurso.`
          : 'Sem cadeia suficiente entre recursos do mesmo domínio para materializar uma jornada.',
        ...routeStatus(journey >= 3, journey > 0)
      },
      {
        filter: 'surface',
        title: 'Tela operacional',
        body: surfaces
          ? `${surfaces} experiência(s) de UI e ${fields} campo(s) sustentam tabela, detalhe ou área de trabalho.`
          : `Nenhuma surface publicada; ${fields} campo(s) ainda permitem inferir parte da tela.`,
        ...routeStatus(surfaces > 0 && fields > 0, surfaces > 0 || fields > 0)
      },
      {
        filter: 'field',
        title: 'Contrato e formulário',
        body: fields
          ? `${fields} campo(s) materializáveis definem o que pode virar formulário, colunas e validação visual.`
          : 'Sem campos materializáveis suficientes para explicar formulário ou tabela.',
        ...routeStatus(fields > 0, false)
      },
      {
        filter: 'stats',
        title: 'Analytics e charts',
        body: stats
          ? `${stats} capability(s) analíticas podem alimentar gráfico, tendência ou distribuição.`
          : 'Sem nó analítico publicado para este recurso no grafo atual.',
        ...routeStatus(stats > 0, false)
      },
      {
        filter: 'action',
        title: 'Automação e workflow',
        body: actions
          ? `${actions} action(s) acionáveis conectam o recurso a comandos de negócio.`
          : 'Nenhuma action publicada; o recurso aparece mais como consulta/gestão do que automação.',
        ...routeStatus(actions > 0, false)
      }
    ];
  }

  function routeStatus(ready, partial) {
    if (ready) return { status: 'ready', statusLabel: 'Pronto' };
    if (partial) return { status: 'partial', statusLabel: 'Parcial' };
    return { status: 'missing', statusLabel: 'Ausente' };
  }

  function bindTopologyGraphControls(shell, cy, model, inspector) {
    if (!shell || !cy) return;
    const scale = shell.querySelector('[data-graph-scale]');
    let currentFilter = 'all';
    let currentMode = 'clean';
    const updateScale = () => {
      if (scale) scale.textContent = `${Math.round((cy.zoom() || 1) * 100)}%`;
    };
    const zoomBy = (factor) => {
      const current = cy.zoom() || 1;
      const next = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), current * factor));
      cy.zoom({ level: next, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
      updateScale();
    };
    const runFilter = (filter) => {
      currentFilter = filter || 'all';
      syncTopologyFilterState(shell, filter);
      cy.elements().removeClass('graph-focus graph-muted');
      applyTopologyGraphMode(cy, shell, currentMode);
      applyTopologyGraphFilter(cy, currentFilter);
      updateTopologyInspector(inspector, topologyFilterSummary(filter, model));
      updateScale();
    };
    const runMode = (mode) => {
      currentMode = mode || 'clean';
      syncTopologyModeState(shell, currentMode);
      cy.elements().removeClass('graph-focus graph-muted');
      applyTopologyGraphMode(cy, shell, currentMode);
      applyTopologyGraphFilter(cy, currentFilter);
      updateTopologyInspector(inspector, topologyModeSummary(currentMode, model));
      updateScale();
    };
    cy.on('zoom pan', updateScale);
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-filter], [data-graph-route-filter]');
      if (!button || !shell.contains(button)) return;
      const filter = button.getAttribute('data-graph-filter') || button.getAttribute('data-graph-route-filter') || 'all';
      runFilter(filter);
    });
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-mode]');
      if (!button || !shell.contains(button)) return;
      runMode(button.getAttribute('data-graph-mode') || 'clean');
    });
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-resource-key]');
      if (!button || !shell.contains(button)) return;
      const resourceKey = button.getAttribute('data-graph-resource-key');
      const linkedResource = state.resources.find((resource) => resource.resourceKey === resourceKey || resource.key === resourceKey);
      if (linkedResource) selectResource(linkedResource.key);
    });
    for (const button of shell.querySelectorAll('[data-graph-route-filter]')) {
      button.addEventListener('click', () => {
        runFilter(button.getAttribute('data-graph-route-filter') || 'all');
      });
    }
    const fitButton = shell.querySelector('[data-graph-fit]');
    fitButton?.addEventListener('click', () => {
      cy.fit(cy.elements(':visible'), 34);
      const root = cy.nodes('[type = "root"]').first();
      if (root?.length) updateTopologyInspector(inspector, root.data());
      updateScale();
    });
    for (const button of shell.querySelectorAll('[data-graph-zoom]')) {
      button.addEventListener('click', () => {
        zoomBy(button.getAttribute('data-graph-zoom') === 'in' ? 1.18 : 1 / 1.18);
      });
    }
    bindTopologyFullscreenControl(shell, () => {
      cy.resize();
      cy.fit(cy.elements(':visible'), shell.classList.contains('is-fullscreen') ? 72 : 34);
      updateScale();
    });
    bindTopologySearchControl(shell, cy, model, inspector);
    runMode(currentMode);
    updateScale();
  }

  function bindTopologyFullscreenControl(shell, afterToggle) {
    const button = shell?.querySelector('[data-graph-fullscreen]');
    if (!shell || !button) return;
    const sync = () => {
      const active = document.fullscreenElement === shell || shell.classList.contains('is-fullscreen');
      shell.classList.toggle('is-fullscreen', active);
      button.textContent = active ? 'Fechar' : 'Expandir';
      button.setAttribute('aria-label', active ? 'Fechar grafo em tela cheia' : 'Abrir grafo em tela cheia');
      button.setAttribute('title', active ? 'Fechar tela cheia' : 'Tela cheia');
      if (typeof afterToggle === 'function') window.setTimeout(afterToggle, 90);
    };
    button.addEventListener('click', async () => {
      if (document.fullscreenElement === shell || shell.classList.contains('is-fullscreen')) {
        if (document.fullscreenElement === shell && document.exitFullscreen) {
          await document.exitFullscreen().catch(() => {});
        }
        shell.classList.remove('is-fullscreen');
        sync();
        return;
      }
      if (shell.requestFullscreen) {
        await shell.requestFullscreen().catch(() => shell.classList.add('is-fullscreen'));
      } else {
        shell.classList.add('is-fullscreen');
      }
      sync();
    });
    document.addEventListener('fullscreenchange', sync);
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && shell.classList.contains('is-fullscreen') && !document.fullscreenElement) {
        shell.classList.remove('is-fullscreen');
        sync();
      }
    });
  }

  function syncTopologyFilterState(shell, filter) {
    for (const item of shell.querySelectorAll('[data-graph-filter], [data-graph-route-filter]')) {
      const itemFilter = item.getAttribute('data-graph-filter') || item.getAttribute('data-graph-route-filter') || 'all';
      item.classList.toggle('active', itemFilter === filter);
    }
  }

  function syncTopologyModeState(shell, mode) {
    for (const item of shell.querySelectorAll('[data-graph-mode]')) {
      item.classList.toggle('active', item.getAttribute('data-graph-mode') === mode);
    }
  }

  function bindTopologyRelationCards(cy, model, inspector) {
    if (!cy) return;
    for (const button of els.domainTopology.querySelectorAll('[data-graph-edge-id]')) {
      button.addEventListener('click', () => {
        focusTopologyEdge(cy, button.getAttribute('data-graph-edge-id'), inspector, model);
      });
    }
  }

  function focusTopologyEdge(cy, edgeId, inspector, model) {
    if (!cy || !edgeId) return;
    const edge = cy.getElementById(edgeId);
    if (!edge?.length) {
      const data = (model?.elements || []).find((item) => item.data.id === edgeId)?.data;
      if (data) updateTopologyInspector(inspector, data);
      return;
    }
    cy.elements().removeClass('graph-focus graph-muted');
    const endpoints = edge.connectedNodes();
    edge.union(endpoints).removeClass('graph-hidden');
    cy.elements().difference(edge.union(endpoints)).addClass('graph-muted');
    edge.addClass('graph-focus');
    endpoints.addClass('graph-focus');
    cy.fit(edge.union(endpoints), 72);
    updateTopologyInspector(inspector, edge.data());
  }

  function applyTopologyGraphFilter(cy, filter) {
    if (!cy) return;
    cy.elements().removeClass('graph-hidden graph-dimmed');
    if (!filter || filter === 'all') {
      cy.elements().removeClass('graph-hidden');
      return;
    }
    const visibleNodes = cy.nodes().filter((node) => node.data('type') === 'root' || node.data('type') === filter || (filter === 'journey' && node.data('isJourney')));
    const visibleNodeIds = new Set(visibleNodes.map((node) => node.id()));
    cy.nodes().forEach((node) => {
      if (!visibleNodeIds.has(node.id())) node.addClass('graph-hidden');
    });
    cy.edges().forEach((edge) => {
      if (!visibleNodeIds.has(edge.source().id()) || !visibleNodeIds.has(edge.target().id())) edge.addClass('graph-hidden');
    });
    cy.fit(cy.elements(':visible'), 34);
  }

  function applyTopologyGraphMode(cy, shell, mode) {
    if (!cy) return;
    cy.elements().removeClass('graph-mode-hidden graph-label-muted');
    if (mode === 'evidence') return;
    cy.nodes().forEach((node) => {
      if (node.data('type') === 'field' && !node.data('isPriority')) node.addClass('graph-mode-hidden');
      if (node.data('type') !== 'root' && !node.data('isPriority')) node.addClass('graph-label-muted');
    });
    cy.edges().forEach((edge) => {
      if (edge.source().hasClass('graph-mode-hidden') || edge.target().hasClass('graph-mode-hidden')) edge.addClass('graph-mode-hidden');
      edge.addClass('graph-label-muted');
    });
    const visible = cy.elements(':visible');
    if (visible.length) cy.fit(visible, 34);
    const hiddenCount = cy.nodes('.graph-mode-hidden').length;
    shell?.querySelector('[data-graph-mode="clean"]')?.setAttribute('aria-label', hiddenCount ? `Modo limpo, ${hiddenCount} campo(s) secundário(s) oculto(s)` : 'Modo limpo');
  }

  function bindTopologySearchControl(shell, cy, model, inspector) {
    const input = shell?.querySelector('[data-graph-search]');
    if (!input || !cy) return;
    const search = () => {
      const query = normalizeSearch(input.value);
      cy.elements().removeClass('graph-search-match graph-muted graph-focus');
      if (!query) {
        updateTopologySearchStatus(shell, 'Digite para localizar campos, UI, workflow ou analytics.');
        updateTopologyInspector(inspector, topologyModeSummary(activeTopologyMode(shell), model));
        return;
      }
      const matches = cy.nodes().filter((node) => topologySearchText(node.data()).includes(query));
      if (!matches.length) {
        cy.elements().addClass('graph-muted');
        updateTopologySearchStatus(shell, `Nenhum resultado para "${input.value}".`, 'empty');
        updateTopologyInspector(inspector, {
          type: 'relation',
          label: 'Nada encontrado',
          detail: `Nenhum nó visível ou oculto corresponde a "${input.value}".`,
          evidence: [{ label: 'Busca', detail: 'Tente parte do nome canônico, campo, surface, action ou tipo semântico.' }]
        });
        return;
      }
      matches.removeClass('graph-hidden graph-mode-hidden graph-label-muted').addClass('graph-search-match graph-focus');
      const connected = matches.connectedEdges().union(matches.neighborhood('node'));
      connected.removeClass('graph-hidden graph-mode-hidden graph-label-muted');
      cy.elements().difference(matches.union(connected)).addClass('graph-muted');
      cy.fit(matches.union(connected), 72);
      updateTopologySearchStatus(shell, `${matches.length} resultado(s); vizinhos e relações foram preservados.`, 'found');
      updateTopologyInspector(inspector, matches.first().data());
    };
    input.addEventListener('input', debounce(search, 180));
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        input.value = '';
        search();
      }
    });
  }

  function activeTopologyMode(shell) {
    return shell?.querySelector('[data-graph-mode].active')?.getAttribute('data-graph-mode') || 'clean';
  }

  function updateTopologySearchStatus(shell, text, tone = 'neutral') {
    const status = shell?.querySelector('[data-graph-search-status]');
    if (!status) return;
    status.textContent = text;
    status.dataset.tone = tone;
  }

  function topologySearchText(data) {
    return normalizeSearch([
      data?.label,
      data?.detail,
      data?.type,
      data?.resourceKey,
      data?.id
    ].filter(Boolean).join(' '));
  }

  function normalizeSearch(value) {
    return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  }

  function debounce(fn, waitMs) {
    let timer;
    return (...args) => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => fn(...args), waitMs);
    };
  }

  function topologyFilterSummary(filter, model) {
    const nodes = model.elements.filter((item) => !item.data.source).map((item) => item.data);
    const edges = model.elements.filter((item) => item.data.source).map((item) => item.data);
    if (!filter || filter === 'all') {
      return {
        type: 'relation',
        label: 'Visão completa',
        detail: `${nodes.length} nós e ${edges.length} relações combinando domínio, UI, workflow, analytics e campos materializáveis.`,
        evidence: [
          { label: 'Leitura', detail: 'Use esta visão para entender o recurso como uma pequena arquitetura de domínio.' },
          { label: 'Navegação', detail: 'Clique em nós, relações ou trilhas para isolar uma camada de materialização.' }
        ]
      };
    }
    const filteredNodes = nodes.filter((node) => node.type === filter);
    const labels = {
      surface: 'UI materializável',
      action: 'Workflows acionáveis',
      field: 'Campos materializáveis',
      stats: 'Analytics publicados'
    };
    return {
      type: filter,
      label: labels[filter] || nodeTypeLabel(filter),
      detail: `${filteredNodes.length} nó(s) ligados ao recurso central nesta camada. Use os nós para inspecionar significado, origem e possível navegação.`,
      evidence: topologyFilterBlueprint(filter, filteredNodes, nodes)
    };
  }

  function topologyModeSummary(mode, model) {
    const nodes = model.elements.filter((item) => !item.data.source).map((item) => item.data);
    const fields = nodes.filter((node) => node.type === 'field');
    const secondaryFields = fields.filter((node) => !node.isPriority);
    const primaryNodes = nodes.length - secondaryFields.length;
    if (mode === 'evidence') {
      return {
        type: 'relation',
        label: 'Modo evidência',
        detail: `${nodes.length} nó(s) visíveis para rastrear campos, UI, workflow, analytics e grounding do recurso.`,
        evidence: [
          { label: 'Leitura', detail: 'Use este modo para auditoria detalhada e rastreabilidade.' },
          { label: 'Busca', detail: 'Digite parte de um campo, action, surface ou conceito para centralizar no grafo.' }
        ]
      };
    }
    return {
      type: 'relation',
      label: 'Modo limpo',
      detail: `${Math.max(0, primaryNodes)} nó(s) principais em foco; ${secondaryFields.length} campo(s) secundário(s) ficam disponíveis por busca ou pelo modo Evidência.`,
      evidence: [
        { label: 'Leitura inicial', detail: 'Reduz a densidade visual para mostrar primeiro o mapa de nós e relações principais.' },
        { label: 'Detalhe', detail: 'Rótulos secundários entram quando você busca, clica em um nó ou ativa Evidência.' }
      ]
    };
  }

  function topologyFilterBlueprint(filter, filteredNodes, allNodes) {
    const count = filteredNodes.length;
    const fields = allNodes.filter((node) => node.type === 'field').length;
    const blueprints = {
      surface: [
        {
          label: 'Componentes prováveis',
          detail: count
            ? `Pode orientar tabela, detalhe, navegação ou área operacional com ${count} surface(s) e ${fields} campo(s).`
            : `Sem surface publicada; ainda há ${fields} campo(s) que podem sustentar uma tela inicial.`
        },
        {
          label: 'O que validar',
          detail: count ? 'Confirmar quais surfaces são tabela, formulário, detalhe ou painel analítico.' : 'Publicar surfaces para deixar a UI menos inferida e mais governada.'
        }
      ],
      field: [
        {
          label: 'Componentes prováveis',
          detail: count ? `Pode materializar colunas, campos de formulário, validações visuais e leitura de DTO com ${count} campo(s).` : 'Sem campos suficientes para montar contrato visual.'
        },
        {
          label: 'O que validar',
          detail: 'Conferir obrigatoriedade, descrições, option sources, tipos e campos que não devem aparecer na UI.'
        }
      ],
      stats: [
        {
          label: 'Componentes prováveis',
          detail: count ? `Pode alimentar ${count} chart(s), cards de métrica, séries temporais ou distribuição.` : 'Sem analytics publicado para gráficos canônicos.'
        },
        {
          label: 'O que validar',
          detail: count ? 'Confirmar granularidade, filtros aceitos e se o chart representa decisão de negócio ou apenas dado técnico.' : 'Publicar stats/capabilities analíticas quando fizer sentido para o domínio.'
        }
      ],
      action: [
        {
          label: 'Componentes prováveis',
          detail: count ? `Pode gerar botões de comando, fluxos assistidos e automações com ${count} action(s).` : 'Sem action publicada; não há workflow acionável nesta camada.'
        },
        {
          label: 'O que validar',
          detail: count ? 'Confirmar pré-condições, efeitos, payload e estados de sucesso/erro de cada ação.' : 'Modelar actions somente quando houver comando de negócio real, não atalho visual.'
        }
      ]
    };
    return blueprints[filter] || [];
  }

  function withTimeout(promise, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout')), timeoutMs);
      promise
        .then((value) => {
          clearTimeout(timer);
          resolve(value);
        })
        .catch((error) => {
          clearTimeout(timer);
          reject(error);
        });
    });
  }

  function loadCytoscape() {
    if (window.cytoscape) return Promise.resolve(window.cytoscape);
    if (state.cytoscapeLoader) return state.cytoscapeLoader;
    state.cytoscapeLoader = loadScriptSequence([
      './assets/vendor/cytoscape/cytoscape.min.js'
    ], 'cytoscape');
    return state.cytoscapeLoader;
  }

  function loadScriptSequence(sources, globalName) {
    return new Promise((resolve, reject) => {
      const [source, ...rest] = sources;
      if (!source) {
        reject(new Error(`${globalName} indisponível`));
        return;
      }
      const script = document.createElement('script');
      script.src = source;
      script.async = true;
      script.onload = () => window[globalName] ? resolve(window[globalName]) : loadScriptSequence(rest, globalName).then(resolve).catch(reject);
      script.onerror = () => loadScriptSequence(rest, globalName).then(resolve).catch(reject);
      document.head.appendChild(script);
    });
  }

  function renderTopologySvgFallback(container, model, inspector, shell) {
    const nodes = model.elements.filter((item) => !item.data.source).map((item) => item.data);
    const edges = model.elements.filter((item) => item.data.source).map((item) => item.data);
    if (!nodes.length) {
      container.innerHTML = '<div class="empty-state">Sem relações suficientes para desenhar o grafo.</div>';
      return;
    }
    const width = 920;
    const height = 420;
    const center = { x: 460, y: 210 };
    const root = nodes.find((node) => node.type === 'root') || nodes[0];
    const outer = nodes.filter((node) => node.id !== root.id);
    const positions = new Map([[root.id, center]]);
    outer.forEach((node, index) => {
      const angle = (Math.PI * 2 * index) / Math.max(1, outer.length) - Math.PI / 2;
      const radius = node.type === 'field' ? 142 : 168;
      positions.set(node.id, {
        x: center.x + Math.cos(angle) * radius,
        y: center.y + Math.sin(angle) * radius
      });
    });
    const edgeMarkup = edges.map((edge) => {
      const source = positions.get(edge.source);
      const target = positions.get(edge.target);
      if (!source || !target) return '';
      return `<line x1="${source.x}" y1="${source.y}" x2="${target.x}" y2="${target.y}" />`;
    }).join('');
    const nodeMarkup = nodes.map((node) => {
      const position = positions.get(node.id);
      const radius = node.type === 'root' ? 38 : Math.max(16, Math.min(26, 12 + (node.weight || 2) * 2));
      return `
        <g class="svg-node ${escapeAttr(node.type || 'concept')}" data-node-id="${escapeAttr(node.id)}" data-node-priority="${node.isPriority ? 'true' : 'false'}" data-node-journey="${node.isJourney ? 'true' : 'false'}" tabindex="0">
          <circle cx="${position.x}" cy="${position.y}" r="${radius}"></circle>
          <text x="${position.x}" y="${position.y + radius + 17}">${escapeHtml(shortGraphLabel(node.label))}</text>
        </g>
      `;
    }).join('');
    container.innerHTML = `
      <svg class="semantic-graph-svg" viewBox="0 0 ${width} ${height}" role="img" aria-label="Mapa visual de relações do domínio">
        <g class="svg-edges">${edgeMarkup}</g>
        <g class="svg-nodes">${nodeMarkup}</g>
      </svg>
    `;
    for (const element of container.querySelectorAll('.svg-node')) {
      element.addEventListener('click', () => {
        const node = nodes.find((item) => item.id === element.getAttribute('data-node-id'));
        if (!node) return;
        updateTopologyInspector(inspector, node);
      });
    }
    bindTopologySvgControls(shell, container, model, inspector);
  }

  function bindTopologySvgControls(shell, container, model, inspector) {
    if (!shell || !container) return;
    let currentFilter = 'all';
    let currentMode = 'clean';
    const runFilter = (filter) => {
      currentFilter = filter || 'all';
      syncTopologyFilterState(shell, filter);
      applyTopologySvgMode(shell, container, currentMode);
      for (const element of container.querySelectorAll('.svg-node')) {
        const type = Array.from(element.classList).find((item) => ['root', 'concept', 'field', 'surface', 'action', 'stats', 'capability', 'journey'].includes(item));
        const isJourney = element.getAttribute('data-node-journey') === 'true';
        element.classList.toggle('graph-hidden', filter !== 'all' && type !== 'root' && type !== filter && !(filter === 'journey' && isJourney));
      }
      updateTopologyInspector(inspector, topologyFilterSummary(filter, model));
    };
    const runMode = (mode) => {
      currentMode = mode || 'clean';
      syncTopologyModeState(shell, currentMode);
      applyTopologySvgMode(shell, container, currentMode);
      runFilter(currentFilter);
      updateTopologyInspector(inspector, topologyModeSummary(currentMode, model));
    };
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-filter], [data-graph-route-filter]');
      if (!button || !shell.contains(button)) return;
      const filter = button.getAttribute('data-graph-filter') || button.getAttribute('data-graph-route-filter') || 'all';
      runFilter(filter);
    });
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-mode]');
      if (!button || !shell.contains(button)) return;
      runMode(button.getAttribute('data-graph-mode') || 'clean');
    });
    shell.addEventListener('click', (event) => {
      const button = event.target.closest('[data-graph-resource-key]');
      if (!button || !shell.contains(button)) return;
      const resourceKey = button.getAttribute('data-graph-resource-key');
      const linkedResource = state.resources.find((resource) => resource.resourceKey === resourceKey || resource.key === resourceKey);
      if (linkedResource) selectResource(linkedResource.key);
    });
    for (const button of shell.querySelectorAll('[data-graph-route-filter]')) {
      button.addEventListener('click', () => {
        runFilter(button.getAttribute('data-graph-route-filter') || 'all');
      });
    }
    for (const button of els.domainTopology.querySelectorAll('[data-graph-edge-id]')) {
      button.addEventListener('click', () => {
        const data = (model?.elements || []).find((item) => item.data.id === button.getAttribute('data-graph-edge-id'))?.data;
        if (data) updateTopologyInspector(inspector, data);
      });
    }
    bindTopologySvgSearchControl(shell, container, model, inspector);
    bindTopologyFullscreenControl(shell);
    runMode(currentMode);
  }

  function applyTopologySvgMode(shell, container, mode) {
    for (const element of container.querySelectorAll('.svg-node')) {
      const isField = element.classList.contains('field');
      const isPriority = element.getAttribute('data-node-priority') === 'true';
      element.classList.toggle('graph-mode-hidden', mode === 'clean' && isField && !isPriority);
      element.classList.toggle('graph-label-muted', mode === 'clean' && !element.classList.contains('root') && !isPriority);
    }
    container.classList.toggle('is-clean-mode', mode === 'clean');
    const hiddenCount = container.querySelectorAll('.svg-node.graph-mode-hidden').length;
    shell?.querySelector('[data-graph-mode="clean"]')?.setAttribute('aria-label', hiddenCount ? `Modo limpo, ${hiddenCount} campo(s) secundário(s) oculto(s)` : 'Modo limpo');
  }

  function bindTopologySvgSearchControl(shell, container, model, inspector) {
    const input = shell?.querySelector('[data-graph-search]');
    if (!input) return;
    const nodes = model.elements.filter((item) => !item.data.source).map((item) => item.data);
    const search = () => {
      const query = normalizeSearch(input.value);
      for (const element of container.querySelectorAll('.svg-node')) {
        element.classList.remove('graph-search-match');
      }
      if (!query) {
        updateTopologySearchStatus(shell, 'Digite para localizar campos, UI, workflow ou analytics.');
        updateTopologyInspector(inspector, topologyModeSummary(activeTopologyMode(shell), model));
        return;
      }
      const matches = nodes.filter((node) => topologySearchText(node).includes(query));
      if (!matches.length) {
        updateTopologySearchStatus(shell, `Nenhum resultado para "${input.value}".`, 'empty');
        updateTopologyInspector(inspector, {
          type: 'relation',
          label: 'Nada encontrado',
          detail: `Nenhum nó corresponde a "${input.value}".`,
          evidence: [{ label: 'Busca', detail: 'Tente parte do nome canônico, campo, surface, action ou tipo semântico.' }]
        });
        return;
      }
      for (const match of matches) {
        const element = findSvgNodeElement(container, match.id);
        element?.classList.remove('graph-hidden', 'graph-mode-hidden');
        element?.classList.add('graph-search-match');
      }
      updateTopologySearchStatus(shell, `${matches.length} resultado(s) no fallback visual.`, 'found');
      updateTopologyInspector(inspector, matches[0]);
    };
    input.addEventListener('input', debounce(search, 180));
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        input.value = '';
        search();
      }
    });
  }

  function findSvgNodeElement(container, id) {
    return Array.from(container.querySelectorAll('.svg-node'))
      .find((element) => element.getAttribute('data-node-id') === String(id));
  }

  function shortGraphLabel(label) {
    const text = readableText(label || '');
    return text.length > 24 ? `${text.slice(0, 22)}...` : text;
  }

  function buildTopologyGraphModel(resource, graph, root) {
    const graphNodes = Array.isArray(graph.nodes) ? graph.nodes : [];
    const graphEdges = Array.isArray(graph.edges) ? graph.edges : [];
    const rootKey = root?.nodeKey || resource.resourceKey || resource.key;
    const nodeIndex = new Map();
    const edgeIndex = new Map();
    const addNode = (id, patch) => {
      if (!id) return;
      const current = nodeIndex.get(id) || {};
      const nextType = current.type === 'root'
        ? 'root'
        : current.type === 'journey' && patch.type === 'concept'
          ? 'journey'
          : patch.type || current.type || 'concept';
      nodeIndex.set(id, {
        id,
        label: patch.label || current.label || id,
        detail: patch.detail || current.detail || '',
        type: nextType,
        resourceKey: patch.resourceKey || current.resourceKey || null,
        confidence: patch.confidence || current.confidence || null,
        color: patch.color || current.color || topologyNodeTheme(nextType).color,
        borderColor: patch.borderColor || current.borderColor || topologyNodeTheme(nextType).border,
        glowColor: patch.glowColor || current.glowColor || topologyNodeTheme(nextType).glow,
        isPriority: Boolean(patch.isPriority || current.isPriority),
        isJourney: Boolean(patch.isJourney || current.isJourney),
        journeyOrder: patch.journeyOrder ?? current.journeyOrder ?? null,
        journeyRole: patch.journeyRole || current.journeyRole || null,
        weight: Math.max(current.weight || 1, patch.weight || 1)
      });
    };
    const addEdge = (source, target, patch = {}) => {
      if (!source || !target || source === target) return;
      const id = patch.id || `${source}->${target}:${patch.type || 'relation'}`;
      if (edgeIndex.has(id)) return;
      edgeIndex.set(id, {
        id,
        source,
        target,
        label: patch.label || relationTypeLabel(patch.type),
        type: patch.type || 'relation',
        confidence: patch.confidence || null,
        detail: patch.detail || null,
        evidenceKeys: patch.evidenceKeys || [],
        evidence: patch.evidence || [],
        edgeColor: patch.edgeColor || topologyEdgeColor(patch.type),
        edgeStyle: topologyEdgeStyle(patch.type),
        isJourney: Boolean(patch.isJourney),
        sourceLabel: nodeIndex.get(source)?.label,
        targetLabel: nodeIndex.get(target)?.label
      });
    };

    addNode(rootKey, {
      label: normalizePortugueseLabel(root?.businessGlossary?.preferredTerm || root?.label || resource.label),
      detail: readableText(root?.description || root?.businessGlossary?.description || resource.description),
      type: 'root',
      resourceKey: resource.resourceKey,
      confidence: root?.confidence || graph?.governance?.confidence,
      isJourney: false,
      weight: 10
    });

    const journey = domainJourneyForResource(resource);
    if (journey?.steps?.length) {
      for (const step of journey.steps) {
        const id = journeyNodeId(step.resource);
        addNode(id, {
          label: step.resource.label,
          detail: step.detail,
          type: step.resource.key === resource.key ? 'root' : 'journey',
          resourceKey: step.resource.resourceKey || step.resource.key,
          isJourney: true,
          isPriority: true,
          journeyOrder: step.order,
          journeyRole: step.role,
          weight: step.resource.key === resource.key ? 10 : 7
        });
      }
      for (const edge of journey.edges) {
        addEdge(journeyNodeId(edge.from), journeyNodeId(edge.to), {
          id: `journey:${journeyNodeId(edge.from)}->${journeyNodeId(edge.to)}`,
          type: 'business_journey',
          label: 'jornada',
          detail: edge.detail,
          isJourney: true,
          evidence: edge.evidence
        });
      }
    }

    for (const node of graphNodes) {
      const nodeKey = node.nodeKey;
      if (!nodeKey) continue;
      addNode(nodeKey, {
        label: normalizePortugueseLabel(node.businessGlossary?.preferredTerm || node.label || nodeKey),
        detail: readableText(node.description || node.businessGlossary?.description || topologyNodeDetail(node)),
        type: node.nodeType || 'concept',
        resourceKey: node.metadata?.resourceKey || null,
        confidence: node.confidence,
        weight: nodeKey === rootKey ? 10 : topologyNodeWeight(node.nodeType)
      });
    }

    const directEdges = graphEdges
      .filter((edge) => edge.sourceNodeKey === rootKey || edge.targetNodeKey === rootKey)
      .slice(0, 22);
    const evidenceByKey = new Map((Array.isArray(graph.evidence) ? graph.evidence : []).map((item) => [item.evidenceKey, item]));
    for (const edge of directEdges) {
      const edgeEvidence = (edge.evidenceKeys || [])
        .map((key) => evidenceByKey.get(key))
        .filter(Boolean)
        .slice(0, 3);
      addEdge(edge.sourceNodeKey, edge.targetNodeKey, {
        id: edge.edgeKey,
        label: readableText(edge.label || relationTypeLabel(edge.edgeType)),
        type: edge.edgeType,
        confidence: edge.confidence,
        evidenceKeys: edge.evidenceKeys || [],
        evidence: edgeEvidence.map((item) => ({
          label: evidenceTypeLabel(item.evidenceType),
          detail: readableText(item.summary || evidenceLocation(item) || item.evidenceKey)
        })),
        detail: edgeEvidence.length
          ? `Relação publicada pelo grafo de domínio com ${edgeEvidence.length} evidência(s) de grounding.`
          : 'Relação publicada pelo grafo de domínio.'
      });
    }

    for (const surface of (resource.surfaces || []).slice(0, 8)) {
      const id = `surface:${surface.id || surface.title || surface.path || surface.schemaUrl}`;
      addNode(id, {
        label: normalizePortugueseLabel(surface.title || surface.id || surface.kind || 'Surface'),
        detail: readableText(surface.description || surface.path || surface.schemaUrl || 'Experiência metadata-driven publicada.'),
        type: surface.kind === 'stats' ? 'stats' : 'surface',
        weight: 4
      });
      addEdge(rootKey, id, {
        type: 'has_surface',
        label: 'materializa UI',
        detail: `Surface publicada pelo starter para ${surface.path || surface.schemaUrl || surface.id || 'este recurso'}.`,
        evidence: [{ label: 'surface', detail: readableText(surface.description || surface.path || surface.schemaUrl || 'Experiência metadata-driven publicada.') }]
      });
    }

    for (const action of (resource.actions || []).slice(0, 8)) {
      const id = `action:${action.id || action.title || action.path || action.operationId}`;
      addNode(id, {
        label: normalizePortugueseLabel(action.title || action.id || action.intent || 'Action'),
        detail: readableText(action.description || action.path || action.operationId || 'Comando de negócio acionável.'),
        type: 'action',
        weight: 4
      });
      addEdge(rootKey, id, {
        type: 'has_action',
        label: 'aciona workflow',
        detail: `Action publicada para ${action.path || action.operationId || action.id || 'este recurso'}.`,
        evidence: [{ label: 'action', detail: readableText(action.description || action.path || action.operationId || 'Comando de negócio acionável.') }]
      });
    }

    for (const field of (resource.fieldList || []).slice(0, 10)) {
      const id = `field:${field.name}`;
      addNode(id, {
        label: normalizePortugueseLabel(field.name),
        detail: `${field.type || 'campo'} · ${field.required ? 'obrigatório' : 'opcional'}`,
        type: 'field',
        isPriority: Boolean(field.required),
        weight: field.required ? 4 : 3
      });
      addEdge(rootKey, id, {
        type: 'has_field',
        label: field.required ? 'campo obrigatório' : 'campo materializável',
        detail: `${field.name} vem do contrato de schema como ${field.type || 'campo'} ${field.required ? 'obrigatório' : 'opcional'}.`,
        evidence: [{ label: 'schema', detail: `${field.type || 'campo'} · ${field.required ? 'obrigatório' : 'opcional'}` }]
      });
    }

    return {
      elements: [
        ...Array.from(nodeIndex.values()).map((data) => ({ data })),
        ...Array.from(edgeIndex.values()).map((data) => ({ data }))
      ]
    };
  }

  function domainJourneyForResource(resource) {
    const scoped = state.resources.filter((candidate) =>
      candidate.group === resource.group && (candidate.resourceKey || candidate.key) && candidate.resourcePath
    );
    if (scoped.length < 3) return null;

    const edges = inferResourceJourneyEdges(scoped);
    if (!edges.length) return null;

    const chains = longestResourceChains(scoped, edges)
      .filter((chain) => chain.some((item) => item.key === resource.key))
      .sort((a, b) => b.length - a.length);
    const chain = chains[0];
    if (!chain || chain.length < 3) return null;

    const edgeKey = (from, to) => `${from.key}->${to.key}`;
    const edgeIndex = new Map(edges.map((edge) => [edgeKey(edge.from, edge.to), edge]));
    const selectedIndex = chain.findIndex((item) => item.key === resource.key);
    const chainEdges = [];
    for (let index = 0; index < chain.length - 1; index += 1) {
      const from = chain[index];
      const to = chain[index + 1];
      const edge = edgeIndex.get(edgeKey(from, to));
      chainEdges.push({
        from,
        to,
        detail: edge?.detail || `${from.label} alimenta ou contextualiza ${to.label} nesta cadeia de negócio.`,
        evidence: edge?.evidence || [{ label: 'inferência', detail: 'Relação derivada de campos, lookup, option source ou path publicado.' }]
      });
    }
    return {
      steps: chain.map((item, index) => ({
        resource: item,
        order: index + 1,
        role: index === selectedIndex ? 'recurso selecionado' : index < selectedIndex ? 'contexto anterior' : 'etapa posterior',
        detail: `${index + 1}/${chain.length} na jornada inferida de ${labelForArea(resource.group)}. ${item.description || 'Recurso de domínio publicado pelo host.'}`
      })),
      edges: chainEdges
    };
  }

  function inferResourceJourneyEdges(resources) {
    const edges = [];
    const byKey = new Map(resources.map((resource) => [resource.key, resource]));
    for (const owner of resources) {
      const filtered = filteredSchemaFields(owner);
      const fields = filtered.length ? filtered : owner.fieldList || [];
      for (const target of resources) {
        if (target.key === owner.key) continue;
        const evidence = [];
        for (const field of fields) {
          const hit = fieldReferencesResource(field, target);
          if (hit) evidence.push(hit);
        }
        const endpointHit = endpointReferencesResource(owner, target);
        if (endpointHit) evidence.push(endpointHit);
        if (!evidence.length) continue;
        edges.push({
          from: byKey.get(target.key),
          to: byKey.get(owner.key),
          score: evidence.length,
          detail: `${target.label} aparece como referência, filtro ou fonte de opção em ${owner.label}.`,
          evidence: evidence.slice(0, 3)
        });
      }
    }
    return edges.sort((a, b) => b.score - a.score);
  }

  function fieldReferencesResource(field, target) {
    const fieldName = normalizeSearch(field?.name).replace(/[^a-z0-9]/g, '');
    const targetAliases = resourceReferenceAliases(target);
    if (fieldName) {
      for (const alias of targetAliases) {
        if (!alias || alias.length < 3) continue;
        if (fieldName === `${alias}id` || fieldName === `${alias}key` || fieldName === `${alias}codigo` || fieldName === `${alias}code`) {
          return { label: 'campo de referência', detail: `${field.name} referencia ${target.label}.` };
        }
      }
    }
    const optionText = normalizeSearch(JSON.stringify(field?.xui?.optionSource || field?.xui?.endpoint || ''));
    if (optionText) {
      for (const alias of targetAliases) {
        if (alias.length >= 4 && optionText.includes(alias)) {
          return { label: 'option source', detail: `${field.name} usa fonte de opção compatível com ${target.label}.` };
        }
      }
    }
    return null;
  }

  function endpointReferencesResource(owner, target) {
    const text = normalizeSearch([
      owner.resourcePath,
      ...(owner.endpoints || []).map((endpoint) => endpoint.path),
      ...(owner.surfaces || []).map((surface) => [surface.path, surface.description, surface.title].filter(Boolean).join(' ')),
      ...(owner.actions || []).map((action) => [action.path, action.description, action.title].filter(Boolean).join(' '))
    ].filter(Boolean).join(' '));
    const targetPath = normalizeSearch(target.resourcePath || '');
    if (targetPath && text.includes(targetPath)) {
      return { label: 'path publicado', detail: `${owner.label} publica caminho que referencia ${target.label}.` };
    }
    return null;
  }

  function resourceReferenceAliases(resource) {
    const segments = [
      resource.resourceKey?.split('.').pop(),
      resource.resourcePath?.split('/').filter(Boolean).pop(),
      resource.key?.split('.').pop(),
      resource.label
    ].filter(Boolean);
    const aliases = new Set();
    for (const segment of segments) {
      const normalized = normalizeSearch(segment).replace(/[^a-z0-9]/g, '');
      if (!normalized) continue;
      aliases.add(normalized);
      aliases.add(singularReferenceAlias(normalized));
    }
    return Array.from(aliases).filter(Boolean);
  }

  function singularReferenceAlias(value) {
    if (value.endsWith('ies')) return `${value.slice(0, -3)}y`;
    if (value.endsWith('oes')) return value.slice(0, -3);
    if (value.endsWith('s') && value.length > 3) return value.slice(0, -1);
    return value;
  }

  function longestResourceChains(resources, edges) {
    const outgoing = new Map();
    for (const edge of edges) {
      const list = outgoing.get(edge.from.key) || [];
      list.push(edge.to);
      outgoing.set(edge.from.key, list);
    }
    const chains = [];
    const maxDepth = Math.min(resources.length, 8);
    const visit = (resource, path) => {
      if (path.length >= maxDepth) {
        chains.push(path);
        return;
      }
      const next = (outgoing.get(resource.key) || []).filter((candidate) => !path.some((item) => item.key === candidate.key));
      if (!next.length) {
        chains.push(path);
        return;
      }
      for (const candidate of next) visit(candidate, [...path, candidate]);
    };
    for (const resource of resources) visit(resource, [resource]);
    return chains.sort((a, b) => b.length - a.length);
  }

  function journeyNodeId(resource) {
    return resource.resourceKey || resource.key;
  }

  function topologyNodeWeight(type) {
    return {
      concept: 6,
      capability: 5,
      surface: 4,
      action: 4,
      stats: 4,
      field: 3,
      journey: 7
    }[type] || 2;
  }

  function topologyGraphStyle() {
    return [
      {
        selector: 'node',
        style: {
          'background-color': 'data(color)',
          'border-color': 'data(borderColor)',
          'border-width': 2,
          color: '#edf3fb',
          'font-family': 'Inter, sans-serif',
          'font-size': 10,
          'font-weight': 700,
          height: 'mapData(weight, 1, 10, 28, 76)',
          label: 'data(label)',
          'min-zoomed-font-size': 7,
          'overlay-opacity': 0,
          'text-background-color': 'rgba(7, 9, 13, .68)',
          'text-background-opacity': .92,
          'text-background-padding': 3,
          'text-outline-color': 'rgba(2, 6, 10, .88)',
          'text-outline-width': 2,
          'text-margin-y': 8,
          'text-max-width': 90,
          'text-valign': 'bottom',
          'text-wrap': 'wrap',
          width: 'mapData(weight, 1, 10, 28, 76)'
        }
      },
      { selector: 'node[type = "root"]', style: { 'background-color': '#3cc8ff', 'border-color': '#aeeaff', 'border-width': 4, height: 88, width: 88 } },
      { selector: 'node[type = "journey"]', style: { 'background-color': '#28e0c4', 'border-color': '#d6fff6', 'border-width': 3 } },
      {
        selector: 'edge',
        style: {
          'curve-style': 'bezier',
          'line-color': 'data(edgeColor)',
          'line-style': 'data(edgeStyle)',
          'target-arrow-color': 'data(edgeColor)',
          'target-arrow-shape': 'triangle',
          opacity: .62,
          width: 1.5,
          label: 'data(label)',
          color: '#9eacbd',
          'font-size': 8,
          'text-background-color': 'rgba(7, 9, 13, .72)',
          'text-background-opacity': .9,
          'text-background-padding': 2,
          'text-rotation': 'autorotate'
        }
      },
      { selector: 'node:selected', style: { 'border-color': '#ffffff', 'border-width': 4 } },
      { selector: 'edge:selected', style: { 'line-color': '#73efb5', 'target-arrow-color': '#73efb5', width: 2.6 } },
      { selector: 'node[isJourney]', style: { 'border-color': '#d6fff6', 'border-width': 4 } },
      { selector: 'edge[isJourney]', style: { 'line-color': '#73efb5', 'target-arrow-color': '#73efb5', opacity: .95, width: 3, label: 'data(label)' } },
      { selector: 'node.graph-focus', style: { 'border-color': '#ffffff', 'border-width': 4, opacity: 1 } },
      { selector: 'edge.graph-focus', style: { 'line-color': '#73efb5', 'target-arrow-color': '#73efb5', opacity: 1, width: 3.2 } },
      { selector: 'node.graph-search-match', style: { 'border-color': '#ffd166', 'border-width': 5, 'background-color': '#ffd166' } },
      { selector: '.graph-label-muted', style: { label: '' } },
      { selector: '.graph-muted', style: { opacity: .18 } },
      { selector: '.graph-mode-hidden', style: { display: 'none' } },
      { selector: '.graph-hidden', style: { display: 'none' } }
    ];
  }

  function topologyNodeTheme(type) {
    const themes = {
      root: { color: '#3cc8ff', border: '#aeeaff', glow: '#62d8ff' },
      concept: { color: '#5267ff', border: '#b7a5ff', glow: '#7a68ff' },
      field: { color: '#4bbdf4', border: '#bdefff', glow: '#62d8ff' },
      surface: { color: '#35d4ad', border: '#a8ffe0', glow: '#73efb5' },
      action: { color: '#9b7cff', border: '#e2d7ff', glow: '#b7a5ff' },
      stats: { color: '#e2b84d', border: '#ffe7a8', glow: '#ffd166' },
      journey: { color: '#28e0c4', border: '#d6fff6', glow: '#73efb5' },
      capability: { color: '#e85c86', border: '#ffc4d2', glow: '#ff6d91' }
    };
    return themes[type] || themes.concept;
  }

  function topologyEdgeColor(type) {
    if (type === 'has_surface') return 'rgba(115, 239, 181, .72)';
    if (type === 'has_action') return 'rgba(183, 165, 255, .76)';
    if (type === 'has_stats') return 'rgba(255, 209, 102, .76)';
    if (type === 'has_field') return 'rgba(98, 216, 255, .68)';
    if (type === 'has_capability') return 'rgba(255, 109, 145, .68)';
    if (type === 'business_journey') return 'rgba(115, 239, 181, .92)';
    return 'rgba(126, 211, 255, .48)';
  }

  function topologyEdgeStyle(type) {
    return ['has_surface', 'has_action', 'has_stats', 'has_field', 'has_capability', 'business_journey'].includes(type) ? 'solid' : 'dashed';
  }

  function updateTopologyInspector(inspector, data) {
    if (!inspector) return;
    const label = normalizePortugueseLabel(data.label || data.id || 'Nó semântico');
    const isRelation = Boolean(data.source && data.target) || data.type === 'relation';
    const route = isRelation ? topologyRouteLabel(data) : '';
    const detail = readableText(data.detail || route || relationTypeLabel(data.type) || 'Evidência do domínio.');
    const resourceHint = data.resourceKey ? `<small>${escapeHtml(data.resourceKey)}</small>` : '';
    const confidence = data.confidence ? `<small>${escapeHtml(confidenceText(data.confidence))}</small>` : '';
    const linkedResource = resourceFromTopologyNode(data);
    const navigation = linkedResource && linkedResource.key !== state.selectedKey
      ? `<button type="button" class="semantic-graph-open-resource" data-graph-resource-key="${escapeAttr(linkedResource.resourceKey || linkedResource.key)}">
          Abrir ${escapeHtml(linkedResource.label || linkedResource.resourceKey || linkedResource.key)}
        </button>`
      : '';
    const evidence = Array.isArray(data.evidence) && data.evidence.length
      ? `<div class="semantic-graph-evidence">${data.evidence.map((item) => `
          <article>
            <strong>${escapeHtml(item.label || 'Evidência')}</strong>
            <span>${escapeHtml(readableText(item.detail || 'Grounding publicado pelo host.'))}</span>
          </article>
        `).join('')}</div>`
      : '';
    inspector.innerHTML = `
      <span>${escapeHtml(nodeTypeLabel(data.type || 'concept'))}</span>
      <strong>${escapeHtml(label)}</strong>
      ${route ? `<small>${escapeHtml(route)}</small>` : ''}
      <p>${escapeHtml(detail)}</p>
      ${resourceHint}
      ${confidence}
      ${navigation}
      ${evidence}
    `;
  }

  function topologyRouteLabel(data) {
    const source = normalizePortugueseLabel(data.sourceLabel || data.source || '');
    const target = normalizePortugueseLabel(data.targetLabel || data.target || '');
    return [source, target].filter(Boolean).join(' -> ');
  }

  function resourceFromTopologyNode(data) {
    const resourceKey = data.resourceKey;
    if (!resourceKey) return null;
    return state.resources.find((resource) => resource.resourceKey === resourceKey || resource.key === resourceKey) || null;
  }

  function renderRootNode(root, resource, graph) {
    const confidence = root?.confidence || graph?.governance?.confidence;
    const label = normalizePortugueseLabel(root?.businessGlossary?.preferredTerm || root?.label || resource.label);
    const description = readableText(root?.description || root?.businessGlossary?.description || resource.description);
    return `
      <article class="topology-root">
        <span>${escapeHtml(nodeTypeLabel(root?.nodeType || 'concept'))}</span>
        <strong>${escapeHtml(label)}</strong>
        <p>${escapeHtml(description || 'Conceito central do recurso selecionado.')}</p>
        <small>${escapeHtml(resource.resourceKey || resource.resourcePath || 'recurso')} · ${escapeHtml(confidenceText(confidence))}</small>
      </article>
    `;
  }

  function groupNodesByType(nodes) {
    const order = ['field', 'surface', 'stats', 'action', 'capability', 'concept'];
    const byType = new Map();
    for (const node of nodes) {
      const type = node.nodeType || 'concept';
      const list = byType.get(type) || [];
      list.push(node);
      byType.set(type, list);
    }
    return Array.from(byType.entries())
      .sort((a, b) => (order.indexOf(a[0]) === -1 ? 99 : order.indexOf(a[0])) - (order.indexOf(b[0]) === -1 ? 99 : order.indexOf(b[0])))
      .map(([type, list]) => ({ type, list }));
  }

  function renderNodeGroup(group) {
    return `
      <section class="topology-group">
        <h3>${escapeHtml(nodeTypeLabel(group.type))}</h3>
        <div class="topology-node-list">
          ${group.list.map(renderTopologyNode).join('')}
        </div>
      </section>
    `;
  }

  function renderTopologyNode(node) {
    const label = normalizePortugueseLabel(node.businessGlossary?.preferredTerm || node.label || node.nodeKey);
    const detail = readableText(node.description || node.businessGlossary?.description || topologyNodeDetail(node));
    return `
      <article class="topology-node ${escapeAttr(node.nodeType || 'concept')}">
        <strong>${escapeHtml(label)}</strong>
        <span>${escapeHtml(detail)}</span>
        <small>${escapeHtml(confidenceText(node.confidence))}</small>
      </article>
    `;
  }

  function topologyNodeDetail(node) {
    if (node.metadata?.fieldName) return `${node.metadata.fieldName} · ${node.metadata.type || 'campo'} ${node.metadata.required ? 'obrigatório' : 'opcional'}`;
    if (node.metadata?.kind || node.metadata?.scope) return `${node.metadata.kind || 'surface'} · ${node.metadata.scope || 'escopo'}`;
    if (node.metadata?.path) return `${node.metadata.method || '--'} ${node.metadata.path}`;
    return node.source ? `Fonte: ${node.source}` : 'Nó semântico publicado pelo domínio.';
  }

  function renderRelation(edge, nodes) {
    const target = nodes.find((node) => node.nodeKey === edge.targetNodeKey);
    const source = nodes.find((node) => node.nodeKey === edge.sourceNodeKey);
    const rootKey = selectedResource()?.resourceKey;
    const related = edge.sourceNodeKey === rootKey ? target : edge.targetNodeKey === rootKey ? source : target || source;
    const relatedLabel = normalizePortugueseLabel(related?.businessGlossary?.preferredTerm || related?.label || edge.targetNodeKey || edge.edgeKey);
    const edgeId = edge.edgeKey || `${edge.sourceNodeKey}->${edge.targetNodeKey}:${edge.edgeType || 'relation'}`;
    return `
      <button type="button" class="relation-item relation-item-button" data-graph-edge-id="${escapeAttr(edgeId)}">
        <strong>${escapeHtml(readableText(edge.label || relationTypeLabel(edge.edgeType)))}</strong>
        <span>${escapeHtml(relatedLabel)} · ${escapeHtml(relationTypeLabel(edge.edgeType))}</span>
        <small>${escapeHtml(confidenceText(edge.confidence))}</small>
      </button>
    `;
  }

  function renderEvidence(item) {
    const ref = item.sourceRef || {};
    const location = evidenceLocation(item);
    return `
      <article class="relation-item">
        <strong>${escapeHtml(evidenceTypeLabel(item.evidenceType))}</strong>
        <span>${escapeHtml(readableText(item.summary || location || item.evidenceKey))}</span>
        <small>${escapeHtml([location, confidenceText(item.confidence)].filter(Boolean).join(' · '))}</small>
      </article>
    `;
  }

  function evidenceLocation(item) {
    const ref = item?.sourceRef || {};
    return [ref.method, ref.path || ref.annotation || ref.capabilityKey].filter(Boolean).join(' ');
  }

  function nodeTypeLabel(type) {
    const labels = {
      root: 'Recurso central',
      concept: 'Conceito central',
      field: 'Campos de contrato',
      surface: 'Experiências de UI',
      stats: 'Capacidades analíticas',
      journey: 'Jornada de negócio',
      action: 'Ações de workflow',
      capability: 'Capabilities',
      relation: 'Relações'
    };
    return labels[type] || titleize(type || 'nó');
  }

  function relationTypeLabel(type) {
    const labels = {
      has_field: 'possui campo',
      has_surface: 'possui surface',
      has_stats: 'possui analytics',
      has_action: 'possui ação',
      has_capability: 'possui capability',
      business_journey: 'encadeia jornada',
      relation: 'relação semântica'
    };
    return labels[type] || titleize(type || 'relação');
  }

  function evidenceTypeLabel(type) {
    const labels = {
      annotation: 'Anotação Java',
      dto_schema: 'Schema DTO',
      openapi_stats: 'OpenAPI analytics',
      openapi_operation: 'Operação OpenAPI'
    };
    return labels[type] || titleize(type || 'evidência');
  }

  function confidenceText(confidence) {
    if (confidence === null || confidence === undefined || confidence === '') return 'confiança não publicada';
    const value = Number(confidence);
    return Number.isFinite(value) ? `confiança ${Math.round(value * 100)}%` : `confiança ${confidence}`;
  }

  function areaTheme(areaOrKey) {
    const areaKey = typeof areaOrKey === 'string' ? areaOrKey : areaOrKey?.key;
    const theme = areaThemes[areaKey] || areaThemes.default;
    const hostIcon = typeof areaOrKey === 'string' ? null : normalizeIconName(areaOrKey?.icon);
    return hostIcon ? { ...theme, icon: hostIcon } : theme;
  }

  function areaStyleVars(areaOrKey) {
    const theme = areaTheme(areaOrKey);
    return `--area-a:${escapeAttr(theme.a)};--area-b:${escapeAttr(theme.b)};`;
  }

  function areaIcon(areaOrKey) {
    const icon = areaTheme(areaOrKey).icon;
    const common = 'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';
    const paths = {
      people: '<path d="M16 19v-1.2c0-1.6-1.3-2.8-2.8-2.8H7.8C6.3 15 5 16.2 5 17.8V19"/><circle cx="10.5" cy="8" r="3"/><path d="M19 19v-1c0-1.4-.8-2.5-2-3"/><path d="M15.5 5.3a3 3 0 0 1 0 5.4"/>',
      route: '<circle cx="6" cy="18" r="2"/><circle cx="18" cy="6" r="2"/><path d="M8 18h3.5A3.5 3.5 0 0 0 15 14.5v-5"/><path d="M15 9.5A3.5 3.5 0 0 0 11.5 6H8"/>',
      chain: '<path d="M9.5 7.5h-2A4.5 4.5 0 0 0 3 12a4.5 4.5 0 0 0 4.5 4.5h2"/><path d="M14.5 7.5h2A4.5 4.5 0 0 1 21 12a4.5 4.5 0 0 1-4.5 4.5h-2"/><path d="M8.5 12h7"/>',
      box: '<path d="M12 3 4.5 7.2 12 11.4l7.5-4.2L12 3Z"/><path d="M4.5 7.2v8.4L12 21l7.5-5.4V7.2"/><path d="M12 11.4V21"/>',
      radar: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/><path d="M12 12 17.5 6.5"/><path d="M4 12h2"/><path d="M18 12h2"/>',
      spark: '<path d="M12 3v5"/><path d="M12 16v5"/><path d="M3 12h5"/><path d="M16 12h5"/><path d="m6.5 6.5 3 3"/><path d="m14.5 14.5 3 3"/><path d="m17.5 6.5-3 3"/><path d="m9.5 14.5-3 3"/>',
      node: '<circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="m8.2 10.9 7.6-3.8"/><path d="m8.2 13.1 7.6 3.8"/>'
    };
    return `<svg ${common} aria-hidden="true">${paths[icon] || paths.node}</svg>`;
  }

  function normalizeIconName(icon) {
    const value = String(icon || '').trim().toLowerCase().replace(/[-\s]+/g, '_');
    if (!value) return null;
    const aliases = {
      account: 'people',
      analytics: 'radar',
      assignment_ind: 'people',
      badge: 'people',
      business: 'chain',
      category: 'box',
      corporate_fare: 'chain',
      directions_car: 'box',
      emergency: 'radar',
      event_note: 'route',
      explore: 'route',
      flag: 'route',
      fort: 'route',
      gavel: 'chain',
      group_work: 'people',
      groups: 'people',
      inventory_2: 'box',
      leaderboard: 'radar',
      paid: 'chain',
      payments: 'chain',
      receipt_long: 'chain',
      report: 'radar',
      report_problem: 'radar',
      security: 'radar',
      shield: 'radar',
      sos: 'radar',
      stacked_bar_chart: 'radar',
      timeline: 'radar',
      trending_up: 'radar',
      warning: 'radar',
      work: 'people'
    };
    return aliases[value] || value;
  }

  function firstText(...values) {
    return values.find((value) => typeof value === 'string' && value.trim())?.trim() || null;
  }

  function renderWorkflow(resource) {
    const readiness = computeResourceReadiness(resource);
    const materializations = [
      ...readiness.dimensions.map((item) => ({
        level: statusLevel(item.status),
        title: item.label,
        body: item.detail
      })),
      ...resource.surfaces.slice(0, 4).map((surface) => ({
        level: surface.availability?.allowed === false ? 'warn' : 'good',
        title: surface.title || titleize(surface.id || surface.intent || 'surface'),
        body: `${surface.kind || 'Surface'} · ${surface.scope || 'escopo'} · ${surface.method || '--'} ${surface.path || ''}`.trim()
      })),
      ...resource.actions.slice(0, 3).map((action) => ({
        level: action.availability?.allowed === false ? 'warn' : 'good',
        title: action.title || titleize(action.id || action.operationId || 'action'),
        body: `${action.method || '--'} ${action.path || ''}`.trim()
      }))
    ];
    els.workflowRail.innerHTML = materializations.map((item) => `
      <article class="workflow-step ${item.level}">
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.body)}</span>
      </article>
    `).join('');
  }

  function renderSemanticReadiness(resource) {
    const dimensions = computeResourceReadiness(resource).dimensions;
    els.semanticReadiness.innerHTML = dimensions.map((item) => `
      <article class="readiness-card ${escapeAttr(item.status)}">
        <span>${escapeHtml(statusLabel(item.status))}</span>
        <strong>${escapeHtml(item.label)}</strong>
        <p>${escapeHtml(item.impact)}</p>
        <small>${escapeHtml(item.detail)}</small>
      </article>
    `).join('');
  }

  function renderRenderability(resource) {
    const profile = resourceRenderability(resource);
    const items = [
      {
        key: 'table',
        ok: profile.canTable,
        source: profile.evidence.table,
        title: 'Tabela',
        value: `${profile.fields || resource.fieldList.length} campos`,
        detail: profile.canTable ? `Colunas podem ser derivadas do schema e surfaces de leitura. ${evidenceDetail(profile.evidence.table)}` : 'Faltam campos ou operação de leitura suficiente.'
      },
      {
        key: 'form',
        ok: profile.canForm,
        source: profile.evidence.form,
        title: 'Formulário',
        value: `${profile.editableFields} editáveis`,
        detail: profile.canForm ? `Campos, tipos, obrigatoriedade e controles vêm de x-ui/schema. ${evidenceDetail(profile.evidence.form)}` : 'Sem surface de form ou campos editáveis confirmados.'
      },
      {
        key: 'filter',
        ok: profile.canFilter,
        source: profile.evidence.filter,
        title: 'Filtros',
        value: profile.filterOperators.size ? `${profile.filterOperators.size} operadores` : (profile.canFilter ? 'endpoint ativo' : 'sem filtro'),
        detail: profile.canFilter ? `O recurso aceita consulta filtrada. ${evidenceDetail(profile.evidence.filter)}` : 'Nenhum endpoint de filtro confirmado para o recurso.'
      },
      {
        key: 'chart',
        ok: profile.canAnalytics,
        source: profile.evidence.analytics,
        title: 'Gráficos',
        value: analyticsModeText(resource),
        detail: profile.canAnalytics ? `Stats de group-by, série temporal ou distribuição podem alimentar charts. ${evidenceDetail(profile.evidence.analytics)}` : 'Sem capability analítica publicada.'
      },
      {
        key: 'lookup',
        ok: profile.canOptions,
        source: profile.evidence.options,
        title: 'Lookups',
        value: `${profile.optionSources} fontes`,
        detail: profile.canOptions ? `Option sources e entity lookups podem alimentar selects e busca assistida. ${evidenceDetail(profile.evidence.options)}` : 'Sem fonte de opção confirmada.'
      },
      {
        key: 'workflow',
        ok: profile.canActions,
        source: profile.evidence.actions,
        title: 'Workflow',
        value: `${(resource.actions || []).length} actions`,
        detail: profile.canActions ? `Comandos de negócio publicados podem ser acionados pela UI/runtime. ${evidenceDetail(profile.evidence.actions)}` : 'Sem comando de negócio publicado para este recurso.'
      }
    ];
    els.renderabilityMatrix.innerHTML = items.map((item) => `
      <article class="renderability-card ${item.ok ? 'ready' : 'missing'} ${escapeAttr(item.source)}">
        <span>${escapeHtml(evidenceStatusLabel(item.source))}</span>
        <strong>${escapeHtml(item.title)}</strong>
        <b>${escapeHtml(item.value)}</b>
        <small>${escapeHtml(item.detail)}</small>
      </article>
    `).join('');
  }

  function renderResourceAnalytics(resource) {
    const profile = resourceRenderability(resource);
    const rings = [
      { label: 'Tabela', value: profile.canTable ? 1 : 0, source: profile.evidence.table },
      { label: 'Form', value: profile.canForm ? 1 : 0, source: profile.evidence.form },
      { label: 'Filtro', value: profile.canFilter ? 1 : 0, source: profile.evidence.filter },
      { label: 'Chart', value: profile.canAnalytics ? 1 : 0, source: profile.evidence.analytics },
      { label: 'Lookup', value: profile.canOptions ? 1 : 0, source: profile.evidence.options },
      { label: 'Action', value: profile.canActions ? 1 : 0, source: profile.evidence.actions }
    ];
    const total = profile.confirmedScore;
    const dimension = preferredStatsDimension(resource);
    if (profile.canAnalytics && dimension && resource.resourcePath) {
      els.resourceChart.classList.add('has-stats-preview');
      els.resourceChart.innerHTML = renderStatsPreview(resource, dimension);
      loadStatsPreview(resource, dimension);
    } else {
      els.resourceChart.classList.remove('has-stats-preview');
      els.resourceChart.innerHTML = `
        <div class="radial-score" style="--score:${escapeAttr(Math.round(total / rings.length * 100))}">
          <strong>${escapeHtml(total)}/${escapeHtml(rings.length)}</strong>
          <span>camadas confirmadas</span>
        </div>
        <div class="radial-legend">
          ${rings.map((item) => `<span class="${item.value ? 'on' : ''} ${escapeAttr(item.source)}">${escapeHtml(item.label)}</span>`).join('')}
        </div>
      `;
    }
    els.filterInsights.innerHTML = filterInsightItems(resource, profile).map((item) => `
      <article class="filter-insight ${escapeAttr(item.level)}">
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.body)}</span>
      </article>
    `).join('');
  }

  function preferredStatsDimension(resource) {
    const fields = filteredSchemaFields(resource);
    const schemaByName = new Map(fields.map((field) => [field.name, field]));
    const statsDimensions = (((resource.capability || {}).stats || {}).fields || [])
      .filter((field) => field && field.field && (field.groupByEligible || (field.modes || []).includes('GROUP_BY')))
      .map((field) => {
        const schemaField = schemaByName.get(field.field) || {};
        return {
          ...schemaField,
          name: field.field,
          type: schemaField.type || 'string',
          label: schemaField.xui?.label || field.label || field.field,
          optionSource: schemaField.xui?.optionSource || null,
          stats: field
        };
      });
    if (statsDimensions.length) {
      return statsDimensions.find((field) => field.optionSource?.byIdsEndpoint)
        || statsDimensions.find((field) => field.optionSource)
        || statsDimensions.find((field) => (field.stats?.metrics || []).includes('COUNT'))
        || statsDimensions[0];
    }
    const candidates = fields
      .map((field) => ({ ...field, optionSource: field.xui?.optionSource || null }))
      .filter((field) => field.name && !isEnvelopeField(field.name));
    return candidates.find((field) => field.optionSource?.byIdsEndpoint)
      || candidates.find((field) => field.optionSource)
      || candidates.find((field) => field.enumValues?.size)
      || candidates.find((field) => ['integer', 'number', 'string', 'boolean'].includes(String(field.type || '').toLowerCase()))
      || null;
  }

  function statsPreviewKey(resource, dimension) {
    return `${resource.key || resource.resourcePath}:${dimension?.name || 'stats'}`;
  }

  function renderStatsPreview(resource, dimension) {
    const cacheKey = statsPreviewKey(resource, dimension);
    const preview = state.statsPreviews.get(cacheKey);
    const title = `Distribuição por ${fieldLabel(dimension)}`;
    if (!preview || preview.status === 'loading') {
      return `
        <section class="stats-preview loading">
          <header>
            <span>Chart real do host</span>
            <strong>${escapeHtml(title)}</strong>
            <small>Consultando ${escapeHtml(resource.resourcePath || 'endpoint do recurso')}/stats/group-by.</small>
          </header>
          <div class="stats-skeleton" aria-hidden="true"><i></i><i></i><i></i></div>
        </section>
      `;
    }
    if (preview.status === 'error') {
      return `
        <section class="stats-preview warning">
          <header>
            <span>Chart indisponível</span>
            <strong>${escapeHtml(title)}</strong>
            <small>${escapeHtml(preview.message || 'O endpoint de stats não retornou buckets para visualização.')}</small>
          </header>
          <p>Fallback: o recurso continua marcado como analítico por capabilities/catálogo, mas o cockpit não conseguiu montar uma amostra operacional agora.</p>
        </section>
      `;
    }
    const buckets = preview.buckets || [];
    const max = Math.max(1, ...buckets.map((bucket) => Number(bucket.value ?? bucket.count ?? 0)));
    return `
      <section class="stats-preview">
        <header>
          <span>Chart real do host</span>
          <strong>${escapeHtml(title)}</strong>
          <small>${escapeHtml(statsHydrationSummary(preview, dimension))}</small>
        </header>
        <div class="stats-bars">
          ${buckets.map((bucket) => {
            const value = Number(bucket.value ?? bucket.count ?? 0);
            const width = Math.max(6, Math.round((value / max) * 100));
            const label = bucket.hydratedLabel || bucket.label || String(bucket.key ?? 'sem valor');
            const technical = bucket.hydratedLabel && String(bucket.hydratedLabel) !== String(bucket.key) ? `ID ${bucket.key}` : '';
            return `
              <article class="stats-bar-row">
                <div>
                  <strong>${escapeHtml(label)}</strong>
                  ${technical ? `<small>${escapeHtml(technical)}</small>` : ''}
                </div>
                <span class="stats-bar-track" aria-hidden="true"><i style="--value:${escapeAttr(width)}%"></i></span>
                <b>${escapeHtml(value)}</b>
              </article>
            `;
          }).join('')}
        </div>
      </section>
    `;
  }

  function fieldLabel(field) {
    return readableText(field?.xui?.label || field?.label || field?.stats?.label || field?.name || 'campo');
  }

  function statsHydrationSummary(preview, dimension) {
    if (preview.hydrated) return `Buckets hidratados via option source ${dimension.optionSource?.key || 'publicada'}; ID técnico preservado como contexto.`;
    if (dimension.optionSource) return 'Option source encontrada, mas o fallback por ID cru foi usado nesta amostra.';
    if (dimension.stats) return 'Dimensão declarada em capabilities.stats; labels vêm diretamente do retorno de stats.';
    return 'Dimensão sem option source; labels vêm diretamente do retorno de stats.';
  }

  async function loadStatsPreview(resource, dimension) {
    const cacheKey = statsPreviewKey(resource, dimension);
    const current = state.statsPreviews.get(cacheKey);
    if (current && current.status !== 'error') return;
    state.statsPreviews.set(cacheKey, { status: 'loading' });
    try {
      const response = await fetchJson(`${resource.resourcePath}/stats/group-by`, {
        method: 'POST',
        timeoutMs: 12000,
        body: {
          filter: {},
          field: dimension.name,
          metric: { operation: 'COUNT' },
          limit: 6,
          orderBy: 'VALUE_DESC'
        }
      });
      const data = response?.data || response;
      const buckets = await hydrateStatsBuckets(dimension, data?.buckets || []);
      state.statsPreviews.set(cacheKey, {
        status: 'ready',
        field: data?.field || dimension.name,
        buckets,
        hydrated: buckets.some((bucket) => bucket.hydratedLabel)
      });
    } catch (error) {
      state.statsPreviews.set(cacheKey, {
        status: 'error',
        message: error?.message || 'stats indisponível'
      });
    }
    if (selectedResource()?.key === resource.key) {
      els.resourceChart.innerHTML = renderStatsPreview(resource, dimension);
    }
  }

  async function hydrateStatsBuckets(dimension, buckets) {
    const source = dimension?.optionSource;
    const endpoint = source?.byIdsEndpoint;
    if (!endpoint || !buckets.length) return buckets;
    const ids = Array.from(new Set(buckets.map((bucket) => bucket.key).filter((value) => value !== null && value !== undefined && value !== '')));
    if (!ids.length) return buckets;
    const cacheKey = `${endpoint}:${ids.join(',')}`;
    let labelMap = state.optionLabelCache.get(cacheKey);
    if (!labelMap) {
      try {
        const separator = endpoint.includes('?') ? '&' : '?';
        const options = await fetchJson(`${endpoint}${separator}ids=${encodeURIComponent(ids.join(','))}`, { timeoutMs: 10000 });
        labelMap = optionLabelsById(options?.data || options || []);
        state.optionLabelCache.set(cacheKey, labelMap);
      } catch (error) {
        state.optionLabelCache.set(cacheKey, new Map());
        labelMap = new Map();
      }
    }
    return buckets.map((bucket) => {
      const label = labelMap.get(String(bucket.key));
      return label ? { ...bucket, hydratedLabel: label } : bucket;
    });
  }

  function optionLabelsById(options) {
    const map = new Map();
    for (const option of Array.isArray(options) ? options : []) {
      const id = option?.id ?? option?.value ?? option?.key;
      const label = option?.label ?? option?.text ?? option?.name;
      if (id !== null && id !== undefined && label) {
        map.set(String(id), String(label));
      }
    }
    return map;
  }

  function filterInsightItems(resource, profile) {
    const ops = resource.capability?.canonicalOperations || {};
    const controls = Array.from(profile.controlTypes).slice(0, 6);
    return [
      {
        level: profile.evidence.filter === 'confirmed' ? 'good' : profile.canFilter ? 'warn' : 'warn',
        title: profile.evidence.filter === 'confirmed' ? 'Consulta governada confirmada' : profile.canFilter ? 'Consulta possível pelo catálogo' : 'Consulta não confirmada',
        body: profile.canFilter
          ? `Consulta filtrada ${ops.cursor ? 'com cursor' : 'sem cursor confirmado'}. ${evidenceDetail(profile.evidence.filter)}`
          : 'O cockpit não encontrou endpoint/capability de filtro para este recurso.'
      },
      {
        level: profile.evidence.analytics === 'confirmed' ? 'good' : profile.canAnalytics ? 'warn' : 'warn',
        title: profile.evidence.analytics === 'confirmed' ? 'Charts confirmados por stats' : profile.canAnalytics ? 'Charts possíveis pelo catálogo' : 'Charts sem fonte analítica',
        body: analyticsDetailText(resource)
      },
      {
        level: profile.uiFields ? 'good' : 'warn',
        title: profile.uiFields ? 'x-ui materializado' : 'x-ui pouco visível',
        body: profile.uiFields
          ? `${profile.uiFields} campo(s) com x-ui; controles: ${controls.length ? controls.join(', ') : 'controles padrão'}.`
          : 'O schema disponível ainda não trouxe metadados x-ui suficientes para preview de UI.'
      }
    ];
  }

  function analyticsModeText(resource) {
    const ops = resource.capability?.canonicalOperations || resource.operationSummary || {};
    const modes = [];
    if (ops.statsGroupBy || resource.operationSummary?.stats) modes.push('grupo');
    if (ops.statsTimeSeries) modes.push('série');
    if (ops.statsDistribution) modes.push('distribuição');
    return modes.length ? joinHuman(modes) : 'sem stats';
  }

  function analyticsDetailText(resource) {
    const text = analyticsModeText(resource);
    if (text === 'sem stats') return 'Não há stats publicados para alimentar gráficos deste recurso.';
    return `Pode alimentar gráfico por ${text}, usando endpoints stats publicados pelo host.`;
  }

  function statusLevel(status) {
    if (status === 'ok') return 'good';
    if (status === 'blocking') return 'bad';
    if (status === 'loading') return 'loading';
    return 'warn';
  }

  function statusLabel(status) {
    const labels = {
      ok: 'ok',
      attention: 'atenção',
      blocking: 'bloqueante',
      optional: 'opcional',
      loading: 'lendo'
    };
    return labels[status] || status;
  }

  function renderEndpoints(resource) {
    const groups = groupEndpointsByOperation(resource, resource.endpoints
      .slice()
      .sort((a, b) => String(a.path).localeCompare(String(b.path)) || String(a.method).localeCompare(String(b.method)))
      .slice(0, 24));
    if (!groups.length) {
      els.endpointMatrix.innerHTML = '<div class="empty-state">Nenhum endpoint catalogado para este recurso.</div>';
      return;
    }
    els.endpointMatrix.innerHTML = `
      ${groups.map((group) => `
        <section class="operation-group">
          <header>
            <strong>${escapeHtml(group.label)}</strong>
            <span>${escapeHtml(`${group.intent} · fonte: ${group.sourceLabel}`)}</span>
          </header>
          ${group.endpoints.map((endpoint) => `
            <div class="endpoint-row">
              <span><b class="method ${escapeAttr(String(endpoint.method || '').toLowerCase())}">${escapeHtml(endpoint.method || '--')}</b></span>
              <code>${escapeHtml(endpoint.path || '--')}</code>
              <span>${escapeHtml(usefulText(endpoint.description) ? endpoint.description : endpoint.summary || 'Operação publicada no catálogo.')}</span>
              <small>${escapeHtml(endpoint.operation.sourceLabel)}</small>
            </div>
          `).join('')}
        </section>
      `).join('')}
    `;
  }

  function groupEndpointsByOperation(resource, endpoints) {
    const groups = new Map();
    for (const endpoint of endpoints) {
      const operation = classifyEndpoint(resource, endpoint);
      const group = groups.get(operation.key) || { ...operation, intents: [], sourceLabels: new Set(), endpoints: [] };
      group.intents.push(operation.intent);
      group.sourceLabels.add(operation.sourceLabel);
      group.endpoints.push({ ...endpoint, operation });
      group.sourceLabel = sourceLabelList(group.sourceLabels);
      group.intent = groupIntent(group);
      groups.set(operation.key, group);
    }
    const order = ['read', 'write', 'filter', 'stats', 'options', 'actions', 'export', 'other'];
    return Array.from(groups.values()).sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
  }

  function sourceLabelList(sourceLabels) {
    const labels = Array.from(sourceLabels || []).filter(Boolean);
    if (labels.length <= 1) return labels[0] || 'fonte não publicada';
    return labels.join(', ');
  }

  function groupIntent(group) {
    const uniqueIntents = Array.from(new Set(group.intents || []));
    if (uniqueIntents.length === 1) return uniqueIntents[0];
    return `${group.endpoints.length} endpoint(s) com evidências de ${sourceLabelList(group.sourceLabels)}.`;
  }

  function classifyEndpoint(resource, endpoint) {
    const fromCapability = classifyEndpointFromCapabilities(resource, endpoint);
    if (fromCapability) return fromCapability;
    const fromSurface = classifyEndpointFromMaterialization(resource.surfaces, endpoint, 'surface');
    if (fromSurface) return fromSurface;
    const fromAction = classifyEndpointFromMaterialization(resource.actions, endpoint, 'action');
    if (fromAction) return fromAction;
    return classifyEndpointFallback(endpoint);
  }

  function classifyEndpointFromMaterialization(items, endpoint, source) {
    const match = (items || []).find((item) => sameEndpoint(item, endpoint));
    if (!match) return null;
    const isAction = source === 'action';
    const key = isAction ? 'actions' : operationKeyFromIntent(match.intent || match.operationId || match.id || match.title);
    return {
      key,
      label: isAction ? 'Ação de negócio' : operationLabelFromKey(key),
      intent: match.description || (isAction ? 'Comando de negócio publicado por action.' : `Surface publicada: ${readableText(match.title || match.intent || match.operationId || 'experiência')}.`),
      source: source,
      sourceLabel: source === 'action' ? 'action publicada' : 'surface publicada'
    };
  }

  function classifyEndpointFromCapabilities(resource, endpoint) {
    const operations = resource.capability?.operations || {};
    const match = Object.values(operations).find((operation) => {
      if (!operation?.supported || !operation.preferredMethod) return false;
      return String(operation.preferredMethod).toUpperCase() === String(endpoint.method || '').toUpperCase()
        && capabilityOperationMatchesPath(operation, endpoint, resource);
    });
    if (!match) return null;
    return {
      key: operationKeyFromIntent(match.id || match.preferredRel),
      label: operationLabelFromCapability(match),
      intent: `Operação ${match.id || match.preferredRel} confirmada por capabilities.`,
      source: 'capability',
      sourceLabel: 'capability canônica'
    };
  }

  function classifyEndpointFallback(endpoint) {
    const method = String(endpoint.method || '').toUpperCase();
    const path = endpoint.path || '';
    // Fallback grouping only; canonical action intent comes from /schemas/actions or capabilities.
    if (isActionDiscoveryEndpoint(path)) return fallbackOperation('other', 'Discovery de actions', 'Endpoint de descoberta contextual; não confirma workflow action publicada.');
    if (isWorkflowActionEndpoint(path)) return fallbackOperation('actions', 'Ação de negócio', 'Comando inferido pelo path do endpoint; confirme em /schemas/actions.');
    if (path.includes('/stats/')) return fallbackOperation('stats', 'Análise e indicadores', 'Leitura agregada inferida pelo path do endpoint.');
    if (path.includes('/options') || path.includes('/option-sources')) return fallbackOperation('options', 'Fonte de opção', 'Fonte de valores inferida pelo path do endpoint.');
    if (path.includes('/filter') || path.endsWith('/filtered')) return fallbackOperation('filter', 'Busca e filtro', 'Consulta refinada inferida pelo path do endpoint.');
    if (path.endsWith('/export')) return fallbackOperation('export', 'Exportação', 'Exportação inferida pelo path do endpoint.');
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) return fallbackOperation('write', 'Escrita governada', 'Escrita inferida pelo método HTTP.');
    if (method === 'GET') return fallbackOperation('read', 'Leitura', 'Leitura inferida pelo método HTTP.');
    return fallbackOperation('other', 'Operação complementar', 'Endpoint publicado pelo catálogo.');
  }

  function isActionDiscoveryEndpoint(path) {
    return /\/actions$/.test(String(path || '')) || /\/\{[^/]+}\/actions$/.test(String(path || ''));
  }

  function isWorkflowActionEndpoint(path) {
    return String(path || '').includes('/actions/') && !isActionDiscoveryEndpoint(path);
  }

  function sameEndpoint(item, endpoint) {
    return String(item?.path || '') === String(endpoint.path || '')
      && String(item?.method || '').toUpperCase() === String(endpoint.method || '').toUpperCase();
  }

  function capabilityOperationMatchesPath(operation, endpoint, resource) {
    const path = endpoint.path || '';
    const rel = String(operation.preferredRel || operation.id || '');
    if (operation.scope === 'COLLECTION' && path === resource.resourcePath) return true;
    if (operation.scope === 'ITEM' && path.startsWith(resource.resourcePath || '') && path.includes('{')) return true;
    if (rel && path.includes(`/${rel}`)) return true;
    if (operation.id === 'view' && String(endpoint.method).toUpperCase() === 'GET' && path.includes('{')) return true;
    return false;
  }

  function operationKeyFromIntent(value) {
    const normalized = String(value || '').toLowerCase();
    if (['create', 'edit', 'update', 'delete'].some((item) => normalized.includes(item))) return 'write';
    if (['view', 'list', 'all', 'byid', 'self', 'detail', 'detalhe', 'obter', 'consultar', 'get'].some((item) => normalized.includes(item))) return 'read';
    if (normalized.includes('filter') || normalized.includes('cursor')) return 'filter';
    if (normalized.includes('stats')) return 'stats';
    if (normalized.includes('option')) return 'options';
    if (normalized.includes('export')) return 'export';
    if (normalized.includes('action') || normalized.includes('workflow')) return 'actions';
    return 'other';
  }

  function operationLabelFromCapability(operation) {
    const key = operationKeyFromIntent(operation.id || operation.preferredRel);
    return operationLabelFromKey(key);
  }

  function operationLabelFromKey(key) {
    const labels = {
      read: 'Leitura',
      write: 'Escrita governada',
      filter: 'Busca e filtro',
      stats: 'Análise e indicadores',
      options: 'Fonte de opção',
      actions: 'Ação de negócio',
      export: 'Exportação',
      other: 'Operação complementar'
    };
    return labels[key] || 'Operação complementar';
  }

  function fallbackOperation(key, label, intent) {
    return {
      key,
      label,
      intent,
      source: 'fallback',
      sourceLabel: 'fallback inferido'
    };
  }

  function renderFields(resource) {
    const fields = filteredSchemaFields(resource);
    const source = fields.length ? (resource.schemaSource?.label || 'schema') : 'catálogo';
    const displayFields = (fields.length ? fields : resource.fieldList)
      .slice()
      .sort((a, b) => Number(b.required) - Number(a.required) || a.name.localeCompare(b.name))
      .slice(0, 10);
    if (!displayFields.length) {
      els.fieldSummary.innerHTML = '<div class="empty-state">Nenhum campo de domínio foi encontrado para este recurso.</div>';
      return;
    }
    els.fieldSummary.innerHTML = displayFields.map((field) => {
      const description = readableText(Array.from(field.descriptions)[0] || `${field.required ? 'Campo obrigatório' : 'Campo opcional'} do contrato.`);
      const enumText = field.enumValues.size ? ` Valores: ${Array.from(field.enumValues).slice(0, 6).join(', ')}.` : '';
      return `
        <article class="evidence-item ${field.required ? 'good' : ''}">
          <strong>${escapeHtml(field.name)} · ${escapeHtml(field.type || 'valor')}</strong>
          <span>${escapeHtml(`${description + enumText} Fonte: ${source}.`)}</span>
        </article>
      `;
    }).join('');
  }

  function filteredSchemaFields(resource) {
    const properties = resource.filteredSchema?.properties;
    if (!properties) return [];
    const required = new Set(resource.filteredSchema.required || []);
    return Object.entries(properties)
      .filter(([name]) => !isEnvelopeField(name))
      .map(([name, property]) => ({
        name,
        type: property.type || property.format || property.$ref || 'valor',
        required: required.has(name),
        descriptions: new Set([property.description].filter(Boolean)),
        enumValues: new Set(property.enum || []),
        directions: new Set(['schema-filtrado']),
        xui: property['x-ui'] || null
      }));
  }

  function isEnvelopeField(name) {
    return ['status', 'message', 'data', 'errors', 'timestamp', '_links'].includes(name);
  }

  function renderDiagnostics(resource) {
    const filteredFields = filteredSchemaFields(resource);
    const diagnostics = [
      {
        level: 'good',
        title: 'Recursos técnicos foram separados',
        body: `${state.discovery.frameworkEndpoints.length} endpoint(s) técnicos não inflaram o mapa de domínio.`
      },
      {
        level: resource.domainGraph ? 'good' : 'warn',
        title: resource.domainGraph ? 'Domínio escopado carregado' : 'Domínio escopado ausente',
        body: resource.domainGraph ? `${countItems(resource.domainGraph.nodes)} nó(s), ${countItems(resource.domainGraph.edges)} relação(ões) e ${countItems(resource.domainGraph.evidence)} evidência(s).` : 'O cockpit ainda está usando fallback de catálogo/path para explicar este recurso.'
      },
      {
        level: filteredFields.length && resource.schemaSource?.canonical ? 'good' : 'warn',
        title: filteredFields.length && resource.schemaSource?.canonical ? 'Schema filtrado priorizado' : 'Origem de schema exige leitura',
        body: filteredFields.length ? `${filteredFields.length} campo(s) foram materializados via ${resource.schemaSource?.label || 'schema'}.` : 'O cockpit caiu para campos do catálogo; pode haver envelope técnico.'
      },
      {
        level: resource.surfaces.length ? 'good' : 'warn',
        title: resource.surfaces.length ? 'Surfaces escopadas encontradas' : 'Sem surface publicada',
        body: resource.surfaces.length ? `${resource.surfaces.length} experiência(s) metadata-driven foram encontradas para este recurso.` : 'A API existe, mas nenhuma surface escopada foi publicada.'
      },
      {
        level: resource.actions.length ? 'good' : 'warn',
        title: resource.actions.length ? 'Actions escopadas encontradas' : 'Sem workflow action publicada',
        body: resource.actions.length ? `${resource.actions.length} ação(ões) de workflow foram encontradas.` : 'Não há action publicada para este recurso; isso não é falha de consulta global.'
      }
      ,
      ...operationalDiagnostics(resource).map((item) => ({
        level: statusLevel(item.level),
        title: item.title,
        body: `${item.impact} Próximo passo: ${item.action}`
      }))
    ];
    els.diagnosticsList.innerHTML = diagnostics.map((item) => `
      <article class="evidence-item ${item.level}">
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.body)}</span>
      </article>
    `).join('');
  }

  function renderEmptyDetail() {
    els.selectedTitle.textContent = 'Nenhum recurso descoberto';
    els.selectedSubtitle.textContent = 'Verifique se /schemas/catalog está público e se o host expõe controllers resource-oriented.';
    els.resourceReadiness.querySelector('strong').textContent = '--';
    els.attentionNow.innerHTML = '<div class="empty-state">Prioridades aparecem quando um recurso é selecionado.</div>';
    els.businessMeaning.textContent = 'Sem recurso selecionado.';
    els.integrationMeaning.textContent = 'Sem endpoints catalogados.';
    els.gapMeaning.textContent = 'Sem evidências suficientes.';
    els.domainTopology.innerHTML = '<div class="empty-state">A topologia aparece quando um recurso é selecionado.</div>';
    els.semanticReadiness.innerHTML = '<div class="empty-state">Prontidão semântica aparece quando um recurso é selecionado.</div>';
    els.renderabilityMatrix.innerHTML = '<div class="empty-state">Capacidades renderizáveis aparecem quando um recurso é selecionado.</div>';
    els.resourceChart.innerHTML = '<div class="empty-state">Gráfico operacional aparece quando um recurso é selecionado.</div>';
    els.filterInsights.innerHTML = '<div class="empty-state">Filtros e analytics aparecem quando um recurso é selecionado.</div>';
    els.workflowRail.innerHTML = '<div class="empty-state">O workflow aparece quando um recurso é selecionado.</div>';
    els.endpointMatrix.innerHTML = '<div class="empty-state">Endpoints aparecem quando um recurso é selecionado.</div>';
    els.fieldSummary.innerHTML = '<div class="empty-state">Campos aparecem quando um recurso é selecionado.</div>';
    els.diagnosticsList.innerHTML = '<div class="empty-state">Diagnósticos aparecem com evidências do host.</div>';
  }

  function setHostStatus(status, text, detail) {
    els.hostStatusDot.className = `status-light ${status === 'ok' ? 'ok' : status === 'error' ? 'error' : ''}`;
    els.hostStatusText.textContent = text;
    els.hostStatusDetail.textContent = detail || '';
  }

  function renderReleaseMarker(info) {
    if (!els.releaseMarker) return;
    const params = new URLSearchParams(window.location.search);
    const release = params.get('release') || info?.build?.version || 'runtime';
    const published = params.get('published') === '1';
    const qa = params.get('qa');
    const buildTime = formatBuildTime(info?.build?.time);
    const buildVersion = info?.build?.version;
    const details = [
      buildVersion ? `host ${buildVersion}` : null,
      buildTime ? `build ${buildTime}` : null,
      published ? 'snapshot público' : null,
      qa ? `qa ${qa}` : null
    ].filter(Boolean);
    els.releaseMarker.innerHTML = `
      <span>${published ? 'Publicado' : 'Publicação'}</span>
      <strong>${escapeHtml(release)}</strong>
      <small>${escapeHtml(details.join(' · ') || 'build ainda não informado')}</small>
    `;
  }

  function formatBuildTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function escapeAttr(value) {
    return escapeHtml(value);
  }
})();
