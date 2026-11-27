# Receitas Analíticas Verificadas e Endpoints de Inteligência Estatística

Este guia descreve como declarar e operar **receitas analíticas verificadas** (`@VerifiedAnalyticalRecipe`) e os endpoints determinísticos de **inferência estatística** e **varredura de anomalias** sobre recursos metadata-driven baseados em `AbstractResourceQueryController`.

> **Escopo:** Todos os exemplos deste guia usam recursos corporativos neutros (por exemplo `workforce.assignments` e `/api/workforce/assignments`) compatíveis com `StatsFieldRegistry`, `ResourceFilterAccessScope` e consumidores `@praxisui/charts` / `@praxisui/ai`.

---

## 1. Receitas Analíticas Verificadas (`@VerifiedAnalyticalRecipe`)

A anotação `@VerifiedAnalyticalRecipe` (repetível via `@VerifiedAnalyticalRecipes`) ancora indicadores analíticos governados diretamente no código do recurso, evitando que clientes ou agentes de IA precisem inferir métricas, campos ou critérios obrigatórios de negócio.

### Principais atributos

| Atributo | Finalidade |
| --- | --- |
| `id` / `title` / `description` / `intent` | Identificador estável (`kebab-case`), título curto e intenção de negócio usada em catálogos e IA. |
| `operation` | Família estatística: `AnalyticsOperation.GROUP_BY`, `TIMESERIES`, `DISTRIBUTION` ou `COMPARISON`. (`INFERENCE` e `ANOMALIES` não são executadas como receita via `/stats/recipes/{recipeId}`). |
| `field` / `dimensions` / `metric` / `metricField` / `metricAlias` | Campos canônicos registrados no `StatsFieldRegistry` do recurso e operação de agregação (`COUNT`, `DISTINCT_COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, etc.). |
| `lockedCriteria` | Critérios de filtro (`@FilterCriterion`) aplicados **incondicionalmente no servidor por último** durante a compilação da consulta; nem clientes HTTP nem agentes de IA podem removê-los ou ampliá-los. |
| `adjustableParams` | Parâmetros limitados (`@AnalyticalRecipeParameter`) que o chamador pode enviar na execução (`parameters`), sujeitos à allowlist de `name`, `filterField`, `dataType`, `required`, `defaultValue`, `allowedValues`, `minValue` e `maxValue`. |
| `governanceTier` / `version` / `domainTags` | Nível de certificação (`AnalyticalRecipeGovernanceTier`: `CERTIFIED`, `OPERATIONAL`, `EXPLORATORY`), versão semântica e tags incluídas no digest determinístico. |

Exemplo neutro de declaração em controller:

```java
@VerifiedAnalyticalRecipe(
    id = "active-headcount-by-department",
    title = "Quadro Ativo por Unidade",
    description = "Distribui o efetivo ativo por unidade organizacional com filtro travado para status ACTIVE.",
    intent = "headcount ativo por departamento",
    operation = AnalyticsOperation.GROUP_BY,
    field = "departmentName",
    metric = StatsMetric.COUNT,
    orderBy = StatsBucketOrder.VALUE_DESC,
    limit = 12,
    governanceTier = AnalyticalRecipeGovernanceTier.CERTIFIED,
    lockedCriteria = {
        @FilterCriterion(field = "status", values = { "ACTIVE" })
    },
    adjustableParams = {
        @AnalyticalRecipeParameter(
            name = "limit",
            dataType = "integer",
            defaultValue = "12",
            minValue = "1",
            maxValue = "50",
            description = "Quantidade máxima de unidades exibidas."
        )
    },
    version = "1.0.0"
)
```

Para receitas geradas programaticamente fora do controller (por exemplo a partir de catálogos configuráveis do host), registre um bean que implemente `VerifiedAnalyticalRecipeProvider`.

---

## 2. Descoberta e Execução Fail-Closed (`/stats/recipes`)

`AbstractResourceQueryController` publica automaticamente as receitas descobertas em múltiplas superfícies:

- **OpenAPI / Schema (`x-ui.analytics.verifiedRecipes` e `/schemas/domain`):** expõe os metadados completos, parâmetros ajustáveis e indicadores de critérios travados.
- **HATEOAS (`stats-recipes`):** quando o recurso possui receitas anotadas ou via `VerifiedAnalyticalRecipeProvider`, o link `stats-recipes` aponta para `GET /{resource}/stats/recipes`.
- **`GET /{resource}/stats/recipes`:** retorna apenas as receitas cuja operação estatística subjacente (`statsGroupBy`, `statsTimeSeries`, `statsDistribution`, `statsComparison`) está disponível no escopo de availability atual.
- **`POST /{resource}/stats/recipes/{recipeId}`:** executa a receita selecionada em modo **fail-closed**, recebendo `VerifiedRecipeExecutionRequest(parameters, timezone)`:
  1. Verifica existência da receita (`404 Not Found` se desconhecida);
  2. Verifica availability da operação estatística (`assertCollectionOperationAvailable`) e governança da receita (`enforceVerifiedRecipeGovernance`);
  3. Compila os filtros no servidor combinando os `adjustableParams` validados e mesclando `lockedCriteria` por último; qualquer parâmetro não declarado, fora da faixa ou conflitando com critério travado retorna `400 Bad Request`;
  4. Retorna `VerifiedRecipeExecutionResponse` contendo exatamente um payload tipado (`groupBy`, `timeSeries`, `distribution` ou `comparison`) e o carimbo imutável `VerifiedRecipeProvenance` (`recipeId`, `title`, `resourceKey`, `version`, `governanceTier`, `provenanceDigest`, `operation`, `lockedFilters`, `appliedParameters`, `adjustableParamNames`, `endpointPath`).

---

## 3. Inferência Estatística e Varredura de Anomalias (`/stats/inference` e `/stats/anomalies`)

Por padrão *fail-closed*, `AbstractBaseQueryResourceService#getInferenceStatsSupportMode()` e `#getAnomalyStatsSupportMode()` retornam **`StatsSupportMode.DISABLED`** (`501 Not Implemented` quando invocados). Para habilitar os endpoints em um recurso que já publica `StatsFieldRegistry` (e com `ResourceIntelligenceExecutor` configurado no host), o service deve sobrescrever explicitamente esses métodos retornando `StatsSupportMode.AUTO` ou `StatsSupportMode.ENABLED`.

### Endpoints e contratos

| Operação | Endpoint HTTP | Request DTO | Verificação de Availability |
| --- | --- | --- | --- |
| Inferência por coortes | `POST /{resource}/stats/inference` | `InferenceStatsRequest<FD>` | `"statsInference"` (`403` quando indisponível, `501` quando desabilitado no recurso, `503` quando o motor externo/executor está indisponível) |
| Varredura de anomalias | `POST /{resource}/stats/anomalies` | `AnomalyScanStatsRequest<FD>` | `"statsAnomalies"` (`403` quando indisponível, `501` quando desabilitado, `503` quando o motor está indisponível) |

### Invariantes obrigatórios de segurança e rigor estatístico

1. **Escopo de acesso e filtro tipado (`FD`):** O filtro `request.filter()` passa pelo `ResourceFilterAccessScope` resolvido no servidor antes de extrair qualquer dado tabular. O parâmetro `datasetKey`, quando enviado, deve corresponder exatamente ao `resourceKey` canônico do recurso (`IllegalArgumentException` -> `400 Bad Request`).
2. **Piso de k-anonimato imposto pelo servidor:** Em `toInferenceRequest(canonicalResourceKey, effectiveMinBucketCount)` e `toAnomalyScanRequest(canonicalResourceKey, effectiveMinBucketCount)`, o limiar mínimo de k-anonimato por coorte nunca pode ser reduzido abaixo do piso governado pelo servidor. Grupos menores são suprimidos (`suppressedCellCount` / `suppressionStatus`).
3. **Parâmetros padronizados:**
   - **`InferenceStatsRequest<FD>`:** `filter`, `requestId`, `datasetKey`, `tests` (`List<InferenceTestKind>`), `groupByField`, `expectedField`, `observedField`, `residualField`, `variableOrRetroactiveEligible`, `alpha` (padrão `0.05`), `materialityThreshold`, `minBucketCount`, `seed`, `asOfDate`.
   - **`AnomalyScanStatsRequest<FD>`:** `filter`, `requestId`, `datasetKey`, `methods` (`Set<AnomalyMethod>`), `columnMapping` (`AnomalyColumnMapping.canonical()` quando omitido), `alpha` (`0.05` para controle BH FDR), `materialityThreshold`, `madThreshold` (`3.5` para escore robusto MAD), `minCohortSize` (`15`), `minGroupSize` (`20`), `enableBenford` (habilitado automaticamente quando omitido e `methods` contém `NIGRINI_BENFORD`; `false` caso contrário), `minBenfordSamples` (`500`), `minBucketCount`, `maxFindings` (`200`), `seed`, `asOfDate`.
4. **Sem fallback fabricado:** Ausência de executor configurado falha fechado com `503 Service Unavailable` / estado `ENGINE_UNAVAILABLE`, preservando o limite de registros filtrados (`413 Payload Too Large` quando excedido).

---

## 4. Simulação Estocástica em Duas Camadas e Busca Contrafactual (`/stats/simulation` e `/stats/counterfactual`)

Por padrão *fail-closed*, `AbstractBaseQueryResourceService#getSimulationStatsSupportMode()` e `#getCounterfactualStatsSupportMode()` retornam **`StatsSupportMode.DISABLED`**. Recursos neutros (ex.: `workforce.budgets`) habilitam os fluxos síncrono e assíncrono via `StatsSupportMode.AUTO` / `ENABLED` com os seguintes contratos:

| Operação | Endpoint HTTP | Request DTO | Orquestrador / Validador SPI |
| --- | --- | --- | --- |
| Simulação síncrona | `POST /{resource}/stats/simulation` | `StochasticSimulationStatsRequest<FD>` | `StochasticSimulationOrchestrator` + `AnchorScenarioRevalidator` (`toleranceRatio` padrão `0.02d`) |
| Submissão assíncrona de simulação | `POST /{resource}/stats/simulation/jobs` | `StochasticSimulationStatsRequest<FD>` | `StochasticSimulationOrchestrator#submitStochasticSimulationJob(...)` -> `202 Accepted` (`Location` + `_links`) |
| Busca contrafactual síncrona | `POST /{resource}/stats/counterfactual` | `CounterfactualStatsRequest<FD>` | `CounterfactualSearchOrchestrator` + `CounterfactualRuleValidator` |
| Submissão assíncrona contrafactual | `POST /{resource}/stats/counterfactual/jobs` | `CounterfactualStatsRequest<FD>` | `CounterfactualSearchOrchestrator#submitCounterfactualJob(...)` -> `202 Accepted` |

### Invariantes da camada de serviço e segurança

1. **`ResourceFilterAccessScope.Mode.DENIED` falha fechado com `403 Forbidden`** antes de derivar pontos ou iniciar orquestração.
2. **Derivação no servidor (`resolveSimulationModelPoints` / `resolveCounterfactualRequest`):** executa dentro de transação curta somente leitura (`resolveReadOnlyStatsTransactionTemplate()`) antes de chamadas remotas. Quando `resolveSimulationModelPoints` retorna lista vazia não nula, o serviço lança `INSUFFICIENT_SAMPLE_SIZE` (`422`) e nunca aceita fallback para `modelPoints` do cliente.
3. **Revalidação determinística (`0.02d` = 2%):** tanto na orquestração via `StochasticRevalidationPolicy` quanto na revalidação inline, qualquer célula suprimida por k-anonimato bloqueia revalidação exata (`K_ANONYMITY_SUPPRESSED`, `422`), âncoras não revalidadas são rejeitadas (`CONTRACT_VERSION_MISMATCH`, `502`/`422`), e `getFailOnAnchorDriftExceeded() == true` abre o circuito com `DRIFT_CIRCUIT_OPEN` (`409`).
4. **Endpoints stateful de jobs vs CSRF:** rotas `/stats/simulation/jobs` e `/stats/counterfactual/jobs` criam estado e pertencem a `PraxisMetadataRequestMatchers.intelligenceJobPostPatterns(...)` (e `/api/praxis/intelligence/jobs/*` em `intelligenceJobPollAndCancelPatterns(...)`), nunca misturadas em `readOnlyPostPatterns()`.
