# Persistência protegida de propostas

A [evidência de avaliação de domínio](BULK-EVALUATION-EVIDENCE.md) amplia este armazenamento com vínculo atômico de fatos/plano e migração V2, sem conferir elegibilidade. A migração V5 acrescenta ledger durável de capacidade e retenção; as regras estão em [execução durável](BULK-DURABLE-EXECUTION.md) e [controle de operação](BULK-OPERATION-CONTROL.md).

O SDK oferece `BulkStoredProposal` e `JdbcBulkProposalStore` para inserir e recuperar a intenção protegida de um lote. O conteúdo inclui contexto confiável, identidade da operação, revisão do schema, atomicidade, codec de identidade, modalidade e intenção normalizada. A projeção pública `BulkProposal` continua separada.

O recorte publicado aceita seleção EXPLICIT e execução SYNC nas três modalidades: alteração uniforme, ação de domínio e alterações por item. Só as representações canônicas Integer, Long, String e UUID são reconstituídas. BulkStoredProposal valida os tokens dos alvos com o codec canônico antes de admitir o objeto; declarar um codecId conhecido em um codec customizado não contorna essa validação. Um codec customizado com representação wire realmente compatível permanece válido. A rc.154 também admite QUERY delimitada, com manifesto de alvos capturado; ASYNC permanece fora deste adapter. Em V5, inserir uma proposta também reserva atomicamente a quota pendente; isso não demonstra avaliação elegível, autorização, reserva de execução, confirmação ou execução de negócio.

## Captura sob locks — publicada na rc.153

A corrida de quota em REPEATABLE READ foi corrigida na rc.153: bloquear um bucket imutável não atualiza o snapshot usado pelo COUNT. A V17 permite atualização física sem alteração da identidade do bucket de deployment, somente na admissão de propostas; reservas/replays conservam os locks anteriores. O concorrente com snapshot antigo aborta antes do callback como UNAVAILABLE; CAPACITY exige uma tentativa nova com snapshot fresco. Não há retry interno do callback. O focal PostgreSQL passou 22 testes, incluindo as corridas 9→10/99→100, SQLSTATE40001 direto no ledger e regressões de quota RC/reserva/execuções ativas. Quota e migração/cutover do núcleo foram provados nos focais do candidato. O código foi integrado pelo PR231; a rc.153 foi publicada pelo workflow oficial [37223334190](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/37223334190), com POM/JAR verificados no Maven Central. O JAR privado anterior não substitui essa prova.

O corte V17 preserva os checksums V1..V16, as identidades dos buckets e os grants por coluna existentes. Atualiza o corpo do guard e sua expectativa de catálogo no SDK. O binário anterior espera o guard de V5 e deve recusar o catálogo atualizado: planejar drain e troca coordenada, sem presumir compatibilidade entre versões simultâneas. O touch físico ocorre apenas quando a admissão exige quota pendente, mantendo SELECT FOR UPDATE para reserva/replay. Em concorrência por deployment, pode haver abort transitório mesmo antes de esgotar a quota; este mecanismo não promete fairness nem retry transparente.

A migração final exige preflight transacional do guard V5, owner, atributos, corpo, ACL e das duas ligações exatas de trigger, inclusive ausência de ligações adicionais em outras tabelas. Deve recusar divergências antes da substituição, sem corrigir silenciosamente o catálogo anterior ou registrar V17. O focal final passou 14 testes, incluindo a matriz de cinco adulterações e a recusa sem reparo silencioso. Os 22 testes de quota provam o mecanismo operacional, cujo corpo não mudou com o preflight. As provas focais do host e do consumidor empacotado precederam a adoção pública, comprovada separadamente abaixo.

