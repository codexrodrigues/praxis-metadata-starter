# Controle durável de operação em lote

A migration V6 fecha uma lacuna de privilégio descoberta na revisão do ledger V5. O runtime precisa manter um lock compartilhado da linha de controle durante admissão/reserva para que a suspensão concorrente tenha um ponto de serialização. No PostgreSQL, SELECT FOR SHARE também exige privilégio de UPDATE; concedê-lo diretamente ao runtime permitiria fabricar estado READY e contornar o descritor governado.

V6 preserva o checksum da V5 e instala funções de superfície mínima SECURITY DEFINER com search_path fixo:

- lock_operation_control(namespace, operation) lê e trava a linha até o fim da transação. Só roles runtime explicitamente configuradas recebem EXECUTE.
- transition_operation_control(namespace, operation, expectedGeneration, target, fingerprint, revision) aplica CAS e incrementa a geração monotônica. Só roles de control plane explicitamente configuradas recebem EXECUTE.
- As triggers de admissão e avaliação executam sob o proprietário dedicado para que as credenciais runtime não precisem de UPDATE no controle. A trigger de avaliação lê a identidade imutável da proposta, depois trava o controle, mantendo a ordem de locks.

praxis_bulk_control_owner é NOLOGIN, NOINHERIT, não tem membros e recebe somente SELECT/UPDATE nas colunas do controle e leitura dos campos de localização imutáveis da proposta. As credenciais runtime e control plane não recebem membership, SELECT nem escrita direta no operation-control; runtime lê e trava pela função definer. O migrator rejeita herança de roles PostgreSQL não declarada (inclusive roles predefinidas com privilégios globais); a única herança prevista é entre membros configurados para retenção e a role `praxis_bulk_retention_executor`. Ele confere owners, definição/atributos das funções, grants de schema/tabela/função e topologia de memberships; não cria grants. Qualquer privilégio excedente ou alteração da definição fecha a validação.

O host deve fornecer controlPlaneGranteeRoles no BulkExecutionRoleConfiguration, a partir do provisionamento real. O starter não infere roles. A conta de migração precisa poder criar a role interna e associar-se a ela temporariamente para transferir ownership; a migration revoga essa membership antes do commit. A role da aplicação que chama a transição recebe apenas EXECUTE, nunca é a conta migradora.

Este incremento implementa o fence durável e o CAS de controle, não a composição/verificação do descritor. Ter uma linha READY ou um fingerprint sintaticamente válido não prova handlers, schemas, providers, generation/fingerprint local, invalidação de cache, discovery ou endpoint executável. A composição S4c ainda deve validar o descriptor completo antes de publicar readiness; nenhuma ação/capability bulk deve ser anunciada por este fundamento isolado.

## Provas

BulkDurableMigrationPostgresTest.operationControlSeparatesRuntimeFenceFromGovernedCas usa roles PostgreSQL independentes para provar leitura/trava runtime, CAS restrito ao control plane, negação de DML direto, rejeição de geração obsoleta e bloqueio da suspensão até a transação runtime que já obteve o lock terminar. As demais provas de migração exercitam a V6 tanto em instalação limpa quanto em upgrade V3→V4→V5→V6 e verificam que checksums V1–V3 não mudam.
