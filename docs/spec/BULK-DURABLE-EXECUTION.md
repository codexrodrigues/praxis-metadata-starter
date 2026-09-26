# Núcleo durável de execução bulk

## Escopo e aderência

Este corte é `contrato-publico` e `arquitetural`. A fonte canônica é o pacote `bulk` do
Metadata Starter e seu schema PostgreSQL explícito. O consumidor direto é o host que adota
o mesmo `DataSource` e `PlatformTransactionManager` do domínio. Este documento descreve o núcleo
durável, não declara endpoint de protocolo, provider executável ou capability pronta. V5 fornece controle estrutural de
operação (criado como `UNCOMPOSED`), quotas e primitivas de retenção/expurgo descritos
abaixo; a migração não coloca o controle em `READY`, nem publica
por si só uma operação executável, fila/job, ou suporte a QUERY, ASYNC ou ATOMIC. A transição
para `READY` depende de publicação governada posterior com fingerprint e revisão estrutural.

## Binding declarativo de operações compartilhadas

`@BulkResourceOperations`, aplicado uma única vez por `resourceKey` a um controller `@ApiResource`, declara os operationIds
globais de leitura da proposta/resultados e execução/resultados, além do cancelamento. Cada ID
precisa corresponder a um método MVC real marcado com seu papel estrutural
`@BulkResourceOperation`. O starter deriva um snapshot imutável e o compartilha entre
`CanonicalOperationResolver` e o customizer Springdoc; assim o mesmo ID explícito é resolvido e
materializado no documento OpenAPI antes da leitura de schema. O binding estrito confirma também
o operationId no endpoint e percorre todos os grupos OpenAPI publicados para detectar IDs
duplicados em paths, callbacks e webhooks, confirmando a identidade da rota em todos os grupos
que a publicam; customizers que
reescrevam a identidade ou criem duplicidade tornam a resolução inelegível. A annotation não cria paths nem
autoriza uma action. IDs em branco/repetidos, papéis ausentes/duplicados, conflito com outra
operação e mapping condicional/ambíguo omitem o binding; a aplicação pode continuar servindo
OpenAPI, enquanto consumidores estritos não resolvem esses IDs. A composição/action projection
ainda precisa considerar esses diagnostics e permanecer `UNCOMPOSED`/`SUSPENDED` até provar
handlers, schemas, provider e publication CAS.

A validação usa o documento exato de cada grupo publicado e lê a lista de grupos no instante da
resolução, incluindo grupos registrados dinamicamente depois da criação do resolver. O serviço
documental mantém fallback para o documento base nos fluxos legados de schema/catálogo, mas esse
fallback nunca prova a existência nem a identidade de um grupo nomeado. Implementações customizadas
de `OpenApiDocumentService` usadas com esta declaração devem fornecer o fetch estrito; o padrão
falha fechado.

Os handlers comuns do protocolo são `GET` para leitura/resultados e `POST` sem body para
cancelamento. `consumes` explícito (que acrescentaria um contrato de request body), parâmetros
`HttpEntity`/`RequestEntity` e qualquer condição de roteamento não representada pelo contrato
estrito impedem o binding. `produces` não muda a identidade da rota e permanece documentado no
OpenAPI; este binding não certifica o contrato de resposta.
Avaliação e confirmação continuam operações reais separadas com operationIds
explícitos em `@Operation`; somente elas fornecem request schema. As cinco leituras/cancelamento
não devem inventar request schemas. A prova completa até OpenAPI real, descriptor, CAS e gate V7
permanece pendente neste incremento.

O inventário anterior é reaproveitado assim:

- intenção e evidência protegidas, vínculo de contexto e infraestrutura transacional:
  `ja-suportado-mal-nomeado-ou-mal-materializado`, por meio de `BulkEvaluationSnapshot`,
  `JdbcBulkProposalStore` e `BulkExecutionInfrastructure`;
- reserva única, controle/epoch, tentativa durável, receipt e recuperação:
  `lacuna-real-de-contrato`;
