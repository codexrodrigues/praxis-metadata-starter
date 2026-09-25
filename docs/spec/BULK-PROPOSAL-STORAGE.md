# Persistência protegida de propostas

A [evidência de avaliação de domínio](BULK-EVALUATION-EVIDENCE.md) amplia este armazenamento com vínculo atômico de fatos/plano e migração V2, sem conferir elegibilidade. A migração V5 acrescenta ledger durável de capacidade e retenção; as regras estão em [execução durável](BULK-DURABLE-EXECUTION.md).

O SDK oferece `BulkStoredProposal` e `JdbcBulkProposalStore` para inserir e recuperar a intenção protegida de um lote. O conteúdo inclui contexto confiável, identidade da operação, revisão do schema, atomicidade, codec de identidade, modalidade e intenção normalizada. A projeção pública `BulkProposal` continua separada.

Este incremento aceita seleção EXPLICIT e execução SYNC nas três modalidades: alteração uniforme, ação de domínio e alterações por item. Só as representações canônicas Integer, Long, String e UUID são reconstituídas. BulkStoredProposal valida os tokens dos alvos com o codec canônico antes de admitir o objeto; declarar um codecId conhecido em um codec customizado não contorna essa validação. Um codec customizado com representação wire realmente compatível permanece válido. QUERY exige um manifesto de alvos capturado; ASYNC permanece fora deste adapter. Em V5, inserir uma proposta também reserva atomicamente a quota pendente; isso não demonstra avaliação elegível, autorização, reserva de execução, confirmação ou execução de negócio.

## Integração explícita

```java
// Valores vêm do provisionamento real do host. Migração fora de qualquer transação Spring.
var roles = new BulkExecutionRoleConfiguration(
    expectedSchemaOwnerRole, runtimeGranteeRoles, retentionExecutorMembers);
int applied = BulkExecutionMigrator.migrate(migrationDataSource, namespaceToDeploymentId, roles);

// Composição do runtime: datasource operacional compartilhado com o domínio.
var infrastructure = new BulkExecutionInfrastructure(
    operationalDataSource, operationalTransactionManager, deploymentNamespace, deploymentId);
var proposals = new JdbcBulkProposalStore(infrastructure);

// Dentro da transação de serviço já existente:
proposals.insert(new BulkStoredProposal(proposalId, createdAt, expiresAt, snapshot));
var stored = proposals.find(trustedContext, proposalId);
```

`migrationDataSource` pode usar credenciais diferentes, mas deve apontar para o mesmo banco operacional. O starter não descobre, cria ou escolhe essas credenciais. A [infraestrutura transacional](BULK-EXECUTION-INFRASTRUCTURE.md) verifica o vínculo do datasource de runtime com o manager; não compara URLs nem comprova a configuração externa do datasource de migração.

Construir o store não acessa o banco nem registra beans. `insert` e `find` exigem a transação física, existente e gravável do serviço, participando com MANDATORY. Nenhuma transação independente é criada pelo adapter. O retorno de insert é provisório até o commit externo; rollback remove a inserção. Um UUID já existente gera `BulkProposalStorageException.Reason.CONFLICT`, sem upsert nem sobrescrita. Essa unicidade técnica não substitui a futura chave de idempotência de negócio.

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

Não copiar o SQL para `db/migration`, reutilizar o histórico do host ou aplicar baseline em um schema desconhecido. Um schema `public` com tabelas existentes não participa dessa linha de migração. O schema próprio é reservado ao SDK. A migração não cria grants automaticamente. PostgreSQL é obrigatório; as provas deste incremento usam PostgreSQL 14.22 real. A validação estrutural usa as formas de expressão retornadas pelo catálogo dessa versão: diferenças falham de modo fechado. Outras versões exigem prova de compatibilidade antes da adoção; não estão certificadas por esta suíte.

O migrator valida o owner esperado, os grantees runtime e os membros do executor de retenção contra o catálogo PostgreSQL, incluindo os privilégios mínimos por tabela/coluna e as funções `SECURITY DEFINER`; não cria grants. O host obtém esses nomes do provisionamento real e executa a validação antes de habilitar o consumo. O schema de lifecycle é protegido por triggers e funções restritas; não conceder `CREATE`, `DELETE` ou escrita direta em tombstone ao runtime. Detalhes de lock, limites 100/10/80 e grants estão em [execução durável](BULK-DURABLE-EXECUTION.md). Privilégios administrativos ainda podem alterar o schema; portanto, a composição operacional precisa controlar credenciais e repetir a validação quando apropriado.

## Provas e próximos gates

`BulkSnapshotStorageCodecTest` cobre as três modalidades, quatro codecs, tipos numéricos, limites decimais programáticos, cópias defensivas e corrupção sanitizada. `JdbcBulkProposalStorePostgresTest` usa PostgreSQL real e conexões independentes para migração repetida/concorrente, schema estranho, drift, commit/rollback conjunto da proposta e allocation pendente, unicidade concorrente, limites de quota, acesso contextual, imutabilidade, conteúdo corrompido e credenciais restritas.

Este pacote não altera x-ui, discovery, endpoints, capability, corpus HTTP ou Angular. As regressões do host provam compatibilidade com o JAR candidato; não demonstram adoção do store pelo host. A composição da avaliação governada, a integração real do host com configuração/grants/migrator e a prova HTTP operacional continuam necessárias antes de declarar o protocolo P1 pronto ou iniciar a evolução Angular.
