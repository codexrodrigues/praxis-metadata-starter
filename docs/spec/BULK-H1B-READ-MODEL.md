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

## Corte interno RS2 — página limitada sobre V11

Base: Metadata main `7f5f7bbd54efd0e3813b3c422181571c331daec5`.
Classificação `arquitetural`/`transversal`, sem contrato público. V8 manifest,
V9 preview e V11 checksum individual fornecem a evidência física; RS3
`withConsistentRead` fornece transação PostgreSQL independente `REPEATABLE READ
READ ONLY` (`suportado-parcialmente`). A lacuna interna era a consulta por
janela limitada com validação local por item. O consumidor futuro é
`bulk-proposal-results` do Quickstart, **após** autorização atual e integral
do conjunto, contrato de response/cursor e revisão próprios. Este corte não
registra rota, capability, DTO público ou `READY`.

`BulkPreviewPageReader` permanece package-private. A primeira consulta lê
somente o vínculo scoped de proposta, avaliação e parent V9/V11, sem selecionar
ou decodificar seus blobs protegidos. A segunda usa o índice do manifest como
eixo, `ordinal > lastOrdinal AND ordinal < watermarkExclusive`, ordenação
ascendente e `LIMIT size+1` (`size` de 1 a 200); o watermark RS2 deve ser
igual ao `targetCount` imutável. Preview e folha entram por `LEFT JOIN`, para
que uma ausência não se transforme em página vazia. Cada linha verifica
continuidade de ordinal, fingerprint, count, wire identity digest, transcript
V11 e diagnostics canônicos contra a allowlist pública do parent. Uma consulta
indexada adicional `ordinal >= targetCount LIMIT 1` rejeita sufixo físico
indevido. Só ordinal, decisão e diagnósticos aprovados saem na observação
interna; wire identity, expectedVersion, targetDigest e digest da folha servem
apenas à verificação. `ABSENT`, `NOT_EVALUATED`, `UNAVAILABLE` e
`UNAVAILABLE_LEGACY` são estados internos distintos e não decidem 404/409
antes do authorizer do host. Drift, falta, duplicidade, shape inválido ou
checksum divergente falham fechados sem detalhe protegido na exceção.
Estados sem avaliação/projeção usam probes indexados `LIMIT 1` para rejeitar
manifest, preview ou folhas que não deveriam existir, em vez de aceitar
um estado de indisponibilidade aparentemente legítimo após drift.

O orçamento agregado é 20 MiB por chamada, incluindo allowlist, manifest,
diagnostics e a linha extra `size+1`; excedê-lo produz indisponibilidade
operacional segura, jamais página truncada. Como V8 admite até 8 MiB de
identidade e 8 MiB de versão por linha, até uma página máxima fisicamente
válida pode ser recusada por esse orçamento. O `PreparedStatement` usa
`fetchSize(1)` **antes** de `executeQuery` como estratégia para reduzir a
materialização antecipada de linhas grandes; o orçamento rejeita a página
quando excedido. Isso não prova um limite de heap no driver e pode custar até
201 fetches de rede numa página de 200. A prova de heap e latência com
PostgreSQL e dados representativos ainda é pendente antes da API/release; não
aumentar fetch sem essa evidência. A janela usa o timeout existente da infraestrutura
RS3 (transação 3 s, statement limitado a 2 s), sem SLA novo prometido.
Nenhum lock de escrita, callback de domínio ou mutação é introduzido.

Provas focais PostgreSQL incluem 10.000 itens e fronteira keyset, estado
indisponível/legado, escopo cruzado, drift de mensagem/folha/manifest e
expiração concorrente pausada após o primeiro SELECT: o snapshot antigo
retém uma página íntegra e uma nova leitura observa ausência. A garantia de
JPA e read-only físico é herdada e comprovada em RS3; este reader usa a mesma
infraestrutura, sem prometer suporte para outra topologia. Nenhum documento
HTTP, corpus, Angular ou exemplo público muda neste corte. Impacto em skill:
`atualizar-existente` para guidance de paginação segura/limite de fetch no
`praxis-java-command-concurrency-authoring`, sob coordenação da atualização
canônica antes de adoção pública.

