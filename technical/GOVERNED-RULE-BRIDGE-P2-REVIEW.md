# Revisão da P2 — integridade, contratos e experiência de consumo

Data: 2026-11-01. Parecer: **CHANGES REQUESTED**.

Registro histórico da revisão. Uma etapa posterior implementou as correções de
R1–R3 e seus testes de regressão; veja o
[registro de correção](GOVERNED-RULE-BRIDGE-P2-CORRECTIONS.md).
O parecer e as reproduções abaixo descrevem os artefatos originais identificados pelos hashes.

A implementação contém todos os componentes principais previstos, mas a revisão
reproduziu uma possibilidade de descumprimento de regra no update e duas falhas
de contrato/diagnóstico. As contagens verdes da implementação não cobrem esses
cenários e não sustentam aprovação funcional da P2.

## Escopo e identificação da evidência

Foram revisados os contratos de violação, fact-tree builder, template/cache,
matcher de vigência, tri-state, integração de exceções, pipeline de custom fields,
testes e guias. O consumidor Angular foi inspecionado no código de
`ErrorMessageService` e `PraxisDynamicForm`, sem execução em browser.

Os artefatos da implementação anterior foram copiados para
`target/p2-review/candidate-{engine,metadata}.jar` antes das reproduções finais:

| Artefato | SHA-256 |
| --- | --- |
| Engine `0.1.0-beta.17` | `708F41B4BEDD608BB75101324293452787FB31378D00D4EC32E697C5BC3B3441` |
| Metadata `8.0.0-rc.188` | `C62B846C0C868AD189E1F05C0B3686A25A86C21FDCAFDF7A48BEFDA95AF7553D` |

As reproduções imprimem o `CodeSource` para comprovar o uso dessas cópias fixas.
Elas não usam a produção parcialmente recompilada da árvore em alteração.
O cenário de update usa mocks de mapper/repository e verifica a chamada a `save`;
não houve alteração de banco real nem alegação de prova de rollback/IAM.

## Achados que exigem correção

### R1 — P1 — A raiz entity ausente pode permitir uma alteração proibida

Fontes: `customfields/CustomFieldValidator.java:21-22`,
`customfields/CustomFieldJsonLogic.java:39-43` e
`service/base/AbstractBaseResourceService.java` nos métodos de validação/update.

O caminho padrão chama o overload do validator sem entidade. Os updates validam
os custom fields antes de carregar o registro existente. A expressão pode ser
válida e fazer referência a `entity`, mas a raiz fica ausente. Isso não produz
necessariamente erro técnico: a semântica canônica de ausência pode fazer uma
condição negativa resultar em verdadeiro.

Reprodução com regra de um campo:

```json
{"!=":[{"var":"entity.status"},"LOCKED"]}
```

Para uma entidade armazenada com `status = LOCKED`, o overload que recebe a
entidade rejeita corretamente. O `AbstractBaseResourceService.update`, com a
mesma definição e DTO, permite a alteração e chama `repository.save`.

Resultado observado:

```json
{"storedStatus":"LOCKED","directValidatorRejected":true,"baseServiceSaved":{"amount":8}}
```

Impacto: um recurso que use essa regra de custom field como restrição de
atualização pode descumpri-la. O problema é material para regras de estado,
elegibilidade, limites e retenção de valores, mesmo sem usar operadores temporais.

Correção canônica necessária: fornecer o estado previamente carregado e
autorizado na etapa de avaliação do update, preservando o snapshot único de
definições e as precondições de concorrência. A admissão/avaliação também precisa
definir quando uma raiz de servidor é obrigatória e impedir ALLOW quando esse
contexto estiver indisponível. O create deve ter uma semântica de ausência
explicitamente documentada. O overload manual não fecha a integração nativa.

Gate de fechamento: teste do serviço padrão, incluindo update versionado, com
entidade bloqueada; nenhum hook de mutação, mapper de update ou `save` pode ser
executado. Provar também o caso autorizado, isolamento do item e uso de uma única
consulta ao provider de definições.

### R2 — P2 — Um ramo NOT_APPLICABLE vira erro de campo em uma negação de outro ramo

Fontes: `rules/violation/PraxisRuleViolationException.java:40-41,71-81` no engine
e `rest/exceptionhandler/GlobalExceptionHandler.java:84-108` no starter.

