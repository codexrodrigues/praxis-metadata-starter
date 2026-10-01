# Changelog - praxis-metadata-starter

All notable changes to this module will be documented in this file.

## Unreleased

- Candidato P3b-S1: `BulkResourceOperations` declara uma única fonte PUT por recurso com `updateSourceOperationId` e `protectedUpdateFields`; `UPDATE_SOURCE` materializa a identidade explícita no PUT herdado ou sobrescrito. Os descriptors de update capturam DTO, schema, projeção e allowlists no mesmo snapshot OpenAPI. O framing `praxis.bulk.structure/4` pertence aos updates; `DOMAIN_COMMAND` conserva `/3`. `BulkOperationLifecycle` passa a receber o `ObjectMapper` configurado no lugar de `TypeFactory`, sem overload legado. Perfis operacionais, capabilities e execução CRUD bulk ainda não são suportados. As provas focais Metadata aprovaram 87 casos distintos, sem falhas na evidência final; dois seletores HTTP do host passaram contra o candidato local com override explícito, e a revisão independente aprovou o corte estrutural. Isso não representa publicação ou adoção pública. Ver `docs/spec/BULK-CRUD-STRUCTURE.md`.

- Candidato B4-R1: `ResourceRepresentationResult` pareia DTO e revisão persistida da mesma entidade em `findById` e PUT versionado; o controller emite ETag da revisão capturada, sem lookup tardio. A API Java beta migra os serviços/overrides versionados para o resultado pareado. Cada implementação deve exigir revisão presente dentro da transação após o último flush/hook, revertendo a escrita se faltar; o guard do controller após commit não fornece esse rollback. Replay histórico usa revisão capturada ou omite ETag. Endpoints e DTO wire não mudam. O candidato local passou 46 testes focais Metadata e 28 testes focais host: 13 com PostgreSQL (5 JPA/MockMvc e 8 receipt/replay), 1 unitário de providers e 14 TCP/H2. A correção adicional do helper de header usa SET singleton para evitar 500 pós-commit em resposta read-only de lista, sem alterar o contrato pareado. Revisão final, integração, release e adoção sem override ainda pendentes; não declara backend completo, grants de role ou snapshot de relações.

- Histórico B3: `JdbcBulkDurableExecution.advance` adicionou composição síncrona com reserva interna protegida, validação durável de vínculo/fence e transação/orçamento por unidade. Replay de qualquer ordinal, inclusive ACK perdido na segunda unidade, não despacha o sufixo; stop, cancelamento, terminal e incerteza encerram a chamada sem retry/recovery automático. O host mantém UUID e lê resultados pelo reader autorizado. Na prova candidata, passaram 17 casos PostgreSQL focais únicos no kernel e 32 casos HTTP focais P1/P2. Depois, Metadata rc.145 foi publicada oficialmente pelo run 36876399026; a preparação local de release reportou 1.203 testes, 0 falhas/erros e 3 skips, e a classe publicada manteve paridade com o candidato. A adoção B3 do host foi integrada pelo PR326 na main `6eaf771`, com composto de 832 casos únicos (804 aprovados, 28 skips), sem repetir toda a suite após o conserto focal da fixture. Esses fatos B3 não são prova PostgreSQL da correção B4-R1 nem autorizam novo backend/deploy.

- A configuração principal exclui auto-configurações registradas do component scan, mantendo o carregamento pelo manifesto ordenado, e publica as definições de grupos antes das condições do core Springdoc, preservando o processor de escopo prototype e o isolamento do cache OpenAPI por grupo com cache padrão habilitado. A prova HTTP distingue grupos read-only, mutantes, agregados e infraestrutura e mantém o adaptador de respostas genéricas. O teste de leitura strict agora confirma rejeição sem alteração de cache/hash, seguida de refresh protegido. Sem novo contrato ou bypass no host.

- Catálogo de actions e capabilities de coleção reutilizam uma composição estrutural por resposta síncrona, com availability fora dos locks de preparo/cache. Readiness scoped revalida provider e geração durável; um fence efêmero preserva época, revisão de transporte e saldo do prazo em reads curtos. Cleanup impede reutilização entre respostas, e falhas após o início do builder propagam sem retry. Não altera autorização ou admissão transacional. Na API Java beta, o construtor de quatro argumentos de `ActionCatalogService` passa a receber o projetor com consumer; callers explícitos devem migrar a assinatura. O construtor de três argumentos permanece disponível.

- A integração Springdoc isola as informações de respostas genéricas por geração do OpenAPI, preservando handlers e schemas sem acumular listas históricas entre grupos. O bean canônico precede o default Springdoc e respeita override explícito do host; o caminho sem respostas genéricas e as invocações diretas não HTTP possuem provas focais. A prioridade explícita do converter Praxis preserva os adaptadores Springdoc antes da resolução terminal, sem depender da ordem incidental das definições. Não modifica budgets, cache de autoridade ou lifecycle durável.

