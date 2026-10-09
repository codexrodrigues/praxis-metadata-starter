# Composição explícita do worker durável — candidato B5b.3

Este contrato é um candidato técnico do Metadata. Não habilita ingresso HTTP ASYNC,
resposta 202, capability READY, publicação de versão ou adoção no host.

O datasource e o transaction manager precisam ser os mesmos usados na mutação de domínio.
O host declara infraestrutura operacional estável, `BulkCapacityBinding` e operações
canônicas. `BulkDurableWorkerComposition.compose` retorna um `SmartLifecycle`;
a construção não conecta ao banco, não executa migração, não instala grants e não
inicia threads. Quando registrado explicitamente no contexto Spring, um lifecycle
com bindings inicia no refresh. Sem registro não há ativação automática pelo starter.

O vínculo contém os mesmos nove campos usados pela instalação: deployment, tenant,
ambiente, binding, geração, database, attestation, authority e epoch. É uma expectativa
configurada pelo servidor, não prova de autoridade. O kernel continua verificando
identidade física, attestation, grants, ocupação e controle durável. O deployment deve
coincidir com o da infraestrutura. Não fornecer credenciais de owner ao worker.

Cada Operation declara recurso e referência completa (grupo, operationId, path,
método), com fábrica de `UnitCallbacks`. A fábrica somente aloca estado local; não
consulta autorização, política ou domínio. É chamada dentro da admissão fornecida
pelo kernel, depois da consulta ao receipt. O callback de admissão recebe a unidade
real e consulta grants atuais usando sujeito e contexto persistidos, não JWT nem
SecurityContext herdados da requisição. Mutação usa o mesmo par da unidade.

O callback cleanup descarta apenas estado local. Executa uma vez por par criado,
em sucesso, negação ou exceção, após o retorno/falha da execução transacional.
Não abrir transação, consultar política/grants ou alterar domínio no cleanup.
Falha de cleanup recebe diagnóstico sanitizado e não substitui o resultado durável
nem causa repetição da mutação. Replay confirmado não cria par e não chama cleanup.
Não há garantia de deadline rígido para código arbitrário de callback/cleanup.

Callbacks podem ser usados por workers concorrentes: a fábrica deve produzir
estado independente por invocação. Não armazenar preparação mutável na Operation,
no singleton de serviço ou em ThreadLocal sem descarte. As listas são copiadas;
bindings duplicados, operações duplicadas e declarações incompletas são rejeitados.
O limite continua oito bindings. Um conjunto vazio não inicia execução.

QUEUE ingress/lifecycle, instalação de capacidade e autorização de novas propostas
continuam nos donos existentes. A composição interna de kernel para unidades owned
não cria uma autorização QUERY alternativa. O host não recebe API de claims, enqueue,
SQL de kernel ou instaladores por este contrato.

Provas executadas em 08/10/2026: 18 testes PostgreSQL na fonte V2 e três testes
estruturais na V3, com prova de artefato revisada e aceita independentemente; integração, publicação e adoção permanecem pendentes.
A primeira campanha retornou exit1 por duas fixtures que mockavam classe final;
a correção test-only teve rerun focal3/0falhas. Não foi executado verify integral.
O consumidor foi compilado independentemente e executado contra o JAR privado
`8.0.0-b5b3-worker-composition-20261008-SNAPSHOT` (SHA256
`ce74b4918cd5e3083af2486a50dabd964fcfefc172db12d7ed3c77a2f475019d`).
As quatro etapas retornaram0; Java21.0.10/PostgreSQL14.22, duas mutações/receipts
com identidade de transação certificada e worker parado. Isso é prova de artefato
candidato privado, não disponibilidade dessa API na coordenada pública rc.155.
A prova de grants off-request do host é separada e qualificada pelo seu baseline;
não equivale a comprovar um endpoint assíncrono operacional.