### Gate de bootstrap V11 para o reader — corte V12 candidato

**Estado da decisão:** revisão arquitetural aprovada; implementação V12 e reader
RS2 são candidatos sob provas PostgreSQL e revisão independente. Nenhum deles
pode ser ativado publicamente antes do aceite completo deste gate.
Reprodução PostgreSQL: após persistir uma avaliação `COMPLETE`, o owner muda
`praxis_bulk_preview_integrity_bootstrap.phase` para `PENDING`; o reader inicial
continuava devolvendo `COMPLETE`, pois validava parent e folhas, mas não consultava o
marker. A revisão classificou a falha como P1. Aderência: o V11 já possui o
marker owner-only e um trigger `SECURITY DEFINER` que barra *inserts* no parent;
falta uma leitura atestada do mesmo marker sob o snapshot RS3. Classificação
`arquitetural`/`transversal` de contrato físico privado, sem endpoint, DTO ou
semântica pública nova. Fonte canônica: Metadata Starter. Consumidor concreto:
`BulkPreviewPageReader` package-private; o Quickstart só poderá consumi-lo após
autorização e contrato de leitura próprios.

Alternativas descartadas: conceder `SELECT` no marker à role runtime rompe o
limite owner-only e amplia a superfície de catálogo; cache de lifecycle pode
ficar obsoleto após `COMPLETE` → `PENDING`; chamar a função V11 existente é
impossível porque ela retorna `trigger`. A menor solução correta é uma função
PL/pgSQL versionada V12, por exemplo
`praxis_bulk.assert_preview_integrity_complete() RETURNS boolean`,
`SECURITY DEFINER`, `STABLE` (nunca `IMMUTABLE`), owner igual ao schema owner atestado, `search_path` fixo
`pg_catalog, pg_temp`, `PUBLIC EXECUTE` revogado, `EXECUTE` concedido somente
às roles runtime explicitamente configuradas. Ela devolve `true` apenas quando
há **exatamente uma** linha de cada marker V11 e V12 e ambas estão `COMPLETE`, e levanta SQLSTATE
`55000` se faltar marker ou a fase divergir. Não recebe identificadores nem
expõe conteúdo da tabela; o runtime continua sem `SELECT` nos markers. O
reader a executa **antes do header**, na mesma conexão e transação
`REPEATABLE READ READ ONLY` de `withConsistentRead`; erro, privilégio ausente
ou função ausente falha fechado como indisponibilidade, sem página ou detalhe
do marker. O reader exige exatamente uma linha e valor booleano `TRUE` da
função; qualquer outra forma ou erro é indisponibilidade. A atestação viva já
feita uma vez por `withConsistentRead` deve passar a conferir assinatura,
owner, `SECURITY DEFINER`, `STABLE`, `search_path`, corpo extraído do SQL V12,
ACL efetiva, ausência de `PUBLIC`/grant option e membership. Não repetir a
atestação por item. Se uma transição owner para `PENDING` ocorrer depois de fixado o
snapshot, a leitura conserva a versão anterior íntegra; a nova transação vê
`PENDING` e falha.

V12 cria a função e um marker owner-only `praxis_bulk_preview_reader_bootstrap`
(`bootstrap_version=12`, fase `PENDING/COMPLETE`) em uma transação Flyway.
Não há novo write set de proposta, avaliação, preview ou folha. O marker V12
autoriza **uma única vez** o grant dinâmico da função às roles runtime; sem ele,
um bootstrap que falha depois do DDL teria de reparar ACL sem distinguir
instalação de drift. O migrator, sob seu advisory lock existente, bloqueia
markers na ordem V8 → V9 → V11 → V12, verifica roles e catálogo, concede
`EXECUTE` exato apenas durante V12 `PENDING`, valida assinatura, linguagem,
volatilidade, owner, `SECURITY DEFINER`, `search_path`, corpo derivado do SQL
versionado, ACL de função e owner-only de ambos os markers; muda V12 para
`COMPLETE` e revalida no **mesmo commit**. Falha ou role incorreta reverte grant
e fase, permitindo retry em `PENDING`. Depois de `COMPLETE`, revogação/alteração
de grant, função ou marker é drift e **não** sofre auto-heal. A função testa os
dois markers ao vivo em cada snapshot e não depende de validação apenas no
startup. Não introduz row lock no reader nem altera a ordem de locks da
retenção ou do writer.

