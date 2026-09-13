# Infraestrutura de lote — participação na transação operacional

`BulkExecutionInfrastructure` vincula explicitamente um DataSource, um PlatformTransactionManager local e um namespace estável da implantação. Sua construção não conecta ao banco, registra beans, cria tabelas ou inicia workers. É a base de integração do futuro adapter JDBC; ainda não é um store de propostas/receipts ou executor de lote.

```java
// Os três valores são fornecidos explicitamente pelo host, após inicialização dos beans.
var infrastructure = new BulkExecutionInfrastructure(
    operationalDataSource, operationalTransactionManager, operationalNamespace);

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

O namespace tem de 1 a 200 caracteres Java, sem espaços nas extremidades, controles ou valor vazio; não é normalizado, inferido de headers nem recebe default. Identifica o binding operacional e precisa permanecer estável e não ser reutilizado para outro escopo. Não substitui tenant/ambiente/ator autenticados, autorização por operação ou isolamento do ledger futuro.

## Participação e falhas

`withConnection` exige propagação **MANDATORY** no manager configurado: não inicia uma transação independente. Rejeita transação ausente, sem sincronização/conexão JDBC exposta, somente leitura ou rollback-only observável pelo manager. Uma marca local em um TransactionStatus externo ainda não propagada ao recurso pode não ser visível ao participante; o dono deve parar de chamar mutações ao decidir rollback.

A conexão entregue pelo JdbcTemplate pertence à transação física e está sem auto-commit. A verificação considera seu ConnectionProxy canônico, preservando a proteção contra close acidental no callback. Não fechar/guardar a conexão, alterar auto-commit ou chamar commit/rollback; o callback é código confiável do adapter. Exceção do callback passa pelo TransactionTemplate e provoca rollback-only conforme a política do manager. Não alterar essa política para permitir confirmação parcial de uma unidade após erro.

O retorno do callback e de `withConnection` é **provisório até o commit externo**. Falha de flush/constraint diferida/commit ainda pode desfazer todos os writes. Esta API não é uma garantia de efeito remoto ou outbox, não controla admissão de jobs e não oferece fencing, replay, retenção ou recuperação. Consistência de schema e credenciais DML também não é validada por este vínculo; pertencerá ao migrator/store.

## Provas

`BulkExecutionInfrastructureTest` cobre namespace, vínculo por identidade, EMF divergente/opaco, managers não suportados, wrappers/roteamento, mutação de configuração e construção sem I/O. `BulkExecutionInfrastructurePostgresTest` inicia PostgreSQL real descartável, sem skip nem fallback H2, e valida:

- mesma conexão física JPA/JDBC e outra conexão com PID distinto, sem visibilidade dos writes antes de commit;
- commit, rollback explícito/exceção, erro capturado marcando rollback-only e falha real no commit por UNIQUE diferida;
- rejeição de ausência/read-only/rollback-only/manager alheio, participação JDBC;
- duas conexões disputando FOR UPDATE, SQLSTATE 55P03 por lock_timeout e liberação depois da conclusão do dono.

As tabelas de domínio/receipt são fixtures de teste. Não são a DDL de execução bulk. O PostgreSQL embarcado e seus binários são dependências somente de teste. O host deve provar seu próprio par de beans e seus writers antes de anunciar atomicidade de domínio + ledger.

Referência: [JpaTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html) descreve participação JDBC no datasource da JPA e a necessidade do dialect adequado; [DataSourceUtils](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html) define acesso/release de conexão transacional. A implementação e a prova foram conferidas também contra as fontes locais Spring 6.1.6; a documentação web pode mostrar versão posterior.
