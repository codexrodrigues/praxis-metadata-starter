# Regras governadas e campos customizados (`decisionKind = BUSINESS`)

> **Fronteira na Praxis Decision Platform (`decisionKind = BUSINESS` vs `decisionKind = AUTHORIZATION`):** Este guia descreve a ponte de execução de **regras de negócio determinísticas (`decisionKind = BUSINESS`)** avaliadas pelo `praxis-rules-engine` (`RuleSet`, `DecisionBinding`, `GovernedRuleEngineTemplate`) e traduzidas em `409 Conflict` / `422 Unprocessable Entity` (`RestApiErrorResponse`). Políticas de autorização, controle de acesso contextual (ABAC), escopo relacional multi-organização/setor (`DataScopePlan`) e mascaramento pós-leitura (`FieldMaskingPlan`) pertencem à trilha **`decisionKind = AUTHORIZATION`** (`praxis-authz-contracts`, `praxis-authz-enforcement-core`, `praxis-authz-opa`, `praxis-authz-starter`), vinculada por `RuntimeEnforcementBinding`.

Os três achados da [revisão de integridade e UX](../technical/GOVERNED-RULE-BRIDGE-P2-REVIEW.md)
receberam correções na fonte canônica. O [registro de correção](../technical/GOVERNED-RULE-BRIDGE-P2-CORRECTIONS.md)
separa a implementação e seus testes da revisão original e de uma futura publicação.

O `praxis-rules-engine` é a fonte canônica do runtime Java puro. O metadata
starter depende desse runtime e traduz `PraxisRuleViolationException` sem
acoplar o engine ao Spring. A ponte não cria RuleSets, snapshots ou decisões
locais de fallback. O host mantém autorização, fatos, transação e efeitos.

`GovernedResourceCommandExecutor` preserva a exceção tipada ao atravessar o provider
do host, permitindo ao handler publicar o mesmo contrato em comandos governados.
Outras exceções inesperadas continuam sanitizadas pelo executor.

## Violação de regra

Lance a exceção pelo `GovernedRuleEngineTemplate.enforce` ou construa-a com
`(RuleDecisionPlan, RuleEvaluationResult, RuleErrorCatalog)`. O catálogo contém
somente textos e lineage publicáveis. Uma rejeição direta de slot também pode
ser representada por `SlotViolationDetail`.

O `GlobalExceptionHandler` responde HTTP 409 quando todas as violações têm
`category=CONFLICT` (case-insensitive), e 422 nos demais casos, com o envelope `RestApiErrorResponse`
canônico, status `failure` e `errors[]` de `CustomProblemDetail` (RFC 7807).
Cada erro tem código atômico e `target` quando o catálogo declarar um campo.
O primeiro erro publica as extensões agregadas planas `decision`, `reasonCodes`,
`slotKeys`, `decisionSources` e `violations`, uma vez para evitar expansão
quadrática. Cada erro publica sua decisão, código, target e `targetField`, quando existente.
Cada entrada de `violations` preserva binding, source, stage, decisão, lineage e
categoria (default `BUSINESS_LOGIC`). A mensagem principal é a primeira mensagem
resolvida no catálogo, com fallback público genérico.
Não há wrapper `properties` nem exposição de facts/outputs/causas.

`reasonCodes` preserva as razões consolidadas de `RuleEvaluationResult`.
Somente os bindings causais dessa decisão são projetados como violações/campos
de correção. Um ramo NOT_APPLICABLE ou INCONCLUSIVE anterior ao DENY de outro
ramo não acrescenta um erro de campo. No resultado NOT_APPLICABLE consolidado,
a projeção conserva os motivos dos bindings terminais. Razões de dependências
bloqueadas que o evaluator não consolidou também não viram erros de campo.

`TECHNICAL_ERROR` e `INCONCLUSIVE` permanecem decisões distintas em `decision`.
A categoria HTTP é `CONFLICT` para 409 e `BUSINESS_LOGIC` para 422. Todos impedem a
continuação via `enforce`. O status dessa ponte não autoriza retry nem significa
que toda falha seja corrigível pelo usuário. Exceções inesperadas continuam no
tratamento sanitizado de HTTP 500.