- decisão de elegibilidade e revalidação governada por unidade: permanece para o consumidor
  S4b e não é inferida do JSON de `plan` neste núcleo;
- projeções públicas `BulkExecution`/`BulkItemResult`: continuam sendo vocabulário público,
  mas não são construídas diretamente a partir destas APIs protegidas no S4a.

O recorte aceito é seleção `EXPLICIT`, transporte `SYNC` e atomicidade `PER_ITEM`, nas três
modalidades de intenção existentes. A proposta deve possuir evidência persistida. Propostas
V1 sem evidência e combinações QUERY/ASYNC/ATOMIC falham fechadas.

Não há artefato derivado de HTTP, landing, Angular ou corpus neste corte. V1–V3 permanecem
byte a byte; V4 acrescenta a admissão governada por unidade, persistindo resultados sem
mutação em `praxis_bulk_admission` e a razão terminal limitada em `terminal_reason_code`.
V5 estende esse modelo com controle estrutural de operação, quotas e primitivas de retenção;
V5 não torna uma operação `READY` nem habilita sozinho uma rota executável. Consulte
[Admissão governada por unidade](BULK-GOVERNED-UNIT-ADMISSION.md) para as regras e provas
específicas de V4. A classificação da atualização de guidance é `atualizar-existente`: a API,
a barreira em três transações, a migração V3→V4, e sua validação física devem constar na
skill de concorrência já usada; a fonte canônica dessa skill fica fora deste checkout.

## Modelo físico base V3 e evolução V4

V3 contém o ledger de execução e receipts descrito abaixo. A migração V3→V4 adiciona os
prazos de unidade, a razão terminal protegida e os resultados de admissão sem mutação,
preservando receipts e histórico anteriores. V1–V3 continuam imutáveis; V5 acrescenta o
ledger de capacidade e retenção documentado adiante.

`praxis_bulk_execution` contém uma reserva por proposta e por chave idempotente no escopo
confiável. A identidade de escopo é formada por namespace operacional, sujeito, recurso e
operationId; tenant/ambiente fazem parte do namespace/binding operacional adotado pelo host.
A tabela guarda somente o digest da chave, nunca seu valor.

As constraints centrais são:

- `PRIMARY KEY (execution_id)`;
- `UNIQUE (proposal_id)`, impedindo outra chave de criar uma segunda execução;
- `UNIQUE (namespace_id, subject_id, resource_key, operation_id,
  idempotency_key_digest)`, serializando a mesma chave dentro do escopo;
- FK imediata e validada de `(proposal_id, evaluation_fingerprint)` para a evidência V2,
  sustentada por `UNIQUE (proposal_id, evaluation_fingerprint)` acrescentada sem reescrever
  seu conteúdo;
- checks exatos para fingerprints/digests, estados, ordinal/progresso, deadline, epoch e o
  grupo atômico das colunas de tentativa ativa;
- nenhuma linha V1/V2 recebe execução ou receipt fabricado durante o upgrade.

`praxis_bulk_item_receipt` é append-only e contém execução, ordinal canônico, digest do alvo
tipado e versão esperada, attemptId, epoch, resultado `CONFIRMED` ou `UNCHANGED` e instante
confirmado. Não cria mensagem, código, referência ou payload paralelo a
`ResourceCommandMessage`/`BulkItemResult`; S4b deriva a projeção pública canônica na fronteira
governada. A PK é `(execution_id, unit_ordinal)`; há unicidade
por `(execution_id, target_digest)` e por `attempt_id`. A FK para execução é imediata,
validada e sem cascade. Trigger recusa `UPDATE` e `DELETE`; a credencial runtime recebe
`SELECT/INSERT`, nunca `UPDATE/DELETE`, nessa tabela.

