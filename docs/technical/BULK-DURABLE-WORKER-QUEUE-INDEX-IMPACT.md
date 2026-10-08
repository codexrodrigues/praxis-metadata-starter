# B5b.2 — mapa de impacto do acesso ordenado à fila (V3)

Estado: proposta concreta V3 para revisão ROOT antes de alterar fontes de produto; V1 preservada em evidência, sem gate de produto. 08/10/2026.
Classificação: arquitetural, com impacto transversal na adoção futura do schema.
Aderência: suportado-parcialmente. Namespace, modo, status, data, UUID, cursor e
claim já existem; falta caminho físico adequado ao seletor existente. Não há
lacuna de DTO/endpoint/anotação nem novo contrato de intenção.

## Motivação e dono

A campanha `retained-growth-one-run1`, aceita por ROOT (SHA256
`a4f9556334aef529859aa00968718442ab22d25de11ad2a5dc22c1550a4f407b`),
observou SeqScan+Sort de34/130/514 linhas com apenas dois QUEUED e histórico
STOPPED32/128/512. Scan4/12/49 páginas, SQL/lock1s, três direitos QUEUE fixos,
zero ACTIVE. Diagnóstico finito PG14 em cache; não prova prazo violado ou SLO.
Dono: Metadata, `BulkExecutionMigrator` e migrations canônicas. Consumidor
concreto: `JdbcBulkDurableExecution.workerNextQueued` do worker privado.

## Incremento proposto e arquivos

1. Nova `db/praxis-bulk-migrations/V20__bulk_worker_queue_index.sql`:
   índice btree não único `praxis_bulk_execution_worker_queue_idx` na tabela
   `praxis_bulk.praxis_bulk_execution`, chaves `(namespace_id, created_at,
   execution_id)`, predicado `execution_mode = 'ASYNC' AND status = 'QUEUED'`.
   Sem INCLUDE, expressão, ordem DESC, mudança de collation, IF NOT EXISTS,
   GRANT, função, trigger ou bootstrap novo. V1–V19 permanecem byte a byte.
2. `BulkExecutionMigrator`: validar V19 histórica antes de Flyway/DDL;
   permitir historicalVersion19 somente no caminho de preflight explícito.
   Atualizar também `currentHistoryVersion` máximo19→20; nenhuma versão futura
   desconhecida é aceita. Alterar a composição em etapas descrita abaixo;
   não aplicar latest20 diretamente a fresh/17/18 antes de COMPLETE19.
   Exigir V19 occupancy COMPLETE, catalog/ACL/fingerprints existentes válidos
   e ausência do novo índice no histórico19. Não aceitar versão desconhecida.
   No caminho atual V20, exigir occupancy COMPLETE e índice exato. Atualizar
   a condição hoje exclusiva `historyVersion == 19` de forma explícita,
   preservando os caminhos históricos17/18 e seus bootstraps específicos.
3. Validador de índice dedicado no mesmo migrator: consultar pg_index,
   pg_class/pg_namespace/pg_am/pg_attribute/pg_opclass/pg_roles. Exigir nome,
   tabela e schema exatos, owner `expectedSchemaOwnerRole`, btree,
   indisvalid/indisready/indislive true, indisunique/indisprimary false,
   três keyatts e natts (sem INCLUDE), ausência indexprs, attnums/nomes
   namespace_id/created_at/execution_id em ordem, indoption0, collations das
   colunas, opclasses pg_catalog default compatíveis com os tipos canônicos.
   Predicado e pg_get_indexdef devem coincidir com a forma canônica normalizada,
   não apenas conter nomes. Relação physical-index/history deve ser exata.
   Incorporar ao inventário orphan apenas no ramo V20 validado; não liberar
   índice homônimo defeituoso nem índices adicionais por whitelist genérica.
4. Teste novo `BulkWorkerQueueIndexPostgresTest`: fresh20, histórico19→20,
   replay0, drifts físicos e invariantes abaixo. Fixture/testes de migração
   atuais atualizam somente expectativas de latest; fatos históricos19
   continuam19. Inventário detalhado precede cada alteração de expectativa.
5. Reutilizar `BulkDurableWorkerQueuePlanPostgresTest` para medir o SQL real
   após V20: inicial, cursor posicionado, esgotado nos três níveis já definidos.
   Não alterar kernel, quantum, rosters, claim, timeout ou direitos para
   produzir plano desejado. Registrar planos completos mesmo se o otimizador
   escolher SeqScan em tabela pequena. Exigir resultado/keyset correto e
   índice físico utilizável; não forçar planner nem vender uso de índice
   como garantia universal. Comparação de custos/linhas é diagnóstico.

