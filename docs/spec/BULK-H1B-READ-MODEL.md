# H1b — decisão de base para leitura de operações em lote

Estado: V8/V9/V10 e a fundação interna RS3 integrados; sem reader
público, endpoint, cursor ou `READY`. Baseline inicialmente auditado: Metadata main
`fdc4fac8cf6bdf6129282db6b0ada26c82ac03cb` (`8.0.0-rc.136`) e consumidor
Quickstart PR #311. O plano do consumidor está em
`internal-planning/bulk-operations/H1B-WRITE-SETS.md` no Quickstart. B0 continua
definindo invariantes e semântica pública; divergências devem ser corrigidas ali e
revisadas antes de modificar um contrato público.

## Decisão V11 — integridade bounded de página RS2

Base: Metadata main `c13e5d8706b3f4b6d0e40e0f1673192d689122eb`, com V9 e RS3.
Classificação: `arquitetural` e `transversal` para a integridade física de
projeção paginada; nenhum contrato público é alterado nesta análise. Aderência:
`praxis_bulk_preview_state` já vincula a projeção inteira à avaliação exata,
revisão/allowlist e digest global (`suportado-parcialmente`); cada
`praxis_bulk_target_preview` está indexado por ordinal e referenciado ao
manifest V8 (`ja-suportado-mal-materializado`). Falta um checksum versionado
verificável **por página limitada** contra alteração de diagnostics de um item
sem ler todos os demais (`lacuna-real-de-contrato` físico, não HTTP).

O consumidor concreto futuro é `proposal-results` no Quickstart, somente depois
de autorização atual e integral de alvos/campos/referências. O caminho interno
pretendido usa `withConsistentRead` em PostgreSQL `REPEATABLE READ READ ONLY`,
consulta scoped por namespace/sujeito/recurso/operationId e keyset por ordinal
exclusivo dentro de `0 <= ordinal < watermarkExclusive`, com limite 1–200 e
`LIMIT size+1`; RS2 imutável fixa `watermarkExclusive=targetCount`. Estado
`UNAVAILABLE`/`UNAVAILABLE_LEGACY` é indisponibilidade explícita, nunca página
vazia. Falta, drift ou duplicidade falham fechados. Nenhuma leitura pode chamar
domínio, decodificar blob protegido por página, emitir 404/410 antes da
autorização, criar cursor/endpoint ou declarar `READY`.

Duas opções de integridade foram confrontadas:

1. Recalcular o digest global V9 a cada página dentro do mesmo snapshot.
   Detecta drift de texto e exige somente código reader, sem DDL ou nova ACL,
   mas lê todos os itens até 10.000 em cada página. No máximo físico de
   `diagnostics` (65.536 bytes por item), isso pode atingir cerca de 655 MB por
   chamada e repetir-se até 50 vezes para páginas de 200. A resposta HTTP
   teria poucas linhas, porém o trabalho não seria bounded e ameaçaria o
   timeout curto do RS3.
2. Adicionar V11 com digest versionado **por item**. O digest local inclui
   fingerprint/revisão/allowlist/digest V9, ordinal, decisão, diagnostics
   canônicos e identidade/versão/digest do manifest. O reader lê somente
   `size+1` itens e recalcula os digests locais na mesma transação curta,
   mantendo o digest global V9 como auditoria integral no bootstrap/migrator.
   Uma árvore Merkle acrescentaria prova contra alteração simultânea de item e
   seu digest por um schema owner, mas esse owner também pode substituir raiz,
   árvore e DDL e está fora do modelo de ameaça deste corte. O custo adicional
   da árvore não se justifica sob as ACLs/immutabilidade governadas.

