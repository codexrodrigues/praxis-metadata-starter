# Operações em lote na base resource-oriented

O candidato local da Onda 3 compõe o protocolo existente com
`AbstractBulkGovernedResourceController`, `JpaResourceBulkEngine` e
`AbstractBaseResourceService.bulkDomainAdapter`. O host declara recursos, DTOs,
políticas e infraestrutura; não precisa repetir o parser HTTP nem as nove rotas.
Esta composição atende `SYNC`/`PER_ITEM`, com `UNIFORM_UPDATE`, `PER_ITEM_UPDATE`
e `DOMAIN_COMMAND`. O kernel durável existente continua sendo a autoridade de
propostas, quotas, READY, fencing, recibos, replay e cancelamento.

## Contrato HTTP e identidade

`PraxisBulkWebAutoConfiguration` registra o conversor de entrada antes de Jackson,
um customizer do documento OpenAPI e `BulkOperationIdentityDiscovery`. Nenhum
deles conecta ao banco, migra tabelas, publica READY ou inicia workers ao adicionar
o JAR. Um bean próprio de `PraxisBulkProtocolHttpMessageConverter` substitui o padrão.

O codec vem do ID real da hierarquia do controller: `Integer` usa inteiro JSON
int32; `Long` usa string decimal canônica int64; `String` usa string. UUID e IDs
compostos usam seus codecs existentes. Não declarar `String` no lugar do ID de
domínio para contornar o parser. Os parâmetros Java de wire ID da base são opacos;
o binding canônico resolve String/Integer antes da composição estrutural.

O conversor mantém os limites, rejeição de chaves duplicadas, operadores e bindings
reservados de `BulkProtocolReader`. Filtros `selection.filter` passam pelos
`FilterPayloadNormalizer` ordenados antes da conversão ao FilterDTO concreto.
O ObjectMapper global não é modificado. A saída continua usando Jackson ordinário.

A leitura do DTO de atualização mantém os tipos estritos na avaliação e na confirmação.
Antes de aplicar as mudanças, o adaptador reconstrói o DTO pelo hook `toBulkUpdate`.
A projeção da resposta persistida preserva as conversões configuradas pelo host e lê
o decimal textual mesmo com a coerção escalar desativada. Uma resposta que publica
um decimal nativo também pode projetá-lo para um DTO de atualização textual.
Um `BigDecimal` explicitamente declarado com `@JsonFormat(shape = STRING)` usa sua
representação decimal textual também no plano confirmado, preservando precisão e escala.
SET exige uma string decimal válida dentro do limite de comprimento numérico do mapper.
Um token JSON numérico nesse campo é rejeitado. Omitir a mudança conserva o valor anterior,
CLEAR autorizado produz null e a string `"0"` representa zero.
Essa declaração não admite strings em números comuns, IDs ou booleans. Deserializers
customizados do host continuam responsáveis por seus próprios contratos; não é
necessário habilitar coerções gerais para transportar um decimal exato.

## Adoção do controller e dos schemas

Herde `AbstractBulkGovernedResourceController<R, ID, F, C, U>` e declare
`@ApiResource(resourceKey=...)` e `@BulkResourceOperations`, incluindo
`updateSourceOperationId` e `protectedUpdateFields` com as identidades, versões e
campos exclusivos de workflow aceitos no DTO. No PUT unitário, declare `@BulkResourceOperation(UPDATE_SOURCE)` e um
`@Operation(operationId=...)` correspondente a `updateSourceOperationId`; se
usar o PUT da base, sobrescreva o método apenas para essas declarações e delegue
para `super.update`. Não anote uma identidade imutável com `@BulkEditable`.

As rotas herdadas são:

| Método | Rota relativa | Resultado público |
| --- | --- | --- |
| POST | `/bulk/update/evaluation` | `BulkProposal` |
| POST | `/bulk/update` | `BulkExecution` |
| POST | `/bulk/update-items/evaluation` | `BulkProposal` |
| POST | `/bulk/update-items` | `BulkExecution` |
| GET | `/bulk/proposals/{proposalId}` | `BulkProposal` autorizado |
| GET | `/bulk/proposals/{proposalId}/results` | Página por cursor |
| GET | `/bulk/executions/{executionId}` | `BulkExecution` autorizado |
| GET | `/bulk/executions/{executionId}/results` | Página por cursor |
| POST | `/bulk/executions/{executionId}/cancel` | `BulkExecution` autorizado |

Confirmações exigem `Idempotency-Key`. Resultados aceitam `size` (1–200) e `after`.
Todas as respostas usam `RestApiResponse`: a projeção fica em `data` e os links
em `_links`. Links de leitura (`self`, `results`, `proposal`, `next`/`prev`)
resolvem as operações reais pelo `CanonicalOperationResolver`; affordances de
confirmação/cancelamento continuam sob o contrato de capabilities. Os helpers de
comando também devolvem esse envelope canônico.
Os quatro IDs de update herdados são `<resourceKey>.bulk.update`,
`<resourceKey>.bulk.update.evaluate`, `<resourceKey>.bulk.update-items` e
`<resourceKey>.bulk.update-items.evaluate`. Os cinco IDs de leitura/cancelamento
vêm da declaração existente. Um host com IDs públicos já estabelecidos pode
sobrescrever os quatro métodos apenas com `@BulkResourceOperation` da função
correspondente, `@Operation(operationId=...)` e delegação a `super`. Esses IDs
explícitos passam pelo mesmo binding MVC; ambiguidades são rejeitadas. Não há
reconhecimento por nome Java ou label.