## Upgrade, estados e locks

Executar por owner fora de transação Spring, com datasources/roles oficiais,
conexões independentes e advisory xact lock `(1347574124,5)` existente.
Preflight19 válido precede V20; CREATE INDEX transacional e lock de DDL
são internos ao Flyway, não rowlocks de execução. Não introduzir concorrência
CONCURRENTLY ou nova ordem nos locks de negócio (marker/publicação/controle/
execução/slot). A membership do índice acompanha status/mode por PostgreSQL;
nenhuma mutação de jobs, receipts, ocupações, slots, direitos ou foto global.
Snapshot antes/depois inclui history1–19/checksums, bootstrap COMPLETE,
namespace bindings, controles, publicação, instalação e ACL de roles.
Replay20 deve validar antes de qualquer bootstrap: drift não se autocorrige/reabre.
A validação estrita do índice pertence ao owner migrate/validate/readiness do
schema. `validateLiveRuntimeRoleAccess` permanece com suas verificações atuais
de role/ACL/funções e budgets: não acrescentar check físico por job nem alegar
recusa online por índice removido. Drift físico operacional requer diagnóstico
no lane owner/runbook, sem autocorreção; índice não é autoridade de negócio.

### Etapas de migração e retomada (substitui a premissa COMPLETE exclusivo de V1)

A. Sob coordenação owner oficial e re-leitura da history, aplicar somente até19
com Flyway target19 quando a history for menor que19. Preservar V17/V18
preflights existentes e checksums; não executar20 nesse estágio. Se a leitura
já for20, não configurar downgrade/target19: validar current20 antes de entrar
em qualquer bootstrap. Rejeitar unknown ou índices prematuros históricos.

### Gate owner phase-aware antes de recuperar grants

Não basta chamar initializer existente e validar ao final. Antes de qualquer
provisionamento/backfill/grant de recuperação, adicionar validação owner
phase-aware do catálogo19, reutilizando os validadores existentes com a fase
real de cada latch. Reutilizar o mesmo dono/catálogo, sem SPI/registry público.

Inventário explícito: V8 manifest, V9 preview, V11 integrity, V12 reader,
V16 atomic, V18 capacity-read e V19 occupancy. Ler exatamente a linha/versão e
fase PENDING ou COMPLETE de cada marker; rejeitar ausente/duplicado/fase
inválida. Verificar owner/estrutura/checks/funcões/triggers/ACL/inventário de
objetos/Flyway target19 e ausência do índice20 antes de qualquer grant. Não
permitir ACL de COMPLETE com base em outro marker PENDING. Funções cujo grant
é controlado por latch usam exatamente zero concessões quando PENDING e
allowlist provisionada quando COMPLETE. Não usar ``algum pending`` como
permissão geral para grants/recriação. Para rows: validar vínculos/evidência
persistidos sem exigir como já concluídas projeções/backfills que sua própria
fase PENDING ainda deve derivar; evidência COMPLETE permanece íntegra, nunca
rederivada para esconder drift. Dados incompatíveis negam antes de mutation.

Na transação real do initializer, os locks já existentes de latches8/9/11/12/16
precedem todos grants; revalidar as fases e o catálogo pertinente depois dos
locks e antes de bootstrapLifecycle/backfill/grants. V18 e V19 mantêm suas
transações de owner, lock do próprio marker e validação da própria fase/ACL
antes e após grants. Não manter conexão com advisory global externo enquanto
essas transações tentam adquirir seus próprios locks. O preflight owner não
substitui esses rechecks. Depois de recuperação canônica, exigir COMPLETE19
no fullpreflight que libera20. Para entrada já20, fullvalidate current20
antes de qualquer bootstrap; o índice físico não ganha bypass para PENDING.

Provas de falha: reproduzir caso existente wrongRuntimeRoleSet deixando
history19 e V16/V18/V19PENDING; correto retry agora executaumaV20 depois de
completar latches, preservando history1–19 (não comparar history inteira como
igual após incluir20). Fresh19 interrompido pode ter todos os sete latches
PENDING: retoma somente owner válido, grants canônicos e COMPLETE precedem20.
Corromper ACL/catalog em fase COMPLETE com outro marker PENDING deve negar
semcura. Drifts/fase ilegal/índice prematuro rejeitam antes grants/DDL20.
Não editar fatos históricos de evidência antiga para parecer nova execução.

