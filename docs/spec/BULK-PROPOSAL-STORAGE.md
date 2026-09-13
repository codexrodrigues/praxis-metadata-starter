# Persistência protegida de propostas

O SDK oferece `BulkStoredProposal` e `JdbcBulkProposalStore` para inserir e recuperar a intenção protegida de um lote. O conteúdo inclui contexto confiável, identidade da operação, revisão do schema, atomicidade, codec de identidade, modalidade e intenção normalizada. A projeção pública `BulkProposal` continua separada.

Este incremento aceita seleção EXPLICIT e execução SYNC nas três modalidades: alteração uniforme, ação de domínio e alterações por item. Só os codecs canônicos Integer, Long, String e UUID são reconstituídos. QUERY exige um manifesto de alvos capturado; ASYNC exige outro ciclo operacional. Ambos permanecem fora deste adapter. Persistir uma entrada não demonstra avaliação, READY, autorização, reserva de quota, confirmação, execução ou idempotência de negócio.

## Integração explícita

```java
// Etapa de implantação, fora de qualquer transação Spring, com credenciais de migração.
int applied = BulkExecutionMigrator.migrate(migrationDataSource);

// Composição do runtime: datasource operacional compartilhado com o domínio.
var infrastructure = new BulkExecutionInfrastructure(
    operationalDataSource, operationalTransactionManager, deploymentNamespace);
var proposals = new JdbcBulkProposalStore(infrastructure);

// Dentro da transação de serviço já existente:
proposals.insert(new BulkStoredProposal(proposalId, createdAt, expiresAt, snapshot));
var stored = proposals.find(trustedContext, proposalId);
```

`migrationDataSource` pode usar credenciais diferentes, mas deve apontar para o mesmo banco operacional. O starter não descobre, cria ou escolhe essas credenciais. A [infraestrutura transacional](BULK-EXECUTION-INFRASTRUCTURE.md) verifica o vínculo do datasource de runtime com o manager; não compara URLs nem comprova a configuração externa do datasource de migração.

Construir o store não acessa o banco nem registra beans. `insert` e `find` exigem a transação física, existente e gravável do serviço, participando com MANDATORY. Nenhuma transação independente é criada pelo adapter. O retorno de insert é provisório até o commit externo; rollback remove a inserção. Um UUID já existente gera `BulkProposalStorageException.Reason.CONFLICT`, sem upsert nem sobrescrita. Essa unicidade técnica não substitui a futura chave de idempotência de negócio.

## Identidade, validade e proteção

A consulta combina UUID, namespace, usuário, recurso e operationId vindos do contexto confiável do servidor. Outro usuário/recurso/operação recebe ausência; namespace divergente da infraestrutura é rejeitado. O `operationRef` original completo, schemaRevision e atomicidade retornam no snapshot para futura revalidação. Uma revisão atual diferente não torna a linha corrompida nem autoriza executá-la.

Os timestamps são truncados a microssegundos antes da persistência. ExpiresAt deve ser posterior a createdAt; o intervalo suportado vai de 0001 a 9999. O store pode recuperar entradas expiradas: a admissão futura deve negar sua execução e aplicar TTL/quota/política. Não há limpeza automática, DELETE público, estado READY ou atualização de proposta neste incremento.

O payload BYTEA contém JSON protegido, sem criptografia adicional fornecida pelo SDK. Credenciais, grants, criptografia do banco/backups e retenção são responsabilidades operacionais do host. Não serializar esses objetos em endpoints, logs ou UI. `@JsonIgnoreType` protege propriedades aninhadas; não é uma autorização para devolver o objeto como resposta raiz.

O codec interno preserva números exatos e a diferença entre inteiro `1` e decimal `1.0`, mesmo após normalização decimal. O envelope persistido é limitado a 8 MiB durante a escrita. Na leitura, o decoder restringe estrutura, profundidade e números, reutiliza os validadores de nós do protocolo e recompõe o fingerprint `praxis.bulk.intent/1`. O contrato HTTP de leitura por bytes conserva seus limites lexicais. O formato persistido deste adapter está associado à migração V1; evoluções incompatíveis exigem migração explícita.

Payload/fingerprint/contexto inconsistente falha com `CORRUPT`; falhas SQL do adapter são traduzidas para `CONFLICT` ou `UNAVAILABLE`, sem encadear mensagens privadas do driver/parser. O fingerprint detecta inconsistência, mas não é assinatura contra um administrador malicioso capaz de alterar payload e hash. Proteção de logs do servidor SQL permanece uma responsabilidade operacional.

## Migração e permissões

As dependências Flyway core e PostgreSQL 11.17.0 são opcionais no starter. O host que adotar o migrator deve fornecer esses módulos. A migração usa exclusivamente:

- location `classpath:db/praxis-bulk-migrations`;
- schema `praxis_bulk`;
- histórico `praxis_bulk_schema_history`;
- `baselineOnMigrate=false`, `cleanDisabled=true` e validação de checksums.

Não copiar o SQL para `db/migration`, reutilizar o histórico do host ou aplicar baseline em um schema desconhecido. Um schema `public` com tabelas existentes não participa dessa linha de migração. O schema próprio é reservado ao SDK. A migração não cria grants automaticamente. PostgreSQL é obrigatório; as provas deste incremento usam PostgreSQL 14.22 real. A validação estrutural usa as formas de expressão retornadas pelo catálogo dessa versão: diferenças falham de modo fechado. Outras versões exigem prova de compatibilidade antes da adoção; não estão certificadas por esta suíte.

A identidade privilegiada aplica e valida a migração fora das transações do domínio. `validate` verifica também o catálogo físico: colunas/tipos/nullabilidade, chave primária, checks e trigger de imutabilidade. Uma história Flyway válida, sozinha, não prova que ninguém removeu uma constraint ou desabilitou o trigger.

A identidade de runtime precisa de USAGE no schema e SELECT/INSERT na tabela. Não conceder CREATE, UPDATE ou DELETE. O trigger BEFORE UPDATE rejeita alteração inclusive pelo dono enquanto está habilitado; privilégios administrativos continuam podendo alterar o schema. O adapter não valida o catálogo a cada operação: executar a validação de implantação antes de habilitar seu consumo é uma obrigação da composição do host.

## Provas e próximos gates

`BulkSnapshotStorageCodecTest` cobre as três modalidades, quatro codecs, tipos numéricos, limites decimais programáticos, cópias defensivas e corrupção sanitizada. `JdbcBulkProposalStorePostgresTest` usa processo PostgreSQL real e conexões independentes para migração repetida/concorrente, schema estranho, drift, commit/rollback, unicidade concorrente, acesso contextual, imutabilidade, conteúdo corrompido e credenciais restritas.

Este pacote não altera x-ui, discovery, endpoints, capability, corpus HTTP ou Angular. As regressões do host provam compatibilidade com o JAR candidato; não demonstram adoção do store pelo host. Avaliação protegida, manifesto QUERY, quotas, expiração na admissão, ledger/fencing, executores e prova HTTP operacional continuam necessários antes do gate backend completo e da evolução Angular.