Para combinar lote e preparo não persistente de cópia, use
`AbstractBulkGovernedDuplicateDraftResourceController`. A composição preserva o
opt-in de `duplicate-draft`, o DTO explícito e os gates create/view/duplicate-draft;
herdar somente a base de lote não publica duplicação.

Implemente `getBulkEngine()` e os hooks `bulkProposalOperationId`/
`bulkExecutionOperationId` para selecionar a operação de confirmação correspondente
ao registro retido. Esses hooks são roteamento interno; não substituem autorização.
Devem respeitar namespace/recurso e não expor a existência de registros fora do
escopo. O engine executa as quatro leituras pelos readers `BulkAuthorized*`, com
reautorização do conjunto completo e cursores autenticados. O cancelamento também
exige leitura autorizada antes de solicitar a transição.

`CustomOpenApiResolver.synthesizeBulkEvaluationSchemas` materializa os schemas no
documento usado por `OpenApiDocumentService`, antes da fotografia governada. Para
`EmployeeController`, publica `EmployeeBulkUniformEvaluationRequest`,
`EmployeeBulkItemEvaluationRequest` e `EmployeeBulkCommandEvaluationRequest`.
Identidades vêm do codec; filtros e parâmetros referenciam os DTOs concretos.
Comandos diferentes no mesmo recurso recebem nomes distintos quando necessário.
As classes manuais de envelope OpenAPI deixam de ser necessárias. A referência
canônica do schema continua sendo `/schemas/filtered` da operação real.

## Composição explícita do engine

Forneça ao `JpaResourceBulkEngine` a mesma `BulkExecutionInfrastructure` usada pelo
`BulkOperationLifecycle` e pelos providers, o adapter do service, codec, allowlists
de `BulkEditableFields.compile`, ObjectMapper, `BulkResourcePolicy`, configuração
de cursores e mapa de adapters de comandos. A allowlist local precisa coincidir
com a compilada do PUT publicado e dos `protectedUpdateFields`; divergências
fecham a avaliação. Configure o `BulkOperationDescriptorProvider` de cada operação,
seus limites e revisões como no protocolo existente.

`BulkResourcePolicy` é a autoridade explícita do host para contexto autenticado,
proveniência da avaliação, admissão atual por unidade, autorização de leitura e
projeções públicas. Nenhum grant é inferido de READY, do payload ou do nome da
operação. A política de admissão deve reconsultar as autoridades necessárias e
usar o orçamento remanescente da unidade. Rejeitar indisponibilidade da autoridade.
O SecurityFilterChain do host continua governando as rotas executáveis.

Use `BulkOperationIdentityDiscovery.discover(namespace)` depois da inicialização
dos mappings MVC para alimentar a instalação explícita. A descoberta usa os
bindings validados e `CanonicalOperationResolver`, sem buscar OpenAPI nem exigir
publicação prévia. Declarações incompletas/ambíguas falham; nenhuma lista manual
de confirmações precisa acompanhar novos controllers.

## Avaliação, confirmação e extensões de domínio

A avaliação captura o lote sob o teto de 30s do protocolo de propostas, conservando o menor saldo do chamador e a expiração. Esse orçamento é diferente de `BulkOperationalProfile.unitDeadline`, limitado a 5s para cada unidade PER_ITEM ou para o conjunto inteiro em ATOMIC. O adapter usa uma única constante do store para a transação e o deadline monotônico; não renova o prazo por alvo e não faz retry automático da captura. Consulte [persistência protegida](../spec/BULK-PROPOSAL-STORAGE.md) para os clamps, locks e limites de cancelamento.

A seleção aplica o FilterDTO e `resolveResourceFilterAccessScope` nas consultas
QUERY e EXPLICIT, com limite e exclusões. O hook
`resolveBulkResourceFilterAccessScope` pode acrescentar um escopo obrigatório da
operação; ele é combinado com o escopo ordinário antes de seleção e limite. O callback de captura e o armazenamento
da proposta usam uma transação operacional `REPEATABLE_READ`.

`bulkDomainAdapter` resolve o UpdateDTO do service, projeta o response para esse
contrato e aplica `SET`/`CLEAR` sobre a cópia JSON completa. Omissão preserva os
valores anteriores. Bean Validation, custom fields e `beforeUpdate` são executados
em cópia destacada da entidade. `applyResourceUpdate(id, entity, dto)` centraliza
hidratação/mapeamento de domínio e é usado tanto no PUT quanto nessa avaliação;
o default chama o mapper. A conversão do plano rejeita coerção escalar, sem
alterar o ObjectMapper global. Hooks de avaliação devem ser livres de escrita;
dirty checking rejeita alterações incidentais em entidades gerenciadas.
Sobrescreva `toBulkUpdate` quando os shapes de response e update diferirem e
`snapshotBeforeMutation` quando o domínio exigir cópias de associações mutáveis.
O default recusa associações que não consegue copiar com segurança.