**Decisão arquitetural: opção 2, digest local V11 sem Merkle.** O write set é
um campo `integrity_version=11` obrigatório **sem default** no parent state e
uma linha `item_digest` por ordinal, com FK ao preview V9 e algoritmo fechado.
O DDL de Flyway é atômico no PostgreSQL: adiciona a coluna
`integrity_version integer NOT NULL DEFAULT 11`, preenchendo linhas V9 sem
disparar o trigger de imutabilidade; remove o default **antes do commit**, fixa
o `CHECK` e instala, na mesma transação, o marker V11 `PENDING` e um trigger
de admissão no parent que exige marker
`COMPLETE` para **todo INSERT**, inclusive writer V11; essa proteção não pode
depender da velocidade nem do sucesso do bootstrap Java. Assim, entre commit
do DDL e commit do bootstrap, falha, crash ou role incorreta só podem deixar
`PENDING` e impedir novas avaliações. Writer V9 omite `integrity_version` e
continua barrado após `COMPLETE`. O guard diferido exige todos os digests de
uma nova avaliação `COMPLETE` antes de commit; `UNAVAILABLE` não admite
linha. O marker é owner-only; a função de guard é definida sem `search_path`
mutável pelo chamador, roda `SECURITY DEFINER` com owner governado,
tem `PUBLIC EXECUTE` revogado e não concede leitura do marker à role runtime.

O transcript é fechado e binário, com prefixo de domínio e versão. **Todo**
campo, inclusive inteiro e domínio, é `comprimento uint32 big-endian || bytes`;
um inteiro é exatamente quatro bytes signed big-endian, portanto seu frame
começa em `00 00 00 04`. Strings são UTF-8; bytea usa bytes persistidos,
sem normalizar texto/JSON após a gravação. `SHA-256(public_allowlist bytea)`
é calculado sobre bytes crus, e o resultado de 32 bytes entra como campo
framed no parent. O contexto do parent é
`SHA-256("praxis.bulk.preview-parent/1", proposal UUID canônico,
integrity_version int32=11, evaluation_fingerprint UTF-8,
projector_revision UTF-8, target_count int32,
SHA-256(public_allowlist bytea), projection_digest UTF-8,
projection_state UTF-8="COMPLETE")`. O item é
`SHA-256("praxis.bulk.preview-item/1", parent_context_digest,
digest_version int32=1, ordinal int32, manifest.wire_identity bytea,
manifest.wire_identity_digest UTF-8, manifest.expected_version bytea,
manifest.target_digest UTF-8, preview.decision UTF-8,
preview.diagnostics bytea)`. O identificador UUID é sua representação
canônica ASCII de 36 bytes, também length-framed. `null` é proibido nos
campos do transcript; o framing impede concatenações ambíguas. O digest
armazenado é `sha256:` seguido de 64 caracteres hexadecimais minúsculos.
Vetor de ouro independente (`hashlib`/`struct`, também fixado em teste): UUID
`123e4567-e89b-12d3-a456-426614174000`, fingerprint `sha256:` + 64 `a`,
revision `prévia\0v1🚀`, count `2`, allowlist UTF-8
`[{"message":"Café\\u0000"}]`, projection digest `sha256:` + 64 `b`
produzem contexto
`9f5a40eb25d55c1e0394984b739bf8abfe0b8f27c6e1c78bf39313c7ecfd28e4`.
Com ordinal `1`, wire identity ASCII `"id\\u0000"`, wire digest `sha256:`
+ 64 `c`, expected version bytes `00 01 00 ff 80`, target digest `sha256:`
+ 64 `d`, decisão `BLOCKED` e diagnostics
`[{"message":"Café\\u0000"}]`, a folha é
`sha256:1afcd8ccf9bb8aca82898b8247b4cd05967ff74c867db2938e068a0a59a4f70b`.
Durante `PENDING`, é permitido calcular folhas para parents V9 cujo
`projection_state` já é `COMPLETE`: o valor `COMPLETE` no transcript é o
estado imutável daquele parent, não a fase do marker V11. Nenhum reader
aceita essas folhas enquanto o marker não for `COMPLETE`.
O writer calcula a folha dos bytes **persistidos** no mesmo transaction scope
da avaliação, manifest e preview, e nunca de uma segunda serialização em
memória. O reader futuro calcula o contexto uma vez por página no mesmo
snapshot RR e verifica cada folha retornada; não recalcula a allowlist por
item nem decodifica a avaliação protegida.