B. Fora da conexão que mantém o advisory do estágioA, executar initializer e
bootstraps canônicos nas suas transações/locks existentes. Retomar PENDING
válido inclusive dos latches8/9/11/12/16/18/19 após fresh19 interrompido;
PENDING não significa storage servível. Usar os checks/grants owner oficiais,
sem repair nem reaplicar grants a COMPLETE defeituoso. Não chamar initializer
sob o xactlock do estágioA/C: ele adquire o mesmo lock em outra conexão e
criaria espera contra si próprio. Preservar o provisionamento canônico de
namespace/operation bindings solicitado por migrate; não eliminar esse
comportamento existente com um retorno incondicional para qualquer history20.
Antes de bootstrap em20, fullvalidate current20; ausência/drift de índice ou
ACL deve negar antes de efeitos. As etapas revalidam o estado relido; leitura
anterior não substitui decisão sob coordenação após migrator concorrente.

C. Reentrar na coordenação owner, re-ler history. Se19, exigir fullpreflight
histórico19 com COMPLETE e novo índice ausente, então Flyway latest20. Se outro
migrator já deixou20, fullvalidate current20, nenhum DDL duplicado. Bootstrap
pendente inválido não autoriza20. Resultado migrationsExecuted soma somente
migrations de fato executadas pela própria chamada: fresh20,17→3,19→1,replay0
em execuções sem concorrência; concorrência usa contagens reais por chamada.
Não sustentar uma conexão de coordenação durante bootstrap em outra conexão.

D. Fullvalidate current20 ao terminar: history/checksums/COMPLETE/índice/catálogo/
ACL exatos. Provar falha apósDDL19 antesbootstraps, falha no bootstrap, retry de
V19PENDING legítimo, negação de drift COMPLETE semcura e replay20. Falha entre
etapas deixa o estado real documentado; não declarar transação única de todas
as etapas ou validar PENDING como serving. Recursos owner seguem contratos
existentes; pool precisa acomodar conexões oficiais e a prova de recursos.


Cutover conservador: parar admissão/drain de executores antigos antes do DDL,
janela owner de manutenção, validar catálogo20 e só então iniciar candidato.
SDK antigo atesta catálogo19 e pode rejeitar índice desconhecido; não há
promessa de rollout misto. CREATE INDEX pode bloquear escritas e custar I/O;
este corte não certifica build online ou duração para histórico de produção.
Falha não autoriza mover tag, apagar history, ampliar privilégio ou timeout.
Orçamento de seleção1s permanece; orçamento de migração é o já oficial,
sem transformar a coordenação em garantia temporal geral do DDL.

## Provas mínimas e recursos

MAIN único escritor/Maven/target/PG/Git; ROOT revisa fonte congelada antes
Maven e evidências depois. Java21/cache privado/GAV privado; host rc.155 intacto.
- Fresh public migrator:20 migrations, catálogo exato e bootstrap COMPLETE;
  replay0, permissões inalteradas; runtime sem privilégio para criar/dropar.
- Histórico genuíno19 por JAR público rc.155 isolado (origem/checksum e
  CodeSource atestados, sem classpath misto), ou mecanismo histórico oficial
  já comprovado se ROOT aceitar: COMPLETE e dados canônicos antes do upgrade,
  uma migration20, snapshots de dados/ACL/fingerprints/history1–19 intactos.
  O JAR rc.155 já disponível em cache de adoção pública, não SDK privado.
- Negativos19: índice inesperado/occupancy bootstrap inválido/catalog drift
  rejeitados antes do registro20/DDL; não afirmar que retries legítimos
  PENDING não alteram ACL/phase: eles devem concluir via bootstrap oficial.
  Para catálogo COMPLETE com drift ou índice prematuro, negar sem autocura.
  Fotografar a fase, ACL, dados e history a cada falha/retomada; duas invocações
  owner concorrentes não duplicam20 nem grants fora dos latches canônicos.
- Negativos20: ausente, chave/ordem/tipo/opclass/predicado/owner divergente,
  expressão/INCLUDE/unique e índice adicional; shape adulterado rejeitado por
  validate e replay, sem reparar ou efeitos de domínio. Para invalid/ready/live
  usar inspeção positiva + fixture negativa suportada quando possível; não
  adulterar pg_catalog para fabricar prova. Sem promessa de caso não executado.
- Focais migration/owner-resource + histórico17→latest já existente (agora
  três migrations18/19/20); growth3níveis/9planos + namespace/default fixture.
  Separar contagem de cenário da de JUnit. Não repetir toda suite por reflexo;
  clean verify/integridade do pacote ficará para integração, após fonte estável.

## Inventário de expectativas de versão e consumidores