O DDL novo é aditivo, mas binários V11 antigos rejeitam relações/funções
desconhecidas no catálogo; portanto drenar hosts V11 e jobs de retenção antes
do Flyway V12, aguardar transações em voo, executar DDL e bootstrap V12,
validar com roles exatas e só então reabrir o serviço. Não se promete deploy
sem interrupção nem rollback binário V11 após o schema V12: falha após DDL deixa
o marker V12 `PENDING` e a função sem grant runtime até retry; reversão de
release requer restauração governada ou correção progressiva, nunca remover
fence/ACL ad hoc. Superuser/schema owner capaz de editar função, marker e
catálogo de forma coerente está fora do modelo de ameaça; runtime role não
tem essa autoridade.

Provas PostgreSQL mínimas do V12: instalação fresca V1→V12 e upgrade
V11→V12, leitura `COMPLETE` normal; owner muda V11 ou
V12 para `PENDING` após avaliação e a próxima leitura recusa, inclusive com
parent/folhas íntegros; snapshot anterior à transição permanece consistente;
runtime não consegue `SELECT`/`UPDATE` marker nem alterar função; chamada
direta só é possível para grantee runtime exato; revogação de `EXECUTE`, grant
ao `PUBLIC`, owner/corpo/search_path desviados e marker ausente falham na
validação sem reparo; bootstrap com role incorreta reverte grant e fase e retry
controlado conclui; o validator do binário V11 rejeita o catálogo V12.
Writer V9 é barrado pelo fence físico V11; writer V11 durante V12 exige o
dreno operacional, não um fence V12 que não existe no write set.

P2 do mesmo aceite: contabilizar o orçamento por bytes UTF-8/bytea **de todas**
as colunas selecionadas do header e de cada linha, inclusive fingerprints,
digest V11, decisão, campos de tamanho fixo e a linha `size+1`, com overhead
conservador documentado. Fazer a cobrança antes de interpretar diagnostics ou
adicionar item ao resultado. Um teste de fronteira com duas linhas fisicamente
válidas e checksum V11 coerente, `size=1`, deve provar que a segunda linha
excede 20 MiB e recusa a chamada completa; outro teste prova página abaixo do
limite. Igualdade exata com 20 MiB é aceita; o primeiro byte acima, inclusive
na linha extra, é recusado. O orçamento limita bytes processados, **não** é promessa de pico de
heap ou SLA; `fetchSize(1)` e benchmark representativo continuam obrigatórios
antes da superfície HTTP.

## Corte interno RS4 — resultados duráveis por página

Base: Metadata main `712eb13f1bad382b8cb6f4a57ae619d24e8e7c1b` (RS1/RS2/V12).
Classificação `arquitetural` interna; nenhum contrato público, SQL físico,
grant, endpoint, cursor ou `READY` é criado. Consumidor futuro: handler
`execution-results` no Quickstart, após autorização corrente integral do
conjunto. Docs HTTP/corpus/Angular não derivam deste corte.

Inventário de aderência: `BulkConsistentExecutionRead` e
`JdbcBulkDurableExecution.inspectConsistent` já certificam controle/prefixo,
mas percorrem avaliação protegida, manifest e todo o ledger de até 10.000
unidades (`suportado-parcialmente`, inadequado para página). Manifest V8 já
vincula ordinal, identidade wire e versão à avaliação; receipts V3 e admissions
V4 guardam outcome e digest duráveis, enquanto tombstone V5 preserva somente
estado terminal (`ja-suportado-mal-materializado`). `BulkExecutionInfrastructure`
já fornece `withConsistentRead` independente `REPEATABLE READ READ ONLY`; não
existe lacuna de contrato público para a **fundação interna**. `BulkItemResult`
é contrato futuro; não é resposta deste leitor nem autoriza expor reason/facts.