O engine continua a avaliação depois de NOT_APPLICABLE para que outro binding
estabeleça a decisão. Quando um binding posterior retorna DENY, o resultado
consolidado contém somente a razão dessa negação. A exceção, porém, transforma
todos os resultados diferentes de ALLOW em violações e acrescenta suas razões
ao resultado consolidado.

Plano reproduzido: ramo opcional `OUT_OF_SCOPE` retorna NOT_APPLICABLE; ramo
`LIMIT` retorna DENY. Catálogo associa o primeiro a `category` e o segundo a
`amount`.

```json
{
  "engineReasons":["LIMIT"],
  "exceptionReasons":["LIMIT","OUT_OF_SCOPE"],
  "violationDecisions":["NOT_APPLICABLE","DENY"],
  "httpFirstCode":"OUT_OF_SCOPE",
  "httpFirstTarget":"category"
}
```

Impacto: o HTTP apresenta como primeiro erro um campo que não causou a negação.
O Angular normaliza cada `errors[].target` e aplica `control.setErrors({server:...})`.
O usuário pode receber um pedido de correção de categoria quando precisa apenas
corrigir o valor. A decisão agregada permanece DENY, mas suas causas públicas e
a orientação de correção deixam de ser fiéis ao resultado do engine.

Correção canônica necessária: manter a autoridade de `RuleEvaluationResult`
para as razões consolidadas e projetar como erros apenas as causas da decisão.
Ramos não aplicáveis e dependências bloqueadas podem permanecer como diagnóstico
de execução, distinguido dos erros de campo. Quando a decisão consolidada for
NOT_APPLICABLE, preservar sua rejeição fail-closed e seus motivos terminais.

Gate de fechamento: planos mistos NOT_APPLICABLE + DENY, INCONCLUSIVE + DENY,
dependências inconclusivas e NOT_APPLICABLE consolidado. Verificar razões,
proveniência, primeiro erro HTTP e ausência de campo falso no consumidor.

### R3 — P2 — Operador temporal é admitido, mas não possui contexto para executar

Fontes: `customfields/CustomFieldJsonLogic.java:24-32` e
`customfields/CustomFieldValidator.java:85-91`.

A construção do descriptor faz validação estática com tempo/timezone nulos.
Isso admite o operador canônico `now`. A execução usa novamente opções sem
tempo/timezone e não oferece contrato para fornecê-los ao validator.

Reprodução:

```json
{"jsonLogic":{">":[{"now":[]},0]}}
```

A definição é construída com sucesso. A validação de um DTO válido lança
`IllegalStateException`, cuja causa é `PraxisJsonLogicException` com código
`RULE_TEMPORAL_INPUT_INVALID`. O handler genérico materializa 500. A hipótese de
nome alternativo `praxis:now` foi rejeitada na admissão e não é a reprodução:
o defeito usa o nome canônico `now`.

Impacto: o catálogo pode publicar uma restrição que nenhum valor do usuário
consegue satisfazer por esse caminho. A mensagem de falha técnica não permite
que o operador de configuração descubra o problema na admissão.

Correção canônica necessária: alinhar capacidades de admissão e execução do
adapter. Rejeitar expressões dependentes de contexto indisponível antes de
publicá-las, ou definir um contexto temporal explícito e congelado por avaliação.
O template governado já recebe contexto explícito e não precisa de mudança de
dialeto ou relógio implícito para resolver essa diferença do adapter.

Gate de fechamento: cada operador contextual admitido deve executar com o mesmo
contrato de contexto; ausência/incompatibilidade deve falhar na fronteira prevista.
Incluir `now`, ao menos um operador relativo de datas e um caso não temporal válido.

## Planejado, executado e lacunas