Há19 fontes/41 expectativas19 ao incluir todo `src/test`;18 estão em
`src/test/java` e uma é `src/test/fixtures/bulk-consumer-artifact/.../
ArtifactConsumerHttpTest.java:123`. Não substituir globalmente. Principais
latest: BulkCapacityOccupancyPostgresFixture:migrate count, owner resource
fresh migrations/count, BulkDurableMigrationPostgresTest e testes leitores/
stores com migrate fresh. Histórico17 isolado continua17; cutoffV19,
funções/V19 membership, snapshots/version='19' e fatos registrados continuam19.
BulkCapacityOccupancyCutoverPostgresTest histórico17→latest muda contagem2→3,
max19→20 e evento explicitamente latest; testar preservação de history17.
Revisar BulkOwnerMigrationResourcesPostgresTest contagem/bootstrap/checkpoint
sem renomear sua prova histórica como se tivesse sido repetida.

O consumidor empacotado ArtifactConsumerHttpTest precisa atualizar expectativa
latest e provar o JAR privado pelo fluxo de fixture oficial antes do aceite;
não instalar bytes privados na coordenada pública nem afirmar adoção Central.

Host `OperationalDatasourceMigrator` e testes de migração serão impactados na
adoção pública futura. Neste candidato não alterar POM, host DDL ou alegar
validação HTTP. Angular, Config, x-ui, endpoints, corpus público, playgrounds
não mudam por este índice privado. Docs STATUS/B1/EXEC recebem estado e provas;
runbook de schema/cutover público só incorpora comportamento estabilizado e
provas, antes da eventual release autorizada. Risco: schema mais novo rejeitado
pelo SDK antigo, custo/bloqueio owner DDL e erros nas expectativas latest.

Skills: avaliar fontes canônicas concurrency/operational-proof (PR715) e
maintenance/migration guidance. Já ensinam prova real sem planner-force;
se V20 tornar novo procedimento de upgrade/drift recorrente mal coberto,
atualizar existente no mesmo ciclo com revisão/PR/audit/sync seletivo. Não
criar skill artificial. Esta decisão final depende da implementação validada.

## Critério de saída e condições de parada

ROOT aceita mapa concreto → MAIN implementa fontes exclusivas → freeze/diff
+ ROOT sourcegate → focais necessários → ROOT aceita provas/docs/skills →
integração isolada na main atual com verificação do delta relevante.
Main remota d123 tem seis fontes MicroVisualization/stats/OpenAPI sem overlap
bulk/POM/DDL; preservar e avaliar integração combinada. Não afirmar que as
858 fontes privadas b354 certificam toda main remota.
Parar para decisão ROOT se preflight histórico exige relaxar ACL, novo direito,
mutação de autoridade, mudança de semântica/contrato ou migração não transacional.
Não declarar ASYNC público/202/READY/backend completo/release/Angular.


## Revisão V4: recuperação histórica e dispatch concorrente

Direção ROOT aceita em `root-worker-queue-index-legacy-pending-direction-review.json`
(SHA256 6b03564f59dfcf2844ed737e9a171583c2d8baa2588a7dca10ad83a2d6efb667).
Não é aceite de build ou runtime. A V3 das fontes implementa esta direção:

- Leitura da versão e attestation histórica sob o mesmo advisory existente
  (1347574124,5). A conexão é liberada antes do initializer; este confere novamente
  versão, fases, catálogo e dados depois de adquirir seus próprios locks.
- V19 com marcador manifest V8 exatamente PENDING é o único ponto owner que
  admite projeções lifecycle ausentes. V8 COMPLETE testemunha a materialização
  transacional já concluída; outras fases PENDING não autorizam repará-la.
  V18 COMPLETE exige V16 COMPLETE, além das dependências anteriores.
- O mapa explícito completo namespace/deployment acompanha ambos preflights.
  Vínculos existentes devem ser subconjunto exato dele; propostas e execuções
  retidas precisam estar cobertas. Nenhum vínculo conflitante é reescrito.
- A projeção esperada reutiliza scopeDigests/proposalAllocation/executionAllocation.
  Cada alocação persistida precisa corresponder integralmente à evidência retida.
  Só linhas ausentes podem ser derivadas pelo initializer existente. O preflight
  readonly calcula as cotas sobre a projeção inteira antes das escritas.
- READY continua exigindo o tuple global publicado. Apenas identidades deny-only
  ausentes podem ser criadas no bootstrap pendente; a correção não concede
  autoridade, não altera receipts/custódia nem recompõe evidência COMPLETE.
- Servicing e history20 continuam com validação exata, reject-only.