O bootstrap em uma única transação com advisory lock valida primeiro V8
manifest e V9 **global** contra a avaliação protegida, incluindo revision,
allowlist e mensagens; só então faz backfill das folhas V11 dos bytes
persistidos. `UNAVAILABLE`/`UNAVAILABLE_LEGACY` não recebem folhas. Na mesma
transação valida folhas/constraints, grants, corpo dos guards, triggers,
owner e catálogo, marca V11 `COMPLETE` por último e atesta o marker antes de
commit. Qualquer falha reverte backfill, ACL e marker juntos; em `PENDING`
o retry repete a validação integral, sem confiar em folhas prévias. Após
`COMPLETE`, migrator apenas valida e **não repara** drift. A role runtime
recebe só `SELECT,INSERT` na nova relação, sem `UPDATE,DELETE`; triggers de
imutabilidade e ACL protegem os bytes. A FK da folha ao preview é
`ON DELETE RESTRICT`; as duas funções governadas de retenção,
`expire_unconsumed_proposal` e `purge_terminal_execution`, removem folhas
**após** obter locks binding → control → buckets → proposal/execution e
**antes** do preview. O trigger de deleção exige o definer
`praxis_bulk_retention_owner`, sem grant direto ao executor; falha em qualquer
DELETE reverte o write set inteiro.
O cutover **exige** drenar writers V9 e os executores de retenção
`expire`/`purge`, aguardar transações em voo, aplicar Flyway V11, executar
bootstrap até marker `COMPLETE` e `validate` com roles exatas, e só então
reativar retenção e writers V11. O marker owner-only não serializa retenção;
uma corrida fora desse procedimento durante `PENDING` pode remover um parent
entre validação V9 e backfill V11, fazendo a transação de bootstrap abortar
por FK ou revalidar somente o conjunto sobrevivente. O resultado aceitável
de falha é marker ainda `PENDING`, grants/folhas revertidos e retry **após**
dreno; não há promessa de upgrade zero-downtime. Lock order existente
binding → operation-control → buckets → proposal/execution é preservado,
bootstrap serializa no advisory lock e reader não toma lock de escrita.
O release/adoption deve tratar o dreno como gate operacional auditável e não
reabrir admissão se marker, ACL, folhas ou validação não estiverem completos.
Consumidor, docs públicos, corpus HTTP e Angular continuam sem mudança até
reader/autorização. Writers V9 devem migrar antes da reabertura da admissão,
exigência intencional no beta.

O teto bruto de diagnostics numa página de 200 é 12,5 MiB, além de identidade,
resultados e overhead; o reader futuro deve impor orçamento agregado de bytes
e tempo de transação, sem transformar `size+1` em varredura global. O scan V9
integral **permanece em todo `migrate`/`validate` e startup que os invoca**,
inclusive após marker V11 `COMPLETE`: com 10.000 itens de diagnostics de
65.536 bytes, pode ler cerca de 655 MB por proposta, além do blob protegido,
manifest e folhas. O gate operacional do V11 deve medir tempo/memória dessa
validação em bases representativas, definir orçamento de startup e impedir
readiness quando o scan falhar; a paginação bounded resolve o custo de cada
leitura RS2, não esse custo de bootstrap/attestation. Na fixture PostgreSQL
de 10.000 itens deste corte, `validateAll` levou 234 ms com heap usado
antes/depois de 62/151 MB no JVM padrão; com `-Xmx256m`, levou 303 ms e o
heap usado antes/depois foi 51/80 MB. Esses valores não medem o pico, nem representam
carga concorrente ou uma base corporativa; não aprovam ainda um orçamento de
startup. Um teste separado com heap limitado a 256 MiB rejeitou uma projeção
de 10.000 diagnostics de 32 KiB corrompidos sem OOM, demonstrando streaming
na falha, não um SLA de inicialização. Essa decisão
detecta corrupção/drift que não seja reescrito coerentemente junto com
o checksum, sob ACL e imutabilidade;
schema owner/superuser que edita coerentemente dados, folhas e DDL está fora
do modelo de ameaça. SHA-256 aqui é checksum de integridade sob ACL e
imutabilidade, não autenticação criptográfica contra writer runtime malicioso
com `INSERT` autorizado.

