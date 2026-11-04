# P2 — correções dos achados R1–R3

Data: 2026-11-01. Correção local na fonte canônica, sem publicação.
Este registro sucede a [revisão original](GOVERNED-RULE-BRIDGE-P2-REVIEW.md),
cujas reproduções e hashes foram preservados. Não constitui revisão independente
nem aprovação de release ou de adoção no showcase.

## Contrato corrigido

| Achado | Fonte canônica e comportamento |
| --- | --- |
| R1 — contexto de servidor ausente no update | `CustomFieldValidator.PreparedValidation` captura definições e fatos do request uma vez. A base resource-oriented carrega/autoriza o item e confere a precondição versionada antes de avaliar com `entity`. Uma negação impede hooks, mapper e save. |
| R2 — causas e campos de rejeição falsos | `PraxisRuleViolationException` preserva exatamente `RuleEvaluationResult.reasonCodes`. Somente razões consolidadas de bindings causais geram violações. NOT_APPLICABLE consolidado usa os bindings terminais; dependências bloqueadas não acrescentam razões excluídas pelo evaluator. |
| R3 — operador contextual sem contexto de execução | `CustomFieldJsonLogic` consulta a pureza no catálogo do engine e rejeita operadores `contextual` na construção do descriptor, inclusive em defaults e ramos não selecionados. O dialeto global não muda e nenhum relógio implícito é introduzido. |

No create, `entity` é JSON null explícito. Chamadas standalone não disponibilizam
essa raiz: referências a ela são recusadas, em vez de comparar um valor ausente e
possivelmente permitir a operação. Overloads com entidade não nula usam o
contrato de update. `validateForUpdate(null)` falha antes da avaliação.

A mesma preparação fornece as chaves readonly usadas pelo mapper, sem segunda
consulta ao provider. Tipos, obrigatoriedade e atribuições protegidas continuam
sendo verificados antes da carga da entidade. Fatos do request e a lista de
definições são capturados sem reter aliases mutáveis do chamador.
Regras que não observam `entity` nem o contexto completo não serializam o estado
persistido: uma restrição somente de valor não deve percorrer associações JPA
não relacionadas. Quando necessário, o host fornece fatos pré-carregados,
respeitando os limites e a rejeição de ciclos do fact-tree builder.

Extensões do lifecycle devem decorar `prepareCustomFields`, preservando o retorno
de `super`, e selecionar explicitamente create/update. O helper protegido
`validateCustomFields` representa apenas validação standalone. Trata-se de uma
mudança deliberada do ponto de extensão do candidato beta; não existe caminho
paralelo de compatibilidade que dispense o contexto autorizado.

A inspeção de consumo identificou 14 serviços do showcase com chamadas manuais
a `validateCustomFields`, incluindo `CargoService`, `VinculoService`,
`ServidorPessoaService` e `OrgaoService`. Seu POM ainda referencia o starter
`8.0.0-rc.186`. Ao adotar este
candidato, a P4 deve migrar esses escritores para a preparação e o contexto
explícitos; testes da base não certificam overrides do host. A adoção do showcase
é um escopo separado no plano das ondas, e esses serviços não foram alterados.

No adapter Bulk já presente na árvore, somente a chamada de validação foi alinhada
para receber o estado autorizado existente. Os demais trabalhos de P1/P3 foram
preservados; este registro não certifica o kernel Bulk.

## Experiência e documentação

O handler HTTP continua consumindo a projeção do engine. Um ramo não aplicável
anterior à negação não acrescenta um campo falso em `errors[].target` ou
`violations[].targetField`. O teste MVC percorre comando governado → engine →
handler e verifica que a continuação não executa após a negação.

O [guia de adoção](../guides/GOVERNED-RULE-BRIDGE.md), a especificação de custom
fields, FieldSpec e changelog descrevem as roots, o ciclo em duas etapas e a
recusa de operadores contextuais. A prova de UX desta correção é do contrato
HTTP consumido pelo formulário; não houve browser/E2E Angular.

## Regressões

- `P2CustomFieldContextTest`: update comum/versionado bloqueado e permitido,
  nenhuma mutação após negação, uma consulta ao provider, preservação readonly,
  autorização e versão antes de ler fatos, create explícito, chamadas standalone,
  snapshot imutável, admissão temporal e regra de valor sem percorrer estado
  persistido não relacionado.
- `PraxisRuleViolationExceptionTest`: NOT_APPLICABLE/INCONCLUSIVE antes de DENY,
  dependência inconclusiva e NOT_APPLICABLE consolidado, com razões/proveniência.
- `RuleViolationHttpTest`: planos mistos retornam somente a causa e o campo
  efetivos de rejeição, inclusive pelo executor de comandos governados.