### Added
- Strict cache reads reject a changed exact-group document before replacing public JSON or retaining an obsolete schema hash. Identical promotion and cold strict reads remain supported; changes require the existing guarded refresh/invalidation path.
- S4c/P1 materializa `ActionExecutionContract.bulk` somente para `DOMAIN_COMMAND` com composição corrente e controle durável `READY` estável em geração, fingerprint e revisão. O catálogo recebe o lote em uma composição, preserva availability contextual e omite `bulk` em falhas/stale. `CapabilityOperation.bulk` para updates permanece fora deste corte.
- Referências UI das sete roles usam a seleção compartilhada de `/schemas/filtered` e dimensões `idField`/`readOnly` explícitas por role; confirmação reutiliza as referências capturadas da action. Seleção/materialização indisponível impede composição operacional, publicação/readiness e exposição da projeção. O digest `praxis.bulk.structure/3` inclui referências e evidência UI separadas do transporte bruto: adoção exige suspender/recompor e republicar pelo lifecycle. Nenhum endpoint, autorização, release ou prontidão produtiva é criado.
- S4c/P1 composition now preserves the action registry's canonical schema references, including the resource `idField`/`readOnly` projection, and keeps the `@ApiGroup` catalog category distinct from the exact OpenAPI snapshot group. Both action schema links and the catalog group remain in the versioned structural digest (introduced in `praxis.bulk.structure/2`, extended to `/3` by the UI projection), while the action operation and all seven bulk operations retain strict identity checks.
- The strict response-schema reader preserves bounded, non-empty `oneOf` variants after recursively resolving each schema in the same canonical snapshot. Request schemas remain composition-free; malformed branches, cyclic references, and resource limits still fail closed.
- RS1 acrescenta `BulkAuthorizedProposalReader` e o provider puro `BulkProposalProjectionProvider`: proposta pública existente somente após autorização integral no mesmo snapshot, projeção de intenção explícita do domínio e validação do preview persistido. READY/BLOCKED são resultados históricos, distintos da readiness operacional. Expiração retida é GONE após autorização; corrupção, legado ou projeção incompatível falham fechados, sem resposta parcial. Não cria endpoint, nova migration ou release.
- G3c-b compõe resultados de execução autorizados com `BulkAuthorizedExecutionResultsReader`, cursor AEAD EXECUTION_RESULTS e janela fixa do RS4; reautoriza o conjunto integral no mesmo snapshot e publica apenas identidade wire, status certificado e diagnóstico genérico. Tombstone continua restrito ao criador sem payload. Não cria HTTP ou READY; publicação e adoção permanecem gates separados.
- G3c-a acrescenta `BulkAuthorizedExecutionReader` para resumo RS3 autorizado na mesma fotografia PostgreSQL: DTO derivado da intenção durável e do prefixo certificado, com tombstone sem payload somente para o criador com grant atual. Autorização integral precede a projeção; corrupção pré-autorização não enumera existência. Não acrescenta endpoint HTTP, resultados paginados RS4, migração, retenção operacional do host ou READY.
- G3b evolui a fachada `BulkAuthorizedProposalResultsReader` com configuração obrigatória `BulkReadCursorConfiguration`, continuação AEAD vinculada ao solicitante/escopo efetivo e reautorização integral em cada página. Publica `CursorPage<BulkProposalItemResult<Object>>` com identidade wire, decisão de avaliação e diagnósticos allowlisted. A migração beta remove o construtor sem configuração e a antiga Page/Item opaca. TTL original até15min, chaves provisionadas e precedência não enumerável são obrigatórios. Não cria controller, RS1 público, leitura de tombstones ou READY; adoção HTTP do host é gate separado.
- G3a publica a fachada Java server-side `BulkAuthorizedProposalResultsReader` e o SPI `BulkReadAuthorizationProvider` para compor a primeira página RS2 somente depois de autorização global e integral do conjunto protegido. RS1, decisão do host e página allowlisted compartilham a mesma conexão e o mesmo snapshot PostgreSQL `REPEATABLE READ READ ONLY`; falhas antes da autorização completa não enumeram proposta, avaliação ou alvo. O contrato tem orçamento monotônico e retorno opaco, mas não é controller, não emite cursor/continuação e não declara endpoint, capability ou `READY`. A adoção pelo host depende de artefato publicado e binding confiável da infraestrutura.
- O read model interno agora contém RS1 de proposta/avaliação, RS2 bounded sobre a projeção V9/V11, resumo RS3 e resultados RS4 bounded. O codec AEAD interno vincula propósito, identidades, escopo e fingerprint de autorização, revisão da projeção, watermark, ordinal, tamanho da página e validade para futura continuação com reautorização; nenhum desses componentes publica sozinho response HTTP, tombstone autorizado, cursor público ou readiness.
- V13 certifica a cronologia persistida da execução. A migration atesta os guards V5/V10, corrige somente o skew histórico comprovável e impõe `created_at <= updated_at`, `terminal_at <= updated_at` e, quando aplicável, `cancel_requested_at <= terminal_at`; writers terminais usam um único instante SQL. O cutover exige drenar binários V12 e não é rolling upgrade. Ver `docs/spec/BULK-H1B-READ-MODEL.md`.
- RS1 interno observa proposta e avaliação protegidas no mesmo snapshot PostgreSQL `REPEATABLE READ READ ONLY`, com escopo confiável, validação dos codecs existentes, distinção segura entre ausência, proposta sem avaliação e avaliação íntegra, e falha fechada em corrupção/ACL. O valor é package-private e opaco à serialização; não publica HTTP, redaction genérica, autorização, cursor ou `READY`. Provas PostgreSQL cobrem expiração concorrente entre os SELECTs, escopo cruzado e corrupção. Ver `docs/spec/BULK-H1B-READ-MODEL.md`.
- V12 cria um gate owner-only para a leitura interna RS2: função `SECURITY DEFINER STABLE` com `EXECUTE` somente para roles runtime configuradas exige markers V11 e V12 `COMPLETE` no mesmo snapshot PostgreSQL `REPEATABLE READ READ ONLY`. O bootstrap V12 concede acesso uma vez e valida função, ACL e owner sem reparar drift após `COMPLETE`; o reader package-private limita janela e bytes, valida checksum V11 por item e não decodifica a avaliação protegida por página. Não há endpoint, cursor, DTO público ou capability `READY`; o cutover requer drenar hosts V11, e a exposição HTTP depende de autorização e benchmark. Ver `docs/spec/BULK-H1B-READ-MODEL.md`.
- V11 acrescenta integridade versionada por item à projeção segura: cada folha vincula avaliação, revisão/allowlist, manifest, ordinal, decisão e diagnostics persistidos. O bootstrap `PENDING/COMPLETE` faz backfill e validação integral em uma transação, com fence de writers antigos, ACL focal e sem reparo automático depois de `COMPLETE`; retenção remove folhas explicitamente antes do parent. O checksum protege drift sob ACL/imutabilidade e não é autenticação contra schema owner ou superuser. Ver `docs/spec/BULK-H1B-READ-MODEL.md`.
- V10 acrescenta pedido de cancelamento durável ao núcleo protegido: `JdbcBulkDurableExecution.requestCancel(scope, executionId)` grava uma única marca temporal no PostgreSQL, é idempotente e preserva receipts/admissions já confirmados. O fence físico impede um writer antigo de iniciar tentativa após o pedido ou confirmar nova evidência depois dele; ACK de evidência anterior ainda reconcilia o prefixo. `STOPPED+CANCELLED_BY_USER` libera a allocation na mesma transação, e o purge guarda `CANCELLED` na tombstone mínima. `BulkExecutionMigrator` valida coluna, CHECKs, trigger, funções/ACL/owner e upgrades. Há provas PostgreSQL de corrida, rollback, JPA, quota, recuperação, retenção e drift; ver `docs/spec/BULK-DURABLE-CANCELLATION.md`. O corte não publica rota HTTP, reader, cursor ou capability READY.
- V9 introduz projeção física RS2 por ordinal: decisão e diagnósticos seguros fornecidos por allowlist do provider, vinculados à avaliação e ao manifest V8. A mudança beta é incompatível na fonte: `JdbcBulkProposalStore.insertEvaluated` agora exige `(evaluation, projection)` e o overload anterior foi removido. `COMPLETE` grava allowlist e digest versionado dos itens, e `UNAVAILABLE` conserva avaliação nova sem projector seguro. Trigger diferido rejeita writers antigos/incompletos; avaliações pré-V9 permanecem `UNAVAILABLE_LEGACY`. Migrator valida vínculo, cardinalidade, integridade, ACLs e bootstrap; retenção/expurgo removem a projeção na mesma unidade. V9 isoladamente não publicou reader, cursor, HTTP ou `READY`; ver `docs/spec/BULK-H1B-READ-MODEL.md`.
- V8 persiste um manifest privado por ordinal, vinculado por FK à avaliação exata, com identidade wire e versão opaca sem perda, digest compacto e fence diferido contra writers antigos. `insertEvaluated` grava o manifest atomicamente; o migrator faz backfill/validação fechada, atualiza retenção e limita os grants da nova tabela às roles runtime configuradas e elegíveis durante o upgrade. Um marcador `PENDING/COMPLETE` torna esse bootstrap repetível após falha e impede restaurar grants revogados em migrations futuras. Instalação inicial e upgrade têm sequências de ACL distintas; ver `docs/spec/BULK-PROPOSAL-STORAGE.md` e `docs/spec/BULK-H1B-READ-MODEL.md`. Ainda sem reader público, cursor, cancelamento ou READY.
- `BulkExecutionInfrastructure` exige a `BulkExecutionRoleConfiguration` explícita. Cada operação de runtime e control-plane revalida identidade de sessão, ACLs e fences V7 na própria conexão antes do namespace/callback/CAS; timeout mais estrito é preservado. Corpos SQL esperados das migrations V5/V6/V7 ficam em cache lazy imutável, pré-normalizados uma única vez, enquanto os catálogos de autorização são consultados a cada entrada. O construtor anterior de quatro argumentos foi removido: hosts devem passar o owner e grantees reais do provisionamento. A prova PostgreSQL reporta amostras agregadas de entradas runtime/control-plane para caracterizar o custo do caminho testado; esses números são sintéticos, não SLA nem promessa de latência de produção. Ver `docs/spec/BULK-EXECUTION-INFRASTRUCTURE.md`.
- `BulkOperationLifecycle` compõe bindings MVC, action e todos os grupos OpenAPI publicados com exatamente um provider/perfil, publica e suspende o descritor completo no CAS PostgreSQL V6/V7, reconcilia confirmação de commit incerta e instala um fence central para qualquer limpeza/refresh de cache. O control plane prova que sua conexão e a runtime veem o mesmo lock advisory transacional no mesmo banco. Ao ativar o lifecycle, o host precisa desabilitar o cache interno Springdoc com `springdoc.cache.disabled=true`. O novo contrato `BulkIdentityCodec.canonicalWireSchema()` exige equivalência wire explícita com o schema publicado. Isso ainda não expõe endpoints, actions/capabilities bulk ou prova os sete handlers/adoção produtivos do host; ver `docs/spec/BULK-OPERATION-LIFECYCLE.md`.
- `@BulkOperation` vincula uma operação `@WorkflowAction` de confirmação a uma avaliação por operationId explícito; `BulkResourceOperationBindings` exige os dois handlers POST distintos no mesmo `@ApiResource`, corpos, action e atomicidade coerentes, e inclui ambos na validação global de IDs. A API só projeta identidade estrutural: não compõe provider/descriptor, readiness, capability ou execução.
- `CanonicalOpenApiGroupSnapshot` oferece captura exata e leituras públicas de request/response sobre a mesma cópia defensiva do grupo, incluindo verificação pública de operação bodyless. `CanonicalOperationResolver` adiciona binding estrito em lote e resolução do DTO de uma referência já validada, com falha fechada para implementações substitutas e sem reler o grupo capturado. O compositor interno usa essas provas para reunir sete operações e a action canônica; aliases estruturais de rota entre grupos são aceitos, IDs conflitantes no mesmo alvo são recusados. O resultado não inclui provider, fingerprint operacional, readiness, capability ou autorização de execução.
- Migração V7 vincula proposta e execução à geração, fingerprint e revisão estrutural do descritor autorizado, rejeita writers antigos sem tuple e serializa nova mutação com suspensão do control plane. O migrator valida estrutura, ACL mínima e binding; registros históricos permanecem sem autoridade retroativa. Ainda não compõe o descritor nem expõe endpoint/capability bulk.
- Migration V6 separa o fence runtime do CAS governado de operação: o runtime recebe `EXECUTE` para lock compartilhado, o control plane recebe `EXECUTE` para transição CAS e nenhuma dessas credenciais ganha acesso direto à tabela de controle. `BulkExecutionRoleConfiguration` declara os três grupos de roles; o migrator rejeita herança PostgreSQL não declarada, inclusive roles predefinidas privilegiadas. Incremento de segurança/substrato, sem composição do descriptor ou capability/endpoints bulk.
- Migração V5 conecta propostas e execuções ao ledger durável de capacidade (100 pendentes por deployment, 10 por sujeito, 80 ativas), com replay antes dos gates exclusivos, liberação terminal atômica, owner/roles e retenção restrita com tombstone. A especificação documenta estados, ACL e provas PostgreSQL; ainda não há exposição governada/endpoint nem adoção completa no host.
- Governança obrigatória em `BulkEvaluationSnapshot`: observações de política, revisão do avaliador e fingerprint de autorização. Comparação canônica de recaptura preserva inteiro/decimal e verifica contexto/validade, sem conceder execução. Constructor beta anterior removido; payload sem governança é recusado, sem alterar migrations V1/V2.
- `BulkTargetEvidence` e `BulkEvaluationSnapshot` vinculam fatos/plano por alvo à proposta com framing próprio, cobertura exata e cópias defensivas. Store grava entrada+evidência atomicamente; migração V2 preserva V1 e valida FK/imutabilidade. Não emite READY nem substitui política do Config.
- `BulkStoredProposal`/`JdbcBulkProposalStore` persistem intenções EXPLICIT/SYNC nas três modalidades, preservando números exatos, contexto e fingerprint. `BulkExecutionMigrator` aplica migração PostgreSQL explícita em schema próprio e valida estrutura física; sem READY ou executor.
- `BulkExecutionInfrastructure` vincula datasource/manager/namespace e participa de transação JDBC/JPA local obrigatória, com prova PostgreSQL real de commit, rollback e locks. Sem DDL, store ou execução bulk.
- `CanonicalOperationResolver.requireResourceRequestBody` vincula a operação estrita ao DTO @RequestBody concreto do handler MVC, preservando genéricos herdados/aninhados e recusando corpos abertos/opcionais/wrappers. Sem fetch de schema ou executor.
- Leitura estrita `OpenApiDocumentService.requireRequestSchema` com snapshot isolado, dialeto declarado e resolução limitada de referências locais; preferência documental de mídia compartilhada e preservada. Sem registro executável de operações em lote.
- Leitores canônicos de request/response exigem grupo OpenAPI exato e compartilham um snapshot isolado; responses só provam contrato com status 2xx explícitos, mídia JSON sintaticamente concreta e schema canônico equivalente. Implementações substitutas devem prover `getDocumentForGroupStrict`; ainda não há composição de descriptor, endpoint ou capability de lote.
- `@BulkEditable` e `BulkEditableFields` compilam opt-in de DTO, nomes wire e schema resolvido em allowlists imutáveis por modalidade, com rejeição de campos protegidos/ocultos/readonly e CLEAR sem nullabilidade explícita. SDK estrutural, sem bootstrap ou runtime de lote.
- Resolução estrita `CanonicalOperationResolver.requireResourceOperation` com ID explícito global, recurso e método HTTP, rejeitando bindings ausentes ou ambíguos. Sem bootstrap declarativo ou runtime de lote neste incremento.
- Tipos de proposta/execução/resultado e fingerprint da intenção normalizada no SDK `bulk`, com snapshots defensivos, totais/estados validados e schemas documentais. A composição HTTP, storage e idempotência durável permanecem fora deste incremento.
- Fundação Java `bulk` com requests das três modalidades, confirmação por proposalId, codecs Integer/Long/String/UUID, parser isolado com limites e validação estrutural SET/CLEAR/omissão. Ainda sem endpoints, discovery ou executor; ver `docs/spec/BULK-PROTOCOL-INPUT.md`.

