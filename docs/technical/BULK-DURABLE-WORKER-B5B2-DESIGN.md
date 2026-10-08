# B5b.2 — worker durável protegido (desenho para revisão)

Estado: proposta de implementação, sem worker implementado ou validação dinâmica nova. Não habilita ASYNC público, HTTP 202, READY, host, Angular ou release.

## Baseline e classe

Mudança arquitetural no Metadata; composição pública/host permanece B5b.3 e exige mapa próprio. Baseline `b35483beac248c20bc107dd9e966a7a70a7d67a2` (tag publicada rc.155 e origin/main conferidas em 08/10/2026). Worktree exclusivo `feat/b5b2-durable-worker-20261008`. Os 34 flags iniciais de Git são byte-idênticos a HEAD, auditados em `/tmp/praxis-b5b-capacity-occupancy-20261007/b5b2-durable-worker-20261008/eol-baseline-audit.json`; preservar e excluir do pacote. Nenhum build realizado neste incremento.

Fonte do objetivo: host `internal-planning/bulk-operations/B5-JOBS-IMPLEMENTACAO.md`, B1/EXEC e revisão ROOT. Semântica pertence ao Metadata; o host não cria fila, ledger ou SQL concorrente.

## Inventário e impacto

| Necessidade | Aderência | Fonte / decisão |
| --- | --- | --- |
| Enqueue durável, ocupação QUEUE, transferência ACTIVE | suportado-parcialmente | `JdbcBulkDurableExecution.enqueue/claim`, V18/V19 já publicados, acesso protegido. Não expor por reflection/bridge. |
| Unidade, grants atuais, efeito+receipt, epoch, reconciliação | ja-suportado-mal-materializado | `executeUnit`, callbacks existentes, `recover`, snapshots/codecs; worker deve reutilizar, sem duplicar protocolo. |
| Seleção automática, ciclo e justiça de atendimento | lacuna-real-de-contrato | Falta control-loop produtivo; fixture de quatro JVMs chama claim manualmente. Novo comportamento interno concreto, sem segundo registry. |
| Expiração QUEUED sem instalação ACTIVE | suportado-parcialmente | SQL claim terminaliza deadline antes da instalação, mas Java exige ACTIVE upfront. Acrescentar transição interna usando lockLifecycle/validação existentes, sem exigir novo slot. |
| Contexto confiável sem HTTP/JWT e wiring público | suportado-parcialmente | Host atual depende SecurityContext. B5b.3 separado; nenhuma cópia de JWT/contexto de requisição. |

Arquivos previstos: kernel existente (seleção protegida/expiração), novo worker package-private concreto no pacote bulk, testes focais de lifecycle/PG e processo JVM. Não alterar DDL inicialmente: grants V19 já permitem terminalização com trigger que certifica/release. Se faltar privilégio ou constraint, parar e revisar mapa antes de migrar. Não modificar DTO público, profile, controller, imports de autoconfiguration ou host neste corte sem decisão adicional ROOT.

Consumidores: testes/composição interna confiável; host e auto-configuração pública recebem B5b.3 posterior. Docs técnicas/planejamento afetados; corpus/receita pública/Angular não materializam worker ainda. Breaking wire: nenhum. Skill concorrência e operational-proof precisam reavaliar guidance após comportamento provado; baseline canônica será conferida, sem inventar aceitação.

## Composição concreta e limites

Proposta de corte: worker package-private com lifecycle Spring `SmartLifecycle`, construído explicitamente com lista imutável de bindings autenticados, kernel associado e callbacks reais existentes de admission/mutation. Não criar SPI/registry público vazio nem auto-start por simples presença de datasource. Lista vazia não inicia; entradas duplicadas/incompatíveis são rejeitadas. Configuração privada define scan batch limitado, polling/backoff positivo limitado e shutdown wait. A exposição/configuração suportada de host/autoconfiguração é gate B5b.3, não prova implícita neste corte. ROOT deve confirmar esse limite antes de patch.

Uma thread nomeada por instância, nenhum executor ilimitado ou buffer de jobs pendentes em memória. Handles locais são apenas reservas confirmed adquiridas por CAS, no máximo um por binding e oito por worker. Quantum é uma executeUnit+ACK por binding/rodada; A com múltiplos ordinais não pode monopolizar B elegível até completar. Outros nós podem ocupar outros direitos ACTIVE. Esse corte tem concorrência menor por binding e não promete throughput máximo. A lista não transporta subject de requisição: seleção lê identidade da execução/proposta protegidas; o kernel decodifica e confronta bindings existentes, e callbacks só aceitam operações previamente compostas. Operações sem callback confiável não são adquiridas. Nenhuma enumeração de subject de cliente como autorização. O callback composto tem obrigação de revalidar grants/policy/domínio por unidade; adapters de teste não provam o host corporativo sem HTTP/JWT. Fixtures usam mutação real e callbacks autenticados; isso não certifica adapters HTTP do host.

## Seleção, concorrência e justiça