A execução é a única linha mutável. Um trigger recusa alteração de proposal/fingerprints,
contexto, digest da chave, deadline, createdAt e total de alvos; somente owner/epoch, estado,
progresso, tentativa ativa, updatedAt e terminalAt formam o controle mutável. A credencial
runtime precisa de `SELECT/INSERT/UPDATE` nela, `SELECT` nas propostas/evidências e
`SELECT/INSERT` nos receipts. O migrator não concede privilégios. A validação física confere
tabelas permanentes, colunas/tipos/null/defaults, PKs, UNIQUEs, FKs imediatas e validadas,
checks, trigger/função de imutabilidade e allowlists pré e pós migração. Também recusa
funções/overloads, tipos, agregados, índices avulsos ou de expressão, regras de reescrita e
políticas RLS inesperados no schema dedicado. O índice interno do Flyway é a única exceção a
índice avulso, identificado por tabela e forma física (`praxis_bulk_schema_history.success`,
btree ascendente, não único), não somente pelo nome. As inspeções de catálogo usam
`pg_catalog` e restauram o `search_path` original na mesma conexão, inclusive para datasource
configurado com `currentSchema=praxis_bulk`.

O host também declara o owner esperado do schema e os principais PostgreSQL que recebem
privilégios para a validação física.
`BulkExecutionMigrator.migrate(dataSource, namespaceToDeploymentId,
BulkExecutionRoleConfiguration)` aceita `runtimeGranteeRoles`, `retentionExecutorMembers` e
`controlPlaneGranteeRoles`; a
sobrecarga `validate(dataSource, configuration)` pode verificar novamente o ambiente depois.
`expectedSchemaOwnerRole` identifica o usuário/role usado para criar o schema e as tabelas;
ownership é validado separadamente porque não aparece como um grant ordinário e pode permitir
alterar privilégios ou remover triggers. Os métodos sem configuração capturam `current_user`
da conexão usada na própria validação; para validar usando outra credencial, passe explicitamente
o owner confiável registrado pelo host.
Esses campos identificam os grantees diretos e todos os membros de role aceitos, inclusive os
transitivos. A validação exige que as identidades existam e sejam não privilegiadas; rejeita
herança de roles não declarada (incluindo roles predefinidas como `pg_write_all_data`), exceto a
membership explícita de cada membro de retenção em `praxis_bulk_retention_executor`; e compara
ACLs do schema exatamente. Para cada tabela,
varre privilégios de tabela e coluna e recusa `PUBLIC`, grant option, grantees não configurados
ou privilégios fora da allowlist específica; tombstones são somente leitura pelo runtime. O
Metadata Starter valida esses grants, mas não os concede: o provisionamento das credenciais e
ACLs continua sob controle do host/administrador do banco.
O runtime não recebe `SELECT` nem `UPDATE` direto em `praxis_bulk_operation_control`; ele lê e
segura o lock compartilhado pela função SECURITY DEFINER `lock_operation_control`. O control
plane também não lê/escreve diretamente a tabela, recebendo só `EXECUTE` na transição CAS.

## Ledger de capacidade e retenção V5

V5 liga as tabelas existentes às mutações reais por meio de allocations duráveis. A ordem
de locks de todas as mutações e rotinas de retenção é: binding de namespace (`FOR SHARE`),
controle da operação (`FOR SHARE`), bucket do deployment (`FOR UPDATE`), bucket do sujeito
(`FOR UPDATE`), proposta (`FOR UPDATE`) e execução (`FOR UPDATE`). O migrator toma os locks
globais nessa mesma ordem antes de reconstruir o ledger. Buckets são pontos de serialização;
as contagens são derivadas das allocations dentro da transação, sem contador mutável paralelo.

Os limites são 100 propostas pendentes por deployment, 10 por sujeito autenticado dentro do
deployment e 80 execuções ativas por deployment. `JdbcBulkProposalStore.insert` e
`insertEvaluated` criam a proposta/evidência e `PROPOSAL_PENDING` juntos. A reserva consulta
primeiro a existência de uma execução por chave sob o escopo confiável, antes de gates que
só se aplicam a trabalho novo; replay válido continua possível sob saturação. Para nova
reserva, os locks serializam concorrentes, o limite ativo é verificado, a allocation
pendente vira `CONSUMED` e a `EXECUTION_ACTIVE` nasce na mesma transação que a execução.
Uma proposta consumida por outra chave retorna sua execução existente; não cria segunda
execução nem nova cobrança pendente.