### Fixed
- Composição bulk prepara documentos fora do write lock público e reconfere a revisão local de invalidação antes do callback/CAS; preserva comparação pública/fresh independente em cache frio. Cliente HTTP oficial recebe timeouts positivos e orçamento agregado de admissão, sem prometer cancelamento do servidor. Falha pré-CAS não reconcilia READY concorrente como sucesso próprio. Ver limites no lifecycle.
- Commons Lang alinhado à versão 3.20.0 para compatibilidade com a cadeia Commons Compress/POI/PostgreSQL; prova permanente extrai e inicia PostgreSQL sem reutilizar cache de binários.
- O profile E2E cria um banco H2 por contexto Spring; o encerramento de uma suíte com `@DirtiesContext` não remove tabelas de outro contexto ainda cacheado.
- Parser bulk preserva decimais válidos além da faixa double; normalização mantém snapshots numericamente válidos após persistência, sem alterar fingerprints. BulkStoredProposal recusa tokens wire incompatíveis com o codec canônico declarado.
- Infraestrutura bulk recusa manager com `globalRollbackOnParticipationFailure=false`, preservando rollback-only após erro do callback.
- Lookup canônico por `operationId` rejeita IDs efetivos duplicados em vez de escolher o primeiro handler; consumidores com configuração ambígua precisam corrigir suas identidades/referências.
- O executor de commands governados agora preserva `ResourceVersionPreconditionException` para o
  handler HTTP canônico, inclusive quando a revalidacao transacional detecta a corrida. Assim,
  `400`, `412` e `428` mantêm seus códigos públicos distintos em vez de virarem erro inesperado.