Os testes novos reproduziram os defeitos antes da correção: três falhas no engine
e três na primeira versão da regressão de contexto contra os JARs originais
fixados pela revisão. Logs locais: `praxis-rules-engine/target/p2-corrections-red.log`
e `praxis-metadata-starter/target/p2-corrections-metadata-red.log`.

## Validação e limites

A execução usa JDK 21 e Maven 3.9.6. Um reactor liga as fontes corrigidas do engine
ao starter, sem substituir os artefatos em `.m2` durante os testes. A rodada no
diretório compartilhado foi invalidada por classes de teste ausentes enquanto
outro build recompilava esse diretório. A repetição usa cópia isolada das fontes,
POMs e recursos, identificada por um manifesto SHA-256; nenhum comportamento de
produção ou configuração canônica de build foi alterado para obter o isolamento.

| Gate desta correção | Resultado |
| --- | --- |
| Engine — `mvn test` completo | 116 testes, 0 falhas, 0 erros, 0 ignorados |
| Reactor isolado — engine, seleção focal | 52 testes, 0 falhas, 0 erros, 0 ignorados |
| Reactor isolado — starter, seleção focal final | 238 testes, 0 falhas, 0 erros, 5 ignorados |
| Regressão de contexto | 8 testes passando, incluídos na seleção do starter |
| Contrato HTTP de violações | 13 casos passando, incluídos na seleção do starter |
| Whitespace dos arquivos alterados nesta correção | `git diff --check` sem erros |
| Reactor — `mvn install -DskipTests` | Sucesso nos dois módulos, com fontes e Javadocs, em repositório Maven local isolado |
| Consistência da evidência | 8 fontes/testes canônicos iguais ao snapshot testado; 6 JARs instalados iguais aos JARs gerados, por SHA-256 |

A seleção focal final terminou em 2026-11-01 às 16:38:24 (UTC−03:00). A rodada
isolada anterior passou com 237 testes no starter; a diferença é o teste de regra
de valor que não percorre a entidade, adicionado ao ajuste final. Os cinco
ignorados pertencem à suíte preexistente `CustomFieldsResourceE2ETest`.

Comando da seleção, a partir do reactor temporário com os dois módulos:

```powershell
mvn test "-Dtest=*Rule*Test,CustomField*Test,P2CustomFieldContextTest,GlobalExceptionHandlerTest,CustomProblemDetailTest,CustomProblemDetailHttpSerializationTest,GovernedResourceCommandExecutorTest,ResourceCommandHttpResponseAdapterTest,ResourceFactAndCustomFieldDomainCatalogTest,AbstractBaseResourceServiceTest,CoreResourceEvolutionIntegrationTest,!*Neon*"
```

O install terminou às 16:48:05 (UTC−03:00). Seu destino é
`D:\CodexHome\tmp\praxis-p2-corrections-20261101-162952\isolated-m2`;
`isolated-settings.xml` usa o cache existente como origem de dependências por
`file://`, sem instalar os candidatos sobre os artefatos compartilhados:

```powershell
mvn -s isolated-settings.xml "-Dmaven.repo.local=D:\CodexHome\tmp\praxis-p2-corrections-20261101-162952\isolated-m2" install -DskipTests -B
```

O Javadoc do starter registrou um link local do engine indisponível no reactor
temporário (`Error fetching link ... Ignored it`) e um aviso preexistente de
`&` em um comentário de query string. O build terminou com sucesso e os dois
JARs de Javadoc foram gerados/instalados. Isso não comprova links de documentação
publicada nem substitui o gate de release.

Logs, POM do reactor, settings e manifestos estão em
`praxis-metadata-starter/target/p2-corrections-isolated/`. O log
`shared-target-invalid.log` preserva a rodada descartada; `focal-test.log` contém
a rodada final aceita. O snapshot completo permanece no diretório temporário
identificado acima. Os JARs representam esse snapshot da árvore, incluindo o
trabalho preexistente de P1/P3, e não um pacote isolado exclusivamente da P2.

| JAR de runtime gerado/instalado | SHA-256 |
| --- | --- |
| Engine `0.1.0-beta.17` | `ED54A0F1385D110A35BD0FC102E943C08184888116FD48A96475EF2A81A58D31` |
| Metadata `8.0.0-rc.188` | `2A075AA5018743A6322C842ABA01C5D7610B8BBBAB24E5CDA86AD8E9692C3643` |

As contagens da implementação original não são apresentadas como prova dessas correções.
Testes PostgreSQL/POSIX/Neon e certificação de consumidores ficam fora deste corte.
O scorecard de completude de recursos não se aplica a este ajuste de biblioteca:
nenhum recurso corporativo de host foi criado ou alterado.
