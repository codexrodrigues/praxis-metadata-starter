# Migração owner do worker durável — candidato privado B5b.2

Este procedimento descreve a árvore privada que acrescenta V20, não a versão
pública rc.155 adotada pelo host. Integração, publicação, adoção e aceite operacional
são gates separados. Não há autostart, endpoint ASYNC/202 ou READY público derivado
da aprovação deste núcleo. Angular permanece após os gates de fechamento backend.

## Responsabilidades e execução

O Metadata define a semântica em `BulkExecutionMigrator`; o host fornece o mapa
explícito namespace→deployment e declara o conjunto completo de roles reais.
A identidade owner realiza DDL e bootstrap. Runtime e control não recebem direitos
owner por conveniência. Migração é opt-in de deployment e não efeito de context startup.
Não executar SQL de reparo, grants manuais ou raw Flyway para substituir a retomada
canônica. Nenhum comando deste guia autoriza alterar uma instalação corporativa.

Antes da operação, conferir o JAR realmente carregado, POM/version/checksum,
recursos SQL, história Flyway, fases dos marcadores, vínculos e roles declaradas.
Registrar limite de conexões e políticas nativas de timeout. Preservar a história
V1–V19 e seus checksums; não mover ou reescrever uma migration já aplicada.

## Ordem e invariantes

O fluxo atesta e avança a história até V19 sob o lock coordenador existente.
A validação anterior à inicialização mantém leitura da história e catálogo sob
esse lock, mas devolve a conexão antes da aquisição de recursos do initializer.
O bootstrap canônico retoma suas fases, incluindo capacity-read e occupancy.
Somente após a atestação estrita de V19COMPLETE instala V20 e valida o estado final.

O vetor de fases validado deve chegar aos validadores owner aninhados. Ele não
substitui os wrappers estritos de serving nem a validação após grants/CASCOMPLETE.
A janela transacional grant-before-COMPLETE permanece controlada pelo initializer.
Não usar um contexto PENDING para autorizar correções após a conclusão.

Em V19 histórico com V8manifestPENDING, algumas derivações de lifecycle e a linha
inicial deny-only global podem legitimamente faltar. A retomada usa o initializer
existente, funções canônicas e deployment explícito; valores já persistidos,
digests, allocations, quotas projetadas, ACLs e catálogo continuam exatos.
Uma identidade deny-only inicial não publica autoridade. V8COMPLETE com outros
marcadores PENDING, ou instalação V20, rejeita a ausência sem reparar a linha.

## Cutover de manutenção e escopo da atestação

O candidato privado acrescenta V20; o host público permanece rc.155/V19.
Interromper novas admissões e drenar executores antigos antes da manutenção owner.
Executar o upgrade canônico e a validação estrita V20 antes de admitir o candidato.
Um leitor V19 pode rejeitar V20: não há prova de rollout misto nem SLA de upgrade
online. A DDL/criação do índice pode bloquear relações e consumir I/O. O budget
SQL do coordenador não limita pool, DDL inteira, rede ou callbacks.

O initializer owner mantém o advisory `(1347574124,5)` e toma os marcadores
em ordem V8→V9→V11→V12→V16→V18→V19 antes de ler fases e ACLs em
READ_COMMITTED. Os bootstraps separados de capacity-read e occupancy mantêm
somente seus respectivos latches V18 e V19; não adquirem esse advisory.
Assim, um grant-before-COMPLETE concorrente deve confirmar ou reverter antes
da atestação do initializer. A validação serving permanece REPEATABLE_READ,
sem locks de escrita ou novos privilégios. Essa ordem não cria uma garantia
nova de timeout global nem muda os limites de pool documentados.

Conferir os sete marcadores: V8manifest, V9preview, V11preview-integrity,
V12preview-reader, V16atomic, V18capacity-read e V19occupancy. Exigir coerência
entre fases dependentes, zero ACLs controladas em PENDING e grants exatos em
COMPLETE. Outro marcador PENDING nunca autoriza reparar um marcador COMPLETE.

A atestação do índice ocorre no owner migrate/validate/readiness; o job role
mantém suas ACLs atuais. Isso não prova detecção online de drift do índice.

## Diagnóstico baseado em evidência

