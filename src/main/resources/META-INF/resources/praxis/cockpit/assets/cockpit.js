(function () {
  const state = {
    resources: [],
    selectedKey: null,
    catalog: null,
    domain: null,
    surfaces: null,
    actions: null,
    capabilities: new Map(),
    filteredSchemas: new Map(),
    endpointStatus: {},
    discovery: {
      frameworkEndpoints: [],
      derivedEndpoints: [],
      partialEndpoints: []
    }
  };

  const els = {
    hostStatusDot: document.getElementById('hostStatusDot'),
    hostStatusText: document.getElementById('hostStatusText'),
    resourceCount: document.getElementById('resourceCount'),
    resourceCountHint: document.getElementById('resourceCountHint'),
    surfaceCount: document.getElementById('surfaceCount'),
    surfaceCountHint: document.getElementById('surfaceCountHint'),
    actionCount: document.getElementById('actionCount'),
    actionCountHint: document.getElementById('actionCountHint'),
    domainCount: document.getElementById('domainCount'),
    sourceMode: document.getElementById('sourceMode'),
    resourceSearch: document.getElementById('resourceSearch'),
    resourceList: document.getElementById('resourceList'),
    selectedDomain: document.getElementById('selectedDomain'),
    selectedTitle: document.getElementById('selectedTitle'),
    selectedSubtitle: document.getElementById('selectedSubtitle'),
    structuralReadiness: document.getElementById('structuralReadiness'),
    semanticReadiness: document.getElementById('semanticReadiness'),
    runtimeReadiness: document.getElementById('runtimeReadiness'),
    aiReadiness: document.getElementById('aiReadiness'),
    structuralFacts: document.getElementById('structuralFacts'),
    semanticFacts: document.getElementById('semanticFacts'),
    runtimeFacts: document.getElementById('runtimeFacts'),
    aiFacts: document.getElementById('aiFacts'),
    capabilityMatrix: document.getElementById('capabilityMatrix'),
    diagnosticsList: document.getElementById('diagnosticsList'),
    refreshResourceButton: document.getElementById('refreshResourceButton')
  };

  const canonicalOperationOrder = [
    'create',
    'view',
    'edit',
    'delete',
    'duplicate-draft',
    'export',
    'filter',
    'cursor',
    'options',
    'optionSources',
    'statsGroupBy',
    'statsTimeSeries',
    'statsDistribution'
  ];

  document.addEventListener('DOMContentLoaded', init);

  async function init() {
    wireEvents();
    await loadHost();
  }

  function wireEvents() {
    els.resourceSearch.addEventListener('input', renderResourceList);
    els.refreshResourceButton.addEventListener('click', async () => {
      const resource = selectedResource();
      if (resource) {
        await enrichResource(resource, true);
        renderDetail(resource);
      }
    });
  }

  async function loadHost() {
    setHostStatus('loading', 'Lendo contratos publicados pelo host...');
    const [health, catalog, domain, surfaces, actions] = await Promise.allSettled([
      fetchJson('/actuator/health'),
      fetchJson('/schemas/catalog'),
      fetchJson('/schemas/domain'),
      fetchJson('/schemas/surfaces'),
      fetchJson('/schemas/actions')
    ]);

    state.catalog = valueOrNull(catalog);
    state.domain = valueOrNull(domain);
    state.surfaces = valueOrNull(surfaces);
    state.actions = valueOrNull(actions);
    state.endpointStatus = {
      health: endpointStatus(health),
      catalog: endpointStatus(catalog),
      domain: endpointStatus(domain),
      surfaces: endpointStatus(surfaces),
      actions: endpointStatus(actions)
    };
    state.resources = composeResources(state.catalog, state.domain, state.surfaces, state.actions);

    renderMetrics();
    renderResourceList();

    if (state.resources.length > 0) {
      selectResource(state.resources[0].key);
      setHostStatus('ok', 'Host conectado. Cockpit publicado pelo starter.');
    } else {
      setHostStatus('error', 'Nenhum resource descoberto. Verifique /schemas/catalog e discovery semantico.');
      renderEmptyDetail();
    }
  }

  async function fetchJson(url) {
    const response = await fetch(url, {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin'
    });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
    return response.json();
  }

  function valueOrNull(result) {
    return result.status === 'fulfilled' ? result.value : null;
  }

  function endpointStatus(result) {
    if (result.status === 'fulfilled') {
      return { ok: true, message: 'ok' };
    }
    return {
      ok: false,
      message: result.reason?.message || 'indisponivel'
    };
  }

  function composeResources(catalog, domain, surfaces, actions) {
    const index = new Map();
    state.discovery = {
      frameworkEndpoints: [],
      derivedEndpoints: [],
      partialEndpoints: []
    };

    for (const endpoint of catalogEndpoints(catalog)) {
      const path = endpoint.path || '';
      const method = (endpoint.method || '').toLowerCase();
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
      const key = resourcePath || path || endpoint.operationId;
      const resource = ensureResource(index, key, {
        resourcePath,
        group: endpoint.group || catalog?.group || domainFromPath(resourcePath),
        sourceConfidence: resourcePath ? 'path-derived' : 'partial'
      });
      resource.endpoints.push(endpoint);
      if (method === 'post' && path.endsWith('/filter')) {
        resource.primaryFilter = endpoint;
      }
      if (endpoint.schemaLinks) {
        resource.schemaLinks.push(endpoint.schemaLinks);
      }
    }

    for (const item of surfaceItems(surfaces)) {
      const resourcePath = normalizeResourcePath(item.resourcePath || item.path);
      if (!item.resourceKey && !resourcePath) {
        continue;
      }
      const key = item.resourceKey || resourcePath || item.id;
      const resource = ensureResource(index, key, {
        resourceKey: item.resourceKey,
        resourcePath,
        group: item.group || domainFromPath(resourcePath),
        sourceConfidence: item.resourceKey ? 'resourceKey' : 'partial'
      });
      resource.surfaces.push(item);
    }

    for (const item of actionItems(actions)) {
      const resourcePath = normalizeResourcePath(item.resourcePath || item.path);
      if (!item.resourceKey && !resourcePath) {
        continue;
      }
      const key = item.resourceKey || resourcePath || item.id;
      const resource = ensureResource(index, key, {
        resourceKey: item.resourceKey,
        resourcePath,
        group: item.group || domainFromPath(resourcePath),
        sourceConfidence: item.resourceKey ? 'resourceKey' : 'partial'
      });
      resource.actions.push(item);
    }

    for (const item of domainItems(domain)) {
      const resourceKey = item.resourceKey || item.resource || item.key;
      if (!resourceKey) {
        continue;
      }
      const resource = ensureResource(index, resourceKey, {
        resourceKey,
        group: item.group || domainFromResourceKey(resourceKey),
        sourceConfidence: 'resourceKey'
      });
      resource.domainItems.push(item);
    }

    return Array.from(new Set(index.values()))
      .map(finalizeResource)
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  function ensureResource(index, key, patch) {
    const stableKey = patch.resourceKey || key;
    const existing = index.get(stableKey) || index.get(key) || findByResourcePath(index, patch.resourcePath);
    if (existing) {
      mergeResource(existing, patch);
      if (!index.has(stableKey)) {
        index.set(stableKey, existing);
      }
      if (!index.has(key)) {
        index.set(key, existing);
      }
      if (patch.resourcePath && !index.has(patch.resourcePath)) {
        index.set(patch.resourcePath, existing);
      }
      return existing;
    }

    const resource = {
      key: stableKey,
      resourceKey: patch.resourceKey || null,
      resourcePath: patch.resourcePath || null,
      group: patch.group || null,
      sourceConfidence: patch.sourceConfidence || 'partial',
      endpoints: [],
      schemaLinks: [],
      surfaces: [],
      actions: [],
      domainItems: [],
      primaryFilter: null,
      capability: null,
      filteredSchema: null
    };
    index.set(stableKey, resource);
    if (key !== stableKey) {
      index.set(key, resource);
    }
    if (patch.resourcePath) {
      index.set(patch.resourcePath, resource);
    }
    return resource;
  }

  function findByResourcePath(index, resourcePath) {
    if (!resourcePath) {
      return null;
    }
    for (const resource of index.values()) {
      if (resource.resourcePath === resourcePath || resource.paths?.includes(resourcePath)) {
        return resource;
      }
    }
    return null;
  }

  function mergeResource(resource, patch) {
    resource.resourceKey = resource.resourceKey || patch.resourceKey || null;
    resource.resourcePath = resource.resourcePath || patch.resourcePath || null;
    resource.group = resource.group || patch.group || null;
    if (resource.sourceConfidence !== 'resourceKey') {
      resource.sourceConfidence = patch.sourceConfidence || resource.sourceConfidence;
    }
  }

  function finalizeResource(resource) {
    resource.key = resource.resourceKey || resource.key;
    resource.domain = resource.group || domainFromResourceKey(resource.resourceKey) || domainFromPath(resource.resourcePath);
    resource.label = resource.resourceKey || trimApiPrefix(resource.resourcePath) || resource.key;
    resource.description = describeResource(resource);
    resource.paths = Array.from(new Set([
      resource.resourcePath,
      ...resource.endpoints.map((endpoint) => normalizeResourcePath(endpoint.path)),
      ...resource.surfaces.map((surface) => surface.path && normalizeResourcePath(surface.path)),
      ...resource.actions.map((action) => action.path && normalizeResourcePath(action.path))
    ].filter(Boolean)));
    return resource;
  }

  function catalogEndpoints(catalog) {
    if (!catalog) {
      return [];
    }
    if (Array.isArray(catalog.endpoints)) {
      return catalog.endpoints;
    }
    if (Array.isArray(catalog.groups)) {
      return catalog.groups.flatMap((group) => (group.endpoints || []).map((endpoint) => ({
        ...endpoint,
        group: endpoint.group || group.group || group.name || group.id
      })));
    }
    return [];
  }

  function surfaceItems(payload) {
    if (!payload) {
      return [];
    }
    if (Array.isArray(payload.surfaces)) {
      return payload.surfaces.map((item) => ({ ...item, resourcePath: payload.resourcePath, group: payload.group }));
    }
    if (Array.isArray(payload.resources)) {
      return payload.resources.flatMap((resource) => (resource.surfaces || []).map((item) => ({
        ...item,
        resourcePath: resource.resourcePath,
        group: resource.group
      })));
    }
    if (Array.isArray(payload)) {
      return payload.flatMap(surfaceItems);
    }
    return [];
  }

  function actionItems(payload) {
    if (!payload) {
      return [];
    }
    if (Array.isArray(payload.actions)) {
      return payload.actions.map((item) => ({ ...item, resourcePath: payload.resourcePath, group: payload.group }));
    }
    if (Array.isArray(payload.resources)) {
      return payload.resources.flatMap((resource) => (resource.actions || []).map((item) => ({
        ...item,
        resourcePath: resource.resourcePath,
        group: resource.group
      })));
    }
    if (Array.isArray(payload)) {
      return payload.flatMap(actionItems);
    }
    return [];
  }

  function domainItems(payload) {
    if (!payload) {
      return [];
    }
    if (Array.isArray(payload.resources)) {
      return payload.resources;
    }
    if (Array.isArray(payload.items)) {
      return payload.items;
    }
    if (Array.isArray(payload)) {
      return payload;
    }
    return [];
  }

  function normalizeResourcePath(path) {
    if (!path) {
      return null;
    }
    if (isFrameworkEndpoint(path) || !path.startsWith('/api/')) {
      return null;
    }
    const normalized = path
      .replace(/\/\{[^}]+}/g, '')
      .replace(/\/(all|filter|filtered|locate|capabilities|export|batch|schemas?)$/g, '')
      .replace(/\/filter\/cursor$/g, '')
      .replace(/\/actions(?:\/.*)?$/g, '')
      .replace(/\/batch(?:\/.*)?$/g, '')
      .replace(/\/schema(?:s)?(?:\/.*)?$/g, '')
      .replace(/\/options(?:\/.*)?$/g, '')
      .replace(/\/option-sources(?:\/.*)?$/g, '')
      .replace(/\/stats(?:\/.*)?$/g, '');
    return normalized === '/api' ? null : normalized;
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

  function trimApiPrefix(path) {
    return path ? path.replace(/^\/api\//, '') : null;
  }

  function domainFromPath(path) {
    const clean = trimApiPrefix(path || '');
    return clean ? clean.split('/')[0] : null;
  }

  function domainFromResourceKey(resourceKey) {
    return resourceKey ? resourceKey.split('.')[0] : null;
  }

  function describeResource(resource) {
    const endpoint = resource.primaryFilter || resource.endpoints[0];
    const action = resource.actions[0];
    const surface = resource.surfaces[0];
    return endpoint?.description || endpoint?.summary || surface?.description || action?.description || 'Resource descoberto por contratos metadata-driven.';
  }

  function renderMetrics() {
    const surfaces = surfaceItems(state.surfaces);
    const actions = actionItems(state.actions);
    const domain = domainItems(state.domain);
    const infrastructureCount = state.discovery.frameworkEndpoints.length + state.discovery.partialEndpoints.length;
    const derivedCount = state.discovery.derivedEndpoints.length;
    els.resourceCount.textContent = String(state.resources.length);
    els.surfaceCount.textContent = String(surfaces.length);
    els.actionCount.textContent = String(actions.length);
    els.domainCount.textContent = String(domain.length || '--');
    els.resourceCountHint.textContent = `${derivedCount} derived, ${infrastructureCount} infra filtered`;
    els.surfaceCountHint.textContent = discoveryHint('surfaces', surfaces.length, 'semantic UI discovery');
    els.actionCountHint.textContent = discoveryHint('actions', actions.length, 'business workflows');
    els.sourceMode.textContent = 'starter';
  }

  function discoveryHint(endpointKey, count, label) {
    const status = state.endpointStatus[endpointKey];
    if (!status?.ok) {
      return `${endpointKey} endpoint ${status?.message || 'indisponivel'}`;
    }
    if (count === 0) {
      return `${label}: sem itens publicados`;
    }
    return label;
  }

  function renderResourceList() {
    const query = els.resourceSearch.value.trim().toLowerCase();
    const filtered = state.resources.filter((resource) => {
      const haystack = [
        resource.label,
        resource.resourceKey,
        resource.resourcePath,
        resource.domain,
        resource.description
      ].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(query);
    });

    if (filtered.length === 0) {
      els.resourceList.innerHTML = '<div class="empty-state">Nenhum resource encontrado com esse filtro.</div>';
      return;
    }

    els.resourceList.innerHTML = filtered.map((resource) => `
      <button class="resource-button ${resource.key === state.selectedKey ? 'active' : ''}" type="button" data-key="${escapeAttr(resource.key)}">
        <strong>${escapeHtml(resource.label)}</strong>
        <span>${escapeHtml(resource.resourcePath || resource.domain || 'semantic discovery')}</span>
      </button>
    `).join('');

    els.resourceList.querySelectorAll('button[data-key]').forEach((button) => {
      button.addEventListener('click', () => selectResource(button.dataset.key));
    });
  }

  async function selectResource(key) {
    state.selectedKey = key;
    renderResourceList();
    const resource = selectedResource();
    if (!resource) {
      renderEmptyDetail();
      return;
    }
    renderDetail(resource);
    await enrichResource(resource, false);
    renderDetail(resource);
  }

  function selectedResource() {
    return state.resources.find((resource) => resource.key === state.selectedKey);
  }

  async function enrichResource(resource, force) {
    const resourcePath = resource.resourcePath || resource.paths[0];
    if (resourcePath && (force || !state.capabilities.has(resource.key))) {
      try {
        const capability = await fetchJson(`${resourcePath}/capabilities`);
        state.capabilities.set(resource.key, capability);
        resource.capability = capability;
      } catch (error) {
        state.capabilities.set(resource.key, null);
        resource.capability = null;
      }
    } else if (state.capabilities.has(resource.key)) {
      resource.capability = state.capabilities.get(resource.key);
    }

    const schemaUrl = firstSchemaUrl(resource);
    if (schemaUrl && (force || !state.filteredSchemas.has(resource.key))) {
      try {
        const schema = await fetchJson(schemaUrl);
        state.filteredSchemas.set(resource.key, schema);
        resource.filteredSchema = schema;
      } catch (error) {
        state.filteredSchemas.set(resource.key, null);
        resource.filteredSchema = null;
      }
    } else if (state.filteredSchemas.has(resource.key)) {
      resource.filteredSchema = state.filteredSchemas.get(resource.key);
    }
  }

  function firstSchemaUrl(resource) {
    const fromCatalog = resource.schemaLinks.find((links) => links?.request || links?.response);
    if (fromCatalog?.response) {
      return fromCatalog.response;
    }
    if (fromCatalog?.request) {
      return fromCatalog.request;
    }
    const surface = resource.surfaces.find((item) => item.schemaUrl);
    if (surface?.schemaUrl) {
      return surface.schemaUrl;
    }
    const action = resource.actions.find((item) => item.responseSchemaUrl || item.requestSchemaUrl);
    return action?.responseSchemaUrl || action?.requestSchemaUrl || null;
  }

  function renderDetail(resource) {
    const scores = computeScores(resource);
    els.selectedDomain.textContent = resource.domain || 'semantic resource';
    els.selectedTitle.textContent = resource.label;
    els.selectedSubtitle.textContent = resource.description;
    setChip(els.structuralReadiness, `Structural ${scores.structural}`, scores.structural);
    setChip(els.semanticReadiness, `Semantic ${scores.semantic}`, scores.semantic);
    setChip(els.runtimeReadiness, `Runtime ${scores.runtime}`, scores.runtime);
    setChip(els.aiReadiness, `AI ${scores.ai}`, scores.ai);
    renderFacts(resource);
    renderCapabilityMatrix(resource);
    renderDiagnostics(resource, scores);
  }

  function computeScores(resource) {
    const structuralSignals = (resource.filteredSchema ? 1 : 0) + resource.schemaLinks.length + resource.endpoints.length;
    const runtimeSignals = (resource.capability ? 1 : 0) + optionSourceCount(resource) + statsSignalCount(resource);
    const aiSignals = resource.domainItems.length + governanceSignalCount(resource);
    return {
      structural: score(structuralSignals),
      semantic: score(resource.surfaces.length + resource.actions.length),
      runtime: score(runtimeSignals),
      ai: score(aiSignals)
    };
  }

  function score(value) {
    if (value >= 2) {
      return 'good';
    }
    if (value >= 1) {
      return 'warn';
    }
    return 'bad';
  }

  function setChip(element, text, value) {
    element.textContent = text;
    element.className = `readiness-chip ${value}`;
  }

  function renderFacts(resource) {
    setFacts(els.structuralFacts, {
      'resourceKey': resource.resourceKey || 'nao publicado nesta leitura',
      'resourcePath': resource.resourcePath || 'path parcial',
      'schema links': String(resource.schemaLinks.length),
      'filtered schema': resource.filteredSchema ? 'resolvido' : 'sob demanda ou indisponivel'
    });
    setFacts(els.semanticFacts, {
      'surfaces': String(resource.surfaces.length),
      'actions': String(resource.actions.length),
      'confidence': resource.sourceConfidence,
      'group': resource.group || 'nao informado'
    });
    setFacts(els.runtimeFacts, {
      'capabilities': resource.capability ? 'snapshot lido' : 'pendente ou indisponivel',
      'option sources': String(optionSourceCount(resource)),
      'stats signals': String(statsSignalCount(resource)),
      'export': exportSignal(resource)
    });
    setFacts(els.aiFacts, {
      'domain items': String(resource.domainItems.length),
      'governance': governanceSignalCount(resource) ? 'evidencia encontrada' : 'nao detectada nesta amostra',
      'grounding': resource.domainItems.length ? 'domain catalog' : 'catalog/schema only',
      'decision': 'diagnostico experimental'
    });
  }

  function setFacts(container, facts) {
    container.innerHTML = Object.entries(facts).map(([key, value]) => `
      <dt>${escapeHtml(key)}</dt>
      <dd>${escapeHtml(String(value))}</dd>
    `).join('');
  }

  function renderCapabilityMatrix(resource) {
    const operations = resource.capability?.operations || {};
    const canonical = resource.capability?.canonicalOperations || {};
    const rows = canonicalOperationOrder.map((operationId) => {
      const operation = operations[operationId];
      const supported = operation ? operation.supported : Boolean(canonical[operationId]);
      return {
        id: operationId,
        supported,
        scope: operation?.scope || '--',
        method: operation?.preferredMethod || '--',
        rel: operation?.preferredRel || '--'
      };
    });

    els.capabilityMatrix.innerHTML = `
      <div class="matrix-row header">
        <span>Operation</span><span>Supported</span><span>Scope</span><span>Method</span><span>Rel</span>
      </div>
      ${rows.map((row) => `
        <div class="matrix-row">
          <strong>${escapeHtml(row.id)}</strong>
          <span>${row.supported ? 'yes' : 'no'}</span>
          <span>${escapeHtml(row.scope)}</span>
          <span>${escapeHtml(row.method)}</span>
          <span>${escapeHtml(row.rel)}</span>
        </div>
      `).join('')}
    `;
  }

  function renderDiagnostics(resource, scores) {
    const diagnostics = [];
    diagnostics.push(diagnostic({
      id: 'semantic.resource-key-pivot',
      category: 'semantic',
      severity: resource.resourceKey ? 'info' : 'error',
      title: resource.resourceKey ? 'resourceKey usado como pivot canonico' : 'resourceKey ausente na composicao atual',
      evidence: resource.resourceKey || resource.resourcePath || resource.key,
      canonicalSource: '/schemas/domain, /schemas/surfaces, /schemas/actions',
      impact: resource.resourceKey
        ? 'A identidade semantica pode ser reconciliada entre discovery, runtime e AI grounding.'
        : 'O cockpit precisou usar path ou operationId como apoio estrutural, o que reduz estabilidade para consumidores corporativos.',
      recommendedFix: 'Publicar resourceKey no dono canonico do recurso, preferencialmente via @ApiResource(resourceKey=...).',
      validation: 'Consultar /schemas/domain, /schemas/surfaces ou /schemas/actions e confirmar o mesmo resourceKey.',
      body: resource.resourceKey ? 'Surfaces, actions ou domain publicaram identidade semantica estavel.' : 'O cockpit precisou usar path ou operationId como apoio estrutural.'
    }));
    diagnostics.push(diagnostic({
      id: 'structural.filtered-schema-resolvable',
      category: 'structural',
      severity: scores.structural === 'bad' ? 'error' : 'info',
      title: scores.structural === 'bad' ? 'Schema estrutural ainda nao resolvido' : 'Contrato estrutural tem evidencia',
      evidence: resource.filteredSchema ? 'filtered schema resolvido' : `${resource.schemaLinks.length} schema link(s), ${resource.endpoints.length} endpoint(s)`,
      canonicalSource: '/schemas/filtered',
      impact: 'Forms, tabelas, option sources e materializacoes runtime dependem de schema estrutural confiavel.',
      recommendedFix: 'Corrigir a operacao/schema canonica no starter ou no controller resource-oriented; nao remendar no consumidor.',
      validation: 'Abrir o schema via /schemas/filtered para o path/metodo do recurso.',
      body: 'A fonte estrutural continua sendo /schemas/filtered; catalogos apenas referenciam schemas.'
    }));
    diagnostics.push(diagnostic({
      id: 'semantic.framework-endpoints-separated',
      category: 'semantic',
      severity: state.discovery.frameworkEndpoints.length ? 'info' : 'warn',
      title: state.discovery.frameworkEndpoints.length ? 'Endpoints tecnicos separados do mapa semantico' : 'Nenhum endpoint tecnico detectado no catalogo',
      evidence: `${state.discovery.frameworkEndpoints.length} framework, ${state.discovery.derivedEndpoints.length} derived`,
      canonicalSource: '/schemas/catalog',
      impact: 'O contador de resources fica didatico e nao mistura auth, actuator, swagger, config infra ou helpers com dominio.',
      recommendedFix: 'Manter a classificacao no cockpit derivada de paths canonicos e promover contrato apenas se houver lacuna real.',
      validation: 'Comparar resources do cockpit com /schemas/catalog e confirmar que /auth/** nao aparece como resource.',
      body: `${state.discovery.frameworkEndpoints.length} endpoint(s) de framework e ${state.discovery.derivedEndpoints.length} operacao(oes) derivada(s) nao inflaram o contador de resources.`
    }));
    diagnostics.push(discoveryDiagnostic(
      'surfaces',
      resource.surfaces.length,
      'semantic.surface-discovery',
      'semantic',
      'Surfaces semanticas publicadas',
      'Surface discovery ausente ou vazio',
      '/schemas/surfaces',
      '/schemas/surfaces deve publicar experiencias de UI quando o host tiver modelo semantico de tela.',
      'Experiencias runtime podem ficar implicitas ou depender de convencao local se a surface nao estiver publicada.',
      'Publicar @UiSurface ou garantir surfaces automaticas no recurso canonico.'
    ));
    diagnostics.push(discoveryDiagnostic(
      'actions',
      resource.actions.length,
      'semantic.action-discovery',
      'semantic',
      'Actions semanticas publicadas',
      'Action discovery ausente ou vazio',
      '/schemas/actions',
      '/schemas/actions deve publicar workflows de negocio quando o host tiver acoes governadas.',
      'Workflows de negocio podem ficar invisiveis para runtime materialization e grounding de IA.',
      'Publicar @WorkflowAction no controller/resource canonico e schemas de request/response quando houver payload.'
    ));
    diagnostics.push(diagnostic({
      id: 'runtime.capabilities-readable',
      category: 'runtime',
      severity: resource.capability ? 'info' : 'error',
      title: resource.capability ? 'Capabilities lidas no recurso' : 'Capabilities indisponiveis nesta leitura',
      evidence: resource.capability ? 'snapshot lido' : resource.resourcePath || 'sem resourcePath',
      canonicalSource: 'GET {resource}/capabilities',
      impact: 'Capabilities governam operacoes atuais e devem alinhar schema, actions, surfaces e links HATEOAS.',
      recommendedFix: 'Corrigir o controller resource-oriented ou a composicao de capabilities no starter.',
      validation: 'Chamar GET {resource}/capabilities no host e comparar operations com o OpenAPI.',
      body: 'Capabilities governam operacao atual, mas nao substituem schema.'
    }));
    diagnostics.push(diagnostic({
      id: 'ai.domain-grounding-evidence',
      category: 'ai',
      severity: scores.ai === 'bad' ? 'error' : 'info',
      title: scores.ai === 'bad' ? 'Grounding AI parcial' : 'Grounding AI com evidencia',
      evidence: `${resource.domainItems.length} domain item(s), ${governanceSignalCount(resource)} governance signal(s)`,
      canonicalSource: '/schemas/domain + x-domain-governance',
      impact: 'IA tem menos contexto governado para explicar, simular e authorar decisoes semanticas.',
      recommendedFix: 'Melhorar catalogo de dominio, descricoes de negocio e governanca no recurso canonico.',
      validation: 'Consultar /schemas/domain e verificar vocabulario, evidencias e governanca do resourceKey.',
      body: 'O cockpit procura /schemas/domain e governanca derivada do schema.'
    }));

    els.diagnosticsList.innerHTML = diagnostics.map((item) => `
      <article class="diagnostic-item ${item.level}">
        <small>${escapeHtml(item.id)} · ${escapeHtml(item.category)} · experimental</small>
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.body)}</span>
        <dl>
          <dt>Fonte</dt><dd>${escapeHtml(item.canonicalSource)}</dd>
          <dt>Evidencia</dt><dd>${escapeHtml(item.evidence)}</dd>
          <dt>Impacto</dt><dd>${escapeHtml(item.impact)}</dd>
          <dt>Fix canonico</dt><dd>${escapeHtml(item.recommendedFix)}</dd>
          <dt>Validacao</dt><dd>${escapeHtml(item.validation)}</dd>
        </dl>
      </article>
    `).join('');
  }

  function diagnostic(item) {
    return {
      experimental: true,
      level: diagnosticLevel(item.severity),
      evidence: item.evidence || '--',
      ...item
    };
  }

  function diagnosticLevel(severity) {
    if (severity === 'error') {
      return 'bad';
    }
    if (severity === 'warn') {
      return 'warn';
    }
    return 'good';
  }

  function discoveryDiagnostic(endpointKey, count, id, category, okTitle, emptyTitle, canonicalSource, body, impact, recommendedFix) {
    const status = state.endpointStatus[endpointKey];
    if (!status?.ok) {
      return diagnostic({
        id,
        category,
        severity: 'error',
        title: `${endpointKey} indisponivel`,
        evidence: status?.message || 'indisponivel',
        canonicalSource,
        impact,
        recommendedFix: 'Garantir que a superficie canonica esteja publicada e acessivel pela politica de seguranca do host.',
        validation: `Chamar ${canonicalSource} no host e confirmar resposta 2xx.`,
        body: `${body} A leitura atual recebeu: ${status?.message || 'indisponivel'}.`
      });
    }
    return diagnostic({
      id,
      category,
      severity: count > 0 ? 'info' : 'error',
      title: count > 0 ? okTitle : emptyTitle,
      evidence: `${count} item(ns)`,
      canonicalSource,
      impact,
      recommendedFix,
      validation: `Chamar ${canonicalSource} e conferir itens associados ao resourceKey.`,
      body: count > 0 ? `${count} item(ns) encontrados para este resource.` : body
    });
  }

  function optionSourceCount(resource) {
    const schema = resource.filteredSchema;
    if (!schema?.properties) {
      return 0;
    }
    return Object.values(schema.properties).filter((property) => property?.['x-ui']?.optionSource).length;
  }

  function statsSignalCount(resource) {
    return resource.endpoints.filter((endpoint) => endpoint.path && endpoint.path.includes('/stats/')).length;
  }

  function governanceSignalCount(resource) {
    const schema = resource.filteredSchema;
    if (!schema?.properties) {
      return 0;
    }
    return Object.values(schema.properties).filter((property) => property?.['x-domain-governance']).length;
  }

  function exportSignal(resource) {
    const exportOperation = resource.capability?.operations?.export;
    if (exportOperation?.supported) {
      return `yes (${(exportOperation.formats || []).join(', ') || 'formats pending'})`;
    }
    return resource.endpoints.some((endpoint) => endpoint.path && endpoint.path.endsWith('/export')) ? 'endpoint found' : 'not detected';
  }

  function renderEmptyDetail() {
    els.selectedTitle.textContent = 'Nenhum resource descoberto';
    els.selectedSubtitle.textContent = 'Verifique se /schemas/catalog, /schemas/surfaces e /schemas/actions estao disponiveis.';
    setFacts(els.structuralFacts, {});
    setFacts(els.semanticFacts, {});
    setFacts(els.runtimeFacts, {});
    setFacts(els.aiFacts, {});
    els.capabilityMatrix.innerHTML = '<div class="empty-state">Capabilities aparecem quando um resource e selecionado.</div>';
    els.diagnosticsList.innerHTML = '<div class="empty-state">Diagnostics experimentais aparecem com evidencias do host.</div>';
  }

  function setHostStatus(status, text) {
    els.hostStatusDot.className = `status-dot ${status === 'ok' ? 'ok' : status === 'error' ? 'error' : ''}`;
    els.hostStatusText.textContent = text;
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