Provas PostgreSQL de aceite do físico V11: 10.000 itens, alteração isolada de
diagnostics, alteração do digest ou manifest, estado indisponível, rollback,
writers V9/V11 barrados no `PENDING`, writer aguardando commit do DDL,
wrong-role rollback seguido de retry, writer V9 após cutover,
ACL/marker/`COMPLETE` no-heal, expiração/purge e concorrência.
O reader RS2 bounded é um corte separado, depois da revisão do V11. O schema
owner capaz de alterar simultaneamente todos os dados e DDL está fora do
modelo de ameaça; runtime não possui esses privilégios.

## Corte interno RS3 — pré-análise de 27/09/2026

Base de implementação: Metadata main `7117f96cb6f34e612711fa57212f13e4f943ba65` (V10).
Classificação: `arquitetural` pela fronteira de snapshot físico e `transversal` entre
infraestrutura JDBC/JPA, ledger, validação e docs; sem alteração de contrato público.
Inventário de aderência: `JdbcBulkDurableExecution.find` já filtra execution pelo
escopo, mas lê linha/contagens sem prova de prefixo nem tombstone (`suportado-parcialmente`);
`loadEvaluation`, `BulkOrdinalManifest.validateOne`, `durablePrefixConsistent` e
os codecs já verificam o vínculo protegido (`ja-suportado-mal-materializado`);
`BulkExecutionInfrastructure.withLifecycleRead` usa `READ_COMMITTED` e transaction
writable, portanto uma leitura curta `REPEATABLE_READ READ ONLY` é lacuna real de
infraestrutura. O consumidor concreto futuro é o handler `execution-read` do
Quickstart PR #311, após authorizer integral atual do conjunto; não serializar
`BulkExecutionSnapshot`, avaliação ou blob protegido.

Write set mínimo: um caminho interno sem HTTP que abre conexão runtime
independente, fixa isolamento `REPEATABLE READ` e `READ ONLY` antes da primeira
consulta de dados, aplica timeouts locais, lê execution/proposal/evaluation,
manifest, receipt/admission, allocation e tombstone no mesmo snapshot MVCC e
finaliza/fecha a transação. Faltas, duplicidades, drift de fingerprint/digest/versão,
prefixo inválido e combinações impossíveis de status/allocation falham fechadas.
Leitura não adquire locks de escrita, não chama domínio, não altera quota/epoch e
não decide 404/410; até a identificação interna de tombstone só pode ser usada
depois da autorização corrente pelo host. Não expor campos protegidos ou
`NOT_PROCESSED` público neste corte. Provas PostgreSQL focais: commit de receipt
concorrente, purge concorrente, corrupção e escopo cruzado, conferindo que o
resultado inteiro vem de um único snapshot.

### Candidato implementado e limite de uso

`BulkExecutionInfrastructure.withConsistentRead` abre transação independente e
verifica no PostgreSQL físico `transaction_isolation=repeatable read` e
`transaction_read_only=on`, inclusive sob `JpaTransactionManager`; o vínculo da
conexão, role runtime, namespace e timeouts são revalidados. O caminho
package-private `JdbcBulkDurableExecution.inspectConsistent` lê tudo na mesma
transação, valida a avaliação protegida contra a proposta, o manifest V8, o
prefixo de receipts/admissions, estado e duas allocations, e devolve somente
uma observação interna `ABSENT`, `LIVE` ou `TOMBSTONE`. Esta observação contém
controle protegido e **não** é contrato/response público; não pode gerar 404/410
antes de autorização atual do host. Nenhum blob, fato, plano ou texto de
diagnóstico é serializado. Nenhum novo write set, migration, grant ou bean é
introduzido.