Cobertura fonte adicional: retained V3→raw19 com dados, preservação independente
de propostas/execuções/history e replay; vínculo conflitante no mesmo ponto19
com snapshots de fases/ACL/history e ausência de escrita. Reutilizar a prova
v3Stop e a prova causal twoIndependentMigratorsSerializeOnPendingV18LatchAndCompleteOnce.
Ainda sem execução das fontes V3.

Inventário editorial: contagens latest derivadas do predecessor aumentam uma DDL;
raw target histórico e invocações de SDKs publicados mantêm seus valores.
Retry V14 grants que deixou19 agora executa1V20. As duas fixtures de transação
bootstrap-only V19 usam target19 explícito, sem alterar seu contrato de pool.
Fotos inteiras de history preservam também a linha SCHEMA/version NULL; latest
do capacity reset e do cutoverV5 é20. Concorrência permite divisão (1,1) ou (2,0),
com soma2 e cada versão19/20 uma única vez.


## Revisão V6: ausência global no primeiro bootstrap

ROOT confirmou o erro real na retomada V7 em
`root-worker-queue-index-v5-first-run1-diagnosis.json`
(SHA256 731ee522b8b6522db9976bc1db45d52b56ad2dc7f5ed366c0140fb2dde753886).
V14 cria a tabela global sem backfill e V16 drena READY; o initializer existente
insere as identidades ausentes como UNCOMPOSED/generation0/digestnull.
O preflight deve admitir essa falta somente no mesmo limite owner history19 +
V8PENDING + vetor coerente. O mapa explícito e a evidência lifecycle continuam
conferidos antes de qualquer escrita; catálogo, ACL, READYtuple e linhas existentes
mantêm suas validações. O recheck pós-lock usa o mesmo limite. Depois do bootstrap,
a validação volta a exigir cobertura global completa. V8COMPLETE, current20 e
serving nunca restauram identidade ausente, mesmo havendo outra fase PENDING.

V6 corrige oracle de payload (full-row JSONB PostgreSQL inclui bytea em hex), usa
a mensagem do gate ACL antecipado e renomeia esse teste para rejeição antes do
bootstrap, sem alegar rollback não executado. O negativo de binding mantém
fixture original e snapshots zero: global ausente é derivável nesta fase, vínculo
conflitante não é. Uma prova negativa nova cobre V8COMPLETE com18/19PENDING e
current20/validate, fotografia completa de todas as tabelas/history, ACL e funções.

Primeiro resultado V5 RED preservado:13JUnit/3fail/1error/0skip,860fontes semdrift.
Não repetir automaticamente seis métodos index e três de migração que passaram;
ROOT avaliará validade diante do delta e o seletor focal das quatro falhas mais
esta negativa. Novo freeze/revisão de fonte antecedem nova execução.


## Revisão V7: vetor de fases no validador aninhado V18

Focal V6:5JUnit/0fail/2errors/0skip,860fontes semdrift. Três novas provas passaram:
dados retidos, conflito de vínculo e ausência global rejeitada em COMPLETE19/20.
Diagnóstico ROOT43be3306711eee0d0d359acd6ddfc1baefb6a2afade841e216f69421958b7de1
e auditor distinto8169794c990fe55571c6d15c0daad551989d4661c7ffdc6b93397de804f985c8
confirmam chamada V18 interna com Map.of, perdendo fases coerentes owner19.

A nova sobrecarga privada recebe o vetor já atestado no protected owner19
preflight. Ela repete a validação V5 com essas fases exatas, sem pular roles/ACL.
Wrappers V18, serving, current20, capacity nativa e validação pós-bootstrap
continuam strict Map.of. A janela grant-before-COMPLETE mantém seu comportamento
transacional existente. Nunca alterar o Map.of intencional pós-provision/CAS.

A fixture wrongRoles local é raw7→grantsbase paraambosroles→raw15→grant explícito
host-owned lock_openapi_publication. V8/9/11/12 ficam PENDING/zeroACL por construção;
não revogar/grantar permissões futuras para normalizar a prova. Depois da negativa,
history15 e ACL runtime anteriores permanecem, embora DDL16–19 acrescente os
próprios objetos/grants internos esperados. Helper v15 compartilhado não muda;
se seus setups raw/grants forem retomados, auditar PENDING versus COMPLETE antes
de usá-los como prova. A nova precondição rejeita pregrants não canônicos.

Próximo seletor proposto: somente os dois erros V6 (wrongRoles e V7Store).
ROOT deve revisar fontes/mapa/freeze e validade das provas componentes anteriores
antes de novo build. Não alegar suite verde ou validar toda árvore com resultados
de versões anteriores.
