# P5-A — Blindagem pós-auditorias das Ondas 1–3

Data: 2026-11-02. Classificação: `contrato-publico`, com impacto transversal
entre `praxis-rules-engine` e `praxis-metadata-starter`.

Resultado histórico da revisão ampliada de 02/11: **CHANGES REQUESTED**,
com sete inconsistências reproduzidas na composição examinada. Os gates
históricos são distintos da revalidação P5-C abaixo.

O tratamento posterior de R01–R07, incluindo a correção funcional de UX no
Angular, está em [P5-A-CORRECTIONS-2026-11-02.md](P5-A-CORRECTIONS-2026-11-02.md).
Consulte seus resultados finais separadamente dos gates históricos abaixo.

## P5-C — Revalidação do candidato local

Os IDs do anexo P5-C e da revisão ampliada nomeiam achados diferentes. A
revisão ampliada continua coberta por testes permanentes:

| Revisão original | Regressão permanente |
| --- | --- |
| R01–R02 — operandos nulos e projeção segura de `entity` | `P5AIndependentReviewTest`, `P5ACustomFieldRegressionTest` |
| R03–R05 — classificação por item em lote | `P5ABulkIndependentReviewTest` |
| R06 — autorização antes de consultar IDs | `P5AStatusAuthorizationReviewTest` |
| R07 — ordenação de nulos e cursor em ambas as direções | `P5ACursorIndependentReviewTest` |

No escopo técnico P5-C, a validação direta compara atribuições protegidas com
um estado anterior vazio quando não há entidade confiável. O comando versionado
valida a projeção anotada ou o estado persistido anotado quando a resposta não
expõe custom fields, usando uma projeção de fatos segura quando a entidade
precisa fornecer o rascunho. A travessia de metadados da expressão é iterativa;
o capturador e o motor canônico limitam a profundidade a 64.

O schema público expõe somente `validationMode: json-logic`, preserva a
invalidação do fingerprint e deixa a execução da regra no servidor. O revisor
semântico aceita descritores e o registry de stats da superfície e usa a mesma
elegibilidade do runtime. As regressões ficam em
`CustomFieldDomainMutationRegressionTest`, `CustomFieldJsonLogicTest` e
`OptionSourceEligibilityTest`.

No checkout atual, `GlobalExceptionHandler` aplica o status canônico a toda
violação, inclusive ao erro único (`RuleViolationHttpTest`). O
`JdbcResourceCommandReceiptStore` não possui TTL, limpeza de expirados nem estado
`FAILED`: o recibo de sucesso faz parte da mesma transação da mutação e a
rejeição permite nova tentativa. `TempoLicencaCommandReceiptTest` protege essa
fronteira. Nenhum mecanismo de expiração ou nova semântica de retry foi
introduzido.

**Gate focal P5-C: PASS — 90 testes, sem falhas, erros ou skips**, incluindo
as três regressões do cursor R07. Engine: 125 testes aprovados. Starter
`8.0.0-rc.188` instalado com sources no Maven local; o SHA-256 do JAR é
`8518c3e397dd51420325f6f516d3be31ca29ebb20ab72aea7cc2793c0c287587`
em `target` e no `.m2`. A suíte ampla não pode receber selo PASS enquanto
houver falhas nas provas HTTP de publicação governada.

O comando amplo prescrito terminou com **1.867 testes, 11 falhas, 3 erros e
6 ignorados** (`target/p5c-test-final.log`). Nenhuma classe `*PostgresTest`
foi selecionada. Nas classes HTTP/estruturais que usam PostgreSQL embarcado,
o servidor cancelou a consulta de atestação por `statement_timeout`
(`SQLSTATE 57014`), inclusive durante a publicação inicial de
`BulkDeclarativeJpaHttpTest`; as chamadas seguintes encontraram a publicação
indisponível. As nove provas de `JdbcResourceCommandReceiptStoreTest` passaram
no mesmo comando. A repetição isolada das duas classes HTTP e do cenário
concorrente do compilador estrutural passou: **14/14**, sem alterar timeouts
ou expectativas (`target/p5c-publication-replay.log`). Esse resultado não
substitui o resultado da suíte ampla. Os relatórios e sondas temporários da revisão original foram
retirados dos caminhos ativos e preservados em `target/p5c-cleanup/`.