- O transporte de versao agora valida separadamente um `If-Match` forte antes de resolver replay
  idempotente, mantendo o header obrigatorio sem rejeitar a versao original ja obsoleta.
- Commands governados agora classificam uma colisao JPA de concorrencia otimista como
  `PRECONDITION_FAILED`/HTTP `412`, com mensagem sanitizada, em vez de converter a corrida
  protegida por `@Version` em falha inesperada.
- Requests de `/schemas/filtered` para `/{resource}/stats/*` agora materializam em `filter`
  o `FilterDTO` concreto já publicado por `/{resource}/filter`, preservando a conformidade de
  campos mesmo quando o Springdoc apaga o parâmetro genérico dos DTOs analíticos.
- Responses concretas `RestApiResource<DTO>` em projections relacionadas agora registram o DTO
  de domínio no grupo OpenAPI pai e publicam o componente flatten `DTO + _links`, permitindo que
  `/schemas/filtered` resolva o schema sem depender de inferência pelo nome do wrapper.
- Capabilities opcionais de resource (`options`, `optionSources`, `stats*` e `export`) agora
  resultam da intersecao entre mapping OpenAPI e suporte estrutural executavel do service.
  `/schemas/filtered`, `/capabilities`, `operations.supported` e `stats.fields` deixam de
  anunciar endpoints herdados que terminariam em `501`/`UnsupportedOperationException`.
- `POST /{resource}/filter` agora reaplica o escopo de acesso server-side ao
  carregar `includeIds`, impedindo que a reidratacao de selecionados atravesse
  o row scope funcional em recursos mutaveis ou read-only.
- `/schemas/domain` agora associa option sources registry-wide ao `resourceKey`
  canonico ja materializado por surfaces/actions para o mesmo `resourcePath`,
  preservando a descoberta mesmo quando a URL nao codifica a identidade
  semantica completa do recurso.
- `/schemas/filtered` publica `x-ui.resource.identity` somente para schemas de
  resposta; schemas de request representam filtros e comandos, nao registros
  materializados.
- `ApiResourceIdentityResolver` agora injeta explicitamente o
  `requestMappingHandlerMapping` canonico do Spring MVC, evitando falha de
  auto-configuracao em hosts que tambem publicam mappings auxiliares, como
  `controllerEndpointHandlerMapping`.

### Removed
- `@ResourceCapabilities`, anotacao declarativa sem consumo runtime que podia divergir do service
  executavel. Durante o beta, a fonte canonica passa a ser a descricao estrutural do service
  associada ao controller resource-oriented.

### Added
- API pública `ResourceRepresentationMaterializer` para projections pai-filho materializarem DTOs
  pelo `resourceKey` canônico do recurso filho. A implementação annotation-driven reutiliza a
  composição oficial de `self`, actions, surfaces e capabilities, rejeita resource keys ausentes
  ou duplicados e evita acoplamento controller-to-controller nos hosts.
- Familia canonica `gauge` no draft `x-ui.chart`, limitada no primeiro corte a um
  bucket, uma metrica e escala explicita, sem opcoes especificas da engine.
- Familia canonica `treemap` no draft `x-ui.chart`, com ao menos uma dimensao e
  exatamente uma metrica, mantendo hierarquia e layout especificos fora do contrato de engine.
- Familias canonicas `funnel` e `pyramid` no draft `x-ui.chart`, ambas com ao menos
  uma dimensao e exatamente uma metrica, sem expor configuracao especifica da engine.
- Preconditions cross-resource para `@WorkflowAction`: collection actions podem declarar
  `IF_MATCH` somente com `resourceVersionTargetResourceKey` e
  `resourceVersionTargetIdField`, permitindo que discovery/capabilities identifiquem o owner
  canônico do ETag e o binding do request sem inferência por URL.
- SPI tenant-neutral `ReactiveDeterminationDefinitionProvider` e projecao fechada
  `x-ui.reactiveDeterminations` em request schemas exatos de `/schemas/filtered`. O starter resolve
  a capability POST por `operationId`, deriva href e schema URLs e falha fechado para bindings
  inexistentes, ids duplicados, writers sobrepostos, ciclos ou tentativa de raw path; regras,
  facts e decisoes aplicadas por tenant permanecem no backend/Config e fora do cache publico.
- `display.statusLabelMap` para Entity Lookups: o backend pode publicar rótulos localizados de
  estados sem substituir os códigos canônicos usados por políticas de seleção, filtros e regras
  de negócio. O runtime continua recebendo `status` bruto em `OptionDTO.extra` e aplica o mapa
  apenas na apresentação.
- Composicao resource-oriented create-only por meio de `AbstractCreateResourceController`,
  `BaseCreateResourceService` e `BaseCreateResourceCommandService`. Recursos podem publicar
  leitura e `POST` sem anunciar ou mapear `PUT`/`DELETE` enquanto essas operacoes permanecem
  indisponiveis por regra de dominio, concorrencia ou gate de migracao.
- Concorrencia otimista atomica e opt-in para updates resource-oriented por meio de
  `VersionedCreateUpdateResourceService`: o controller vincula o `If-Match` a identidade
  canonica, e o service valida a precondicao contra a versao persistida dentro da mesma
  transacao e do mesmo lock do update. Recursos nao versionados preservam o contrato anterior.
- `@WorkflowAction` agora publica um bloco canonico `execution` em `/schemas/actions` e
  `/capabilities`, cobrindo politica de interacao e risco, requisitos de idempotencia,
  correlacao e versao persistida, binding de selecao, atomicidade/outcome e invalidacao de
  projections. `resourceVersionField` identifica a versão presente nas linhas para que tabelas
  e listas materializem `If-Match` ou mapas de versões sem aliases locais. O contrato referencia
  os schemas reais da operacao e nao duplica payload.
- `filtering.searchStrategies` para option sources governados, com seleção
  explícita por `searchStrategy` no endpoint canônico. O runtime resolve a
  estratégia única automaticamente, rejeita busca ambígua antes do provider e
  normaliza `normalized-document` sem publicar o valor bruto em metadata ou
  `OptionDTO.extra`.
- `filtering.searchStrategies[].inputFormat=digits` para validar códigos de
  negócio numéricos antes da resolução do provider, mantendo `text` como
  default compatível e sem expor bindings internos.
- API publica `ResourceStructuralCapabilities` e resolver annotation-driven por `@ApiResource`
  para centralizar a disponibilidade estrutural estavel de options, option sources, stats e
  export sem misturar autorizacao contextual.
- Contrato publico governado `ResourceOperationFailure` para falhas conhecidas de operacoes
  resource-oriented, com kind canonico derivando HTTP/category, codigo estavel, mensagem segura,
  target publico opcional e causa privada transportada separadamente por
  `ResourceOperationFailureException`. `BusinessException(String)` preserva os defaults anteriores,
  e create/update/delete agora documentam o envelope de erro no OpenAPI.
- API publica `ResourceFilterAccessScope` e hook
  `AbstractBaseQueryResourceService.resolveResourceFilterAccessScope()` para o
  host declarar explicitamente acesso irrestrito, negado ou restrito por
  `Specification`, sempre resolvido pelo contexto autenticado do servidor.

