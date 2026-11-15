# P5-A — Correções da revisão ampliada R01–R07

Data: 2026-11-02. Classificação: `contrato-publico` e `transversal`.
Situação: R01–R07 implementados, gate focal final e validação Angular PASS.
O comando complementar amplo permaneceu FAIL; suas ocorrências passaram em
revalidação isolada. Isso não constitui aprovação do gate amplo literal.
Este registro complementa a
revisão inicial de 02/11, cujas sondas foram incorporadas à suíte permanente.

## Mapa de impacto e decisões

| Fonte canônica | Aderência anterior | Correção |
| --- | --- | --- |
| Engine JSON Logic | Suportado parcialmente: código de tipo não indicava a origem nula | Evidência imutável de paths de operandos nulos, sem alterar valores/decisões/corpus |
| Custom-field adapter | Suportado parcialmente: dispensa ampla e entity graph genérico | Só dispensa origem comprovada em `value`; projeta escalares e o container JSON |
| Engine bulk declarativo | Categorias já suportadas, mal materializadas entre fases | Uma classificação tipada por alvo para avaliação, admissão e gravação |
| Mudanças de campos bulk | Lacuna de discriminação entre payload e erro de programação | `BulkFieldChangeValidationException`, ainda subtipo de `IllegalArgumentException` |
| Status bulk | Roteamento parcialmente suportado | Política obrigatória e grants globais antes da correlação por ID |
| Keyset | Ordenação explícita existente, não aplicada ao fallback | Mesma ordem Criteria e boundary nos dois caminhos |
| Angular Core/Form | `decision` já vinha do backend, mas era descartada | Preserva decisão; não transforma resultado técnico em erro de campo |

Não foram introduzidos novos endpoints, campos `x-ui`, parâmetros de authoring,
flags ou regras locais de host. O engine mantém independência de Spring/JPA.
Config Starter mantém sua própria fronteira de publicação e persistência.

## Tratamento dos achados

| Achado | Implementação | Evidência |
| --- | --- | --- |
| R01 | `getNullOperandPaths()` comprova as origens explícitas; o adapter exige exclusivamente `value` | Divisão por zero, outro tipo inválido, outro root nulo e iterator local não são dispensados; comparação opcional válida continua não aplicável |
| R02 | `entityFacts` projeta escalares/embedded sem associações; restaura explicitamente o container custom fields | Ciclo `ManyToOne` não é percorrido; os dois overloads de update preservam `entity.status` e `entity.customFields.amount` |
| R03 | `EntityNotFoundException` recebe `TARGET_NOT_FOUND` também na avaliação/admissão | HTTP/PostgreSQL: alvo removido após a proposta é INVALID; alvo seguinte CONFIRMED e alteração persistida |
| R04 | Falhas técnicas/inconclusivas e 5xx não recebem resultado de validação | Probes preservam exceções técnicas; HTTP/PostgreSQL confirma STOPPED e ausência de gravações em falha técnica de admissão |
| R05 | `CONFLICT` usa categoria `CONFLICT_DEPENDENCY` e conserva `TARGET_STATE_CONFLICT` | HTTP/PostgreSQL: primeiro alvo CONFLICT, segundo CONFIRMED; alteração do primeiro não é persistida |
| R06 | Grants são verificados no snapshot antes do SELECT por ID; somente operações autorizadas entram no SQL | HTTP/PostgreSQL: conhecidos/ausentes retornam 403 em proposta, execução, resultados e cancelamento; testes verificam ausência de SELECT quando negado |
| R07 | Fallback usa `Specification` com ordem Criteria explícita e Pageable sem Sort nativo | H2/JPA: todos os registros aparecem uma vez; nulos explícitos, ASC/DESC e retorno usam a mesma ordem do caminho EntityManager |

### Contratos de falha e autoridade

A classificação de alvo reconhece somente falhas tipadas: versão obsoleta,
conflito de estado, ausência, acesso negado, validação e mudança de campos inválida.
Uma `IllegalArgumentException` genérica em configuração/callback permanece
técnica. `BulkFieldChanges` distingue os cinco erros de entrada do payload das
allowlists inválidas do servidor. Erros Jackson `MismatchedInputException` ao
converter o plano para UpdateDTO são traduzidos na fronteira do adapter;
configuração inválida do mapper não é tratada como entrada corrigível.

