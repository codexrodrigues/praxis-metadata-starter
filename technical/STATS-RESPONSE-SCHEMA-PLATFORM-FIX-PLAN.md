# Stats Response Schema Platform Fix Plan

## Status do documento

Este arquivo virou registro histórico de uma correção de plataforma já
implementada e validada. Ele é útil para auditoria técnica e para reconstruir a
causa raiz original do problema de `stats/*`.

Não use este texto como fonte primária do contrato atual de stats; o contrato
vivo está no código, nos testes e nos guias/specs sincronizados do starter.

## Status em 2026-03-21

- Fase 1 concluída: `AbstractCrudController` publica `response schema` explícito para `group-by`, `timeseries` e `distribution`.
- Fase 2 concluída: `ApiDocsController` foi endurecido para seleção consistente de `content` e resolução de wrappers de stats.
- Fase 3 concluída: `ApiDocsController` e `DomainCatalogController` passaram a compartilhar a política de resolução via `OpenApiDocsSupport`.
- Fase 5 concluída: o starter ganhou cobertura cruzada entre `catalog` e `/schemas/filtered`.
- Fase 6 concluída: o `quickstart` foi validado com `StatsSchemaSmokeHttpTest`, confirmando `BUILD SUCCESS` para request/response schema e coerência de `schemaLinks`.
- Fase 7 auditada: não foi encontrado consumidor ativo no monorepo degradando `response schema` estrutural de `/schemas/filtered` para `responseSchema` de `/schemas/catalog`; o fluxo correto segue sendo `catalog` para discovery e `filtered` para estrutura.
- Endurecimento adicional no consumidor backend de AI: `SchemaRetrievalService` permanece acoplado apenas a `/schemas/filtered`, e a documentação do `praxis-config-starter` agora explicita que `/schemas/catalog` não substitui resolução estrutural.

## Objetivo

Corrigir em nível de plataforma a inconsistência entre:

- o contrato OpenAPI publicado para `POST /stats/group-by`
- o contrato OpenAPI publicado para `POST /stats/timeseries`
- o contrato OpenAPI publicado para `POST /stats/distribution`
- o comportamento de `/schemas/filtered`
- o comportamento de `/schemas/catalog`

A meta não é apenas fazer o frontend funcionar. A meta é restaurar um contrato
canônico, verificável e consistente entre a documentação OpenAPI, os schemas
derivados e os consumidores metadata-driven.

## Diagnóstico Consolidado

### Fato 1: os DTOs canônicos já existem

O starter já possui DTOs explícitos e padronizados para stats:

- `GroupByStatsResponse`
- `TimeSeriesStatsResponse`
- `DistributionStatsResponse`

Também já existe o wrapper canônico `RestApiResponse<T>`.

Ou seja, o problema não é falta de modelagem de domínio.

### Fato 2: o vínculo da operação com o schema de resposta está incompleto

Os endpoints de stats em `AbstractCrudController` publicam exemplos no `200`,
mas não publicam explicitamente o `schema` da resposta no conteúdo OpenAPI.

Na prática, isso permite que o OpenAPI final preserve:

- `components.schemas.RestApiResponseGroupByStatsResponse`
- `components.schemas.GroupByStatsResponse`
- exemplos operacionais de resposta

mas perca o elo canônico:

- `paths -> ... -> responses -> 200 -> content -> application/json -> schema`

Quando esse elo some, `/schemas/filtered` deixa de conseguir resolver
`schemaType=response`.

### Fato 3: a falha é sistêmica na família `stats/*`

A investigação mostrou que isso não afeta só `vw-perfil-heroi/group-by`.
O padrão é repetido em toda a família `stats/*`.

Na superfície pública verificada, os casos sem `schema` de resposta estavam
concentrados exclusivamente em `stats/*`.

### Fato 4: `/schemas/catalog` e `/schemas/filtered` não estão operando com a mesma garantia contratual

Hoje o catálogo:

- pode enxergar `responseSchema`
- sempre publica `schemaLinks.response`

mas o filtered:

- exige que o schema seja resolvível no documento OpenAPI selecionado
- falha com `400` quando esse vínculo não existe

Isso significa que o catálogo pode expor um link contratual que o endpoint
estrutural não consegue honrar.

### Fato 5: há divergência na estratégia de resolução do documento OpenAPI