Write set mínimo: somente leitor package-private e provas PostgreSQL; queries
indexadas no mesmo snapshot observam header de execution, vínculo
proposal/evaluation e tombstone scoped, depois keyset por ordinal do manifest
com LEFT JOIN das receipts/admissions, `LIMIT size+1` e limite agregado de
bytes. Escopo confiável inclui namespace, subject, resource e operationId;
fingerprints, targetCount, digest da identidade wire, versão e targetDigest
são comparados antes de aceitar um item. A primeira página fixa e devolve
`watermarkExclusive`; toda continuação interna exige esse valor explícito e
jamais amplia a janela quando ACK ou `STOPPED` avança `nextOrdinal`/status.
O futuro cursor público deve vincular o watermark ao escopo/shape governado.
O prefixo servido deve estar em
`ordinal < nextOrdinal` e ter exatamente uma evidência durável coerente por
ordinal. Evidência no sufixo de `RECONCILIATION_REQUIRED` nunca vira item
`UNKNOWN`; ele fica fora da página até reconciliar. Em `STOPPED` terminal,
`NOT_PROCESSED` é permitido apenas em ordinal >= `nextOrdinal` com ausência
física comprovada de receipt e admission na própria linha. Estados não
terminais não fabricam itens futuros. `COMPLETED` e
`COMPLETED_WITH_ERRORS` exigem `nextOrdinal=targetCount` e evidência por item.
Um `EXISTS` indexado do ledger de admission exige ausência para `COMPLETED`
e presença para `COMPLETED_WITH_ERRORS`, sem scan global por página. A garantia
do leitor é local aos itens retornados e ao estado terminal mínimo; a auditoria
integral do prefixo, contagens e allocations continua em RS3.
Drift/duplicidade/shape impossível falha `CORRUPT`; SQL/ACL/timeout falha
`UNAVAILABLE`, sem payload ou razão privada na exceção. O orçamento de 20 MiB
inclui bytes do manifest e a linha de lookahead; aceita igualdade e rejeita o
primeiro byte acima do limite. Ele limita bytes processados, não prova pico
de heap nem estabelece SLA; `fetchSize(1)` preserva a estratégia conservadora
até benchmark representativo antes de HTTP.

Locks: nenhum lock de escrita; o primeiro SELECT fixa a visão MVCC, então
ACK/receipt, cancelamento, recovery e purge podem confirmar em outra conexão
sem página híbrida. Tombstone scoped nunca é convertido em 410 antes de
autorização do host. O valor interno contém apenas bytes wire canônicos,
ordinal e outcome/razão de código para interpretação posterior e tem
serialização/toString opacos. Não lê o blob da avaliação por página; portanto
este corte comprova a integridade **local** dos itens servidos contra manifest
imutável e controle durável, não reexecuta a auditoria integral RS3 em cada
requisição. A ordenação, a linha extra e o orçamento são verificáveis em
PostgreSQL com 10.000 alvos, corrupção, fronteira de bytes, escopo cruzado e
barreiras determinísticas de ACK/purge. Antes de expor HTTP ainda faltam
authorizer granular, cursor protegido e redaction explícita de razões/identidade.

Prova focal em PostgreSQL 14.22: dez métodos de
`BulkDurableExecutionPostgresTest` cobrem prefixo/`STOPPED`, admission,
reconciliação sem `UNKNOWN`, escopo cruzado, manifest/receipt drift, ACK e
purge concorrentes entre SELECTs, watermark fixado entre páginas,
admission/status terminal incompatíveis, 10.000 alvos e orçamento da linha extra.
O método de 10.000 alvos mediu 69 ms para duas páginas de 200 itens no PG
embedded local; isto não é orçamento de latência de produção. A bateria final
tem 10/10 sem falhas/erros no XML Surefire desta árvore, log local
`/tmp/praxis-rs4-ten.log`. RS3 já prova o
`withConsistentRead` físico com JDBC e JPA; RS4 não altera essa infraestrutura.
Impacto de skill: `atualizar-existente` em
`praxis-java-command-concurrency-authoring`; o arquivo canônico consultado em
`praxis-codex-skills` HEAD `d99aef0` ensina outcomes em lote, mas ainda não
descreve esta leitura protegida bounded e seus limites. A coordenação entrega
e valida a atualização na fonte canônica; este pacote Metadata não edita skills.

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