Cada rodada visita bindings por round-robin; dentro de um binding, seleção QUEUED usa `(created_at, execution_id)` estável e filtro de operações compostas. Leitura limitada é hint, sem lock de execução antecipado; não usar SKIP LOCKED antes de binding/control/buckets. Seleção de token ACTIVE instalado e slot livre também é hint. `claim` e triggers são a única autoridade: revalidam marker, tuple READY, estado QUEUED, deadline, epoch e ocupação na transação. Dois nós vendo o mesmo hint têm um único vencedor; perdedor não despacha callback.

Janela de seleção deve avançar por keyset limitado para que candidatos suspensos/corruptos não bloqueiem indefinidamente candidatos posteriores. Nenhum cursor de memória determina posse ou autorização. Após uma página limitada por rodada, keyset avança somente como hint. Exhaustion faz wrap obrigatório ao início na rodada seguinte; depois de varredura limitada também não se mantém cursor eternamente sem wrap. Novas inserções anteriores ao cursor, clock move e empates de timestamp/UUID reaparecem no wrap. ACTIVE cheio não torna job invisível nem induz busy-spin. O cursor de varredura pode reiniciar no restart; propriedade durável é o CAS e ledger existentes. Rejeição/backoff por binding não bloqueia os demais.

Justiça provada neste corte: serviço round-robin entre bindings/tenants configurados com direitos disponíveis; dentro de cada fila elegível, ordem estável e varredura progressiva. Não prometer FIFO total ou justiça global entre workers distribuídos. Justiça da emissão durável de direitos pelo issuer é distinta. Sem direitos instalados, nenhum worker inventa capacidade ou devolve direito global.

## Estados, relógios e ordem de locks

`QUEUED(epoch1) -> claim confirmado -> RUNNING(epoch2) -> executeUnit -> ACK -> próximo ordinal`. Apenas ack não replay, estado RUNNING e avanço exato permitem sufixo. Terminal/erro/replay/resultado incerto interrompem dispatch; caminho de reconciliação não chama callbacks. Claim com acknowledgment recuperado (`replayed=true`) não inicia novas mutações: reconcile conservadoramente, preservando identidade/efeitos.

Expiração: independente de haver ACTIVE livre/instalado, identificar hint deadline vencida e revalidar em transação sob locks. Só QUEUED, prefixo vazio certificado e clock_timestamp >= deadline permitem STOPPED/DEADLINE_EXCEEDED. Não marcar CANCELLED_BY_USER para shutdown/expiry. Cancelamento existente pode vencer; re-leitura terminal é no-op. Corrupção exige reconciliação e não liberação inventada. Oráculo planejado: operation SUSPENDED impede claim, mas permite expiry/cleanup sem exigir READY; marker FENCED impede claim/mutação nova, mas permite terminalização/reconciliação certificada sem reabrir marker ou refund global. `lock_capacity_marker`/statement fence V19 apenas tomam share lock, sem gate ACTIVE; `requireBinding(..., false)` mantém identidade física para cleanup, e materialize_capacity_execution libera somente terminal certificado. Estes casos exigem prova PG antes de afirmar comportamento suportado.

Clock PostgreSQL decide deadline e transição. System.nanoTime serve somente polling/espera local. Nenhuma lease de tempo libera slot ou retoma execução automaticamente. Epoch durável/protocolo existente fence executor antigo; recuperação preserva receipts e terminaliza restante sem mutação.

Ordem: marker -> namespace binding -> operation control -> deployment bucket -> subject bucket -> proposal -> execution/epoch -> receipt/admission -> target, conforme fase existente. Hint read não adquire authority; claims e finalização conservam a ordem canônica. Cada unidade segue suas transações existentes, sem segurar bucket durante domínio ou abrir transação distribuída.

## Parâmetros internos e critérios de teste

Defaults privados propostos: até oito bindings e oito handles (cap ACTIVE deployment existente); um handle por binding; scan quantum oito candidatos por binding/rodada com keyset e LIMIT 1, para limitar decode e avançar além de suspensos/corruptos; polling idle 250 ms, backoff de erro por binding 1 s, sem busy-spin; stop() espera local até 10 s e reporta pendência, sem callback falso. Não alterar limites de domínio 10k/deadline30min/unidade5s. O kernel limita aquisição/read/reconcile por timeouts SQL existentes; callback confiável deve cooperar com remainingBudget, pois SQL timeout não limita código Java arbitrário. Não prometer bound absoluto de shutdown de callback não cooperativo.

Spring phase `Integer.MAX_VALUE - 1024` permite parada antes dos dependentes de fase inferior, mas dependências explícitas prevalecem. Composição privada de teste declara dependsOn datasource/transactionManager e DefaultLifecycleProcessor com timeout de fase 30 s; futura composição host precisa declarar a mesma ordem e calibrar timeout a recursos reais. Esses valores são limites locais de engenharia, não SLO. DefaultLifecycleProcessor pode prosseguir depois do timeout de fase mesmo com isRunning verdadeiro; o worker não garante datasource vivo depois de contextClose. Teste Spring real deve observar parada antes destroy do datasource no caminho cooperativo e pendência verdadeira no caminho timeout/barreira; release e cleanup após observação. Referência oficial: [DefaultLifecycleProcessor Spring 6.2.19](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/context/support/DefaultLifecycleProcessor.html).