- `@AnalyticsRecordOpen`, `@AnalyticsSurfaceTarget` e
  `x-ui.analytics.projections[].interactions.recordOpen` para ligar o campo
  publico de identidade de uma linha nominal a uma surface ITEM cross-resource.
  O contrato publica somente `sourceIdentityField`, `resourceKey` e `surfaceId`;
  catalogo, availability, enforcement e materializacao de `surface.open`
  permanecem em suas fontes canonicas.
- `@AnalyticsDimensionBinding.keyFilterField` e
  `x-ui.analytics.projections[].bindings.primaryDimension.keyFilterField` para
  vincular `bucket.key` ao campo publico exato do request de filtro. Projections
  com `crossFilter=true` agora falham fechado quando o binding nao existe, sem
  expor property paths internos nem inferir por nome ou label.
- `/capabilities.operations` agora publica tambem `byId`, `update`, `all`,
  `filter`, `cursor`, `options`, `optionSources`, `statsGroupBy`,
  `statsTimeSeries`, `statsDistribution` e `statsComparison`, permitindo ao
  `ResourceOperationAvailabilityProvider` governar query/stats por principal
  sem transformar `canonicalOperations` em autorizacao.
- `@AnalyticsPolicyReference` e
  `x-ui.analytics.projections[].governance.policyRefs[]` para publicar identidade
  e versao de policies de dominio, papel, campo de resultado e atestacao
  opcional sem expor thresholds, expressoes ou dados de runtime.
- `x-ui-field.schema.json` agora publica o contrato de atalhos de periodo para
  `dateRange` e `inlineDateRange`, incluindo `shortcuts[]`,
  `inlineQuickPresets`, `inlineOverlay`, fixtures valid/invalid e cobertura para
  impedir callbacks como `calculateRange` em metadata JSON.
- Base publica `AbstractCollectionCommandResourceController` para recursos que possuem somente
  actions no escopo da colecao: publica `/actions` e `/capabilities`, integra a execucao ao
  boundary governado e aos schemas filtrados da operacao real, sem expor CRUD, filtros ou
  persistencia ficticios.
- Guia canonico `docs/guides/ENTERPRISE-AVAILABILITY-ADOPTION.md`,
  checklist de readiness e fixture E2E non-Ergon para orientar availability
  enterprise entre `ResourceOperationAvailabilityProvider`,
  `ActionAvailabilityRule`, `SurfaceAvailabilityRule`,
  `ResourceStateSnapshotProvider`, `_links`, `/capabilities`, `/actions` e
  `/surfaces` sem expor politicas privadas do host.
- API Java inicial de **Governed Resource Command Execution** em
  `org.praxisplatform.uischema.command`, com executor host-neutral, provider,
  request/result, response policies, outcomes publicos, error categories e
  sanitizacao de evidence privada, alem de adapter opt-in para converter
  outcomes governados em `RestApiResponse`/`CustomProblemDetail` canonicos e
  helpers protegidos em `AbstractResourceQueryController` para actions reais
  executarem comandos governados preservando `X-Data-Version` e links de
  schema sem criar dispatcher generico nem alterar endpoints/discovery
  existentes; o executor tambem converte `ResponseStatusException` publica de
  providers Spring em outcomes governados para reduzir adaptacao local nos
  hosts.
- Engine canonico `ExcelCollectionExportEngine` para exportacao XLSX real em
  `POST /{resource}/export`, registrado por auto-configuracao junto aos engines
  CSV/JSON e governado pela mesma allowlist de campos, `applyFormatting`,
  `localization`, headers, ordem tabular e protecao contra formula injection.
- `GET /{resource}/capabilities` agora publica `stats.fields` como discovery publico
  derivado de `StatsFieldRegistry`, incluindo campo, `propertyPath`, label sugerido,
  metricas e modos elegiveis para dashboards e cockpits metadata-driven.
- Base canonica `AbstractCreateUpdateResourceController` e portas `BaseCreateUpdateResourceService` / `BaseCreateUpdateResourceCommandService` para recursos `read + create/update` que nao publicam `delete`.
- Base canonica `AbstractUnitDeleteResourceController` e portas `BaseUnitDeleteResourceService` / `BaseUnitDeleteResourceCommandService` para recursos `read + create/update + delete unitario` que nao publicam `DELETE /batch`.
- SPI publica `ResourceOperationAvailabilityProvider` para availability host-neutral de operacoes canonicas de recurso, integrada a `/capabilities` e `_links`.
- Tipos publicos `ResourceOperationAvailabilityContext` e `NoOpResourceOperationAvailabilityProvider` para hosts corporativos plugar guards legados sem expor detalhes privados no contrato.
- Base canonica `AbstractLegacyBackedResourceController` e portas `LegacyBackedResourceService` / `LegacyBackedResourceCommandService` para recursos mutaveis resource-oriented com escrita delegada ao host legado.
- Portas opcionais `DuplicateDraftLegacyBackedResourceService` / `DuplicateDraftLegacyBackedResourceCommandService` para `duplicate-draft` nao mutante, retornando DTO de rascunho editavel separado do DTO de resposta persistida.
- Operacao canonica opcional `duplicate-draft` em `capabilities.operations` quando o recurso publica `POST /{resource}/{id}/duplicate-draft`.
- Builder publico `GovernedOptionSourceCatalog` para declarar lookups provider-backed com endpoints canonicos, dependency mapping, selected-value reload e politica de sort sem boilerplate por service.
- Contrato publico `OptionSourceRuntimeContract`, `OptionSourceSelectedReloadPolicy` e `OptionSourceInvalidSortPolicy` para projetar `filterEndpoint`, `byIdsEndpoint`, `selectedReloadPolicy` e `invalidSortPolicy` em `x-ui.optionSource`.
- Contrato publico `RelatedResourceSurface` e `RelatedResourceChildOperation` para que `@UiSurface` descreva colecoes filhas relacionadas, binding do item pai, selecao e affordances da colecao filha em `/schemas/surfaces`.
- `@UISchema.preset()` e `UISchemaPreset` para acelerar metadados repetitivos de apresentacao sem gerar texto de dominio; o resolver publica `x-ui.presentationPreset`.
- API publica `SemanticMetadataReviewer` para gerar relatorio de qualidade de autoria, apontando descricoes ausentes, copiadas de labels, derivadas de nomes de campos e vazamento de contexto privado sem governanca.
- Endpoint `POST /{resource}/option-sources/{sourceKey}/options/by-ids` com `OptionSourceByIdsRequest` para selected-value reload contextual sem quebrar o `GET .../by-ids` canonico.
- API publica `OptionSourceByIdsRequest` para extensoes de service provider-backed que precisam tratar selected-value reload por IDs sem criar contrato local no host.
- Propriedade `@UISchema.dependsOn()` como atalho canonico para publicar dependencias de LOV/options em `x-ui.optionSource.dependsOn`.
- Contrato canonico de filtro rico para `RESOURCE_ENTITY` em `x-ui.optionSource.filtering`, com `availableFilters`, `defaultFilters`, `sortOptions`, `defaultSort`, `quickFilterFields` e `searchPlaceholder`.
- Tipos publicos `LookupFilterDefinition`, `LookupFilteringDescriptor` e `LookupSortOption` para publicar o contrato de filtro rico no starter sem convencoes locais de frontend.
- Execucao JPA compartilhada para `LIGHT_LOOKUP`, com projeção leve `OptionDTO{id,label}`,
  busca textual e reidratacao por IDs quando o descriptor publica `propertyPath` ou
  `valuePropertyPath`/`labelPropertyPath`.