## Corte interno RS1 — proposta e avaliação no mesmo snapshot

Base: Metadata main `17d102ec69c5e00c4b75101ad8c6f0f9652bb228` (V12). A
mudança é `arquitetural` interna: cria uma observação protegida coerente, mas
não altera contrato público, endpoint, autorização, projeção segura ou estado
`READY`. Fonte canônica: `praxis-metadata-starter`; consumidor concreto futuro:
handler `bulk-proposal-read` do Quickstart, somente após autorização corrente
e redaction explícita do provider. Não há artefato HTTP, corpus, Angular ou
playground derivado deste corte.

Inventário de aderência: `JdbcBulkProposalStore.find` já verifica escopo e
fingerprint da intenção; `findEvaluation` já verifica o vínculo da avaliação,
mas chama `find` e abre outra conexão/observação. Os codecs protegidos
`BulkSnapshotStorageCodec` e `BulkEvaluationStorageCodec` já validam shape,
fingerprints e vínculo do conteúdo. `BulkExecutionInfrastructure.withConsistentRead`
já estabelece transação física independente `REPEATABLE READ READ ONLY`, escopo
operacional, ACL viva e timeout curto. Classificação: `suportado-parcialmente`;
falta somente a composição desses leitores em **um** snapshot. Não se cria DTO
de resposta, registry, SPI ou nova migration.

Write set: nenhum. O reader package-private observa proposta e avaliação por
`proposal_id` e escopo confiável `(namespace, subject, resource, operationId)`;
decodifica ambas na mesma conexão após o primeiro SELECT fixar o snapshot.
`ABSENT`, `NOT_EVALUATED` e `EVALUATED` são estados internos, não códigos HTTP.
Se a proposta estiver presente sem avaliação, a observação exige ausência de
manifest/projeção/folhas; se houver avaliação, exige vínculo de fingerprint
exato. Duplicidade, bytes inválidos, vínculo/escopo divergente ou linha
dependente inesperada sob proposta sem avaliação falham `CORRUPT` sem incluir
payload na exceção. SQL/ACL/timeout falham
`UNAVAILABLE`. A leitura não usa locks de escrita: o snapshot MVCC permite
expiração concorrente e impede misturar proposta anterior com avaliação
posterior ou vice-versa. O futuro host não deve converter `ABSENT` em 404/410
antes de autenticação, autorização atual e decisão sobre histórico/tombstone.

Mapa de impacto: apenas pacote `bulk` e testes PostgreSQL do Metadata; a API
pública existente de `JdbcBulkProposalStore` mantém a semântica. Risco material
é vazamento de `facts`, `plan` ou intenção protegida por serialização acidental:
o valor interno fica package-private/`@JsonIgnoreType`, com `toString` opaco.
Não produzir `redactedIntent` genérico; a autorização histórica ambígua,
delegação, expiração pública e shape da projeção do provider permanecem lacunas
separadas de RS1. Provas focais: PostgreSQL real em conexões independentes,
cross-scope, proposta sem avaliação, corrupção dos dois blobs/vínculo, read-only
físico e expiração da proposta não consumida entre SELECTs, com snapshot antigo
coerente e leitura nova ausente. A prova focal executada em PostgreSQL 14.22 foi
`BulkProtectedProposalReaderPostgresTest` (4 testes, sem falhas/erros): estados,
escopo, conteúdo opaco em `toString`/Jackson, corrupção de ambos os blobs,
ACL revogada como indisponibilidade e expiração confirmada entre os dois SELECTs.
Log local `/tmp/praxis-h1b-rs1-reader.log` e XML Surefire homônimo da classe;
a infraestrutura RS3 já possui prova própria de transação física READ ONLY/JPA.
Skill: `atualizar-existente` em `praxis-java-command-concurrency-authoring`
(fonte canônica `praxis-codex-skills` HEAD `d99aef06` inspecionada): o guidance
atual cobre comando/retry, mas não ensina leitura protegida coerente e
autorização posterior. A atualização canônica fica com a coordenação; esta
árvore Metadata não modifica skills.

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