Os dois controladores não resolvem grupo/documento da mesma forma:

- `ApiDocsController` resolve grupo a partir do path real solicitado
- `DomainCatalogController` resolve grupo com uma estratégia mais ampla

Mesmo que isso não fosse a causa raiz principal, isso já é um problema
arquitetural porque permite inconsistência entre duas superfícies derivadas do
mesmo contrato.

## Objetivos da Correção

### Objetivo Primário

Garantir que todo endpoint `stats/*` publique um `response schema` OpenAPI
explícito, estável e resolvível por `/schemas/filtered`.

### Objetivo Secundário

Garantir que `/schemas/catalog` e `/schemas/filtered` derivem suas respostas a
partir de premissas compatíveis e testadas.

### Objetivo de Plataforma

Garantir a seguinte propriedade:

- se `/schemas/catalog` expõe `schemaLinks.response` para uma operação
- então `/schemas/filtered` deve conseguir resolver esse `response schema`

Essa propriedade precisa virar contrato de plataforma, não convenção informal.

## Princípios de Solução

### 1. Corrigir na origem do contrato

A solução correta começa na publicação OpenAPI dos endpoints de stats, não no
frontend e não em fallbacks oportunistas do catálogo.

### 2. Evitar inferência onde o contrato pode ser explícito

`findResponseSchema(...)` pode ter heurísticas defensivas, mas stats não deve
depender de inferência por nome ou estrutura parcial. O contrato deve ser
explícito.

### 3. Tornar as superfícies derivadas coerentes entre si

`/schemas/catalog` e `/schemas/filtered` podem ter finalidades diferentes, mas
não podem divergir sobre a existência do schema estrutural da mesma operação.

### 4. Proteger a correção com testes de consistência cruzada

Não basta testar cada endpoint isoladamente. É necessário testar a coerência
entre:

- OpenAPI da operação
- `/schemas/catalog`
- `/schemas/filtered`

## Escopo da Correção

### Incluído

- endpoints `stats/group-by`, `stats/timeseries` e `stats/distribution`
- publicação OpenAPI desses endpoints no starter
- resolução de `response schema` em `/schemas/filtered`
- coerência contratual em `/schemas/catalog`
- testes unitários/integrados do starter
- validação downstream no quickstart

### Excluído do primeiro lote

- redesign dos payloads de stats
- mudança semântica dos DTOs de stats
- novos endpoints analíticos
- features de frontend além da remoção de workarounds temporários

## Plano de Implementação

### Fase 0: Congelar o contrato desejado

Definir explicitamente o contrato-alvo para cada operação de stats:

- `group-by` retorna `RestApiResponse<GroupByStatsResponse>`
- `timeseries` retorna `RestApiResponse<TimeSeriesStatsResponse>`
- `distribution` retorna `RestApiResponse<DistributionStatsResponse>`

Também registrar a expectativa de documentação:

- `requestBody.content.application/json.schema` presente
- `responses.200.content.application/json.schema` presente
- `examples` preservados
- wrappers e DTOs presentes em `components.schemas`

Saída esperada da fase:

- lista de asserts canônicos a serem codificados nos testes

### Fase 1: Corrigir a publicação OpenAPI na origem

Ajustar `AbstractCrudController` para que cada `@ApiResponse(responseCode = "200")`
de stats publique explicitamente o schema do wrapper correto.

Direção recomendada:

- declarar `mediaType = "application/json"`
- declarar `schema = @Schema(implementation = ...)`
- preservar os `examples` já existentes no mesmo `@Content`

Ponto crítico:

- a anotação não deve degradar os exemplos operacionais já publicados
- a anotação não deve colapsar o tipo em `Object.class`
- a anotação deve continuar gerando wrappers específicos e navegáveis em
  `components.schemas`

Arquivos-alvo:

- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`

### Fase 2: Endurecer a resolução de `response schema` em `/schemas/filtered`

Mesmo com a publicação corrigida, o `ApiDocsController` deve ficar mais robusto.

Trabalho recomendado:

- revisar `findResponseSchema(...)`
- preferir seleção de content type via helper equivalente ao usado no catálogo
- suportar de forma consistente `application/json`, `*/*` e primeiro content
  disponível quando seguro
- manter prioridade para `x-ui.responseSchema` quando explicitamente publicado

Objetivo:

- reduzir fragilidade do endpoint derivado sem transformá-lo em resolvedor
  heurístico excessivo

Arquivos-alvo:

- `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`

### Fase 3: Unificar a política de resolução entre catálogo e filtered

Hoje os dois controladores podem consultar documentos/grupos diferentes para a
mesma operação.

Direção recomendada:

- extrair uma política compartilhada de resolução de grupo/documento OpenAPI
- fazer `DomainCatalogController` usar a mesma lógica de path real quando
  `pathFilter` estiver presente
- reduzir o espaço para divergência entre grupo específico e fallback global

Opção preferida:

- criar um componente compartilhado para:
  - resolver grupo a partir do path
  - buscar documento OpenAPI com fallback
  - selecionar content node preferido

Arquivos-alvo:

- `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`
- `src/main/java/org/praxisplatform/uischema/controller/docs/DomainCatalogController.java`
- possivelmente um novo helper compartilhado em `controller/docs` ou `util`

### Fase 4: Endurecer o contrato de `/schemas/catalog`

O catálogo não deve publicar `schemaLinks.response` cegamente.

Duas opções:

- opção preferida: só publicar o link quando o response schema for
  resolvível/consistente
- opção aceitável: publicar sempre, mas adicionar teste obrigatório de
  resolubilidade e falhar a build quando isso quebrar

Recomendação:

- manter os links, mas fazer a suíte de testes garantir a consistência
- se durante a implementação isso ainda deixar janelas de falso positivo,
  condicionar a emissão do link à resolubilidade real

Arquivos-alvo:

- `src/main/java/org/praxisplatform/uischema/controller/docs/DomainCatalogController.java`

### Fase 5: Cobertura de testes

#### 5.1 Testes de `ApiDocsController`

Adicionar cenários para:

- `schemaType=response` com `group-by`
- `schemaType=response` com `timeseries`
- `schemaType=response` com `distribution`
- response com `examples` e `schema` explícito
- content type alternativo quando aplicável
- fallback controlado para wrapper `RestApiResponse<...>`

Também adicionar um teste negativo:

- operação com examples, mas sem response schema explícito
- comportamento esperado documentado de forma clara

#### 5.2 Testes de `DomainCatalogController`

Adicionar cenários para:

- stats response schema publicado corretamente
- `schemaLinks.response` consistente com a operação
- comportamento quando o documento não permite resolver response schema

#### 5.3 Teste de consistência cruzada

Adicionar um teste de integração do starter com esta propriedade:

- para uma operação de stats no catálogo
- o `schemaLinks.response` emitido deve ser resolvido com sucesso por
  `/schemas/filtered`

Esse é o teste mais importante do lote porque fecha a regressão estrutural.

Arquivos-alvo:

- `src/test/java/org/praxisplatform/uischema/controller/docs/ApiDocsControllerTest.java`
- `src/test/java/org/praxisplatform/uischema/controller/docs/DomainCatalogControllerTest.java`
- novo teste integrado, se necessário

### Fase 6: Validação downstream no quickstart

Depois da correção no starter:

- atualizar a dependência consumida pelo quickstart
- validar que os endpoints reais `stats/*` continuam operando
- validar que os docs OpenAPI reais agora publicam `response schema`
- validar que `/schemas/catalog` e `/schemas/filtered` convergem nos casos
  `funcionarios`, `vw_perfil_heroi` e `vw_indicadores_incidentes`

Validações mínimas:

- `GET /schemas/filtered?...schemaType=request` continua `200`
- `GET /schemas/filtered?...schemaType=response` passa a `200`
- `GET /schemas/catalog?...stats...` continua trazendo request/response/examples
- os links de schema do catálogo passam a ser efetivamente navegáveis

Arquivos/superfícies-alvo:

- `praxis-api-quickstart` consumindo a versão corrigida do starter
- OpenAPI público do quickstart

### Fase 7: Remoção de contingências em consumidores

Se houver fallback temporário no frontend para usar `responseSchema` do catálogo
quando `/schemas/filtered` falhar, esse fallback deve ser tratado como
contingência transitória.

Após a correção de plataforma:

- remover ou reduzir esse fallback
- voltar a tratar `/schemas/filtered` como fonte estrutural canônica
- manter `/schemas/catalog` como fonte documental/discovery

Resultado da auditoria de 2026-03-21:

- `praxis-ui-angular` na feature `stats-examples` já usa `/schemas/filtered` para `request/response` e `/schemas/catalog` apenas para discovery/exibição.
- `praxis-config-starter` usa `SchemaRetrievalService` apontando somente para `/schemas/filtered`.
- Não foi identificado fallback estrutural ativo a remover neste lote; a ação correta foi endurecer o contrato e documentar explicitamente a separação de responsabilidades.

## Backlog Técnico por Arquivo

### Starter

- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java`
  - explicitar `schema` do `200` em `group-by`, `timeseries`, `distribution`
  - preservar `examples`

- `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`
  - endurecer `findResponseSchema(...)`
  - alinhar seleção de content type
  - reduzir dependência de heurísticas frágeis

- `src/main/java/org/praxisplatform/uischema/controller/docs/DomainCatalogController.java`
  - alinhar resolução de grupo/path
  - revisar emissão de `schemaLinks.response`
  - manter coerência com resolubilidade real

- `src/test/java/org/praxisplatform/uischema/controller/docs/ApiDocsControllerTest.java`
  - cobrir stats response reais

- `src/test/java/org/praxisplatform/uischema/controller/docs/DomainCatalogControllerTest.java`
  - cobrir consistência de links e response schema para stats

- `src/test/java/...`
  - incluir teste de consistência cruzada entre catálogo e filtered

### Quickstart

- atualizar a versão do starter
- rerodar smoke de stats
- validar docs reais e endpoints derivados

## Ordem Recomendada de Execução

1. adicionar testes que reproduzem a falha atual
2. corrigir a anotação OpenAPI em `AbstractCrudController`
3. corrigir/fortalecer `ApiDocsController`
4. alinhar `DomainCatalogController`
5. adicionar teste de consistência cruzada
6. validar no quickstart
7. só então ajustar consumidores se necessário

Essa ordem é importante porque força a correção a nascer no contrato e não no
consumidor.

## Critérios de Aceite

### Contrato OpenAPI

- cada endpoint `stats/*` publica `responses.200.content.application/json.schema`
- os `examples` continuam presentes
- os wrappers específicos continuam em `components.schemas`

### `/schemas/filtered`

- `schemaType=request` continua resolvendo
- `schemaType=response` passa a resolver `group-by`, `timeseries` e
  `distribution`

### `/schemas/catalog`

- continua expondo request schema, response schema e operation examples
- `schemaLinks.response` não aponta mais para um endpoint estrutural quebrado

### Consistência

- existe teste garantindo que links de schema publicados pelo catálogo são
  resolvíveis

### Downstream

- quickstart validado com recursos reais
- consumidores de frontend deixam de depender de workaround para stats response

## Riscos

### Risco 1: SpringDoc gerar wrappers inesperados

Ao explicitar `schema` no `@ApiResponse`, o SpringDoc pode gerar nomes de
wrapper diferentes dos atuais.

Mitigação:

- proteger por testes de snapshot/asserts estruturais
- validar nomes reais publicados em `components.schemas`

### Risco 2: perda involuntária de examples

Ao mexer em `@Content`, os exemplos podem desaparecer ou ser sobrescritos.

Mitigação:

- asserts específicos para `operationExamples`
- validação de catálogo e filtered após a mudança

### Risco 3: resolver divergência entre docs públicos e internos

Se `app.openapi.internal-base-url` estiver apontando para uma superfície
OpenAPI diferente da pública, a inconsistência pode continuar mascarada.

Mitigação:

- validar explicitamente a origem usada server-side
- documentar essa dependência operacional
- garantir que os testes do starter não dependam de diferenças ambientais

## Definition of Done

- a família `stats/*` publica response schema OpenAPI explícito
- `/schemas/filtered` resolve request e response para stats
- `/schemas/catalog` e `/schemas/filtered` ficam coerentes para stats
- existe teste de consistência cruzada cobrindo `schemaLinks.response`
- quickstart validado com recursos reais
- workarounds temporários em consumidores ficam desnecessários ou claramente
  marcados para remoção

## Próxima PR Recomendada

Escopo da primeira PR:

- testes reprodutores no starter
- correção de `AbstractCrudController`
- ajuste mínimo de `ApiDocsController`
- teste de consistência cruzada básico

Escopo da segunda PR:

- refatoração compartilhada da resolução de documento/grupo
- endurecimento adicional do catálogo
- validação downstream no quickstart
