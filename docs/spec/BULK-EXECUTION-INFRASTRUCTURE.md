# Infraestrutura de lote — participação na transação operacional

`BulkExecutionInfrastructure` vincula explicitamente um DataSource, um PlatformTransactionManager local, um namespace e um identificador imutável de deployment. Sua construção não conecta ao banco, registra beans, cria tabelas ou inicia workers. Cada callback valida o binding durável namespace→deployment sob lock compartilhado antes da operação. É a base de integração de `JdbcBulkProposalStore`, descrito em [Persistência protegida de propostas](BULK-PROPOSAL-STORAGE.md). A infraestrutura em si não publica endpoint nem readiness.

```java
// Datasource, manager, namespace, deployment e roles vêm explicitamente do host/provisionamento.
var infrastructure = new BulkExecutionInfrastructure(
    operationalDataSource, operationalTransactionManager, operationalNamespace, deploymentId, roles);

// A camada de execução é dona da transação externa e do seu commit.
var transaction = new TransactionTemplate(infrastructure.transactionManager());
var result = transaction.execute(status -> {
    // Escrita de domínio JPA deve participar deste mesmo manager/transação.
    return infrastructure.withConnection(connection -> {
        // Trabalho JDBC do adapter. Fechar Statements/ResultSets; não controlar a conexão.
        return performJdbcWork(connection);
    });
});
// Só depois de transaction.execute concluir há confirmação do commit desta unidade.
```

`performJdbcWork` é ilustrativo, não uma API do Starter. O callback usa o contrato Spring `ConnectionCallback`; não se cria uma segunda abstração de acesso a conexão. Regras, autorização, prazos da unidade, validação e SQL continuam responsabilidades das camadas que usam essa infraestrutura.

## Vínculo verificável

O datasource deve ser a mesma instância gerenciada por `DataSourceTransactionManager` (incluindo JdbcTransactionManager) ou `JpaTransactionManager`. No caminho JPA, o EntityManagerFactory também deve expor essa instância por `EntityManagerFactoryInfo`. Um EMF opaco, manager diferente ou datasource escolhido por coincidência de URL não é aceito. Os beans devem estar inicializados e permanecer estáveis; a classe volta a conferir o vínculo a cada chamada. Reconfigurar esses objetos concorrentemente não é suportado.

Este primeiro subset rejeita AbstractRoutingDataSource e DelegatingDataSource, incluindo proxies de datasource. O host fornece diretamente o pool operacional compartilhado. Não é uma equivalência geral de wrappers; suporte a roteamento/JTA/outros managers precisa de contrato e conformidade próprios. O datasource e seu manager são colaboradores confiáveis, não uma fronteira de segurança contra implementações maliciosas.

O host também fornece uma `BulkExecutionRoleConfiguration` proveniente do provisionamento real. Em cada chamada, antes de consultar o namespace ou executar o callback, a infraestrutura verifica na própria conexão transacional que `session_user` e `current_user` são iguais e que a identidade consta na allowlist runtime. A mesma conexão revalida owner, privilégios e ACLs das tabelas e funções protegidas, além do corpo, owner, atributos e triggers dos fences V7; a atestação não usa conexão administrativa separada nem executa Flyway/DDL. Cada SQL de atestação fica limitado a 250 ms (não é um orçamento total da sequência de consultas), preservando timeout mais estrito já configurado e restaurando o valor anterior. As verificações isoladas de lifecycle/control também limitam lock e statement timeout a 1/2 segundos sem ampliar limites mais estritos do pool. Se identidade, grants, owner, definições ou atributos de segurança mudarem após startup, a chamada falha fechada antes do trabalho operacional. Esse teste contínuo não substitui provisionamento seguro: a conta DBA/migradora permanece confiável, e o Starter não protege contra um administrador que altera simultaneamente a definição e a execução do banco.

O namespace tem de 1 a 200 caracteres Java, sem espaços nas extremidades, controles ou valor vazio; não é normalizado, inferido de headers nem recebe default. Identifica o binding operacional e precisa permanecer estável e não ser reutilizado para outro escopo. Não substitui tenant/ambiente/ator autenticados, autorização por operação ou isolamento do ledger futuro.

