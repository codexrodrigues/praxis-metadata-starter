# P2 — ponte governada de regras

Data: 2026-11-01. Implementação local, sem publicação ou adoção no showcase.

**Revisão posterior: CHANGES REQUESTED.** Foram reproduzidas falhas de contexto
`entity`, projeção de ramos não aplicáveis e admissão de operadores temporais.
Consulte o [parecer de integridade e UX](GOVERNED-RULE-BRIDGE-P2-REVIEW.md).
As contagens abaixo registram a execução original e não representam aprovação funcional.

Os três achados receberam uma [correção subsequente](GOVERNED-RULE-BRIDGE-P2-CORRECTIONS.md),
com mudança no ciclo canônico de validação e na projeção de causas.

## Escopo e impacto

Classificação: `contrato-publico`, `arquitetural` e `transversal`.

| Dono canônico | Alteração | Consumidores / validação |
| --- | --- | --- |
| rules-engine | Catálogo imutável, violações por slot e exceção neutra | Catálogos de hosts; testes de identidade, proveniência e isolamento |
| rules-engine | Fact-tree builder com datas ISO, coleções, limites e intervalos inclusivos | Contextos pré-carregados de hosts; testes de determinismo, ciclos, tipos e limites |
| rules-engine | Template com cache por regra/scope e execução fail-closed | Hosts Java; concorrência, atualização de conteúdo e cinco estados |
| rules-engine | Matcher de matriz e overrides tri-state/numéricos | Resolvedores paramétricos; vigência, especificidade, desempate, false/zero/ausência |
| metadata-starter | Handler HTTP 422 e propagação da exceção em comandos governados | `errors[]` RFC 7807; testes MVC/Jackson e do executor |
| metadata-starter | `validationRules.jsonLogic` no descriptor, validator e schema | Providers de custom fields; quatro roots explícitas, precisão decimal, fingerprint e FieldSpec |

O dialeto JSON Logic, o corpus normativo e o contrato serializado de RuleSet 1.6
permanecem os existentes. A nova dependência do starter usa o runtime canônico;
não há implementação paralela de JSON Logic. O engine continua sem Spring.

As alterações anteriores de P1 no starter foram preservadas. Não foram alterados
hosts Ergon, regras de negócio, banco, infraestrutura Bulk ou dependências PG17.
O gate Entity-to-DTO de recursos não se aplica: este corte altera bibliotecas e
adaptadores compartilhados, sem criar ou revisar um recurso corporativo de host.

## Decisões de integração

- Tempo e timezone do template são explícitos por avaliação; não há relógio do
  processo, fallback de decisão, persistência ou autoridade de ativação no cache.
- O cache guarda apenas planos imutáveis. Hosts são responsáveis por admissão,
  ordem de ativação e invalidação de scopes retirados.
- Somente `ALLOW` permite continuar por `enforce`. Os demais estados são preservados
  na exceção, inclusive `NOT_APPLICABLE`, `INCONCLUSIVE` e `TECHNICAL_ERROR`.
- Diagnósticos não contêm facts, outputs Java, causas ou stack traces. O catálogo
  do host deve conter apenas mensagens e lineage publicáveis.
- O agregado de violações aparece uma vez no primeiro `errors[]`; os erros de campo
  conservam seus próprios código/target. Isso evita amplificação quadrática.
- O executor de comandos deixa a exceção tipada chegar ao handler, seguindo a
  fronteira já usada para precondições. Outras falhas continuam sanitizadas.
- `entity` no validator só existe quando código servidor fornece a entidade
  previamente carregada e autorizada. O fluxo padrão não inventa esse estado.
- Regras de custom fields são validadas na construção, capturadas como dados
  imutáveis e avaliadas pelo engine Java. Falso usa o HTTP 400 de validação de
  campo existente; falha técnica continua 500 sanitizado. A ponte de violações
  governadas usa HTTP 422.
- As verificações básicas de todos os campos precedem as expressões cruzadas.
  Uma ausência/tipo inválido de operando obrigatório continua sendo erro de campo.
- O teste de catálogo revelou um acesso a `.stream()` sobre `complianceTags`
  ausente no código anterior da P1. Foi aplicado somente o guard de null,
  preservando a semântica de ausência e os demais ajustes anteriores.

## Artefatos derivados e limites

Foram atualizados o guia de adoção, a especificação FieldSpec, a documentação de
custom fields, README, índice e changelog do starter, além do README, arquitetura
e guia do engine. Não foram criados endpoints ou exemplos HTTP de host. Landing,
corpus HTTP e Angular não foram alterados: este corte é um candidato local de
backend, sem declaração de execução de `validationRules` no runtime Angular.
Adoção em hosts e remoção das implementações do showcase pertencem à P4.