### Changed
- Availability de operacoes passa a respeitar o `scope` da propria operacao em
  snapshots de colecao e item; links HATEOAS `all`, `filter` e `filter-cursor`
  acompanham a mesma decisao publicada em capabilities.
- Regras default de surfaces avaliam `requiredAuthorities` antes de exigir
  contexto de item: principals inelegiveis recebem `missing-authority`, enquanto
  principals elegiveis sem `resourceId` recebem `resource-context-required`.
- `@UISchema.options` agora pode enriquecer labels/metadados de opcoes derivadas de `enum` em `x-ui.options`, preservando os valores canonicos do schema OpenAPI e ignorando valores extras que nao pertencem ao enum.
- `_links` de create/edit/delete/export passam a respeitar a availability canonica avaliada pelo `CapabilityService`, evitando divergencia entre HATEOAS e `/capabilities`.
- `/schemas/domain` agora preenche a descricao do no conceitual de recurso a partir da
  descricao OpenAPI do schema raiz, priorizando schemas de resposta para o Cockpit
  materializar contexto de negocio sem convencoes locais no host.
- `duplicate-draft` agora e opt-in via `AbstractDuplicateDraftLegacyBackedResourceController`; `AbstractLegacyBackedResourceController` publica apenas o baseline CRUD legado-backed, e o endpoint de rascunho retorna `200 OK` sem criar item persistido.
- `deleteBatch` passa a validar availability de colecao e de cada item antes de delegar exclusao em lote.
- `SemanticMetadataReviewer` passa a revisar campos herdados de DTOs, evitando que contexto privado em superclasses escape sem governanca.
- `OpenApiGroupResolver` agora respeita fronteira de segmento ao resolver grupos, evitando falso match entre recursos com prefixos comuns, como `/vinculos` e `/vinculos-funcionais`.
- A documentacao do starter agora explicita a forma canonica de publicar controllers customizados de recursos relacionados com `@ApiGroup` e `@RequestMapping` de classe.
- `EntityLookupDescriptor` agora pode publicar o bloco `filtering` como parte da semantica canonica de `entityLookup`.
- `x-ui-field.schema.json`, fixtures de exemplo e a RFC de `optionSource` passam a documentar o contrato de filtro rico para buscas corporativas.
- Requests com `sort` contendo direcao diferente de `asc` ou `desc` agora retornam erro de cliente em vez de serem normalizadas silenciosamente para `ASC`.
- `OptionSourceEligibility` preserva `OptionSourceExecutionMode.PROVIDER_REQUIRED` ao enriquecer descriptors derivados por stats, evitando fallback JPA indevido para fontes externas.
- A documentacao de `@UISchema` foi alinhada ao contrato atual de `type`, `controlType`, `numericFormat` e semantica textual para codigos, documentos e identificadores numericos de legado.
- `@UiSurface` agora pode publicar `relatedResource` para surfaces `ITEM` que projetam colecoes relacionadas; o schema continua resolvido por `/schemas/filtered` da operacao real e metadados parciais de colecao filha sao rejeitados.
- `x-ui-field.schema.json` passa a documentar `presentationPreset` como acelerador visual, explicitamente separado da descricao OpenAPI de dominio.
- `conditionalDisplay`, `conditionalRequired` e `conditionalValidation[].condition` agora sao validados no backend como Json Logic canonico contra a matriz de operadores do runtime Angular antes de serem publicados em `x-ui`.
- Mensagens de erro para condicionais Json Logic malformados agora distinguem JSON invalido de contrato Json Logic invalido, e a validacao bloqueia literais com shape basico incompatível com o runtime Angular.

### Fixed
- Links HATEOAS absolutos agora respeitam headers `Forwarded` e
  `X-Forwarded-*`, preservando host, porta e protocolo de proxies e dev servers
  ao publicar affordances como `capabilities`, `actions` e discovery contextual.
- `_links` operacionais resource-local (`self`, `all`, `filter`,
  `filter-cursor`, `create`, `update`, `delete`, `export` e
  `duplicate-draft`) agora publicam paths relativos com `contextPath`, mantendo
  o mesmo origin do consumidor atras de proxy Angular ou headers forwarded.
- Resolucao interna de documentos OpenAPI agora usa a origem local do backend
  ao rodar atras de proxy/forwarded headers, evitando que `/schemas/*`,
  `/capabilities`, actions e surfaces tentem consumir `/v3/api-docs` pela
  origem publica sem porta do proxy local.
- `/schemas/filtered` agora resolve paths OpenAPI template-equivalentes apenas
  quando a operacao HTTP solicitada tambem existe no candidato estrutural, mantendo
  match exato como prioridade e rejeitando ambiguidades em vez de escolher um schema
  incorreto para recursos relacionados nested.
- `POST /{resource}/option-sources/{sourceKey}/options/filter` agora aceita
  dependencias publicas declaradas em `dependsOn`/`dependencyFilterMap` para
  fontes `PROVIDER_REQUIRED` mesmo quando esses campos nao existem no
  `FilterDTO` do recurso host, preservando o payload publico governado antes da
  conversao do filtro estrutural e sem interpretar campos legados chamados
  `search`, `sort`, `filters` ou `includeIds` como envelope quando eles existem
  no `FilterDTO`.
- O mesmo endpoint provider-backed volta a publicar `OptionSourceFilterRequest`
  como schema OpenAPI de request, mantendo a documentacao publica estavel mesmo
  com parsing interno por JSON bruto para preservar dependencias governadas.
- `/schemas/filtered` agora preserva descricoes `@Schema` de campos `BigDecimal`
  ao manter o formato `decimal`, evitando que metricas monetarias ou agregadas
  percam semantica de negocio no Cockpit e em consumidores AI.
- O cockpit agora calcula formularios esperados e workflows acionaveis como
  cobertura contextual, evitando tratar recursos read-only ou analiticos como
  lacunas operacionais por nao publicarem formulario ou action.
- O cockpit agora verifica `/capabilities` apenas para recursos com `resourceKey`
  canonico publicado, evitando 404 falso-positivo em endpoints tecnicos isolados
  descobertos pelo catalogo OpenAPI.
- O cockpit agora materializa o mapa de dominio apenas a partir de endpoints com
  `resourceKey` canonico, mantendo endpoints tecnicos ou custom sem `@ApiResource`
  fora da contagem de recursos de negocio.
- O cockpit agora usa timeout maior ao ler catalogos por grupo, evitando falso
  "sem dominio materializavel" em hosts grandes durante inicializacao fria do OpenAPI.
- O cockpit e o catalogo de actions agora reconhecem `POST /{resource}/{id}/duplicate-draft`
  como workflow action canonica opt-in do starter, sem exigir aliases locais em `/actions/...`.
