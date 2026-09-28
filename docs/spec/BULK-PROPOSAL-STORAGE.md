# Persistência protegida de propostas

A [evidência de avaliação de domínio](BULK-EVALUATION-EVIDENCE.md) amplia este armazenamento com vínculo atômico de fatos/plano e migração V2, sem conferir elegibilidade. A migração V5 acrescenta ledger durável de capacidade e retenção; as regras estão em [execução durável](BULK-DURABLE-EXECUTION.md) e [controle de operação](BULK-OPERATION-CONTROL.md).

O SDK oferece `BulkStoredProposal` e `JdbcBulkProposalStore` para inserir e recuperar a intenção protegida de um lote. O conteúdo inclui contexto confiável, identidade da operação, revisão do schema, atomicidade, codec de identidade, modalidade e intenção normalizada. A projeção pública `BulkProposal` continua separada.

Este incremento aceita seleção EXPLICIT e execução SYNC nas três modalidades: alteração uniforme, ação de domínio e alterações por item. Só as representações canônicas Integer, Long, String e UUID são reconstituídas. BulkStoredProposal valida os tokens dos alvos com o codec canônico antes de admitir o objeto; declarar um codecId conhecido em um codec customizado não contorna essa validação. Um codec customizado com representação wire realmente compatível permanece válido. QUERY exige um manifesto de alvos capturado; ASYNC permanece fora deste adapter. Em V5, inserir uma proposta também reserva atomicamente a quota pendente; isso não demonstra avaliação elegível, autorização, reserva de execução, confirmação ou execução de negócio.

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
ou endpoint RS2. A árvore atual acrescenta checksum V11, gate/leitor interno V12
e a fachada Java G3a de primeira página autorizada; continua sem rota HTTP,
continuação/cursor público ou `READY`.

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

Este pacote não altera x-ui, discovery, endpoints, capability, corpus HTTP ou Angular. A árvore atual contém composição Java autorizada da primeira página RS2 e prova candidata no host, mas isso não equivale a artefato publicado, adoção pelo host, rota HTTP ou protocolo `READY`. Continuação com cursor, contrato HTTP, integração operacional do host e respectivas provas permanecem gates antes da evolução Angular.