## Escopo e impacto

O engine é dono de fatos e diagnósticos neutros; o starter materializa HTTP,
governança de comandos, validação de custom fields, consultas e transações bulk.
O escopo P5-A é a infraestrutura desses dois módulos. A migração do Ergon
Showcase corresponde à P5-B e não é certificada por este recorte.

As duas árvores já continham mudanças locais extensas das Ondas 1–3. Esta Issue
preserva essas mudanças. Os diffs contra HEAD incluem esse trabalho anterior e
não representam isoladamente o patch P5-A. Nenhum commit, push, tag ou publicação
foi solicitado ou executado.

| Superfície | Aderência encontrada | Correção P5-A |
| --- | --- | --- |
| Erros do engine | Suportado parcialmente | Categoria por violação, mensagem resolvida e status neutro `409`/`422`; construtor original de nove argumentos preservado. |
| Captura de fatos | Suportado parcialmente; projeção escalar ausente | Validação incremental por nó e agregada em `build`; helper escalar ignora associações JPA antes da leitura. |
| HTTP de comandos | Suportado parcialmente | Propagação de falhas tipadas de custom fields; outcome `INVALID_ENTITY` materializa `422`. |
| Custom fields | Suportado parcialmente | Comparação de read-only com o estado autorizado, opcionais vazios, entidade obrigatória em validação standalone e escopo local de iteradores. |
| Draft de UPDATE | Suportado parcialmente | Mapper sobre cópia destacada, projeção DTO combinada com estado hidratado; estado persistido permanece a root `entity`. |
| Consultas e locks | Suportado parcialmente | Guardas de item em locate/export/includeIds, autorização antes do lock e revalidação após refresh. |
| Cursor | Suportado parcialmente | Assinatura SHA-256 truncada do Sort efetivo, incluindo propriedades/direções/null handling/case sensitivity. |
| Bulk | Suportado parcialmente | Limpeza de avaliação suja; rejeição funcional de gravação causa rollback completo do item e registro local sob fences antes de continuar. |
| Roteamento de status bulk | Lacuna real | `BulkOperationStatusLookup`, condicionado à infraestrutura explícita, correlaciona namespace/recurso/ID com operações descobertas. A autorização permanece nos readers. |

Consumidores diretos: services/controllers dos hosts, clientes de cursores e
clientes de comandos. `praxis-config-starter` mantém suas próprias fronteiras de
persistência/publicação. O dialeto JSON Logic, seus operadores e corpus não foram
alterados. O contrato HTTP agora diferencia conflitos do restante das rejeições;
clientes devem consumir `decision`, código e target estruturados.

Cursores antigos ou reaproveitados com outra ordenação são rejeitados como
payload inválido; uma consulta nova obtém cursores compatíveis. Não há trilha de
compatibilidade paralela. `category` e os outcomes acrescentam dados/valores ao
contrato. O construtor Java original de nove argumentos permanece disponível,
mas consumidores que decompõem o record `SlotViolationDetail` em record patterns
precisam considerar seu décimo componente. Switches exaustivos sobre os enums de
outcome/categoria também precisam considerar os novos valores ao recompilar.
As coordenadas
continuam sendo candidatos locais `0.1.0-beta.17` e `8.0.0-rc.188`.

## Evidências de regressão