As nove coordenadas vêm do provisionamento governado descrito em
[BULK-CAPACITY-INSTALLATION](../spec/BULK-CAPACITY-INSTALLATION.html), fora de headers
e de cópias restauradas. UUIDs não devem ser inventados pelo consumidor; geração do
binding, epoch da autoridade e epoch de controle da unidade são identidades distintas.

## Identidade provisionada e responsabilidade

| Campo | Origem e significado |
|---|---|
| deploymentId | deployment atribuído pelo provisionamento; igual ao da infraestrutura |
| tenantId | tenant ao qual o binding local foi atribuído |
| environment | ambiente atribuído ao deployment |
| bindingId | identificador local declarado pelo provisionador |
| generation | geração positiva do binding; não é o epoch do executor |
| databaseId | UUID físico provisionado e fixado no bootstrap owner |
| attestationId | UUID provisionado verificado pelo protocolo de witness |
| authorityId | UUID da autoridade de capacidade independente |
| authorityEpoch | epoch positivo esperado dessa autoridade; não é o epoch da unidade |

Os quatro textos devem ser não vazios, sem espaços nas extremidades nem controles;
os três UUIDs são obrigatórios. Essas constraints não substituem verificações
contra marker/attestation/ledger atuais. A configuração do servidor fornece a
expectativa completa, fora de requisições e fora da cópia restaurada do banco.
Não inferir autoridade de URL JDBC, nome de bean, UUID copiado ou igualdade de epoch.

Owner provisiona, migra e instala direitos pelo protocolo existente. Runtime usa
credenciais operacionais e não recebe credenciais do owner. Metadata governa
ocupação, claims, fences, receipts e transação. Host fornece domínio e autorização
atual na admissão: sujeito/contexto da unidade persistida, autoridade exigida pela
operação de servidor, orçamento restante e grants do banco. Ausência/revogação/
indisponibilidade precisam negar a nova mutação conforme o contrato do host;
receipt confirmado continua replay sem consulta exclusiva de nova mutação.
A prova aqui não afirma um endpoint HTTP ou implementação de grants genérica.

## Composição corporativa e receita pública compilável

O objetivo é permitir execução fora da requisição sem transferir autorização ou
preparação mutável entre operadores/tenants. PostgreSQL permanece a fila durável e
máquina de estados governada pelo Metadata; Config continua dono de políticas e
authoring, e o host fornece grants atuais, domínio e wiring. Este corte não adiciona
broker ou um loop de claims no host, nem habilita ingresso ASYNC.

O helper JDBC abaixo é mínimo para a **prova privada** e cria seu manager na fixture.
No wiring corporativo, reutilize a infraestrutura operacional e manager já compostos;
não crie novo pool/manager por worker ou tenant. Este exemplo público usa apenas a
infraestrutura existente e o binding provisionado:

```java
import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.praxisplatform.uischema.bulk.BulkCapacityBinding;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkDurableWorkerComposition;

public final class CorporateWorkerWiring {
    public static SmartLifecycle compose(BulkExecutionInfrastructure existingInfrastructure,
            BulkCapacityBinding provisionedBinding,
            List<BulkDurableWorkerComposition.Operation> operations) {
        return BulkDurableWorkerComposition.compose(List.of(
                new BulkDurableWorkerComposition.Binding(existingInfrastructure, provisionedBinding, operations)));
    }
}
```

O host registra explicitamente o retorno como bean SmartLifecycle, junto com a
infraestrutura compartilhada. A fonte literal desta receita foi compilada com javac21
contra o mesmo JAR privado; não implica uma prova de JPA ou HTTP.

### Helper JDBC mínimo comprovado

Esta receita corresponde ao consumidor compilado contra o JAR privado. Os nove
valores já vêm do provisionamento; callbacks são compostos pelo host por operação.
O datasource usado pelo domínio deve ser este mesmo objeto. O exemplo usa JDBC;
uma composição JPA deve obedecer à identidade de datasource do manager e EMF,
sem ampliar a evidência deste consumidor JDBC para JPA.