Ao confirmar estado terminal, um trigger protegido muda exatamente uma allocation ativa
para `RELEASED/TERMINAL_RECONCILED` na mesma transação. A validação física exige essa
correspondência exata; allocation ativa órfã após terminalização é drift e deve falhar
fechado, sem exceção tolerante que deixe quota ocupada indefinidamente. Falha/commit incerto,
owner ativo ou evidência incompleta não libera capacidade. Proposta expirada e não consumida
é limpa por `expire_unconsumed_proposal`, não por delete do runtime.

`praxis_bulk_retention_executor` tem somente `USAGE` no schema e `EXECUTE` nas duas funções
de retenção. Não recebe mutação direta de tabelas. A validação também confere roles e
memberships, ownership, ACLs por coluna/tabela, triggers de proteção, funções definer e
search paths fixos. A função de purge aceita uma execução terminal reconciliada, completa e
com `terminal_at` imutável de pelo menos 30 dias; na mesma transação grava tombstone mínimo
com scope/key digest e identidade do resultado, e depois remove conteúdo operacional,
receipts, alocações, proposta e execução. O tombstone é retido para sempre dentro do
namespace e impede reutilizar a chave. Replay autorizado por tombstone resulta em
`RESULT_PURGED` (mapeável ao HTTP 410); conflitos ordinários de chave/vínculo continuam
`CONFLICT` (409). Sem tombstone correspondente, não inferir expurgo nem efeito.

Provas PostgreSQL exercitam os jobs sob `SET ROLE praxis_bulk_retention_executor`, a
liberação junto da transição terminal, expiração apenas de proposta não consumida, purge,
tombstone, replay após purge e rejeição de estado terminal com allocation ativa. Migração e
validação física após um caminho de trigger desabilitado precisam falhar até o operador
reconciliar a allocation; checksums de Flyway não bastam.

## Estados e sequência

Os estados protegidos do kernel são:

| Estado | Significado e entradas permitidas |
|---|---|
| `RUNNING` | Reserva confirmada e sem tentativa pendente; pode preparar exatamente o `nextOrdinal`. |
| `UNIT_IN_FLIGHT` | Marcador de tentativa confirmado antes da callback. Bloqueia qualquer outro ordinal. |
| `UNIT_COMMITTED_PENDING_ACK` | Domínio e receipt confirmaram juntos; ainda não houve avanço confirmado do controle. Callback nunca é repetida. |
| `COMPLETED` | Todos os ordinais possuem receipt e o avanço final foi confirmado. |
| `STOPPED` | Recuperação ou rollback conhecido encerrou a execução; unidades restantes não são executadas. |
| `RECONCILIATION_REQUIRED` | Invariante/evidência está ausente ou contraditória; nenhuma nova mutação é permitida. |

`STOPPED` e `COMPLETED` são terminais neste recorte. `RECONCILIATION_REQUIRED` é bloqueante,
não terminal e nunca significa licença para retry. Estes estados internos não ampliam nem
reinterpretam automaticamente o enum público `BulkExecutionStatus`.

O bloqueio de `RECONCILIATION_REQUIRED` vale para nova mutação. Um receipt individual já
confirmado continua consultável e pode ser devolvido como replay depois que ordinal, alvo,
versão, attempt, epoch, outcome e instante forem validados contra a avaliação e o deadline.
Esse replay não reconhece tentativa pendente, não altera progresso e não limpa o estado
agregado bloqueado. Receipt incoerente permanece impedimento de reconciliação.

Cada chamada mutante leva um controle protegido `(executionId, ownerId, epoch)`. A transação
sempre adquire `SELECT ... FOR UPDATE` da execução antes de ler ou alterar progresso. Owner e
epoch são comparados sob o lock. Recuperação explícita, autorizada pelo host, adquire o mesmo
lock, incrementa o epoch e troca owner. Lease/horário não prova perda do lock e não permite
roubo por fora da linha.