A API publicada na rc.153 `captureAndInsertEvaluated(proposal, capture, project, callerRemaining)` compõe a captura confiável do host com a persistência existente para SYNC/EXPLICIT, incluindo a população explícita representada por `items` em PER_ITEM_UPDATE. A rc.154 pública acrescenta o overload `captureAndInsertEvaluated(ReadyAdmission, proposal, capture, project, callerRemaining)` para `UNIFORM_UPDATE/SYNC/PER_ITEM/QUERY` sob perfil publicado com teto de até 200. As APIs antigas `insert`, `insertEvaluated` e captura sem token continuam negando QUERY; o overload não habilita ASYNC ou endpoint por si. O host MissionParticipant provou separadamente o consumo candidato, conservando seu teto de 200 alvos e orçamento nominal de 8s; isso não substitui a publicação oficial rc.154 nem o aceite da adoção do host, cujo verify integral está em curso.

O host prepara intenção, UUID, contexto, tuple de controle e janela de validade antes de abrir uma transação física REPEATABLE READ gravável no datasource operacional. O store participa com MANDATORY, sem nova transação. Binding/controle/buckets de capacidade precedem o callback; o callback recebe a mesma conexão. Na seleção QUERY, o host captura a população e retém o grant autoritativo **dentro da callback**, sob esses locks, antes de ler os fatos de domínio; o SDK não retém G por conta própria. Readers read-only não mudam de modo nem passam a autorizar essa captura. Os buckets permanecem retidos até commit/rollback, serializando capturas relacionadas; não há garantia de fairness ou escala derivada deste corte.

Na captura QUERY, `filter` e `excludedIds` ficam na intenção protegida, sem `targets` fornecidos pelo cliente. A callback confiável devolve evidência não vazia, sem duplicatas ou IDs excluídos, com até o teto publicado e na ordem numérica/canônica resolvida pelo host. O mesmo conjunto alimenta avaliação, manifesto, preview e allocation na mesma transação. A fábrica pública `invalidSelection()` não recebe argumento nem causa e só representa ausência de alvos ou excesso do teto **após** a captura; uma callback também pode devolvê-la para essas duas condições. Runtime inesperada de projeção, codec ou persistência não ganha esse código: é `UNAVAILABLE` sanitizado, com rollback dos sete artefatos mesmo se o chamador capturar a exceção. Excluir um ID e depois incluí-lo na evidência é erro de captura, não sucesso parcial.

O callback retorna `BulkEvaluationSnapshot` da mesma proposta: UUID, contexto, fingerprint da intenção, tuple de controle e createdAt/expiresAt devem coincidir. A projeção pública exige `requireMatches`. Proposta, avaliação, manifesto, preview (estado, itens e integridade) e allocation são inseridos juntos e permanecem provisórios até commit externo. Falhas após participação marcam rollback-only, mesmo se capturadas pelo chamador. Callbacks são confiáveis: não podem executar mutação de domínio, gerenciar transações, alterar auto-commit ou reter a conexão/supplier após retorno.

O chamador cria um deadline monotônico antes da aquisição de pool/TX e fornece seu restante vivo. O store aplica o menor entre saldo do chamador, seu limite próprio não renovável de 30s e expiração observada no PostgreSQL, com verificações após captura/projeção/encoding e entre persistências. SQL da captura usa LOCAL statement_timeout/lock_timeout, preservando o menor limite vigente não zero e restaurando a configuração do chamador quando possível. Tempo anterior à participação é contabilizado e pode invalidar a captura; pool, attestation e código Java arbitrário não são cancelados preemptivamente. Não interpretar 8s/30s como teto duro end-to-end.

Na rota persistente MissionParticipant, expiresAt é fixado como createdAt+validity antes da captura; espera ou avaliação não renovam a janela. A avaliação standalone anterior mantém sua política de validade própria. Runtime inesperada de callback/projection/supplier é sanitizada como `UNAVAILABLE`, sem texto ou causa protegidos; o store não interpreta exceções HTTP do host. O host conserva a negativa inicial conhecida 403 e classifica conservadoramente redução de escopo durante captura como 503 genérico: não foi possível produzir uma fotografia autoritativa completa. Isso não comprova indisponibilidade nem concede autorização.