| Área | Prova |
| --- | --- |
| Engine | `PraxisRuleViolationExceptionTest`, `PraxisFactTreeBuilderTest`: categoria default/CONFLICT, mensagem do catálogo/fallback, `409`/`422`, ausência de leitura de associações, limites agregados e cópias destacadas. |
| HTTP | `RuleViolationHttpTest`, `GovernedResourceCommandExecutorTest`: status da resposta/problema, targets, categorias e falhas customizadas preservadas em comandos. |
| Custom fields | `P5ACustomFieldRegressionTest`, `CustomFieldAdoptionRegressionTest`, `CustomFieldJsonLogicTest`, `P2CustomFieldContextTest`, `CustomFieldResourceLifecycleTest`: omissão/null/zero, condicional, iteradores, snapshot read-only, draft comum PUT/bulk e ausência de save na avaliação. O bulk voltou a usar `audit=99` read-only. |
| Acesso e cursor | `CoreResourceEvolutionIntegrationTest`, `ResourceKeysetSupportTest`: H2/JPA real, guardas sem depender só de SQL scope, ausência de lock antes de `403`, rechecagem após refresh e rejeição de Sort incompatível. |
| Bulk | `JpaResourceBulkEngineTest`: descarte de estado entre candidatos e classificação conservadora de falhas locais/técnicas. `BulkDurableExecutionPostgresTest.targetRejectionAfterJpaFlushRollsBackOnlyThatItemAndContinuesTheSuffix`: rollback JPA após flush, admissão local e continuidade do próximo item. |
| Discovery | `BulkOperationStatusLookupTest`, `BulkDeclarativeProtocolTest`: correlação scoped, falha não enumeradora, default do controller e bootstrap/override sem I/O. `BulkDeclarativeJpaHttpTest` usa o default, sem SQL ad hoc no controller. |

## Validação

Gates finais com OpenJDK 21 (`21+35-2513`) e Maven 3.9.6.