Coordenadas locais: engine `0.1.0-beta.17`, metadata `8.0.0-rc.188`. Uma instalação
em `.m2` não equivale a versão publicada, smoke contra Maven Central ou gate de
release. A publicação futura exige coordenadas e dependências coordenadas.

## Evidência

Os comandos foram executados com Maven 3.9.6 e JDK 21. Logs em `target/p2-*.log`.
A prova HTTP é Spring MVC/Jackson do starter, incluindo comando → engine → handler;
o contador de continuidade comprova a interrupção do handler de teste antes da
mutação simulada, sem alegar prova de rollback de banco ou segurança de um host.

O comando amplo solicitado usa `-Dtest=!*Bulk*Postgres*Test,!*Neon*`. Esse filtro
não alcança todos os testes que indiretamente exigem POSIX/PG17, nem a prova Bulk
que exige árvore de produção commitada. Esses gates permanecem separados da P2.

A primeira execução ampla também foi contaminada pela reinstalação do JAR do
engine durante a execução do fork de testes, com `ClassNotFoundException` nas
classes novas. Ela não é aceita como prova de integração da P2. As execuções
seguintes mantêm o JAR fixo e verificam novamente os cenários afetados.

### Resultados locais

| Gate | Resultado |
| --- | --- |
| Engine — `mvn test` | 113 testes, 0 falhas, 0 erros, 0 ignorados |
| Engine — `mvn install -DskipTests` | Sucesso, incluindo fontes e Javadocs; quatro avisos de Javadoc em contratos anteriores de parâmetros |
| Engine — árvore de dependências de runtime | Apenas jackson-databind, jackson-core e jackson-annotations; zero Spring |
| Starter — bateria focal final | 188 testes, 0 falhas, 0 erros, 5 ignorados |
| Starter — comando amplo original | Executado: 1.778 testes, 9 falhas, 45 erros, 6 ignorados; execução contaminada descrita acima, não aceita como gate da P2 |
| Starter — repetição ampla com cinco exclusões adicionais | 1.726 testes, 0 falhas, 0 erros, 6 ignorados |
| Starter — `mvn install -DskipTests` | Sucesso, incluindo fontes e Javadocs |

Bateria focal final:

```powershell
mvn test "-Dtest=CustomField*Test,RuleViolationHttpTest,GlobalExceptionHandlerTest,CustomProblemDetailTest,CustomProblemDetailHttpSerializationTest,GovernedResourceCommandExecutorTest,ResourceCommandHttpResponseAdapterTest,ResourceFactAndCustomFieldDomainCatalogTest"
```

Repetição ampla válida nesta máquina:

```powershell
mvn test "-Dtest=!*Bulk*Postgres*Test,!*Neon*,!BulkCapacityLocalAdministrationMainTest,!BulkCapacityProvisioningMainTest,!BulkConsumerArtifactIntegrationTest,!BulkCrudOperationalCapabilityHttpTest,!BulkOperationStructuralCompilerTest"
```

Exclusões adicionais, sem modificar esses testes ou o código Bulk:

| Classe | Bloqueio observado no comando original |
| --- | --- |
| `BulkCapacityLocalAdministrationMainTest` | `Files.setPosixFilePermissions` não suportado no Windows |
| `BulkCapacityProvisioningMainTest` | Mesmo requisito POSIX no setup |
| `BulkConsumerArtifactIntegrationTest` | Exige produção/POM commitados; a árvore já continha P1 e agora também contém P2 |
| `BulkCrudOperationalCapabilityHttpTest` | Store exige PostgreSQL 17; fixture embedded disponível usa uma versão anterior |
| `BulkOperationStructuralCompilerTest` | Oito cenários exigem o mesmo store PG17; a classe inteira foi excluída desta repetição |

O JAR do engine permaneceu fixo na validação final:
`708F41B4BEDD608BB75101324293452787FB31378D00D4EC32E697C5BC3B3441` (SHA-256).
Logs: `target/p2-focused-final.log`, `target/p2-broad.log`,
`target/p2-broad-compatible.log` e `target/p2-install.log` no starter;
`target/p2-test-final.log`, `target/p2-install-final.log` e
`target/p2-runtime-dependencies.txt` no engine.

O JAR final do starter e sua cópia instalada em `.m2` têm SHA-256 idêntico:
`C62B846C0C868AD189E1F05C0B3686A25A86C21FDCAFDF7A48BEFDA95AF7553D`.
Os dois installs são locais e não constituem publicação. `git diff --check`
passou nos dois módulos.