Os corpos SQL esperados das migrations V5/V6/V7/V8/V9 são extraídos e normalizados uma vez por classloader para um holder lazy e imutável, falhando fechado se a extração não for possível. Só esses valores imutáveis são reutilizados; roles, memberships, grants, ownership, atributos e fences do catálogo PostgreSQL são consultados novamente em toda entrada pública. A V8 acrescenta manifest privado de avaliação; a V9 acrescenta projeção física segura e guarda de commit para ela. Nenhuma delas é uma resposta pública neste corte.

## Participação e falhas

`withConnection` exige propagação **MANDATORY** no manager configurado: não inicia uma transação independente. Rejeita transação ausente, sem sincronização/conexão JDBC exposta, somente leitura ou rollback-only observável pelo manager. Uma marca local em um TransactionStatus externo ainda não propagada ao recurso pode não ser visível ao participante; o dono deve parar de chamar mutações ao decidir rollback.

A conexão entregue pelo JdbcTemplate pertence à transação física e está sem auto-commit. A verificação considera seu ConnectionProxy canônico, preservando a proteção contra close acidental no callback. Não fechar/guardar a conexão, alterar auto-commit ou chamar commit/rollback; o callback é código confiável do adapter. Exceção do callback passa pelo TransactionTemplate e provoca rollback-only conforme a política do manager. Configurar globalRollbackOnParticipationFailure=false é rejeitado na construção e em cada chamada, pois permitiria confirmação parcial de uma unidade após erro.

O retorno do callback e de `withConnection` é **provisório até o commit externo**. Falha de flush/constraint diferida/commit ainda pode desfazer todos os writes. Esta API não é uma garantia de efeito remoto ou outbox, não controla admissão de jobs e não oferece fencing, replay, retenção ou recuperação. A validação estrutural feita pelo migrator continua necessária no provisionamento; a chamada revalida as ACLs/identidade vivas para detectar drift operacional entre provisionamento e uso.

## Provas

`BulkExecutionInfrastructureTest` cobre namespace, vínculo por identidade, EMF divergente/opaco, managers não suportados, wrappers/roteamento, mutação de configuração e construção sem I/O. `BulkExecutionInfrastructurePostgresTest` inicia PostgreSQL real descartável com logins restritos e grants explícitos, sem skip nem fallback H2, e valida:

- mesma conexão física JPA/JDBC e outra conexão com PID distinto, sem visibilidade dos writes antes de commit;
- commit, rollback explícito/exceção, erro capturado marcando rollback-only e falha real no commit por UNIQUE diferida;
- rejeição de ausência/read-only/rollback-only/manager alheio, participação JDBC;
- duas conexões disputando FOR UPDATE, SQLSTATE 55P03 por lock_timeout e liberação depois da conclusão do dono.

As provas também verificam que cada operação usa `session_user=current_user`, rejeita acesso do owner/admin como runtime e percebe drift ACL depois do startup pela entrada pública. No PostgreSQL real, runtime e control plane rejeitam grant CAS indevido, DML/CREATE não permitido, membership extra, revogação do lock runtime, `PUBLIC`, grant option, owner divergente e sessão `SET ROLE`; nenhuma rejeição chama o callback. O teste também comprova que o runtime marca a transação rollback-only, estado/generation do control plane permanecem `UNCOMPOSED:0`, e a operação volta a ser aceita depois de restaurar o catálogo, sem reconstruir as infraestruturas. O timeout menor configurado de 100 ms permanece efetivo nos três caminhos. Uma medição sintética sequencial e aquecida no Embedded PostgreSQL 14.22 registrou 25 entradas `withLifecycleRead` entre 451–481 ms e 25 entradas control-plane (incluindo prova do banco físico via advisory lock) entre 897–916 ms, em duas execuções. Esses valores são amostras locais, não SLA nem orçamento de deployment.

As tabelas de domínio/receipt são fixtures de teste. Não são a DDL de execução bulk. O PostgreSQL embarcado e seus binários são dependências somente de teste. O host deve provar seu próprio par de beans e seus writers antes de anunciar atomicidade de domínio + ledger.

Referência: [JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html) descreve participação JDBC no datasource da JPA e a necessidade do dialect adequado; [DataSourceUtils](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html) define acesso/release de conexão transacional. A implementação e a prova foram conferidas também contra as fontes locais Spring 6.1.6; a documentação web pode mostrar versão posterior.