Em `RECONCILIATION_REQUIRED`, receipts/admissions fisicamente presentes depois
de `nextOrdinal` permanecem evidência incerta. O read model valida sua forma,
digest, versão e unicidade, mas conta como `CONFIRMED`/outro outcome apenas o
prefixo certificado. O snapshot interno apresenta contagens certificadas, e
`unknown = targetCount - nextOrdinal` cobre conservadoramente todo o sufixo;
não transforma receipt posterior em sucesso nem em `NOT_PROCESSED`. O ledger
físico pode, portanto, ter mais linhas do que a contagem certificada no read
model. Duplicidade ou digest/versão impossível continuam `CORRUPT`.

Prova focal em PostgreSQL 14.22: `BulkDurableExecutionPostgresTest` selecionou
`internalConsistentRead*` e dois cenários existentes de recuperação, 11 testes,
0 falhas/erros. Inclui isolamento físico de
escrita (`SQLSTATE 25006`), JPA, escopo cruzado, commit concorrente de receipt,
purge concorrente com tombstone, e corrupção de versão/ordinal, digest do
manifest e lifecycle de allocation, além de 10.000 alvos e de receipt incerto
após recuperação com prefixo certificado zero ou um. A barreira de teste pausa a leitura depois
do primeiro SELECT que fixa o snapshot: commits concorrentes aparecem apenas
numa nova leitura, nunca como mistura de épocas na mesma observação. Log local:
`/tmp/praxis-h1b-rs3-reconciliation.log`; relatório Surefire:
`target/surefire-reports/TEST-org.praxisplatform.uischema.bulk.BulkDurableExecutionPostgresTest.xml`.

Este validador interno percorre até o limite do snapshot protegido; ele **não**
é um reader paginado. O futuro reader público precisa de consulta limitada por
ordinal e prova própria de custo/latência para 10.000 alvos, autorização atual,
redaction/projeção e cursor seguro. Não declarar RS1/RS3 público ou `READY` com
esta fundação. O material canônico inspecionado em
`praxis-codex-skills` `origin/main cff2c46` local cobre V8/V9, mas não ensina o
snapshot físico `REPEATABLE READ READ ONLY`. Como essa referência local pode
estar defasada, a classificação é provisória: `atualizar-existente`
(`praxis-java-command-concurrency-authoring`, com ajuste de manutenção de
starter se necessário), sujeita a confronto com o HEAD canônico atual pelo
coordenador. A atualização canônica fica sob a coordenação do pacote de skills,
antes do aceite final. Docs HTTP, corpus,
playgrounds, Angular e exemplos públicos não têm superfície nova neste corte.

## Classificação, fonte e impacto

Esta decisão nasceu como `docs-apenas`; o corte V9 é `contrato-publico`,
`arquitetural` e `transversal`: pacote `bulk`, migrations, migrator, codecs, readers,
projeções públicas e composição MVC do Metadata; Quickstart como consumidor concreto;
documentação `docs/spec`, guias, corpus HTTP e skills correspondentes. Config não é
dono dos resultados; Angular só consumirá depois do aceite do backend. Há risco de
quebra para armazenamentos já migrados e de vazamento de fatos/planos protegidos.
Cada corte físico exige PostgreSQL real, teste de migração a partir de V1–V7,
ACL/retention e revisão independente. Nenhuma rota/DTO novo será considerada pronta
por um teste isolado de serialização.

## Inventário de aderência

| Necessidade | Evidência existente | Aderência / lacuna |
|---|---|---|
| Proposta | `BulkProposal` público e `JdbcBulkProposalStore.find` scoped | `suportado-parcialmente`: falta projeção/redaction do provider para `redactedIntent`, diagnostics e evidence, além de snapshot coerente e autorização corrente; `findEvaluation` abre outra conexão |
| Avaliação por alvo | `BulkEvaluationSnapshot` imutável e protegido, até 10.000 alvos | `lacuna-real-de-contrato`: `BulkItemResult` é outcome da execução; falta DTO seguro da avaliação e fonte paginável |
| Execução | `BulkExecution`/`BulkExecutionTotals` públicos e `JdbcBulkDurableExecution.find` protegido | `suportado-parcialmente`: faltam reader, horários/contagens por outcome e estados de cancelamento persistidos |
| Resultado por alvo | `BulkItemResult`, receipts/admissions por ordinal | `suportado-parcialmente`: identidade wire está dentro do blob da avaliação; não há reader/cursor público |
| Cursor | `CursorPage` é envelope; `CursorEncoder` usa Base64 reversível | `lacuna-real-de-contrato`: falta token autenticado, confidencial e ligado ao escopo |