A ordem de locks é fixa: execução, receipt do ordinal/digest, proposta/evidência já vinculada,
locks do domínio feitos pela callback, inserção do receipt, atualização da execução. Não há
transação externa cobrindo o loop PER_ITEM.

## Reserva idempotente

`reserve(scope, proposalId, idempotencyKey, ownerId, structuralRevision, deadline)` rejeita
transação Spring ambiente e abre uma transação operacional própria. Ela recupera proposta e
evidência pelo escopo confiável, valida EXPLICIT/SYNC/PER_ITEM e exige `now < expiresAt` antes
da primeira reserva. O binding da reserva enquadra proposalId, fingerprints de intenção e
avaliação e structuralRevision com framing versionado. A chave e textos têm limites e são
validados antes do banco; exceções públicas não carregam SQL, payload ou causa do driver.

O insert usa as duas constraints. Depois de uma corrida:

- mesma chave e mesmo binding retorna a execução vencedora;
- mesma chave e binding diferente retorna `CONFLICT`;
- outra chave para a mesma proposta retorna a execução já vinculada;
- outra proposta ou escopo nunca é revelado pelo UUID/chave.

Replay é resolvido antes do TTL inicial. Proposta expirada impede apenas uma reserva inédita;
uma execução existente permanece legível sob autorização atual. Erro/ACK incerto no commit da
reserva exige readback independente sob escopo. Sem readback conclusivo, o retorno é
`RECONCILIATION_REQUIRED`; nunca se cria chave/UUID substituta automaticamente.

## Unidade concreta e barreira de commit incerto

`executeUnit(control, expectedOrdinal, admission, mutation)` implementa três transações curtas, todas no manager
operacional do binding:

1. **Preparação.** Sob lock e fencing, consulta primeiro o receipt do `expectedOrdinal`.
   Receipt existente é replay daquele ordinal e a callback não roda. Sem receipt, exige
   `expectedOrdinal == nextOrdinal`, valida `RUNNING`, deadline e alvo,
   gera attemptId, persiste `UNIT_IN_FLIGHT` com ordinal/digest/epoch e confirma essa barreira.
   O mesmo `UPDATE` persiste `active_unit_deadline_at = min(deadline_at, database_now + 5 s)`.
   A callback só pode começar depois que esse commit retornou confirmado. Se o commit de
   preparação for incerto, nenhuma callback ocorreu e a chamada devolve
   `RECONCILIATION_REQUIRED`. O chamador precisa pedir recuperação explícita, que lê o estado
   sob o mesmo lock, incrementa o epoch e encerra sem nova mutação; não há retry automático da
   preparação. A chamada nunca avança implicitamente para outro ordinal.
2. **Domínio + receipt.** Sob o mesmo lock/fencing e o mesmo manager, a callback recebe uma
   unidade protegida com a evidência do alvo. Ela grava domínio/outbox usando participantes
   JDBC ou JPA da transação atual e devolve apenas `CONFIRMED` ou `UNCHANGED`. O prazo absoluto
   persistido cobre a unidade desde o marcador durável, incluindo grant, Config, leitura e lock
   do alvo, callback e tentativa de commit. O consumidor recebe orçamento restante ancorado em
   relógio monotônico, calculado a partir do relógio do banco, para não depender de sincronização
   de relógios entre processos. Config, grant e fatos limitam cada transação/query ao orçamento
   restante; aquisição de conexões dos pools operacionais tem espera máxima de 1 s. Antes de
   chamar a mutação e ao gravar o receipt, o banco confere o prazo com `clock_timestamp()`. Se
   ele expirou, a transação é revertida e o receipt não é gravado. O insert do receipt usa o
   relógio do PostgreSQL e só ocorre quando `confirmed_at` antecede o prazo da execução e o
   prazo persistido da unidade.
   Domínio, receipt e mudança para `UNIT_COMMITTED_PENDING_ACK` confirmam juntos. Resultado
   `CONFIRMED` nunca é observado antes desse commit.