## validationRules.jsonLogic

`CustomFieldDescriptor.validationRules` aceita uma expressão canônica e mensagem
opcional. O construtor sem regras continua sendo uma conveniência para definições
que não possuam essa restrição adicional.

```json
{
  "jsonLogic": {"<=": [{"var": "value"}, {"var": "draft.limit"}]},
  "message": "O valor solicitado ultrapassa o limite."
}
```

O schema público sinaliza a regra com `x-ui.validationMode: json-logic`. A árvore
e a mensagem da definição ficam no servidor; a mensagem de rejeição é retornada
no erro de campo quando a regra falha. O fingerprint continua sensível à
definição. A projeção de filtro omite o marcador de validação de escrita.
O cliente usa os metadados de apresentação e exibe o erro do servidor.

Roots explícitas, sem resolução implícita:

| Root | Origem |
| --- | --- |
| `value` | Valor do campo; null explícito tanto para omissão quanto para chave enviada com null |
| `customFields` | Container completo enviado |
| `draft` | DTO enviado; no overload de mapas, objeto contendo `customFields` |
| `entity` | Estado previamente carregado e autorizado no update; null explícito no create |

As bases resource-oriented usam `prepareCustomFields(dto, schemaType)` para
capturar um único snapshot de definições por recurso/tenant/operação e validar tipos
e restrições básicas antes da carga. A preparação guarda uma cópia dos fatos do
request e as atribuições read-only para compará-las com o registro autorizado
no update e rejeitá-las no create.

No update, a base carrega e autoriza a entidade; no update versionado também
confere a precondição sob o protocolo de lock existente. Somente então chama
`PreparedValidation.validateForUpdate(existing)`, antes de hooks de negócio,
mapping da escrita ou save. Snapshots read-only idênticos ao persistido são aceitos;
alterações e limpezas explícitas são rejeitadas. A mesma preparação devolve as chaves read-only para preservá-las
durante o mapping. Não há segunda consulta ao provider de definições.
Regras restritas a `value`, `customFields` ou `draft` não percorrem a entidade.
Quando a expressão observa `entity` ou o contexto completo, o estado deve estar
pré-carregado. A ponte projeta escalares, coleções escalares e valores embedded
sem invocar getters de associações JPA. O container JSON `customFields` é
preservado explicitamente. Relações não fazem parte dessa projeção; fatos que
dependem delas exigem uma projeção JSON confiável preparada pelo domínio.

No create, `validateForCreate()` fornece `entity = null` explicitamente.
As guardas primitivas continuam antes do mapping e dos hooks. Quando uma regra
observa `draft`, a base pode construir um candidato não persistido e executar
`prepareCreateValidationDraft(dto, candidate)` antes de avaliar JSON Logic.
O default conserva o DTO de entrada. O host pode hidratar o candidato com fatos
autorizados do pai e devolver uma projeção DTO explícita; não deve devolver um
grafo JPA nem persistir nesse hook.

No update, `prepareUpdateValidationDraft(id, candidate, dto)` recebe uma cópia
destacada após as guardas de acesso/versão. O default aplica `applyUpdate(candidate,dto)`
e retorna `toResponse(candidate)` (ou o DTO se a projeção for nula), combinando os
novos valores com o estado hidratado. O `entity` da regra continua sendo o
estado persistido autorizado, não essa cópia. `draft` recebe a projeção preparada,
com fatos derivados, antes de `beforeUpdate` e do mapper da escrita. Avaliação
bulk usa o mesmo preparo. Os hooks são chamados somente quando a expressão
observa `draft`, inclusive por `var` do contexto completo ou defaults aninhados.
Definições, valores submetidos e chaves protegidas são capturados uma vez. Regras compartilhadas entre create/update podem
tratar essa ausência declarada. Nenhum estado é copiado de um campo do cliente
para a raiz de servidor.

Os overloads públicos sem entidade fazem validação standalone: `entity` fica
fora das roots disponíveis, e uma referência direta a ela falha como configuração
incompatível com esse contexto. Eles não presumem que a chamada seja um create.
Um chamador que conhece a operação de criação pode usar:

```java
var prepared = validator.prepare(dto, resourceKey, tenantId, "request");
prepared.validateForCreate(); // ou validateForCreate(trustedEffectiveDraft), após hidratação explícita
```

Overloads que recebem uma entidade não nula usam `validateForUpdate(entity)`.
Extensões do ciclo padrão devem decorar `prepareCustomFields` e preservar a
preparação retornada por `super`. O helper `validateCustomFields` continua sendo
uma validação standalone, sem fornecer estado persistido.

A admissão de authoring deve chamar
`CustomFieldValidationRules.validate(validationRules)` antes de gravar/ativar uma
definição. O descriptor usa exatamente essa entrada canônica e copia a definição
na construção. Operador, root, shape ou
limite inválido rejeitam a definição. O adapter não tem relógio/timezone implícito:
operadores com `purity = contextual` no catálogo canônico, como `now` e
`daysSince`, são recusados na construção, inclusive em defaults e ramos não
selecionados. O dialeto global mantém esses operadores; o template governado os
executa com contexto temporal explícito. Valores de datas em fatos e operadores
puros continuam disponíveis neste adapter.
Erro de execução é técnico e permanece HTTP 500 sanitizado; resultado falso usa
`CustomFieldValidationException` e o HTTP 400 existente com
`errors[].target = customFields.<key>`. A mensagem não inclui valores submetidos.
Omitir um campo opcional dispensa somente restrições locais ao seu `value`.
Regras que observam `draft`, `entity`, `customFields` ou o contexto completo
continuam sendo avaliadas e podem tornar o campo condicionalmente obrigatório.
Essa dependência é decidida pelo parser/validador do dialeto, não por texto ou
substrings. O mesmo tratamento vale para `null` explícito. Regras cross-field
recebem `value=null`: resultado falso mantém a rejeição condicional, enquanto
somente incompatibilidade causada por esse valor nulo pode ser não-aplicável.
O código `RULE_ARGUMENT_TYPE_INVALID`, isoladamente, também identifica erros em
outros operandos e não autoriza ignorá-los. O starter dispensa a avaliação
somente quando `getNullOperandPaths()` comprova que todos os
operandos nulos responsáveis vieram da root `value`. Erros em `draft`, divisões
por zero e origens locais de iteradores continuam técnicos. Origem não comprovada
não autoriza suprimir a falha. Veja as
[correções R01–R07](../technical/P5-A-CORRECTIONS-2026-11-02.md).
Zero é um valor presente e continua sendo validado. `var` vazio em predicados de iteradores
refere-se ao item local e não exige a root `entity`.
Campos obrigatórios e read-only continuam recusando entradas inválidas.
Antes de executar as expressões, o validator verifica todos os tipos, campos
obrigatórios e restrições básicas do snapshot. Assim, um operando obrigatório
ausente ou mal tipado não é confundido com uma falha técnica da expressão de outro campo.

## Experiência de consumo

O Angular consome `errors[].target`, mensagens do catálogo e `decision`.
`DENY` permite materializar a rejeição no campo; decisões técnicas,
inconclusivas ou não aplicáveis permanecem no resumo, sem invalidar o controle.
Erros legados sem `decision` preservam a materialização pelo target. Uma decisão
desconhecida preserva a mensagem segura e não cria erro de campo.
Os testes focais do normalizador e do formulário são distintos de um fluxo
integrado HTTP/browser. Para fechar o contrato de UX no host:

- publicar como erros de campo somente causas de rejeição que o usuário possa corrigir;
- preservar decision/category no consumo e tratar falhas SYSTEM sem exigir uma edição inútil;
- oferecer resumo localizado, mantendo os detalhes pt-BR e a correlação técnica;
- provar os casos mistos de decisão, os campos customizados aninhados e a limpeza
  dos erros depois da correção, usando o runtime oficial.

## Desenvolvimento local

Este corte P2 usa os candidatos locais `praxis-rules-engine:0.1.0-beta.17` e
`praxis-metadata-starter:8.0.0-rc.188`, sem bump ou publicação. Instale o engine
antes de compilar o starter. Esse `mvn install` prova composição local; release
requer versionamento coordenado e publicação oficial antes do consumo público.
A adoção e remoção das implementações do showcase pertencem à P4.