`BulkProtocolReader` valida **entrada** JSON; não é reader de resultados. Os blobs
protegidos guardam fatos, plano, governança, seleção e versões para replay. Eles não
são um response público nem uma fonte de paginação bounded. A existência dos DTOs
públicos não prova que o banco consegue preenchê-los corretamente.

## Decisão 1 — índice privado imutável por ordinal

O primeiro incremento implementável é um manifest privado por `(proposal_id,
ordinal)`, vinculado por FK composta à avaliação exata
`(proposal_id, evaluation_fingerprint)`, além da proposta. Ele guarda apenas identidade wire canônica, versão esperada e vínculo
verificável ao snapshot/target original, sob o schema e as ACLs de `praxis_bulk`.
É índice de leitura derivado, não substitui o blob protegido como autoridade para
replay/admissão. Não guardar `facts`, `plan`, mensagens livres ou parâmetros de
negócio nesse índice. Identidade wire também pode ser dado sensível: só código
autorizado a consulta, nunca uma serialização automática da linha SQL.
O vínculo por alvo deve reutilizar a fórmula versionada `praxis.bulk.unit/1` já
usada por `JdbcBulkDurableExecution.targetDigest`, sem copiar uma segunda fórmula
no store. O produtor e o validador precisam compartilhar o mesmo helper canônico.

`JdbcBulkProposalStore.insert` também admite uma proposta **sem** avaliação. Para
ela, a cardinalidade correta é zero linhas de manifest e nenhum reader de
resultado pode afirmar avaliação concluída. Para toda proposta com avaliação,
o manifest deve conter exatamente `targetCount` ordinais válidos; ausência ou
parcialidade é corrupção, não página vazia.

`insertEvaluated` deve gravar proposta, avaliação, manifest e allocation na mesma
transação do chamador. Qualquer falha marca rollback de tudo; nunca deixar proposta
com manifest incompleto. A ordem de lock existente (`namespace binding` →
`operation-control` → deployment bucket → subject bucket → proposal → execution)
deve ser preservada. O índice precisa impor ordinal
contíguo de 0 a `targetCount-1`, identidade única do alvo por proposta, FKs à proposta e à avaliação exata e
imutabilidade, com custo de escrita e tamanho limitados pelo perfil. A verificação
de contiguidade e vínculo ao blob é responsabilidade do migrator/reader, não uma
suposição derivada do número de linhas.

Migration posterior à V7 deve incluir tabela, checks, índices, triggers de
imutabilidade, grants mínimos, proteção de DELETE e alteração das funções de
expiração/purge. Proposta nunca consumida expira com avaliação/manifest em uma
transação, sem tombstone; execução terminal reconciliada conserva o conjunto até
seu horizonte e o purga junto com as evidências, emitindo apenas tombstone mínimo.
`RECONCILIATION_REQUIRED` não pode ser purgada. Migrator valida estrutura física,
ACL, triggers, funções e consistência de linhas existentes. Backfill Java de avaliações
legadas ocorre no bootstrap antes da validação final, de modo repetível e
atômico: decodifica e verifica integralmente input/evaluation fingerprint,
ordinal e digest; falha fechado se não provar equivalência e não fabrica preview
público. Nenhuma instância rc.136 pode continuar a gravar avaliação durante ou
após esse backfill: suspender admissão e controle, drenar/parar writers antigos,
aplicar migration, backfill e validação, subir somente writers compatíveis e
então reabrir. Alternativa automática só é admissível com constraint/trigger
transacional que faça o writer antigo falhar fechado. Testes devem provar a
janela de cutover e mixed-version, inclusive nó antigo aguardando lock.