## Shutdown e falhas

stop impede nova seleção e novo ordinal. Um monitor curto lineariza STOPPING versus autorização local do próximo claim/unidade; não manter monitor durante JDBC. Unidade autorizada antes de stop pode concluir; depois de STOPPING, nenhum novo ordinal é autorizado. Start durante STOPPING não cria outra thread. Cada start efetivo usa owner UUID novo, nunca reassume RUNNING. Stops repetidos registram callbacks idempotentes, chamados uma vez por registro somente após terminação. Não interromper arbitrariamente thread dentro da transação; aguardar unidade em curso retornar e ACK/erro, então reconciliar job inacabado conservadoramente. Se stop disputar claim e este confirmar, não despachar domínio; reconciliar a reserva adquirida. Crash real pode conservar slot até reconciliação explícita; restart não varre RUNNING para executar sufixos.

Timeout de espera local não prova encerramento: `isRunning` permanece verdadeiro enquanto a thread/unidade existir; callback de stop somente após terminação efetiva. Relatar espera excedida sem declarar recursos fechados ou tocar processos terceiros. Antes da reconciliação interna, recoverOwned compara executionId/proposalId/owner/epoch da reserva sob lockLifecycle NA MESMA transação da recuperação existente. FENCED ou terminal válido não toca sucessor. Não chamar recover público cegamente após perder autoridade. Falha de reconciliação mantém evidência/ocupação durável, não refund/retry de mutação. Eventos mínimos sanitizados diferenciam idle, claim perdido, falha de aquisição, execução encerrada e shutdown pendente, sem subjects/tokens/payloads.

## Provas e lease propostas (ainda sem executar)

1. PostgreSQL real: seleção de escopo/ordem/janela, instalada ACTIVE livre, expiry sem ACTIVE, cancel-before-claim, full tuple/fence e liberação uma vez.
2. Dois JVMs reais disputam mesmo job sem chamada manual ao claim pelo teste: um callback de domínio, um receipt, epoch/ocupação/durable terminal certos; reinício não repete efeito.
3. Dois bindings/tenants reais com backlog e direitos instalados: ambos atendidos; binding sem direito ou operation suspensa não monopoliza varredura. Não inferir limite distribuído de teste local.
4. Barreiras PG/controle de processo provam deadline/cancel antes do claim, recovery tornando epoch antigo inválido, ausência de novas mutações após recovery.
5. Shutdown durante unidade real e contexto Spring: stop sem próximo ordinal, unidade/receipt juntos, reconciliação sem callback e thread/PG encerrados. Timeout local mantém lifecycle pending observável até release da barreira.
6. Lifecycle sem composição não inicia; limites inválidos negados; erro de um binding não mata atendimento de outro. Fonte congelada e revisão independente de evidências antes de aceite.

MAIN único dono de Maven/target/PG/Git e arquivos acima; ROOT read-only revisa desenho e árvore final. Depois do aceite de desenho, editar POM literal para coordenada privada distinta `8.0.0-b5b2-durable-worker-20261008-SNAPSHOT`, cache privado `/tmp/praxis-b5b-capacity-occupancy-20261007/b5b2-durable-worker-20261008/m2-private`, target deste WT e embedded PostgreSQL oficial. Nunca instalar candidato sob rc.155 nem contaminar cache público. POM privado não é release. Validar focais novos e regressões diretamente afetadas; provas anteriores aceitas não são repetidas por reflexo. Registrar hashes/resultados/cleanup e atualizar B1/EXEC após revisão.

## Mapa de casos antes da implementação

| Arquivo previsto | Casos mínimos | Recursos |
| --- | --- | --- |
| BulkDurableWorkerPostgresTest | keyset/identidade/operação completa; expiry sem ACTIVE; cancel/deadline antes claim; dois bindings A vários ordinais e B servido antes A terminal; full tuple/fence; error isolation | embedded PG oficial, callbacks com SQL de domínio real |
| BulkDurableWorkerProcessesPostgresTest + processo runtime exclusivo | dois PIDs JVM, ambos control-loop real, um callback/receipt; restart não reassume; recuperação exclui epoch antigo | classpath candidato exato, roles runtime, barreiras finitas e resultados sanitizados |
| BulkDurableWorkerLifecyclePostgresTest | stop linearizado/in-flight/ACK; recoverOwned não toca sucessor; callbacks repetidos/start STOPPING; Spring dependsOn/destroy; timeout pendente sem falso cleanup | contexto Spring real, PG/barreiras, um executor exclusivo |

Revisão ROOT em andamento: os ajustes de quantum e recoverOwned foram concordados; aceite final do desenho/números/matriz ainda pendente. Não houve edição de produto/POM/testes/build.

Consulta limitada não garante custo limitado: capturar EXPLAIN PG real da seleção com histórico retido, sem inferir SLA10k. Se faltar índice, propor mudança DDL/index com mapa/constraints/prova/revisão próprios antes de editar; não mudar migrations aceitas por conveniência. Casos de wrap/inserção antes cursor, suspended/corrupt head, ACTIVE full temporário e timestamp/UUID tie integram a matriz focal.