- `OptionSourceRuntimeContract.canonical(...)` rejeita `sourceKey` nao URL-safe antes de publicar endpoints de runtime.
- `CustomOpenApiResolver` agora preserva `x-ui.type=text` e nao publica `valuePresentation` numerico automatico quando um campo com transporte OpenAPI numerico e declarado como texto, controle textual ou mascara textual.
- `/schemas/filtered` agora pode derivar `x-ui.resource.idField` de um identificador natural escalar obrigatorio quando o DTO de resposta nao possui `id` ou `*Id`, cobrindo recursos como `EmpresaDTO.empresa`.
- O cockpit empacotado em `/praxis/cockpit` agora mescla o catalogo default com catalogos por grupo, evitando leitura parcial quando um grupo demora ou falha, e reorganiza endpoints em uma coluna no mobile para impedir linhas largas no painel do recurso.
- O cockpit agora prioriza o prefixo semantico de `resourceKey` antes de grupos tecnicos genericos como `application`, e reduz rotulos secundarios no modo limpo do grafo para melhorar a leitura da constelacao de relacoes.
- O cockpit agora inclui uma leitura rapida acionavel na lista de recursos e compacta o grafo semantico no mobile, reduzindo rolagem cega antes de escolher recursos, charts, formularios e workflows.
- O cockpit agora exibe um marcador de publicacao no topo, combinando `release`/`published`/`qa` da URL com `build.version` e `build.time` do host para reduzir ambiguidade durante validacao publica.
- O cockpit agora calcula workflows acionaveis a partir do cache canonico de
  `/schemas/actions`, evitando ressalva falsa quando a verificacao assíncrona ja
  carregou a action mas o objeto de recurso renderizado ainda esta desatualizado.

## [8.0.0-rc.14] - 2026-04-24

### Added
- Anotacoes publicas `@DomainGovernance` e `@AiUsagePolicy` para declarar
  classificacao semantica e politicas de uso por IA diretamente no codigo-fonte
  dos campos publicados pelo starter.
- Enums publicos `DomainGovernanceKind`, `DomainClassification`,
  `DomainDataCategory` e `AiUsageMode` para fixar os tokens canonicos emitidos em
  `x-domain-governance` e republicados por `/schemas/domain`.

### Changed
- `SemanticDomainCatalogService` agora prioriza governanca explicita publicada em
  `x-domain-governance` antes do fallback heuristico por nome ou descricao de
  campo.
- A anotacao `@DomainGovernance` usa vocabulario tipado no codigo Java e continua
  materializando os mesmos valores wire compativeis com o contrato semantico.

## [8.0.0-rc.80] - 2026-07-08

### Added
- Hooks protegidos de lifecycle em `AbstractBaseResourceService` para customizar
  create, update, delete individual e delete em lote sem sobrescrever o fluxo
  canonico de mapper, save, refresh e response.
- Helpers protegidos em `AbstractBaseResourceService` para resolver referencias
  JPA de entidades relacionadas por ID e substituir colecoes relacionais mutaveis,
  evitando boilerplate de `EntityManager#getReference` em updates de aggregates.
- RFC publica de suporte GraphQL, posicionando GraphQL como adapter derivado e
  nao como segunda fonte primaria da semantica metadata-driven.

## [8.0.0-rc.13] - 2026-04-22

### Changed
- `GET /schemas/domain` now emits `praxis.domain-catalog/v0.2` with explicit
  semantic ownership, lifecycle, business glossary, resolution metadata and
  source evidence keys on generated context/node items.
- Domain catalog aliases are now materialized from generated labels and stable
  runtime identifiers such as field names, workflow action IDs and UI surface
  IDs.
- Domain field governance now recognizes operational risk and regulatory
  compliance vocabulary, so AI context can classify mission, incident,
  jurisdiction, approval and blocking fields beyond privacy/financial signals.

### Fixed
- Domain catalog governance now emits config-compatible enum values for
  `annotationType`, `dataCategory` and `aiUsage.visibility`, including
  `security`, `operational`, `legal` and `summarize_only`.

### Validated
- `praxis-api-quickstart` consumes this release with `praxis-config-starter`
  `0.1.0-rc.6` and validates critical `/schemas/domain` payloads against the
  config-starter schema contract.

## [8.0.0-rc.7] - 2026-04-21

### Added
- `GET /schemas/domain` as the runtime semantic domain catalog surface.
- Domain catalog nodes for contexts, concepts, actions, surfaces, states, policy hints and DTO fields.
- Domain catalog edges, bindings and evidence derived from runtime annotations, option sources and OpenAPI schemas.
- Field extraction for canonical `/schemas/filtered` references, including wrapper and `$ref` resolution.

### Changed
- `OptionSourceRegistry` now contributes semantic option-source signals to the domain catalog.
- Auto-configuration registers the semantic domain catalog service and controller.

## [8.0.0-rc.6] - 2026-04-20

### Removed
- Superficies paralelas de CRUD removidas para consolidar o baseline `resource-oriented`.
- Suite de testes e fixtures ajustadas para manter uma unica hierarquia canonica.
- Fallback configuravel de payload escalar para filtros de range removido; ranges aceitam apenas lista ou objeto canonico.

### Changed
- `DynamicSwaggerConfig` passa a reconhecer controllers da hierarquia `AbstractResourceQueryController`.
- Guias publicos passam a apontar onboarding ativo apenas para o baseline resource-oriented.
- `README.md`, `docs/index.md` e `docs/spec/CONFORMANCE.md` passam a tratar `option-sources` como superficie publica canonicamente suportada quando o recurso publica `OptionSourceRegistry`.
- `OptionSourceDescriptor` passa a carregar e publicar `dependencyFilterMap` diretamente para qualquer tipo de option-source, preservando a cascata canonica em `x-ui.optionSource` quando o campo dependente difere da chave de filtro.
- Guia do consumidor piloto passa a ser guia de adocao canonica, sem narrativa de migracao entre modelos.
- `/capabilities` passa a publicar detalhes governados da operacao `export` apenas quando o service declara suporte real a exportacao de colecao.
- `POST /{resource}/export` passa a expor headers de limite, truncamento, linhas candidatas e warnings quando o resultado inline trouxer esses metadados.

### Added
- Rollout do baseline semantico `resource + surface + action + capability`, com `@UiSurface`, `@WorkflowAction`, `GET /schemas/surfaces`, `GET /schemas/actions` e snapshots agregados em `/capabilities`.
- Auto-configuracao canonica de `OptionSourceQueryExecutor`, `OptionSourceEligibility` e `OptionSourceRegistry` agregado para discovery e enrich de `/schemas/filtered`.
- Contrato rico de Entity Lookup para `x-ui.optionSource` com `RESOURCE_ENTITY`, incluindo `entityKey`, paths de display/status/busca, `dependencyFilterMap`, `selectionPolicy`, `capabilities` e `detail`.
- Execucao JPA de `RESOURCE_ENTITY` rico, com busca multi-campo, reidratacao por IDs e `OptionDTO.extra` governado para Entity Lookup.
- Superficie canonica `POST /{resource}/export` para exportacao de colecao, com request preservando escopo, selecao, filtros, ordenacao, campos e limites.
- Camada reutilizavel de exportacao de colecoes com executor canonico e engines CSV/JSON tabulares.
- Guia canonico `docs/guides/COLLECTION-EXPORT.md` para contrato, responsabilidades do recurso, capabilities, headers, limites e checklist de publicacao.

### Fixed
- Corrigida a lacuna que impedia `option-sources` reais de funcionar apenas com o starter: recursos que expoem `OptionSourceRegistry` agora publicam `x-ui.optionSource` em `/schemas/filtered` e executam `POST /{resource}/option-sources/{sourceKey}/options/filter` e `GET /{resource}/option-sources/{sourceKey}/options/by-ids` via auto-configuracao padrao.
- Exportacao CSV passa a proteger contra formula injection mesmo quando o valor perigoso vem depois de whitespace inicial.
- Requests de exportacao com campos informados, mas nenhum campo suportado pelo recurso, passam a falhar em vez de cair silenciosamente para os campos default.