Expiração preserva a transição de allocation `PENDING` → `RELEASED` e libera o
slot exatamente uma vez antes de excluir manifest, avaliação e proposta.
Purge conserva tombstone e elimina receipts/admissions e ambas as allocations
antes de execution, manifest, evaluation e proposal, respeitando as FKs.
Triggers e privilégios de DELETE devem permitir somente essas funções governadas;
testes verificam contadores e ausência de dupla liberação sob concorrência.

Este primeiro incremento **não** introduz API de leitura, cursor, DTO de avaliação
ou READY. Seu aceite depende de testes PostgreSQL reais para atomicidade de
inserção/rollback, ordem/limites, migração V1–V7, corrupção/ACL, backfill,
cutover mixed-version, expiração e purge/quota. A evidência precisa corresponder
ao commit e às roles exatas.

## Decisão 2 — projeções e autorização subsequentes

RS1 reutiliza o shape `BulkProposal`, mas `redactedIntent`, diagnostics e evidence
exigem projeção/redaction do provider com campos explicitamente permitidos.
O Metadata não deduz o conteúdo seguro a partir do blob, e o manifest privado
não resolve essa lacuna. Sem projeção compatível, RS1 fica indisponível de modo
seguro; não preencher valores fictícios para satisfazer o DTO. RS3 reutiliza
`BulkExecution` e seus totais; RS4 reutiliza `BulkItemResult` e `CursorPage`.
Esses readers serão construídos no
Metadata em um snapshot SQL consistente, sem ler os 10.000 alvos para devolver
uma página pequena. Não pedir ao Quickstart que leia tabelas, faça joins sobre
blobs ou redefina estado de execução.

RS2 exige um resultado **de avaliação**, distinto do outcome de execução.
Before/after genérico não é seguro: apenas o provider do domínio pode produzir
uma projeção tipada, explicitamente permitida para campos de negócio específicos.
Metadata valida shape, identidade, limites e proveniência; nunca copia `facts` ou
`plan` arbitrários para HTTP. Ausência de projector compatível deixa o preview
indisponível, sem inventar resultado a partir do blob. O contrato público final
de RS2 e o ajuste correspondente de B0 precisam de decisão/revisão própria antes
de código público.

### Corte V9 — projeção física RS2

`BulkPreviewProjection` é a entrada explícita do provider para o armazenamento:
revisão versionada e allowlist de diagnósticos públicos por categoria/código com
mensagem segura. A decisão `EXECUTABLE|BLOCKED` vem da elegibilidade tipada;
diagnóstico privado sem definição pública recusa a projeção inteira. O provider
não fornece mensagens copiadas de `facts`, `plan`, alvo ou metadata; a coluna
`diagnostics` guarda apenas categoria, código e texto seguro, em bytes UTF-8
JSON para não perder caracteres aceitos pelo contrato. O estado `COMPLETE` guarda
também a allowlist pública canônica e um digest versionado da revisão, avaliação,
allowlist e de cada item (inclusive texto seguro). Validação recompõe o digest
e compara cada mensagem à allowlist, detectando drift parcial de texto. O schema
owner continua confiável: alguém que altera simultaneamente linhas, digest e DDL
está fora dessa garantia. Nenhuma comparação
antes/depois genérica é fabricada neste corte.

