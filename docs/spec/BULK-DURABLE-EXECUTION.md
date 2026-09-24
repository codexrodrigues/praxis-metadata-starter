# Núcleo durável de execução bulk

## Escopo e aderência

Este corte é `contrato-publico` e `arquitetural`. A fonte canônica é o pacote `bulk` do
Metadata Starter e seu schema PostgreSQL explícito. O consumidor direto é o host que adota
o mesmo `DataSource` e `PlatformTransactionManager` do domínio. Não há endpoint, registry,
annotation, auto-configuração, capability, READY, quota, fila, job, QUERY, ASYNC, ATOMIC,
retenção ou expurgo neste corte.

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

Não há artefato derivado de HTTP, landing, Angular ou corpus neste corte. V1 e V2 permanecem
byte a byte; V3 é somente incremental. A skill de concorrência já exige receipt junto ao
domínio, fencing e parada em commit incerto, e a skill de autoconfiguração já exige adoção
explícita. A classificação deste corte é `atualizar-existente`: a nova API, a barreira em
três transações, V3 e sua validação física precisam substituir o guidance ainda futuro após
as provas e revisão. A fonte canônica das skills fica fora deste checkout e será atualizada
pelo coordenador no mesmo ciclo.

## Modelo físico V3

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

`executeUnit(control, expectedOrdinal, callback)` implementa três transações curtas, todas no manager
operacional do binding:

1. **Preparação.** Sob lock e fencing, consulta primeiro o receipt do `expectedOrdinal`.
   Receipt existente é replay daquele ordinal e a callback não roda. Sem receipt, exige
   `expectedOrdinal == nextOrdinal`, valida `RUNNING`, deadline e alvo,
   gera attemptId, persiste `UNIT_IN_FLIGHT` com ordinal/digest/epoch e confirma essa barreira.
   A callback só pode começar depois que esse commit retornou confirmado. Se o commit de
   preparação for incerto, nenhuma callback ocorreu e a chamada devolve
   `RECONCILIATION_REQUIRED`. O chamador precisa pedir recuperação explícita, que lê o estado
   sob o mesmo lock, incrementa o epoch e encerra sem nova mutação; não há retry automático da
   preparação. A chamada nunca avança implicitamente para outro ordinal.
2. **Domínio + receipt.** Sob o mesmo lock/fencing e o mesmo manager, a callback recebe uma
   unidade protegida com a evidência do alvo. Ela grava domínio/outbox usando participantes
   JDBC ou JPA da transação atual e devolve apenas `CONFIRMED` ou `UNCHANGED`. Antes e depois
   da callback, o banco verifica o deadline. O insert do
   receipt usa o relógio do PostgreSQL e só ocorre quando `confirmed_at < deadline_at`.
   Domínio, receipt e mudança para `UNIT_COMMITTED_PENDING_ACK` confirmam juntos. Resultado
   `CONFIRMED` nunca é observado antes desse commit.
3. **Acknowledgement.** Somente depois que o manager confirmou a transação anterior, uma nova
   transação relê receipt/attempt sob lock, avança `nextOrdinal`, limpa a tentativa e escolhe
   `RUNNING` ou `COMPLETED`. Se o ack falha, o receipt continua sendo fonte de verdade e o
   estado pendente bloqueia a próxima callback; uma chamada posterior para o mesmo ordinal
   pode apenas reconhecer esse receipt. Quando a recuperação encontra evidência inconsistente,
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
    reservation.control(), 0, unit -> {
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
expõe somente razão segura (`NOT_FOUND`, `CONFLICT`, `EXPIRED`, `FENCED`, `NOT_EXECUTABLE`,
`DEADLINE_EXCEEDED`, `RECONCILIATION_REQUIRED`, `CORRUPT`, `UNAVAILABLE`).

## Limites de garantia

O kernel prova atomicidade local somente quando callback, receipts e domínio usam o mesmo
manager/datasource verificável. Não detecta `REQUIRES_NEW` ou banco remoto por introspecção;
o consumidor precisa de prova de rollback e de readback independente. O S4a não decide grants,
política, schema atual ou elegibilidade e não publica uma operação executável. O deadline
protege a inserção do receipt no banco; não promete interrupção segura de rede ou COMMIT.
Quando o resultado do commit não é conhecido, a execução permanece bloqueada até recuperação.