```powershell
$env:JAVA_HOME='D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

| Gate | Resultado |
| --- | --- |
| Engine `mvn test` | PASS — 120 testes, zero falhas/erros/skips. |
| Engine `mvn install -DskipTests` | PASS — instalação Maven local, incluindo sources e Javadocs. |
| Starter focal | PASS — 113 testes, zero falhas/erros/skips. |
| Starter HTTP/JPA e rollback direcionados | PASS — 9 testes PostgreSQL 17.11, incluindo rollback após flush e continuidade do próximo item. |
| Reprodução com OpenJDK 21 | PASS — 17 testes da sequência HTTP/ASYNC/ATOMIC. |
| Starter gate Windows solicitado | FAIL — interrompido após a classe ATOMIC concluir 71 casos: 65 PASS, 1 falha e 5 erros. Não constitui aprovação ampla. |
| ATOMIC: reprodução isolada dos grupos de recibos/outbox afetados | PASS — 15 testes, zero falhas/erros/skips. Não substitui o gate amplo. |
| Starter complementar sem classes `*PostgresTest` | PASS — 1.832 testes, zero falhas/erros, 6 ignorados. Inclui os testes HTTP/JPA declarativos. |
| PER_ITEM PostgreSQL no OpenJDK 21 | PASS — 1 teste JPA após flush: primeiro item revertido e registrado como conflito; segundo item confirmado. |
| Starter `mvn install -DskipTests` | PASS — `8.0.0-rc.188` instalado localmente, incluindo sources e Javadocs. Não muda o resultado FAIL do gate amplo. |

Comando do gate Windows solicitado:

```powershell
mvn test "-Dtest=!*Neon*,!BulkCapacityAuthorityRollbackPostgresTest,!BulkCapacityLocalAdministrationMainTest,!BulkCapacityProvisioningMainTest,!BulkConsumerArtifactIntegrationTest"
```

Na execução OpenJDK, `BulkAtomicExecutionPostgresTest` concluiu em 1.469 segundos
com falhas de reconciliação/recibo: `RECONCILIATION_REQUIRED`, ausência do recibo
esperado e estado diferente do esperado nos testes de corrupção de vínculo de
efeitos e de commit de conjuntos de 50 alvos. A execução foi interrompida após
o resultado dessa classe; o restante da seleção não foi certificado. Os grupos
de commit/outbox e readback/recovery passaram ao repetir seus 15 casos isolados.
Isso delimita a instabilidade observada, sem estabelecer uma causa definitiva.
O caminho ATOMIC não recebeu mudanças nesta Issue. Seus limites e testes foram
preservados, e o gate amplo permanece reprovado.

Gate complementar, registrado separadamente:

```powershell
mvn test "-Dtest=!*Neon*,!*PostgresTest,!BulkCapacityLocalAdministrationMainTest,!BulkCapacityProvisioningMainTest,!BulkConsumerArtifactIntegrationTest"
```

Logs locais: `praxis-rules-engine/target/p5a-engine-openjdk-test.log`,
`praxis-rules-engine/target/p5a-engine-openjdk-install.log`,
`praxis-metadata-starter/target/p5a-focal-test.log` e
`praxis-metadata-starter/target/p5a-windows-openjdk21-test.log`.
As validações complementares estão em `target/p5a-complementary-test.log`,
`target/p5a-atomic-failures-isolated.log` e
`target/p5a-per-item-openjdk21-test.log`; a instalação em
`target/p5a-starter-install.log`.

As primeiras tentativas amplas com GraalVM 21.0.2 foram interrompidas após uma
divergência de SHA-256 durante a validação de V6. Uma captura dos bytes lidos
na falha é idêntica ao recurso canônico (13.247 bytes, SHA-256
`a554c758c995f91d1b75375fdc4577b5a5bba04b2d8880149bf7a99fce91d553`),
embora aquela JVM tenha calculado outro digest. A mesma sequência de 17 testes
passou no OpenJDK 21. A evidência sustenta a troca do JDK de validação, sem
atribuir uma causa interna definitiva ao runtime. A instrumentação temporária
foi removida; recursos, catálogo de proveniência e verificador de hashes
permanecem íntegros e sem mudanças. Os logs das tentativas anteriores e a captura
`target/p5a-mismatch-V6.bin` foram preservados como evidência local.

## Artefatos derivados

O blueprint embarcado e o GUIA-00 são cópias integrais. A seção 2.1 relaciona as
APIs enumeradas pela Issue aos 20 pilares (a lista fornecida contém 13 nomes).
O blueprint do JAR também é idêntico aos dois arquivos, SHA-256
`655a53cb12012ba4ff3fe79662c3dffa8727d9c2c9eed6db9604d124fe0a2de2`.
Os JARs instalados no repositório Maven local são idênticos aos builds verificados;
o ABI instalado do engine expõe o construtor de nove argumentos, `category()`
e `getHttpStatus()`. A instrumentação de diagnóstico temporária está ausente
das fontes, dos testes compilados e do verificador empacotado.
Guias de regras, bulk, especificação de custom fields e CHANGELOG refletem as
semânticas corrigidas. Auto-configuração usa `PraxisBulkWebAutoConfiguration`,
já registrada no manifesto; não há nova configuração a importar.

As correções preservam rotas, envelopes e schemas de entrada existentes; não há
novo campo `x-ui` nem alteração dos schemas JSON de UI. Nenhum exemplo HTTP,
playground Angular ou site público foi alterado. Publicação Maven, downstream
instalado do registry público, integração de host/Ergon, Neon e browser/E2E são
gates distintos da instalação e dos testes locais desta Issue.

A inspeção estática de `GenericCrudService` confirmou que ele propaga o
`HttpErrorResponse` completo. `ErrorMessageService` já trata `400`/`422` e o
contrato de erros permite os dados estruturados. Essa leitura apoia manter o
recorte no backend; ela não é uma validação Angular/browser.

## P5-C — Revalidação após a revisão

Em comandos versionados, o mapa anotado da entidade é a fonte dos valores
validados. A projeção continua disponível como contexto de rascunho e
fornece os valores quando a entidade não declara o contêiner.
A regressão verifica uma projeção anotada que omite um valor persistido
inválido e comprova a rejeição antes do save.

| Gate local | Resultado |
| --- | --- |
| Focal de campos customizados e comandos versionados | 42 testes aprovados, sem falhas, erros ou skips |
| Instalação Maven local de JAR e fontes | `8.0.0-rc.188`, SHA-256 do JAR `042daecec149b0fcfb4d713ce400a4c28c3190056327e452467609473efccadf` |
| Suíte Windows com as exclusões de P5-C | 1868 testes, zero falhas de asserção, dois erros e seis skips |
| Provas incluídas na suíte Windows | Nove testes de recibos JDBC e três de cursor R07 aprovados |

Os erros da suíte ampla ocorreram na atestação do papel de bulk, por timeout
SQL, e em uma requisição HTTP local `/fast` que excedeu o orçamento do cliente
interno de OpenAPI. O log completo é `target/p5c-followup-windows.log`.
Na repetição focal, a atestação de bulk passou. O teste HTTP voltou a
falhar, desta vez porque o handler de `/slow` não foi alcançado dentro da
janela do teste. O log da repetição é `target/p5c-followup-timeout-replay.log`.