```java
package org.praxisplatform.uischema.consumer;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.praxisplatform.uischema.bulk.*;

/** Composição explícita de servidor; callbacks e identidade já governados pelo host. */
public final class BulkWorkerExternalConsumer {
    private BulkWorkerExternalConsumer() { }
    public static SmartLifecycle compose(DataSource runtime, String namespace, String deployment,
            BulkExecutionRoleConfiguration roles, String tenant, String environment, String binding,
            long generation, UUID database, UUID attestation, UUID authority, long epoch,
            BulkDurableWorkerComposition.Operation operation) {
        var infrastructure = new BulkExecutionInfrastructure(runtime, new DataSourceTransactionManager(runtime),
                namespace, deployment, roles);
        var expectation = new BulkCapacityBinding(deployment, tenant, environment, binding,
                generation, database, attestation, authority, epoch);
        return BulkDurableWorkerComposition.compose(List.of(
                new BulkDurableWorkerComposition.Binding(infrastructure, expectation, List.of(operation))));
    }
}
```

A fábrica de callbacks recebida pela Operation aloca um holder novo por invocação.
A admissão coloca nesse holder a preparação de domínio validada para a unidade;
a mutação usa e descarta essa mesma preparação; cleanup remove qualquer restante.
Não usar singleton mutável compartilhado, DTO/requisição capturada ou factory que
consulta grants antes de receber a unidade. Nenhum registry/annotation adicional
é exigido por esta seam. O host não precisa implementar outro loop de claims.

No Spring, registrar explicitamente o SmartLifecycle retornado como bean: uma
composição não vazia tem autoStartup; a vazia não inicia. `compose` isolado não
inicia thread. `stop(Runnable)` confirma somente depois da thread terminar;
`stop()` tem espera limitada e pode registrar shutdown ainda pendente. O host
não deve encerrar pools/TM antes da confirmação real. Não há hard deadline para
callbacks arbitrários; limites de banco não garantem interrupção do código Java.

## Diagnóstico e validação

- Falha na construção: conferir deployment/identidade, listas, duplicatas de
  operação completa e callbacks obrigatórios. Não substituir binding por null
  nem publicar UUIDs/credenciais em mensagens.
- Falha de seleção/claim: conferir protocolo owner/runtime e autoridade atual;
  não conceder grants extras ou contornar fence para iniciar worker.
- Falha de cleanup: mensagem sanitizada; conferir estado durável antes de retry.
  Cleanup não é compensação de domínio nem pode desfazer receipt confirmado.
- Falha de parada: distinguir parada solicitada de término real. Não anunciar
  ACK de shutdown por timeout ou ausência temporária de trabalho.
- Mudança de identidade/restore: seguir protocolo governado de capacity fencing;
  não recuperar autorização apenas copiando configuração ou banco.

Regressões focais: BulkDurableWorkerPostgresTest, LifecyclePostgresTest,
HandlerIdentityPostgresTest, NamespacePostgresTest, OperationHeadPostgresTest,
ActiveCapacityPostgresTest, CallbackScopePostgresTest e BulkWorkerPublicCompositionTest.
O último é estrutural; as sete classes anteriores usam PostgreSQL real. Prova do
JAR exige outro javac com JAR/dependências e **sem** target/classes/test-classes
no compile path do consumidor público; harness owner/seed é separado. Verificar
CodeSource com caminho real exato, hashes do JAR e paridade de classes/recursos/
migrations, classpath real, exits por etapa e ausência de processos exclusivos.

Não repetir provas válidas por edição editorial. Registrar fonte/dependências que
cada prova cobre, inclusive a campanha RED e o rerun focal. Publicação, adoção do
host atual, ingresso assíncrono governado, HTTP/corpus e B7 continuam etapas separadas.
