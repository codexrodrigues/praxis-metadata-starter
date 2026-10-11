# Praxis Metadata Starter Docs

Documentacao publica do `praxis-metadata-starter`.

Esta home orienta pessoas, LLMs e indexadores para a semantica atual da
plataforma.

## O que este site publica

- a trilha principal do backend canonico resource-oriented
- o contrato estrutural e documental publicado pelo starter
- a semantica de `domain`, `surfaces`, `actions` e `capabilities`
- a operacao canonica de exportacao de colecao
- referencia tecnica complementar em Javadoc

## SDK em desenvolvimento

- [Autoridade de capacidade candidata B5b.1a](spec/BULK-CAPACITY-AUTHORITY.html): núcleo com 42 provas focais de direitos globais e migração; banco dedicado é pré-requisito de provisionamento; não integra a rc.154 nem aceita jobs, ASYNC ou HTTP202.
- [Instalação local candidata B5b.1b.A](spec/BULK-CAPACITY-INSTALLATION.html): atestado e direitos reais com 97 casos PostgreSQL compostos; runtime sem history/latch SELECT, bootstrap durável sem healing e quatro processos owner. Sem ocupação/jobs, ASYNC/202/READY, restore-safe, adoção na rc.154 ou Angular.

- [Lifecycle governado candidato R2](spec/BULK-OPERATION-LIFECYCLE.html): fotografia OpenAPI imutável, publicação global/operação V14/V15 e reconciliação explícita; prova HTTP do host e adoção pública são gates separados.

- [Ocupação local protegida B5b.1b.B e caracterização de clone C1a](spec/BULK-CAPACITY-OCCUPANCY.html): slots, histórico, QUEUED/claim e lifecycle no ledger existente. Fonte interna integrada pelos PR242/243, com provas de capacidade global, quatro JVMs de runtime e cópia real do banco. C1a demonstra uma limitação: cercar a origem não impede a cópia de executar. Retirada controlada43 e quarentena externa autenticada C0-02 acrescentam provas delimitadas (HBA reload/JVM restart, cópia TEMPLATE e credenciais distintas); C0-03 acrescenta interlock privado cooperativo, três crashes e CAS entre duas JVMs; não fecha custódia monotônica. Essas provas não fecham proteção integral de clone/restore, continuidade externa, worker ou composição operacional. Esse núcleo não integra o artefato público rc.154 e não certifica ASYNC público, HTTP202, adoção do host, backend completo ou Angular.
- [Núcleo durável de execução](spec/BULK-DURABLE-EXECUTION.html): reserva, unidade transacional, ledger de capacidade V5, retenção/tombstones e recuperação; fundação sem exposição HTTP nem adoção completa no host.
- [Evidência protegida da avaliação](spec/BULK-EVALUATION-EVIDENCE.html): fatos/plano por alvo vinculados à proposta, sem decisão de elegibilidade.
- [Decisão H1b de leitura](spec/BULK-H1B-READ-MODEL.html): manifest privado V8, projeção física segura V9, reader autorizado de resultados de proposta e continuação G3b, resumo autorizado de execução e tombstone G3c-a, composição paginada RS4 G3c-b e proposta RS1 com projeção explícita do domínio; a biblioteca não cria endpoint HTTP.

- [Projeção P1 de action bulk](spec/BULK-ACTION-PROJECTION.html): sete referências UI por role, composição única e fence READY; não concede execução/autorização.
- [Entrada do protocolo de operações em lote](spec/BULK-PROTOCOL-INPUT.html): API Java, sem runtime executável ou integração de discovery neste incremento.

- [Propostas, resultados e fingerprint de intenção](spec/BULK-PROTOCOL-RESULTS.html): snapshots e schemas de SDK, sem execução durável.

- [Binding canônico de operações](spec/CANONICAL-OPERATION-BINDING.html): unicidade, vínculo operação/recurso/método e DTO de request concreto do handler MVC.

- [Leitura estrita de request](spec/CANONICAL-REQUEST-SCHEMA.html): schema da operação, dialeto e limites da composição backend.
- [Persistência protegida de propostas](spec/BULK-PROPOSAL-STORAGE.html): captura EXPLICIT/SYNC publicada na rc.153 e captura QUERY sob admissão opaca na rc.154, restrita a `UNIFORM_UPDATE/SYNC/PER_ITEM` com teto de até 200; o SDK não cria endpoint nem autoriza o domínio do host.
- [Infraestrutura transacional de lote](spec/BULK-EXECUTION-INFRASTRUCTURE.html): vínculo JDBC/JPA explícito, sem store ou DDL.

