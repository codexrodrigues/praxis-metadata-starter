# Onda 3 / P3 — kernel declarativo de operações em lote

Este corte implementa a P3 dos documentos `plano-issues-evolucao-plataforma-3-ondas.md`
e `auditoria-evolucao-plataforma-ergon-showcase.md`, exclusivamente no starter.
As alterações locais das Ondas 1 e 2 permanecem na árvore. Não há migração de host,
publicação, alteração de versão ou execução de workflows remotos neste corte.

## Classificação e mapa de impacto

Mudança transversal, arquitetural e de contrato público. O inventário identificou
suporte parcial: protocolo, armazenamento, lifecycle, compilação de campos,
autorização de leituras e execução durável já existiam. A lacuna era sua composição
reutilizável com MVC, bases resource-oriented e schemas concretos de avaliação.
Nenhum segundo protocolo, schema owner ou kernel de persistência foi criado.

| Requisito | Implementação canônica |
| --- | --- |
| HTTP estrito | `PraxisBulkProtocolHttpMessageConverter`, codecs do ID de domínio e normalizadores ordenados do FilterDTO |
| Instalação sem listas manuais | `BulkOperationIdentityDiscovery` sobre bindings MVC validados e resolver canônico, sem I/O de documentos ou banco |
| Controller declarativo | `AbstractBulkGovernedResourceController`: nove rotas, papéis explícitos, envelope `RestApiResponse.data`/`_links` e IDs de update derivados do resourceKey |
| Comandos de domínio | Helpers resolvem a action de coleção pelo registry; adapters de domínio recebem somente intenção avaliada e versão retida |
| Avaliação JPA | `bulkDomainAdapter` + `JpaResourceBulkEngine`, escopo de coleção, candidato destacado, UpdateDTO completo, Bean Validation e hooks |
| Captura consistente | `ISOLATION_REPEATABLE_READ` e armazenamento no `JdbcBulkProposalStore` existente |
| Confirmação | `JdbcBulkDurableExecution`, autorização atual, revalidação da governança/evidência e update versionado sob lock pessimista |
| Campos protegidos | Allowlist compilada do PUT publicado e de `protectedUpdateFields`, comparada à composição do engine |
| OpenAPI | Síntese pelo `CustomOpenApiResolver` no documento canônico, com codec e DTOs concretos; sem flexibilizar o leitor de schemas |
| Windows | BOM dos binários de teste embedded PostgreSQL 17.11.0 para todas as plataformas |

O novo auto-configurador está em `AutoConfiguration.imports`, é restrito a Servlet
e aceita override explícito do conversor. Não migra, publica nem inicia workers.
Os demais beans e contratos das Ondas 1 e 2 mantêm sua composição existente.

## Provas e limites

`BulkDeclarativeProtocolTest` cobre parser, normalização, ID real versus wire ID,
bindings herdados, descoberta antes da publicação, tipos canônicos, schemas e
auto-configuração/override sem infraestrutura bulk.

`BulkDeclarativeJpaHttpTest` usa TCP real, Springdoc, PostgreSQL 17 e JPA. Exercita
os três modos, as nove rotas, avaliação sem escrita, filtro/tenant, CLEAR,
versão obsoleta, admissão revogada, catálogo de erros do Rules Engine,
idempotência, recibo e rollback da alteração junto ao recibo. Verifica também
schemas de avaliação, `ETag`, `X-Schema-Hash` e resposta condicional 304.

### Fechamento da Issue P3

A validação final é executada na árvore principal, com JDK 21.0.2 e PostgreSQL
embedded 17.11. A dependência local `praxis-rules-engine:0.1.0-beta.17` corresponde
ao candidato da Onda 2 validado, SHA-256
`ed54a0f1385d110a35bd0fc102e943c08184888116fd48a96475ef2a81a58d31`.

Por orientação do mantenedor, o gate da P3 compreende exclusivamente:

```powershell
mvn test "-Dtest=BulkDeclarativeProtocolTest,BulkDeclarativeJpaHttpTest,BulkOperationStructuralCompilerTest,BulkAsyncPublicAdmissionTest,BulkCrudOperationalCapabilityHttpTest,CanonicalRequestBodyBindingTest,RuleViolationHttpTest,P2CustomFieldContextTest"
mvn install -DskipTests
```

Resultado final em 2026-11-01, na árvore principal:

| Classe | Testes | Falhas | Erros | Ignorados |
| --- | ---: | ---: | ---: | ---: |
| `BulkDeclarativeProtocolTest` | 4 | 0 | 0 | 0 |
| `BulkDeclarativeJpaHttpTest` | 8 | 0 | 0 | 0 |
| `BulkOperationStructuralCompilerTest` | 35 | 0 | 0 | 0 |
| `BulkAsyncPublicAdmissionTest` | 9 | 0 | 0 | 0 |
| `BulkCrudOperationalCapabilityHttpTest` | 1 | 0 | 0 | 0 |
| `CanonicalRequestBodyBindingTest` | 13 | 0 | 0 | 0 |
| `RuleViolationHttpTest` | 13 | 0 | 0 | 0 |
| `P2CustomFieldContextTest` | 8 | 0 | 0 | 0 |
| **Total** | **91** | **0** | **0** | **0** |

- `mvn test`: **BUILD SUCCESS**, 13 min 28 s, encerrado às 19:55:03 (UTC−03:00).
- `mvn install -DskipTests`: **BUILD SUCCESS**, 1 min 23 s, encerrado às 19:56:52 (UTC−03:00).
- Instalados JAR, fontes, Javadocs e POM em
  `C:\Users\rodrigo.moreira\.m2\repository\io\github\codexrodrigues\praxis-metadata-starter\8.0.0-rc.188`.
- Os três JARs instalados conferem byte a byte com os gerados, por SHA-256;
  o runtime contém as cinco implementações P3 e o novo auto-configurador.

A instalação consolida a árvore local das Ondas 1, 2 e 3, com a dependência P2
indicada acima. Trata-se de instalação Maven local.


A bateria longa de infraestrutura/capacidade foi interrompida e não integra o
gate da P3. As alterações feitas apenas para essa bateria foram revertidas.
O worktree `.p3-validation-20261101` e os artefatos temporários `target/p3-*` e
`target/p2-*` foram removidos. Não há certificação dessas suítes extensas a partir
deste fechamento.

O teste estrutural acompanha o wrapper canônico `BulkDiscoveryUnavailableException`
nos casos de fencing/discovery, mantendo a rejeição. A fixture assíncrona acompanha
o novo argumento privado de admissão, sem tornar pública sua construção.
A resolução de bodies concretos fora da hierarquia resource-oriented foi preservada.

Os gates quickstart/Angular não foram executados porque a Issue restringe o trabalho
ao starter. O scorecard de recursos de host não se aplica: nenhum recurso corporativo
real foi criado ou migrado.

Este adapter declara `SYNC`/`PER_ITEM`. Extensões de domínio precisam validar
parâmetros, fatos/dependências, escopo, locks e ETags segundo seu próprio contrato.
O snapshot padrão recusa associações mutáveis que não consegue copiar; o service
pode especializar `snapshotBeforeMutation`/`toBulkUpdate`, ou fornecer um adapter.
Hooks de avaliação não podem persistir efeitos. Não há evidência de adoção de host,
IAM real, Neon, ASYNC, ATOMIC, desempenho ou release a partir destas fixtures.

Guia de adoção: [Bulk declarativo no core](../guides/BULK-DECLARATIVE-RESOURCE-ENGINE.md).