`JdbcBulkProposalStore.insertEvaluated(evaluation, projection)` exige esse valor
na mesma transação física que proposta, avaliação, manifest e allocation. A
projeção é vinculada à avaliação exata, possui estado `COMPLETE` e linhas
contíguas por ordinal; um trigger diferido exige a projeção completa antes do
commit da avaliação. Instâncias anteriores à V9 não podem continuar a gravar
após o cutover: mesmo se aguardarem lock, o commit sem projeção falha. A
assinatura anterior de `insertEvaluated` foi removida no beta; o host consumidor
deve passar a projeção do provider. Propostas históricas recebem
`UNAVAILABLE_LEGACY`, sem itens e sem preview inventado. Para uma avaliação nova
tipada sem projector seguro, o provider escolhe explicitamente
`BulkPreviewProjection.unavailable(evaluation)`; a avaliação válida persiste
com estado `UNAVAILABLE` sem itens. O trigger exige estado explícito e por isso
continua a rejeitar writer antigo que não gravou a projeção. Um guard de
`INSERT` no item consulta o estado pai antes de admitir dados e recusa
`UNAVAILABLE`/`UNAVAILABLE_LEGACY` mesmo em transação posterior ao commit da
avaliação. A FK do item adquire o lock de chave do pai e impede corrida com
DELETE de retenção. A retenção trava a proposta e remove o item antes do estado. O futuro reader
deve tratar ambos os estados indisponíveis com `BULK_PREVIEW_UNAVAILABLE` após
autorização atual.

A V9 adiciona ACL focal para as mesmas roles runtime já aptas à avaliação,
marcador de bootstrap `PENDING/COMPLETE` exclusivo do owner, validação de
constraints/funções/triggers/linhas e ordem de expiração/purge
`preview_item → preview_state → manifest → evaluation → proposal`. Após
`COMPLETE`, migração/validação não restauram grants revogados nem preenchem
projeções ausentes. O corte não expõe leitor, paginação, cursor, HTTP ou
capability. Autorização atual, forma pública de RS2 e paginação continuam gates
separados.

Toda leitura, inclusive proposta, status e totais agregados, valida autenticação
e autorização **atuais** para o conjunto armazenado, com permissões granulares
de alvo, campo e referência exigidas pelo perfil. Uma permissão parcial ou
revogada fecha a resposta inteira; não filtra nem renumera silenciosamente a
página, nem publica contagens ou diagnósticos do conjunto inacessível. Os testes
devem cobrir grants parciais, revogação e troca de sujeito em RS1–RS4. Leitura histórica usa
namespace, sujeito, recurso e `operationId` imutáveis; mudança posterior de
schema, path ou grupo OpenAPI não invalida o replay autorizado. Tombstone produz
410 só após prova autorizada de vínculo; ausência de tal prova produz 404. Um
cursor de recurso retido pode produzir 412 por precondição obsoleta, nunca como
substituto de expurgo.

O cursor futuro precisa autenticar e ocultar escopo, execução/proposta, ordinal,
limite/direção, validade, versão do projector/shape e `watermarkExclusive`.
Em execução ativa ele fixa apenas o prefixo reconciliado
`0 <= ordinal < watermarkExclusive`; item incerto e sufixo ficam fora da página,
sem `NOT_PROCESSED` inventado. Novos commits em ordinal igual ou maior preservam
o cursor já aberto. Uma nova consulta após terminalização comprovada pode incluir
o sufixo comprovadamente não processado. Status e totais atuais são lidos
separadamente; não exigem igualdade com uma página antiga. O desenho de token,
segredo/rotação, autorização granular e snapshot SQL são gates da etapa de reader.

Cancelamento é outro corte: `cancel_requested_at`, razão terminal, guards de
ACK/recovery, allocation/retenção e projeção pública. Não inferir cancelamento
confirmado de um pedido recebido enquanto há commit incerto. Ele exige provas de
duas conexões e interleavings antes de ser usado pelos handlers.

## Sequência e critérios de saída

1. Implementar manifest privado e evolução física completa, validar PostgreSQL e
   revisão independente; integrar corte autocontido na main.
2. Integrar a projeção física V9 RS2 após testes e revisão independente; fechar
   cursor/ACL em B0, revisar contratos públicos e construir readers RS1–RS4
   sobre manifest/projeção, com testes de paginação, auth e purge.
3. Implementar cancelamento durável, corridas com ACK/recovery e projection.
4. Publicar o Metadata no marco autorizado, adotar no Quickstart e implementar os
   sete handlers. Somente composição real, readback e provas HTTP/PostgreSQL podem
   retirar o host de `UNCOMPOSED`.

Nenhum desses passos autoriza mutação automática na recuperação, expurgo de efeito
incerto ou anúncio antecipado de backend completo.