3. **Acknowledgement.** Somente depois que o manager confirmou a transação anterior, uma nova
   transação relê receipt/attempt sob lock, avança `nextOrdinal`, limpa a tentativa e escolhe
   `RUNNING` ou `COMPLETED`. Se o ack falha, o receipt continua sendo fonte de verdade e o
   estado pendente bloqueia a próxima callback; uma chamada posterior para o mesmo ordinal
   pode apenas reconhecer esse receipt. ACK, readback, reconciliação e recuperação usam limite
   de 1 s para lock e statement; esgotar esse limite devolve `RECONCILIATION_REQUIRED` e mantém
   o receipt confirmado como barreira, sem repetir callback nem avançar o sufixo. O prazo da
   unidade não invalida um receipt já confirmado: replay/ACK consultam primeiro a evidência e
   permanecem possíveis depois do prazo, dentro do limite próprio de controle. Quando a recuperação encontra evidência inconsistente,
   `nextOrdinal` fica limitado ao prefixo contíguo de receipts que passou as validações; somente
   itens antes desse limite podem ser lidos em replay, e o receipt que provocou reconciliação
   permanece bloqueado. Se o commit do acknowledgement ocorreu e somente sua
   resposta se perdeu, o readback encontra `nextOrdinal` avançado, mas a chamada original
   continua vinculada ao ordinal anterior e devolve o receipt anterior como replay. Ela jamais
   despacha implicitamente o novo ordinal.

Replay de ordinal já dentro do prefixo reconhecido, inclusive em
`RECONCILIATION_REQUIRED`, é leitura sem mudança de progresso. Receipt pendente exatamente em
`nextOrdinal` pode reconhecer a unidade cujo domínio+receipt já foram commitados e avançar
somente esse acknowledgement; em nenhum dos casos replay volta a chamar a callback.

A barreira é durável em uma transação anterior à callback e permanece tanto quando a
transação de domínio confirma quanto quando ela reverte. Isto resolve os dois resultados de
uma exceção de COMMIT:

- se domínio+receipt confirmaram e o ACK da conexão se perdeu, o banco contém receipt e
  `UNIT_COMMITTED_PENDING_ACK`; nenhuma unidade seguinte entra até readback/reconciliação;
- se houve rollback real, o banco contém `UNIT_IN_FLIGHT` sem receipt; nenhuma unidade
  seguinte entra e o kernel não chama novamente a mutação por suposição.

Uma notificação de conclusão do gerenciador não é a barreira nem evidência suficiente para
classificar uma perda de conexão. Retorno normal do manager é commit confirmado. Após falha,
o kernel readquire a linha em uma transação independente: tentativa ainda em flight e ausência
de receipt provam rollback local e permitem `STOPPED`; receipt presente prova que a unidade
confirmou, mas preserva a barreira e devolve `RECONCILIATION_REQUIRED`. Readback indisponível
também devolve `RECONCILIATION_REQUIRED`. Exceção da callback nunca vira receipt
`DENIED`/`INVALID`.

O retorno da callback é deliberadamente restrito a `CONFIRMED` e `UNCHANGED`. Estados de
autorização, conflito, validação e revalidação pertencem ao provider governado S4b e devem ser
decididos antes da mutação; uma exceção transacional nunca é reinterpretada como resultado de
negócio. O teste de conformidade usa uma callback real que altera uma tabela de domínio, não
um SPI vazio ou mock de serialização.

## Recuperação sem nova mutação

`recover(scope, executionId, recoveryOwner)` é uma ação explícita do host; o kernel não a
agenda nem infere abandono por lease. Ela tenta adquirir o mesmo lock. Enquanto uma unidade
mantém o lock, a recuperação espera/falha conforme o timeout do chamador e não encerra a
conexão alheia. Ao adquirir o lock, incrementa epoch antes de qualquer conclusão; o executor
antigo falha no próximo gate e não pode atualizar o agregado.

Sob controle novo, a recuperação compara attempt, ordinal, digest, epoch e receipts:

- receipt válido para a tentativa confirma somente aquela unidade;
- tentativa ativa sem receipt, depois de adquirir o lock que exclui qualquer commit ainda em
  curso, é encerrada sem callback e as unidades restantes ficam não processadas;
- receipts completos permitem `COMPLETED`; conjunto parcial coerente termina `STOPPED`;
- receipt ausente quando estado/progresso afirma que a unidade já confirmou, receipt
  duplicado/divergente, ordinal impossível ou vínculo quebrado mantém
  `RECONCILIATION_REQUIRED`. Isso é distinto da tentativa ainda `UNIT_IN_FLIGHT` sem receipt:
  depois que o recuperador adquiriu o lock e a atomicidade local foi validada, essa ausência
  prova rollback da unidade e permite `STOPPED` sem nova mutação. Contagem isolada nunca prova
  cobertura; ordinal, target digest, attemptId e epoch precisam coincidir.

Recuperação nunca chama a callback, nunca reconstitui resultado pelo estado atual do domínio
e nunca transforma ausência de receipt em falha histórica quando a atomicidade do mesmo banco
não pode ser provada. O receipt preserva o epoch/attempt original; o novo owner apenas fecha o
controle.

## API protegida prevista

As classes ficam em `org.praxisplatform.uischema.bulk`, usam `@JsonIgnoreType`, não têm
constructors públicos para forjar controle/receipt e redigem `toString`:

```java
var kernel = new JdbcBulkDurableExecution(infrastructure);

BulkExecutionReservation reservation = kernel.reserve(
    scope, proposalId, idempotencyKey, ownerId, structuralRevision, deadline);

BulkUnitExecutionResult result = kernel.executeUnit(
    reservation.control(), 0,
    unit -> validateCurrentGrantAndPolicy(unit.remainingBudget()),
    unit -> {
        // serviço/repositório JDBC ou JPA participa do manager operacional atual
        mutateDomain(unit.targetEvidence());
        return BulkUnitMutationResult.confirmed();
    });

BulkExecutionRecovery recovery = kernel.recover(
    scope, reservation.executionId(), recoveryOwner);
```

O ordinal explícito é a identidade estável da unidade dentro da avaliação imutável. Retry de
A sempre pede A, inclusive se o acknowledgement já avançou o banco para B; o kernel consulta
o receipt de A e nunca interpreta esse retry como pedido para B. Ordinal fora do progresso
esperado é rejeitado sem callback. Controle antigo após incremento de epoch é `FENCED`;
avanço de ordinal não incrementa epoch nem transforma uma chamada de A em B.

`BulkExecutionReservation` informa criada/replay, status protegido, progresso e controle
vigente. `BulkUnitExecutionResult` distingue receipt confirmado/replay, execução concluída,
rollback conhecido, deadline e reconciliação obrigatória. `BulkExecutionRecovery` informa
epoch novo, status final/bloqueante e contagem derivada de receipts. `BulkDurableExecutionException`
expõe somente razão segura (`NOT_FOUND`, `CONFLICT`, `CAPACITY`, `EXPIRED`, `RESULT_PURGED`,
`FENCED`, `NOT_EXECUTABLE`, `DEADLINE_EXCEEDED`, `RECONCILIATION_REQUIRED`, `CORRUPT`,
`UNAVAILABLE`). `RESULT_PURGED` só é emitido após correspondência de tombstone no escopo
autenticado consultado; a API host pode mapear este caso a HTTP 410.

## Limites de garantia

O kernel prova atomicidade local somente quando callback, receipts e domínio usam o mesmo
manager/datasource verificável. Não detecta `REQUIRES_NEW` ou banco remoto por introspecção;
o consumidor precisa de prova de rollback e de readback independente. O S4a não decide grants,
política, schema atual ou elegibilidade e não publica uma operação executável. O deadline
protege a inserção do receipt no banco; não promete interrupção segura de rede ou COMMIT.
Quando o resultado do commit não é conhecido, a execução permanece bloqueada até recuperação.