| Sintoma | Evidência a confrontar | Ação canônica e prova esperada |
| --- | --- | --- |
| Role declarada omitida | Roles reais/ACLs de functions e tabela; snapshot antes/depois | Corrigir declaração do host; nenhuma escrita de bootstrap no primeiro erro; retry canônico instala V20 |
| Dados antigos com bootstrap interrompido | História V19 e fases PENDING, retained rows completos e mapa deployment | Retomar via migrator; conteúdo e checksums preservados, derivações exatas, replay zero |
| Binding/digest/ACL divergente | Tupla persistida, constraints/catálogo e grants | Rejeitar sem mutation; não mascarar divergência concedendo privilégios ou regravando identidade |
| Linha global ausente após V8COMPLETE | Fases e snapshots de todas as tabelas/ACLs | Rejeitar sem healing, mesmo com outras fases pendentes |
| Pool insuficiente ou espera | Sampling real Hikari, aquisição, timeouts nativos e cleanup | Diagnosticar recurso sem ampliar timeout por reflexo; positivo pool4 não prova mínimo nem zero espera |
| Índice V20 divergente | Owner, btree, ordem de chaves, predicate parcial e validade | validate/replay rejeitam drift, não recriam índice silenciosamente |

As primeiras campanhas RED separaram defeitos de produto (contexto de fases
perdido e identidade inicial ausente antes do bootstrap) de defeitos de fixture/
oracle (pré-grants em PENDING, comparação byte[] por identidade e expectativa de
retry/replay). Não confundir falha de teste com corrupção comprovada de domínio.
Cada correção recebe revisão independente e prova apenas dos cenários afetados.

## Provas existentes e limites

As campanhas imutáveis V7two, V20growth e ownerpool4 usam a mesma árvore de860
fontes, freeze `ce1b0acdac95016addb40024ca6dc1ad3a29c28d752873146355e9e6d941dd9d`.
Os resultados e pareceres estão vinculados em EXECUCAO/B1 e no status B5b.2.
Provas anteriores pertencem às suas respectivas fontes, não a um whole verify
implícito da árvore atual. Os processos exclusivos dos testes foram encerrados.

O crescimento finito usa PostgreSQL14.22 e32/128/512STOPPED+2QUEUED, direitos
3QUEUE/0ACTIVE e limites originais SQL/lock1s. Com128/512 houve Index Only Scan
V20 e1–3 páginas cacheadas no nó de acesso; heap fetches podem ocorrer. Com32
permaneceu Seq Scan+Sort. Não somar buffers inclusivos de pais nem inferir todas
as entradas visitadas pelas linhas emitidas. Cache/corpus finito não prova SLO,
T13, PG16 ou carga corporativa completa. A medição preserva a baseline pré-índice.

## Próximos gates de entrega

Conferir recursos V1–V20 dentro do JAR privado contra a fonte, versão embutida e
CodeSource do consumidor independente. Compilar contra target/classes não prova
consumo empacotado. Não instalar a coordenada pública localmente como substituto
da disponibilidade no Central nem alterar o host para concluir uma prova privada.

Concluir orientação canônica de manutenção com manifest/auditorias/revisão distinta,
integração e sync seletivo, mantendo drifts alheios. Integrar o incremento completo
somente depois das provas exigidas; uma aprovação focal não anuncia backend pronto.

## Fontes para conferir o procedimento

- [Migrator canônico](../../src/main/java/org/praxisplatform/uischema/bulk/BulkExecutionMigrator.java).
- [DDL V20](../../src/main/resources/db/praxis-bulk-migrations/V20__bulk_worker_queue_index.sql).
- [Provas de índice](../../src/test/java/org/praxisplatform/uischema/bulk/BulkWorkerQueueIndexPostgresTest.java).
- [Retomada e negativas COMPLETE](../../src/test/java/org/praxisplatform/uischema/bulk/BulkDurableMigrationPostgresTest.java).
- [Recursos e pool owner](../../src/test/java/org/praxisplatform/uischema/bulk/BulkOwnerMigrationResourcesPostgresTest.java).
- [Estado e evidências do incremento](BULK-DURABLE-WORKER-B5B2-STATUS.md).