- [Campos editáveis em lote](spec/BULK-EDITABLE-FIELDS.html): annotation e compilação estrutural de SET/CLEAR.
- [Composição estrutural CRUD](spec/BULK-CRUD-STRUCTURE.html): fonte PUT e DTO reais, campos protegidos e allowlists; sem disponibilidade operacional de update bulk.
- [Composição operacional CRUD](spec/BULK-CRUD-OPERATIONS.html): quatro identidades UPDATE por modo/atomicidade no SDK público; a rc.154 publica QUERY somente para `UNIFORM_UPDATE/SYNC/PER_ITEM`, mantendo `bulk-update`. `PER_ITEM_UPDATE`, ATOMIC e ASYNC não recebem QUERY; autorização e mutação continuam no host.
- [Guia de composição de lote](technical/BULK-COMPOSITION-GUIDE.html): escolha de modalidade, fronteira Metadata/Config/host, sequência de composição e limites comprovados da rc.154.

## Comece por objetivo

### Quero adotar o baseline atual

- [Guides hub](guides/index.html)
- [Architecture overview](architecture-overview.html)
- [Java package overview](packages-overview.html)
- [UI Schema concept](concepts/ui-schema.html)
- [Conformance](spec/CONFORMANCE.html)
- [Options e option-sources](guides/OPTIONS-ENDPOINT.html)
- [RFC - x-ui.optionSource](spec/x-ui-option-source-rfc.html)
- [Exportacao de colecoes](guides/COLLECTION-EXPORT.html)
- [RFC - GraphQL Support](technical/GRAPHQL-SUPPORT-RFC.html)

### Quero gerar uma aplicacao nova

1. [Guia 01 - Backend - Aplicacao Nova](guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.html)
2. [Guia 02 - Backend - Recurso Metadata-Driven](guides/GUIA-02-AI-BACKEND-CRUD-METADATA.html)
3. [Guia 04 - Quando usar Resource, Surface, Action e Capability](guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.html)
4. [Guia 05 - Do CRUD ao Contrato Semantico](guides/GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.html)
5. [Guia 06 - Redacao Semantica de Annotations para IA](guides/GUIA-06-REDACAO-SEMANTICA-DE-ANNOTATIONS-PARA-IA.html)
6. [Semantic Metadata Authoring](guides/SEMANTIC-METADATA-AUTHORING.html)

### Quero integrar um runtime Angular

- [Guia 03 - Frontend - Angular CRUD Completo](guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.html)
- [Checklist de Validacao](guides/CHECKLIST-VALIDACAO-IA.html)

### Quero referencia tecnica Java

- [Javadoc publico](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)
- [Indice humano do Javadoc](api/index.html)
- [Documentacao tecnica](technical/index.html)

## Baseline Atual

O baseline canonico atual do starter e:

- `resource`
- `surface`
- `action`
- `capability`
- HATEOAS

Isso significa:

- `/schemas/filtered` segue como contrato estrutural
- `/schemas/catalog` segue como catalogo documental
- `/schemas/domain` publica vocabulario, aliases, evidencias, governanca AI-operable e option sources registry-wide que nao possuem campo estrutural correspondente
- `/schemas/surfaces` e `/schemas/actions` publicam discovery semantico
- `/{resource}/capabilities` agrega as capacidades do recurso sem redefinir o contrato estrutural
- `canonicalOperations` descreve suporte estrutural; `operations` materializa CRUD, query, options, stats e export com availability contextual
- `/{resource}/capabilities` tambem publica `stats.fields` quando o service declara `StatsFieldRegistry` e habilita `StatsSupportMode`, permitindo que runtimes escolham dimensoes e metricas de charts antes de executar stats
- `POST /{resource}/export` executa exportacao de colecao a partir de escopo, selecao, filtros, ordenacao e campos; o resultado pode ser binario inline ou `202 Accepted` com `status=deferred`, `downloadUrl` e `jobId`
- detalhes de exportacao em `/capabilities` sao derivados do suporte real do service e podem publicar `formats`, `scopes`, `maxRows` e `async`
- resultados inline podem publicar headers de linhas, truncamento, limite efetivo e warnings para UI corporativa

## Regra De Leitura

Quando houver duvida sobre a superficie publicada:

- priorize os guias desta home
- priorize `architecture-overview`
- use a trilha desta home como referencia principal do baseline atual

- [Administração local de capacidade (candidato C0-04)](spec/BULK-CAPACITY-LOCAL-ADMINISTRATION.html): INSPECT/FENCE com OWNER e vínculo explícitos; provas privadas não são publicação ou adoção produtiva.
- [Estado do worker durável B5b.2](technical/BULK-DURABLE-WORKER-B5B2-STATUS.html): candidato privado com primeira bateria aceita; matriz de fila, composição pública e fechamento do backend ainda pendentes.

- [Migração owner e cutover do worker durável — candidato privado B5b.2](technical/BULK-DURABLE-WORKER-OWNER-UPGRADE.html).

- [Composição explícita do worker — candidato B5b.3](technical/BULK-WORKER-EXPLICIT-COMPOSITION.html): vínculo canônico e callbacks por unidade; provas focais e consumidor JAR privado executados, revisão/integração e publicação pendentes, sem ingresso HTTP ASYNC.

- [Adoção de operações em lote por owner gerenciado](spec/BULK-MANAGED-OWNER-ADOPTION-PLAN.html): candidato PostgreSQL17+, linhagem imutável, bootstrap transacional e provas focais; publicação e adoção hospedada pendentes.