O host candidato pré-publicação passou cinco testes de captura JPA/JDBC e dois HTTP reais de revogação/redução de cobertura, com auditorias completas e ausência de novas escritas após a negativa. O consumidor Maven isolado passou dois testes, incluindo um HTTP interno. A guidance canônica foi integrada e sincronizada seletivamente. Esses aceites são delimitados: não provam corrida administrativa, IAM completo ou avaliação positiva completa. O verify local histórico reportou 1.517 testes, uma falha, zero erros e três skips; o oráculo temporal falho foi corrigido e passou em focal próprio. O empacotamento sem repetir a suíte e o Javadoc passaram depois dessa correção; o verify local anterior não é apresentado retroativamente como verde. Na árvore corrigida da tag rc.153, o clean verify oficial passou com 1.517 testes, zero falhas/erros e três skips explícitos. Esse é outro resultado, posterior à correção. Publicação e documentação oficiais concluídas; POM/JAR públicos verificados.

A adoção pública EXPLICIT foi integrada no host pelo [PR387](https://github.com/codexrodrigues/praxis-api-quickstart/pull/387), head `18fa2d0fca7574e72152a8fb808258a562f64b46` → main `fd186925806ba11ba13883ac76b80b15714a1e6b`. O verify integral passou com **1.198 testes, zero falhas/erros e 23 skips explícitos**, Java 21 e dependências Maven Central sem override. Os JARs Metadata rc.153 e Config rc.158 dentro do BOOT-INF correspondem aos artefatos públicos; 39 migrations e um bootstrap foram conferidos. A evidência `praxis-api-quickstart/internal-planning/bulk-operations/evidence/20261004-b5a-rc153-public-host-adoption.json` delimita esse marco. Ele não certifica seleção QUERY, avaliação positiva, IAM completo, READY global ou deploy.

O recorte QUERY posterior tem 25 provas SDK compostas (12 + 1 + 12), incluindo PostgreSQL real para captura, quota, rollback, admissão e replay. O host acrescentou 49 provas focais compostas e HTTP real em `MissionParticipantUniformQueryHttpPostgresTest`: captura de 200 alvos, confirmação/replay de um alvo e perda de cobertura com STOP durável sem admission ordinal e leitura pública 404 quando o grant global permanece válido. São provas focais do candidato privado anterior; a publicação QUERY rc.154 foi comprovada separadamente pelo workflow oficial 37236119846, com 1.530 testes, zero falhas/erros, três skips e POM/JAR verificados no Maven Central. O host já usa rc.154/Config rc.158, mas seu verify integral e aceite de adoção ainda estão em curso. Essas provas não certificam IAM completo ou confirmação de 200 alvos. Também não atestam o experimento de perda de resposta COMMIT anteriormente rejeitado.

## Integração explícita

```java
// Instalação inicial: migração fora de qualquer transação Spring.
BulkExecutionMigrator.migrate(migrationDataSource, namespaceToDeploymentId);
// O host provisiona as roles e grants completos no PostgreSQL antes do runtime.
var roles = new BulkExecutionRoleConfiguration(
    expectedSchemaOwnerRole, runtimeGranteeRoles, retentionExecutorMembers, controlPlaneGranteeRoles);
BulkExecutionMigrator.validate(migrationDataSource, roles);

// Composição do runtime: datasource operacional compartilhado com o domínio.
var infrastructure = new BulkExecutionInfrastructure(
    operationalDataSource, operationalTransactionManager, deploymentNamespace, deploymentId, roles);
var proposals = new JdbcBulkProposalStore(infrastructure);

// Dentro da transação de serviço já existente:
proposals.insert(new BulkStoredProposal(proposalId, createdAt, expiresAt, snapshot));
var stored = proposals.find(trustedContext, proposalId);
```

`migrationDataSource` pode usar credenciais diferentes, mas deve apontar para o mesmo banco operacional. O starter não descobre, cria ou escolhe essas credenciais. A [infraestrutura transacional](BULK-EXECUTION-INFRASTRUCTURE.md) verifica o vínculo do datasource de runtime com o manager; não compara URLs nem comprova a configuração externa do datasource de migração.

No **upgrade V7→V8**, primeiro suspenda admissão e drene/pare todos os writers anteriores à V8. Com as roles runtime V7 e seus grants exatos de avaliação já provisionados, `BulkExecutionMigrator.migrate(migrationDataSource, namespaceToDeploymentId, roles)` aplica V8, faz backfill e concede somente `SELECT, INSERT` no novo manifest a essas roles elegíveis. A validação estrita deve passar antes de iniciar writers V8 e reabrir admissão. A V8 impede commit de writer antigo sem manifest, mas essa proteção não substitui o cutover operacional. Na instalação inicial, ainda não existem grants V7: use a sequência em duas fases do exemplo e valide após o provisionamento. O migrator não provisiona as demais ACLs da aplicação.

No upgrade histórico V14→V15, o trecho V7→V8 acima descreve o bootstrap do manifest, não a sequência completa daquele upgrade. Drene os escritores antigos, aplique o DDL até V15, conceda explicitamente os grants novos de publicação e a assinatura de oito argumentos do controle, e só então execute `BulkExecutionMigrator.migrate(migrationDataSource, namespaceToDeploymentId, roles)` para concluir os bootstraps pendentes e validar o catálogo completo. Essa chamada pode executar zero migrations Flyway sem deixar de realizar o bootstrap. Siga os grants e o cutover de [V14/V15](BULK-OPERATION-CONTROL.md); o migrator não corrige ACL revogada depois de `COMPLETE`. Essa sequência histórica não substitui o cutover V16→V17 descrito em [captura sob locks](#captura-sob-locks--publicada-na-rc153).

A V8 registra um marcador privado `PENDING` após o DDL. Backfill, grant focal e transição para `COMPLETE` ocorrem na mesma transação de bootstrap. Se o processo cair ou a transação falhar depois do DDL, uma repetição pode concluir o estado `PENDING` mesmo com zero migrations Flyway novas. Antes de `COMPLETE`, o bootstrap confere na mesma transação que as roles configuradas correspondem aos grantees reais da avaliação; uma chamada com roles incompletas não consome a chance de retry. Depois de `COMPLETE`, o migrator não reconstrói linhas ausentes nem restaura grants revogados, inclusive quando uma versão futura de migration for aplicada; drift de dados ou ACL falha na validação e exige intervenção explícita do provisionamento. O marcador é exclusivo do schema owner: runtime, retention owner/executor e `PUBLIC` não recebem privilégios diretos; qualquer grant não-owner nele deve falhar na validação.

Construir o store não acessa o banco nem registra beans. `insert` e `find` exigem a transação física, existente e gravável do serviço, participando com MANDATORY. Nenhuma transação independente é criada pelo adapter. O retorno de insert é provisório até o commit externo; rollback remove a inserção. Um UUID já existente gera `BulkProposalStorageException.Reason.CONFLICT`, sem upsert nem sobrescrita. Essa unicidade técnica não substitui a futura chave de idempotência de negócio.

No **upgrade V8→V9**, drene/pare writers antigos e suspenda admissão antes da
migration. O provider passa a fornecer `BulkPreviewProjection` com revisão e
allowlist explícita de diagnósticos públicos; `insertEvaluated` agora exige
`(evaluation, projection)`. A V9 grava estado `COMPLETE` e exatamente uma linha
segura por ordinal na mesma transação de proposta, avaliação, manifest e quota.
Avaliações legadas são marcadas `UNAVAILABLE_LEGACY` sem projetar mensagens
protegidas. Uma avaliação nova sem projector seguro exige escolha explícita
`BulkPreviewProjection.unavailable(evaluation)`, persistida como `UNAVAILABLE`;
o futuro reader retornará indisponibilidade após autorização, preservando a
avaliação válida. `COMPLETE` grava allowlist pública e digest versionado de
revisão e itens; o migrator recomputa e compara mensagens, detectando drift
parcial. Trigger diferido impede commit de writer antigo que não gravou estado
explícito. Um guard de `INSERT` no item exige pai `COMPLETE`; a FK fornece lock
referencial contra DELETE concorrente e impede
acrescentar itens tardios a estados indisponíveis. O bootstrap V9 tem marcador próprio `PENDING/COMPLETE`: enquanto
`PENDING`, concede `SELECT, INSERT` nas tabelas de projeção somente às roles
runtime já aptas à avaliação e explicitamente configuradas; depois de
`COMPLETE`, não restaura grants nem reconstrói linhas. Valide estrutura, ACL,
linhas e roles antes de reabrir admissão. V9 isoladamente não publicou reader
ou endpoint RS2. O recorte histórico G3a acrescentou checksum V11, gate/leitor interno V12
e a fachada Java de primeira página autorizada, ainda sem rota HTTP,
continuação/cursor público ou `READY` naquele corte. Os leitores paginados posteriores
e o lifecycle têm contratos próprios em [resultados](BULK-PROTOCOL-RESULTS.md)
e [lifecycle](BULK-OPERATION-LIFECYCLE.md); este store isoladamente não publica HTTP.

## Identidade, validade e proteção

A consulta combina UUID, namespace, usuário, recurso e operationId vindos do contexto confiável do servidor. Outro usuário/recurso/operação recebe ausência; namespace divergente da infraestrutura é rejeitado. O `operationRef` original completo, schemaRevision e atomicidade retornam no snapshot para futura revalidação. Uma revisão atual diferente não torna a linha corrompida nem autoriza executá-la.

Os timestamps são truncados a microssegundos antes da persistência. ExpiresAt deve ser posterior a createdAt; o intervalo suportado vai de 0001 a 9999. O store pode recuperar entradas expiradas: a reserva de execução deve negar novas mutações após a validade. A V5 fornece procedimentos restritos para expirar propostas não consumidas e expurgar resultados terminais após retenção; eles não são jobs automáticos, nem autorizam DELETE público ou atualização de proposta. A migration cria controles de lifecycle em `UNCOMPOSED`; somente a publicação governada posterior pode colocá-los em `READY`. Este adapter não publica endpoint nem executa automaticamente uma operação de negócio.

O payload BYTEA contém JSON protegido, sem criptografia adicional fornecida pelo SDK. Credenciais, grants, criptografia do banco/backups e retenção são responsabilidades operacionais do host. Não serializar esses objetos em endpoints, logs ou UI. `@JsonIgnoreType` protege propriedades aninhadas; não é uma autorização para devolver o objeto como resposta raiz.

O codec interno preserva números exatos e a diferença entre inteiro `1` e decimal `1.0`, mesmo após normalização decimal. A normalização mantém os limites de precisão/escala na própria árvore: remover zeros de 10e256 não pode produzir um snapshot irrecuperável. O framing do fingerprint permanece o mesmo. O envelope persistido é limitado a 8 MiB durante a escrita. Na leitura, o decoder restringe estrutura, profundidade e números, reutiliza os validadores de nós do protocolo e recompõe o fingerprint `praxis.bulk.intent/1`. O contrato HTTP de leitura por bytes conserva seus limites lexicais. O formato persistido deste adapter está associado à migração V1; evoluções incompatíveis exigem migração explícita.

Payload/fingerprint/contexto inconsistente falha com `CORRUPT`; falhas SQL do adapter são traduzidas para `CONFLICT` ou `UNAVAILABLE`, sem encadear mensagens privadas do driver/parser. O fingerprint detecta inconsistência, mas não é assinatura contra um administrador malicioso capaz de alterar payload e hash. Proteção de logs do servidor SQL permanece uma responsabilidade operacional.

## Migração e permissões

As dependências Flyway core e PostgreSQL 11.17.0 são opcionais no starter. O host que adotar o migrator deve fornecer esses módulos. A migração usa exclusivamente:

- location `classpath:db/praxis-bulk-migrations`;
- schema `praxis_bulk`;
- histórico `praxis_bulk_schema_history`;
- `baselineOnMigrate=false`, `cleanDisabled=true` e validação de checksums.

Não copiar o SQL para `db/migration`, reutilizar o histórico do host ou aplicar baseline em um schema desconhecido. Um schema `public` com tabelas existentes não participa dessa linha de migração. O schema próprio é reservado ao SDK. As migrations V8–V12 automatizam somente as ACLs focais dos objetos que introduzem: manifest V8, projeção V9, folhas de integridade V11 e `EXECUTE` da função de atestação V12, sempre para roles runtime explicitamente configuradas e pré-qualificadas durante o bootstrap correspondente. V10/V13 substituem funções governadas preservando owner e ACLs validados. Não há provisionamento genérico de contas ou permissões, e markers `COMPLETE` não autorizam reparar grants revogados. PostgreSQL é obrigatório; as provas destes incrementos usam PostgreSQL 14.22 real. A validação estrutural usa as formas de expressão retornadas pelo catálogo dessa versão: diferenças falham de modo fechado. Outras versões exigem prova de compatibilidade antes da adoção; não estão certificadas por esta suíte.

O migrator valida o owner esperado, grantees runtime/control-plane e membros do executor de retenção contra o catálogo PostgreSQL, incluindo os privilégios mínimos por tabela/coluna/função e funções `SECURITY DEFINER`. O host obtém esses nomes do provisionamento real e executa a validação antes de habilitar o consumo. Cada entrada de runtime também revalida identidade e ACLs protegidas na própria conexão autenticada, antes do binding/callback. Configure uma identidade de login runtime não privilegiada; usar o owner como runtime, ou chegar ao papel permitido por `SET ROLE`, falha fechado. O runtime não recebe escrita direta no controle de operação; lock e CAS governado são concedidos por funções dedicadas da V6. Não conceder `CREATE`, `DELETE` ou escrita direta em tombstone ao runtime. Detalhes de lock, limites 100/10/80 e grants estão em [execução durável](BULK-DURABLE-EXECUTION.md), [infraestrutura transacional](BULK-EXECUTION-INFRASTRUCTURE.md) e [controle de operação](BULK-OPERATION-CONTROL.md). Privilégios administrativos ainda podem alterar o schema; portanto, a composição operacional precisa controlar credenciais e repetir validação estrutural no provisionamento.

## Provas e próximos gates

`BulkSnapshotStorageCodecTest` cobre as três modalidades, quatro codecs, tipos numéricos, limites decimais programáticos, cópias defensivas e corrupção sanitizada. `JdbcBulkProposalStorePostgresTest` usa PostgreSQL real e conexões independentes para migração repetida/concorrente, schema estranho, drift, commit/rollback conjunto da proposta e allocation pendente, unicidade concorrente, limites de quota, acesso contextual, imutabilidade, conteúdo corrompido e credenciais restritas.

O recorte histórico de persistência/primeira página RS2 não alterou x-ui, discovery, endpoints, capability, corpus HTTP ou Angular; sua composição Java e prova candidata no host não certificavam publicação, adoção, HTTP ou `READY`. A continuação com cursor e a leitura posteriores têm contratos próprios em [resultados](BULK-PROTOCOL-RESULTS.md). A captura EXPLICIT sob locks, publicada na rc.153 e adotada pelo host, não habilita QUERY, ASYNC ou Angular por si só; o overload QUERY publicado na rc.154 mantém gates próprios de integração e adoção do host.

## Baseline de adoção managed-owner em revisão

O incremento managed-owner estabelece PostgreSQL 17 para o storage de execução bulk; o version guard rejeita versões anteriores antes das novas consultas de membership ou DDL. A prova histórica em 14.22 descrita acima é preservada e não certifica o candidato atual. Não usar o marcador PENDING como autorização para reparar ACLs. Consulte o [plano](BULK-MANAGED-OWNER-ADOPTION-PLAN.md) para cutoffs de histórico, grants iniciais transacionais e provas ainda pendentes. Não há nova API pública de provisionamento nem modo paralelo de migração.
