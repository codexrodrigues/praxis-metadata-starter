# H1b — decisão de base para leitura de operações em lote

Estado: V8/V9/V10 integrados; a fundação interna RS3 abaixo ainda é candidata, sem reader
público, endpoint, cursor ou `READY`. Baseline inicialmente auditado: Metadata main
`fdc4fac8cf6bdf6129282db6b0ada26c82ac03cb` (`8.0.0-rc.136`) e consumidor
Quickstart PR #311. O plano do consumidor está em
`internal-planning/bulk-operations/H1B-WRITE-SETS.md` no Quickstart. B0 continua
definindo invariantes e semântica pública; divergências devem ser corrigidas ali e
revisadas antes de modificar um contrato público.

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