Para decisões dependentes da operação, use a
[admissão contextual de mutações](RESOURCE-MUTATION-ADMISSION.md): avaliação e
confirmação encaminham o modo real, a etapa e o instante do servidor. A
revalidação de confirmação usa o contexto de confirmação, e a evidência retornada
por `validateUpdate` integra a fotografia protegida comparada antes da escrita.

Falhas previsíveis bloqueiam o alvo. Violações de regras preservam códigos,
mensagens e campos de correção do catálogo da Onda 2 na evidência protegida.
Somente mensagens explicitamente permitidas por `publicDiagnostics` entram na
prévia pública; textos técnicos e causas de exceções não são publicados. O
catálogo permitido pode ser maior que a prévia: somente os pares categoria/código
emitidos são projetados, mantendo o limite público de 64 diagnósticos. Uma
entrada explícita do host localiza a mensagem do mesmo par canônico.

Na confirmação, `JdbcBulkDurableExecution` reserva a proposta, reconhece replay e
executa unidades em sua transação operacional. A política é reavaliada; mudanças
na governança, versão, fatos ou plano impedem a escrita. O update do service
adquire `PESSIMISTIC_WRITE`, confere o ETag do item e executa mapper,
`beforeUpdate`/`afterUpdate`, flush e refresh. Alteração e recibo confirmam ou
revertem juntos. Se a revisão persistida permanece igual, o recibo registra
`UNCHANGED`; uma revisão nova registra `CONFIRMED`. A resposta vem do reader
público autorizado.

Para `DOMAIN_COMMAND`, declare endpoints reais com `@WorkflowAction` de coleção,
`@BulkOperation(mode=DOMAIN_COMMAND)` e DTO concreto de parâmetros. Use os helpers
`evaluateBulkDomainCommand`/`confirmBulkDomainCommand`. O
`BulkDomainCommandAdapter` deve avaliar sem escrita e, ao confirmar, adquirir os
locks e validar o ETag antes dos efeitos de domínio. Pode reutilizar
`executeVersionedMutation` do service. Regras, dependências e efeitos pertencem ao
domínio; nenhum dispatcher de comandos é inferido de palavras ou caminhos.

## Limites deste corte

Durante a avaliação, `bulkDomainAdapter.discardEvaluationState` limpa o contexto
JPA se um candidato deixar alterações não persistidas, impedindo contaminação do
próximo item. A limpeza nunca faz flush. A projeção de UPDATE aceita custom fields
read-only idênticos ao persistido, preservando o snapshot de PUT/bulk.

Na confirmação, falhas funcionais reconhecidas durante a própria gravação
(`412`, conflito de estado `409`, regra/validação `422`, acesso/existência)
fazem rollback de todos os efeitos do item. Após esse rollback, o kernel registra
o resultado local sob os mesmos fences de execução e permite o próximo item.
Durante a própria gravação, falhas técnicas continuam interrompendo a execução;
commit incerto continua
exigindo reconciliação. Não se publica recibo de sucesso para um item rejeitado.

A avaliação/admissão e a mutação compartilham a classificação de falhas por
alvo. Ausência concorrente gera `TARGET_NOT_FOUND`; conflito de estado mantém
`TARGET_STATE_CONFLICT`. Decisões técnicas/inconclusivas e indisponibilidade
não viram validação. Erros do payload de campos usam
`BulkFieldChangeValidationException`; uma `IllegalArgumentException` genérica de
callback ou configuração não é presumida como erro de dados. Consulte as
[correções R03–R05](../technical/P5-A-CORRECTIONS-2026-11-02.md).

O controller possui resolução default dos IDs de propostas e execuções por
`BulkOperationStatusLookup`, auto-configurado apenas com infraestrutura bulk
explícita. A correlação filtra namespace/recurso e aceita somente operações
reais de `BulkOperationIdentityDiscovery`. Isso resolve roteamento; os readers
continuam exigindo autorização atual e não recebem permissão dessa correlação.

O engine passa sua `BulkResourcePolicy` ao lookup. Resolução de bindings MVC e
contexto autenticado ocorre antes do JDBC; os grants globais são consultados
dentro do snapshot de leitura, antes de qualquer consulta por ID. A correlação
filtra somente operações autorizadas. Sem grant, IDs conhecidos e ausentes têm
o mesmo 403; autoridade ausente/indisponível falha com 503. Permissão parcial não
permite correlacionar operações negadas. O reader ainda revalida o grant e a
visibilidade do conjunto completo. Chamadas diretas ao lookup devem fornecer a
política; a assinatura antiga sem autoridade foi removida no candidato beta.

Este guia descreve fonte local não publicada. Migração de consumidores, publicação
Maven, integração Neon, ASYNC/worker e composição de operações ATOMIC não são gates
da Onda 3. Os contratos e provas desses mecanismos existentes permanecem nos
respectivos documentos do SDK. A adoção do Ergon Showcase pertence à Onda 4.