### Documentation
- `README.md` e `docs/spec/CONFORMANCE.md` passam a citar explicitamente `/schemas/surfaces` e `/schemas/actions` como superficies publicas canonicas de discovery.
- `README.md`, `docs/index.md` e o guia `docs/guides/OPTIONS-ENDPOINT.md` passam a integrar a checklist minima de validacao de `option-sources` e o posicionamento canonico dessa superficie no starter.
- `README.md`, `docs/index.md`, `docs/spec/CONFORMANCE.md`, guias e checklist tecnica passam a documentar a politica de exportacao de colecoes, incluindo limites corporativos, truncamento, headers e allowlist de campos.

## [2.0.0-rc.7] - 2026-03-21

## [5.0.0-rc.2] - 2026-03-24

### Fixed

- Corrige a resolucao de `x-ui.resource.idField` em `/schemas/filtered` para que request schemas com campos relacionais `...Id` nao publiquem uma FK como identificador canonico do recurso quando chamados diretamente.
- Endurece a cobertura de regressao no starter e no quickstart para o cenario de request schema com relacoes.

### Added
- Novo endpoint `GET /schemas/catalog` como superficie canonica de discovery, exemplos operacionais e navegacao para `request`/`response` schema.
- Arquivo fisico `LICENSE` (Apache 2.0) adicionado ao root do modulo para alinhar repositorio, artefato e distribuicao publica.

### Changed
- `ApiDocsController` agora separa payload estrutural de payload documental no calculo de `ETag` e `X-Schema-Hash`, evitando invalidar cache por mudancas apenas em exemplos/documentacao.
- `x-ui.operationExamples` passou a respeitar `schemaType=request|response` no payload de `/schemas/filtered`.
- Exemplos derivados do OpenAPI podem ser complementados ou sobrescritos por `x-ui.operationExamples` explicito na propria operacao.
- Extracao de examples passou a preservar `externalValue` alem de `summary`, `description` e `value`.
- `DomainCatalogController` agora publica `schemaLinks.request` e `schemaLinks.response` apontando diretamente para `/schemas/filtered`.

### Fixed
- Corrigido o acoplamento indevido entre metadados documentais e hash estrutural do contrato retornado por `/schemas/filtered`.
- Melhorada a codificacao de links do catalogo para paths com `/`, espaco e outros caracteres reservados.
- Tornado mais robusto o merge de exemplos operacionais entre OpenAPI derivado e overrides explicitos por recurso.

### Documentation
- `SCHEMA-INTEGRATION-PLAN.md` atualizado para refletir a separacao formal entre contrato estrutural (`/schemas/filtered`) e catalogo/documentacao (`/schemas/catalog`).
- `CONFORMANCE.md` e a spec `x-ui-operation.schema.json` atualizadas para documentar `operationExamples`, incluindo `externalValue`.
- `README.md` e `docs/overview/VISAO-GERAL.md` alinhados para a nova RC.

## [1.0.0-rc.6] - 2025-11-06

### Added/Changed
- Resolver: serializacao completa de x-ui por propriedade, incluindo `tableHidden` e `formHidden` (visibilidade por contexto).
- Suporte a propriedades avancadas de `@UISchema` em x-ui (layout/icones, condicionais, triggers, numericos, validacoes e mensagens, arquivos).
- Fallbacks do schema OpenAPI: `name`, `label` derivado, `placeholder`, `helpText`, validacoes basicas, `enum -> options`.
- Decisao temporaria: `filterOptions` permanece `string`; registrada em `docs/spec/CONFORMANCE.md`. Follow-up detalhado em `docs/follow-ups/filter-options-array.md` para migrar para `array` conforme a spec.
- Operacao (`x-ui.operation`): chaves `displayColumns`/`displayFields` mantidas apenas na spec/exemplos (backend nao gera/consome).
- Testes: adicionados `VisibilityFlagsTest` e `ExplicitAdvancedPropsTest` cobrindo novas chaves/flags.

## [1.0.0-rc.1] - 2025-10-31

### Changed
- Migracao para repositorio standalone `praxis-metadata-starter` com metadados SCM corrigidos.
- Adicionado workflow de release para Maven Central com extracao de versao via tag `v*` e fallback de GPG key id.
- Adicionado workflow de documentacao (Javadoc + Markdown -> HTML) publicado em `gh-pages`.
- Heuristica de `controlType` (strings): threshold de `textarea` ajustado de `>100` para `>300` e deteccao por nome com maior precedencia para campos single-line (e.g., `nome`, `titulo`, `assunto` -> `input`).
- Enums: inferencia por cardinalidade (`<=5` -> `radio`, `6-25` -> `select`, `>25` -> `autoComplete`).
- Booleanos: padrao `checkbox` (ou `toggle`); `radio` quando enum textual binaria.
- Arrays de enums: pequeno -> `chipInput`; maiores -> `multiSelect` e dica `filterControlType = multiColumnComboBox`.
- Percent: aplica `numericStep=0.01`, `placeholder="0-100%"`, `numericMin=0`, `numericMax=100` (quando ausentes).
- Filtros: novas operacoes adicionadas - `NOT_EQUAL`, `GREATER_OR_EQUAL`, `LESS_OR_EQUAL`, `NOT_LIKE`, `STARTS_WITH`, `ENDS_WITH`, `NOT_IN`, `IS_NULL`, `IS_NOT_NULL`.
- Filtros (Lote 1 - Core): `BETWEEN_EXCLUSIVE`, `NOT_BETWEEN`, `OUTSIDE_RANGE`, `ON_DATE`, `IN_LAST_DAYS`, `IN_NEXT_DAYS`, `SIZE_EQ`, `SIZE_GT`, `SIZE_LT`, `IS_TRUE`, `IS_FALSE`.

### Documentation
- Endpoints Overview enriquecido (`doc-files/endpoints-overview.html`):
- Problemas que resolve, funcionamento interno, parametros/retornos, erros/limites.
- Notas de integracao frontend por endpoint (debounce, multi-sort, includeIds, infinite scroll, reset de cursores, jump-to-row, reidratacao de options, uso do `X-Data-Version`).
- Anti-patterns e exemplos praticos: cursor pagination (JS), jump-to-row (`/locate`) e reidratacao (React/Angular).
- Ancoras por endpoint e links cruzados a partir da pagina overview do Javadoc.
- Javadoc ampliado:
- Controllers resource-oriented: detalhes de `/filter`, `/filter/cursor`, `/locate`, `/options` (inclui blocos "Uso em DTOs (@UISchema)").
- `@UISchema`: secao "Referenciando endpoints de Options em DTOs" (OptionDTO vs DTO completo; combos dependentes com interpolacao; reidratacao).
- `OptionDTO`: exemplo de referencia em DTOs com `@UISchema`.

### Behavior
- Respostas de `GET /{id}`, `POST /` e `PUT /{id}` agora anexam o cabecalho `X-Data-Version` quando o service expoe `getDatasetVersion()` (padronizacao com os demais endpoints).

### Migration Notes
- Campos `string` que eram inferidos como `textarea` apenas por `maxLength` entre `101` e `300` agora serao `input` por padrao. Para manter `textarea`, use `@UISchema(controlType=TEXTAREA)` ou utilize nomes semanticos como `descricao`/`observacao`.

### Notes
- Este RC prepara a publicacao `1.0.0` final; sem mudancas de API em relacao ao beta.1.

## [1.0.0-beta.1] - YYYY-MM-DD

### Added
- New annotation `@OptionLabel` to declare the label source for OptionDTO on entity field or getter (supports inheritance).
- Default `OptionMapper` fallback in resource services: if `getOptionMapper()` is not overridden, entities are projected to `OptionDTO` using `extractId()` and `computeOptionLabel()`.

### Compatibility
- No breaking changes. Existing services and custom mappers continue to work unchanged.