O lookup resolve bindings MVC/OpenAPI e contexto autenticado **antes** do JDBC;
isso não concede leitura do ID. O `preAuthorize` de cada operação declarada roda
**dentro** do snapshot operacional, com orçamento agregado de três segundos.
Autoridade ausente, indisponível ou vinculada a outra infraestrutura/recurso
falha fechada com 503. Sem grants, o resultado é 403 independentemente do ID.
Com grant parcial, somente operações autorizadas entram no filtro SQL. O reader
subsequente ainda revalida o grant, o criador e a visibilidade do conjunto completo.

Chamadas diretas a `BulkOperationStatusLookup` agora exigem `BulkResourcePolicy`.
A assinatura sem autoridade foi removida; controllers da base passam a política
pelo engine. Trata-se de correção do candidato beta local, sem alias permissivo.
Não foi localizada chamada direta desse lookup nos sources do Quickstart,
Config Starter ou Ergon Showcase.

### UX funcional

`PraxisSubmitErrorDetail` preserva `DomainRuleDecision`. O normalizador aceita as
cinco decisões canônicas; uma decisão desconhecida conserva a mensagem pública
segura e não projeta target para correção. O formulário só materializa uma decisão
governada no campo quando ela é `DENY`. As demais ficam no resumo, sem marcar o
controle inválido/touched e sem retry automático. Erros legados sem decisão
mantêm o comportamento por target, inclusive caminhos aninhados.

Não há alteração de layout, editor, inputs/outputs, config persistida, tradução
ou manifesto AI. A API pública já exportava o tipo de erro normalizado; ganhou
somente o campo opcional `decision`. Os manifests e o round-trip de authoring
não recebem nova semântica. A fixture `relatedResourceSurface` de um spec do
Core estava declarada dentro de outro teste; seu escopo foi corrigido para
permitir compilar e executar a prova focal, sem mudar suas expectativas.

## Gates

OpenJDK 21 e Maven 3.9.6 para Java; instalação Node Windows e ChromeHeadless para
os specs Angular. O MCP Angular forneceu as boas práticas do CLI instalado.

| Gate | Resultado |
| --- | --- |
| Engine `mvn install`, incluindo testes/sources/Javadocs | PASS — 125 testes, zero falhas/erros/skips; instalação somente local |
| Starter seleção focal inicial ampliada | PASS — 125 testes, incluindo as dez reproduções negativas da revisão |
| Starter HTTP/PostgreSQL final | PASS — 12 testes, incluindo quatro cenários novos de concorrência/autoridade |
| Starter complementar Windows, árvore compartilhada | FAIL — 1.862 testes: 1 falha, 2 erros, 6 ignorados; ocorrências descritas abaixo |
| Starter focal final, snapshot isolado | PASS — 160 testes, zero falhas/erros/skips, incluindo as 12 provas HTTP/PostgreSQL e o caso de bootstrap |
| Delta concorrente do helper de patches | PASS — 16 testes de cópia/commands/custom fields; inclui casos já presentes no gate focal, sem somar como testes únicos |
| Angular Core: normalização e adapter de surface | PASS — 24 specs em ChromeHeadless |
| Build Core e dependentes pelo script canônico | PASS — 13 bibliotecas, incluindo Dynamic Form |
| Form consumidor: specs e build | PASS — 238 specs ChromeHeadless; build incluído na sequência canônica |
| Instalação do starter final | PASS — `8.0.0-rc.188`, sources e Javadocs instalados localmente; não altera o FAIL do lote amplo |

Comando complementar:

```powershell
mvn test "-Dtest=!*Neon*,!*PostgresTest,!BulkCapacityLocalAdministrationMainTest,!BulkCapacityProvisioningMainTest,!BulkConsumerArtifactIntegrationTest"
```