| Item da P2 | Avaliação focal |
| --- | --- |
| Catálogo imutável e DTOs de violação | Presentes; projeção causal precisa da correção R2 |
| Fact-tree builder | Datas ISO, coleções, precisão e rejeição de ciclos/limites implementadas; nenhum bloqueador adicional confirmado |
| Template e cache por `(ruleSetKey, scopeKey)` | Presentes, com tempo explícito e `enforce` permitindo apenas ALLOW; admissão/ativação continua pertencendo ao host |
| Matcher de vigência | Especificidade lexicográfica, vigência inclusiva e desempate explícito presentes |
| Tri-state e número opcional | Preservam proibição, zero e herança sem inventar permissão |
| HTTP 422 e comandos governados | Integração presente; campo de correção afetado por R2 |
| JSON Logic de custom fields | Parcial: R1 e R3 impedem tratar as quatro roots e o dialeto como integração pronta |
| UX Angular | Consome target/message; aplicação dos estados e validação preventiva de `validationRules` não estão certificadas |
| Comando amplo com filtro original | Não ficou verde na implementação; rodada alternativa excluiu cinco classes Bulk adicionais |

O gate de completude Entity-to-DTO é **não aplicável**: esta revisão trata de
bibliotecas/infraestrutura, sem adicionar ou aprovar recurso corporativo de host.

## Documentação e UX

Os guias passam a apontar este parecer e a separar overload disponível,
integração nas bases e suporte de operadores. As contagens antigas são mantidas
como histórico de execução, com a conclusão funcional corrigida.

A inspeção estática do Angular identificou pontos para o fechamento de consumo:

- `ErrorMessageService` preserva code/target/category, mas descarta decision e
  proveniência agregada. O campo de correção é aplicado sem filtrar categoria.
- `PraxisDynamicForm.applySubmitFieldErrors` aplica todo target existente como
  erro de controle. Isso torna R2 diretamente relevante para a experiência.
- A mensagem genérica do envelope tem prioridade sobre a mensagem pt-BR do
  catálogo no resumo/snackbar. Convém definir resumo localizado e preservar os
  detalhes executivos de negócio, sem interpretar texto para classificar falhas.
- SYSTEM/TECHNICAL_ERROR/INCONCLUSIVE precisam de orientação de indisponibilidade
  ou diagnóstico, sem obrigar o usuário a editar um campo que não pode resolver
  a falha. Não há prova browser/E2E deste comportamento nesta revisão.

## Verificações executadas nesta revisão

| Verificação | Resultado e limite |
| --- | --- |
| Engine — `mvn test`, JDK 21 | 113 testes, zero falhas/erros/ignorados |
| Starter — comando focal Maven da árvore atual | Bloqueado na compilação por alterações Bulk/P3 concorrentes; não chegou à suíte de testes |
| Subconjunto unitário sobre JARs e classes de teste congelados | 93 testes, zero falhas/erros/ignorados; não equivale à bateria focal completa de 188 |
| Probes adicionais sobre os mesmos JARs | R1, R2 e R3 reproduzidos com assertions e CodeSource; sem banco real |

O subconjunto de 93 testes compreende `CustomFieldSchemaTenantIsolationTest`,
`CustomFieldResourceLifecycleTest`, `CustomFieldSchemaProjectionTest`,
`CustomFieldSpecContractTest`, `GlobalExceptionHandlerTest` e
`CustomProblemDetailTest`. Os primeiros ensaios de launcher com classes ao vivo
incompletas não foram aceitos como prova; o resultado acima usa cópias privadas.

A compilação atual falhou em `BulkEvaluationSchemaSynthesizer` (retorno Schema
versus StringSchema/ArraySchema) e em um acesso de `AbstractBaseResourceService`
ao tipo inacessível `ResourceFilterQuery`. São mudanças de P3 fora desta revisão
de P2. Elas foram preservadas. Não houve novo install, publicação, mudança de
código de produção ou correção de Bulk nesta revisão.

Evidências locais:

- `target/p2-review-engine.log` no engine;
- `target/p2-review-focused.log` no starter, incluindo erros de compilação;
- `target/p2-review/P2ReviewProbe.java` e `probe-pinned.log`;
- `target/p2-review/P2ReviewJUnit.java` e `junit-unit-subset.log`;
- `target/p2-review/pinned-classpath.txt` e cópias dos dois JARs.

## Critério para nova revisão

Fechar R1–R3 com testes permanentes que falhem antes da correção e passem depois,
compilar um snapshot estável da P2, repetir os testes focais/HTTP sobre esse
snapshot e atualizar a evidência. A aprovação permanece restrita à P2 e não
abrange adoção P4, publicação, banco, IAM ou certificação do consumidor Angular.