Logs Java em `target/p5a-r1-r7-*.log`. Logs Angular em
`D:\CodexHome\review-workspaces\p5a-20261102-1201\angular-*.log`.
Manifesto das 13 fontes de produção relevantes em
`target/p5a-r1-r7-source-manifest.json`. O JAR do engine instalado localmente tem
SHA-256 `e7ae99edde4350389e81ede78c3c140f56f1343cc21531f02e8a7e267e9cc13b`;
o starter exige esse candidato com `getNullOperandPaths()`. A instalação local
não prova disponibilidade dessas APIs em uma coordenada pública do registry.
O manifesto `target/p5a-r1-r7-freeze-final-manifest.json` compara os 679 arquivos
de `src/main` e POM com o snapshot. Uma alteração concorrente em
`ResourceStateCopySupport` foi detectada antes da instalação, incorporada ao
snapshot e validada pelos 16 testes do delta, preservando o trabalho recebido.
Após instalar, os 679 arquivos continuavam iguais ao snapshot final.
O JAR do starter instalado tem SHA-256
`bb971002e2ab1cc278861f24f6745095a839b90c83e59225880a5a7458c80c45`;
os JARs de `target` e do repositório Maven local são idênticos para os dois
artefatos, conforme `target/p5a-r1-r7-artifacts.json`.
`javap` confirmou que o lookup empacotado exige a política nas duas chamadas.
O Javadoc inclui essa assinatura e a exceção de payload tipada. O blueprint
embarcado mantém SHA-256
`655a53cb12012ba4ff3fe79662c3dffa8727d9c2c9eed6db9604d124fe0a2de2`, igual à
fonte e ao GUIA-00.
As primeiras tentativas HTTP detectaram problemas de composição que foram
corrigidos antes do gate final; seus resultados não são contados como PASS.

O lote complementar literal não terminou verde. Houve uma falha ao iniciar o
PostgreSQL embarcado (`Gave up waiting for server to start after 10000ms`) antes
de executar `BulkOperationStructuralCompilerTest.mixedResourceKeepsCommandFingerprintAndReadinessWhileUpdatesRemainUncomposed`.
Também executou a versão antiga de uma expectativa de readonly de comandos,
atualizada concorrentemente em `CustomFieldAdoptionRegressionTest`, e um probe
novo com dado literal posicionado incorretamente como operador JSON Logic.
O probe foi corrigido para usar `draft.rows`, preservando as expectativas e
verificando o código de erro de tipo. As versões atuais foram revalidadas no
snapshot isolado `D:\CodexHome\review-workspaces\p5a-corrections-20261102-2140\praxis-metadata-starter`.
A repetição focal não apaga o resultado FAIL do comando amplo original.

Comando do gate focal final, registrado em `target/p5a-r1-r7-isolated-final.log`:

```powershell
mvn "-Dtest=P5AIndependentReviewTest,P5ABulkIndependentReviewTest,P5AStatusAuthorizationReviewTest,P5ACursorIndependentReviewTest,P5ACustomFieldRegressionTest,P2CustomFieldContextTest,CustomFieldAdoptionRegressionTest,CustomFieldDomainMutationRegressionTest,CustomFieldJsonLogicTest,CustomFieldResourceLifecycleTest,CoreResourceEvolutionIntegrationTest,ResourceKeysetSupportTest,JpaResourceBulkEngineTest,BulkFieldChangeValidationTest,BulkOperationStatusLookupTest,BulkDeclarativeProtocolTest,BulkDeclarativeJpaHttpTest,GovernedResourceCommandExecutorTest,RuleViolationHttpTest,BulkOperationStructuralCompilerTest#mixedResourceKeepsCommandFingerprintAndReadinessWhileUpdatesRemainUncomposed" test
```

## Limites de aceite

No fallback keyset sem EntityManager, consulta `distinct` falha explicitamente
antes da leitura dos dados. Filtros relacionais que deduplicam resultados exigem
a composição com EntityManager, que projeta também as expressões de ordenação.
Esse limite não afeta o caminho Spring/JPA normalmente injetado e não é ocultado
por ordenação em memória.

O kernel SQL/Flyway e o caminho ATOMIC não foram alterados neste tratamento.
O antigo gate amplo com ATOMIC continua sem aprovação; não foi substituído pela
regressão complementar. Não houve execução Neon/POSIX, publicação remota, aceite
de IAM de um host real nem prova de fluxo integrado backend/browser ou AXE.
ChromeHeadless executa os specs dos runtimes; não certifica a jornada visual.

O scorecard de completude de recursos corporativos segue N/A: este recorte é a
infraestrutura compartilhada, sem migração de entidade/DTO de host. As mudanças
anteriores e concorrentes das árvores foram preservadas e não são atribuídas
a estas correções.
