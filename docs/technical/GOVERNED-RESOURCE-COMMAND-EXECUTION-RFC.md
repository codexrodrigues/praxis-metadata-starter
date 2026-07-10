# RFC - Governed Resource Command Execution

Data: 2026-07-10

Status: `spike-1-implemented`

Classificacao da mudanca atual: `contrato-publico` restrito a Java API; sem
alteracao de HTTP, `/schemas`, `/actions`, `/surfaces` ou `/capabilities`

Owner canonico proposto: `praxis-metadata-starter`

## Objetivo

Investigar uma capacidade host-neutral para execucao governada de comandos de
recurso corporativos, reduzindo decisoes repetidas em hosts consumidores sem
amarrar o Praxis a um backend privado especifico.

O caso Ergon e evidencia de recorrencia, nao fonte de semantica publica. O
contrato Praxis nao deve conhecer HADES, Oracle, Archon, nomes de procedures,
locators privados, tabelas privadas ou convencoes de erro de um host.

## Problema Observado

O Praxis ja publica uma borda resource-oriented forte para recursos mutaveis:

- `AbstractCreateUpdateResourceController`;
- `AbstractResourceController`;
- `AbstractLegacyBackedResourceController`;
- `BaseResourceCommandService`;
- `LegacyBackedResourceCommandService`;
- `DuplicateDraftLegacyBackedResourceCommandService`;
- `ResourceOperationAvailabilityProvider`;
- `AvailabilityDecision`;
- `@ResourceIntent`, `@WorkflowAction`, actions, surfaces e capabilities.

Esse baseline cobre a borda publica: endpoints, schemas, links, capabilities,
availability e discovery semantico.

Ainda assim, consumidores corporativos que delegam mutacoes a backends privados
tendem a repetir decisoes imperativas por recurso:

- resolver se o comando existe e esta disponivel;
- montar contexto privado de execucao;
- chamar guard ou autorizacao do host;
- executar procedure, mainframe, workflow engine, fila, SaaS ou servico interno;
- mapear resultado privado para outcome publico;
- reler o recurso apos escrita quando necessario;
- decidir como tratar rowcount zero, conflito, dependencia, validacao e erro;
- gerar evidencias de smoke, cleanup, read-after-write e nao vazamento de
  locators privados.

O gap nao e "criar CRUD" nem "Praxis saber escrever no backend do consumidor".
O gap e padronizar o lifecycle publico e governado de comandos cuja execucao
real pertence a uma fronteira privada do host.

## Classificacao De Produto

| Necessidade | Classificacao | Decisao |
| --- | --- | --- |
| Endpoints resource-oriented, DTOs, schemas, HATEOAS, create/update/delete | `already-supported` | Usar o baseline existente do starter. |
| Disponibilidade dinamica de operacoes | `already-supported` | Usar `ResourceOperationAvailabilityProvider` e catalogs/capabilities. |
| Actions e comandos de negocio explicitos | `already-supported` | Usar `@WorkflowAction` com endpoint e schemas reais. |
| Execucao privada com outcome, response policy, error policy e evidencia | `supported-partially` | Definir uma SPI neutra, se a recorrencia for confirmada. |
| Guard/checker contra locators privados e evidencias incompletas | `platform-gap` + `migration-skill-gap` | Evoluir tooling/skills junto com a SPI. |
| Regras, rotas, sessoes, procedures ou erros privados de um host | `application-domain` | Implementar em provider do host, fora do contrato Praxis. |

## Proposta

Criar uma capacidade conceitual chamada **Governed Resource Command Execution**.

Ela deve modelar comandos corporativos de recurso como decisao semantica
governada:

1. o Praxis publica a existencia do comando, seu escopo, schemas, links,
   disponibilidade, response policy e categorias publicas de erro;
2. o host implementa a execucao privada por provider;
3. o resultado privado e convertido para um outcome publico estavel;
4. a IA, Cockpit, docs e runtime consomem somente o contrato publico.

## Superficie Conceitual

O primeiro spike implementou uma superficie Java host-neutral pequena em
`org.praxisplatform.uischema.command`, sem expor ainda nova metadata HTTP.

```java
interface ResourceCommandExecutionProvider {
    ResourceCommandExecutionResult execute(ResourceCommandExecutionRequest request);
}

record ResourceCommandExecutionRequest(
        String resourceKey,
        String resourcePath,
        String commandId,
        ResourceCommandScope scope,
        Object resourceId,
        Object payload,
        ResourceCommandResponsePolicy responsePolicy,
        Map<String, Object> publicMetadata
) {}

record ResourceCommandExecutionResult(
        ResourceCommandOutcome outcome,
        ResourceCommandResponsePolicy responsePolicy,
        Object publicId,
        Object body,
        List<ResourceCommandMessage> messages,
        Map<String, Object> evidence
) {}
```

Tipos do spike:

- `GovernedResourceCommandExecutor`;
- `ResourceCommandHttpResponseAdapter`;
- helpers protegidos `executeCollectionCommand` e `executeItemCommand` em
  `AbstractResourceQueryController`;
- `ResourceCommandExecutionProvider`;
- `ResourceCommandExecutionRequest`;
- `ResourceCommandExecutionResult`;
- `ResourceCommandExecutionException`;
- `ResourceCommandMessage`;
- `ResourceCommandOutcome`;
- `ResourceCommandResponsePolicy`;
- `ResourceCommandScope`;
- `ResourceCommandErrorCategory`;
- `ResourceCommandEvidenceSanitizer`.

Politicas publicas:

- `READ_AFTER_WRITE`;
- `RETURN_COMMAND_RESULT`;
- `NO_CONTENT`;
- `ACCEPTED_ASYNC`.

Categorias publicas de erro:

- `VALIDATION`;
- `PERMISSION`;
- `NOT_FOUND`;
- `CONFLICT_DUPLICATE`;
- `CONFLICT_DEPENDENCY`;
- `PRECONDITION`;
- `UNEXPECTED_SANITIZED`.

O executor ja integra com `ResourceOperationAvailabilityProvider`: se a
availability do host negar a operacao, o provider privado nao e executado e o
resultado publico volta como `PERMISSION_DENIED`.

O executor tambem aplica `ResourceCommandEvidenceSanitizer` sobre evidencias do
provider e sobre metadata de availability, removendo chaves/valores com tokens
privados como `rowid`, `sql`, `procedure`, `package`, `session`, `token`,
`authorization`, `cookie`, `secret` e `password`.

Excecoes inesperadas do provider sao convertidas para
`UNEXPECTED_SANITIZED`, preservando apenas o tipo publico da excecao. Excecoes
controladas usam `ResourceCommandExecutionException` para retornar outcome,
mensagem publica e evidence sanitizada.

Para reduzir adaptacao em hosts Spring existentes, `ResponseStatusException`
tambem e tratada como falha publica governada:

- `400` -> `VALIDATION_FAILED`;
- `403` -> `PERMISSION_DENIED`;
- `404` -> `NOT_FOUND`;
- `409` -> `CONFLICT_DEPENDENCY`;
- `412` -> `PRECONDITION_FAILED`;
- demais status -> `UNEXPECTED_SANITIZED`.

Isso permite que services de dominio ja existentes migrem para
`executeCollectionCommand`/`executeItemCommand` sem envolver cada fluxo em
try/catch local apenas para preservar mensagens publicas e status HTTP.

`ResourceCommandHttpResponseAdapter` converte outcomes e response policies para
o envelope HTTP canonico da plataforma:

- `READ_AFTER_WRITE` e `RETURN_COMMAND_RESULT` -> `200 OK` com
  `RestApiResponse.success`;
- `NO_CONTENT` -> `204 No Content` sem envelope ad hoc;
- `ACCEPTED_ASYNC` -> `202 Accepted` com envelope de sucesso carregando
  resultado publico ou job id;
- `VALIDATION_FAILED`, `PERMISSION_DENIED`, `NOT_FOUND`,
  `CONFLICT_DUPLICATE`, `CONFLICT_DEPENDENCY`, `PRECONDITION_FAILED` e
  `UNEXPECTED_SANITIZED` -> `RestApiResponse.failure` com
  `CustomProblemDetail` publico.

`AbstractResourceQueryController` publica helpers protegidos opt-in para actions
reais de controllers resource-oriented:

- `executeCollectionCommand(...)`;
- `executeItemCommand(...)`.

Esses helpers montam `ResourceCommandExecutionRequest` com `resourceKey`,
`resourcePath`, `commandId`, escopo, payload e metadata publica, reutilizam a
availability de `CapabilityService` antes de executar o provider privado e
devolvem a resposta pelo adapter canonico. Em respostas de sucesso, tambem
preservam links HATEOAS de colecao/item e links `schema` de request/response
derivados da forma canonica de action. Eles nao criam endpoint generico nem
alteram discovery: o controller consumidor continua declarando uma operacao
HTTP real, normalmente anotada com `@WorkflowAction`.

## O Que Entra No Praxis

- envelope neutro de comando;
- outcome neutro de comando;
- politica publica de resposta;
- vocabulario publico de erro;
- binding com resource/action/capability existente;
- availability integrada a `ResourceOperationAvailabilityProvider`;
- introspeccao para Cockpit e IA;
- hooks de evidencia sanitizada;
- checker de contrato publico para evitar locators privados em schema, payload
  e metadata publica.

## O Que Nao Entra No Praxis

- nomes de rotas privadas do consumidor;
- sessoes, usuarios, empresas ou contexto operacional privado;
- SQL, procedure, package, trigger, mainframe transaction ou fila especifica;
- locators privados como ids internos nao publicos;
- nomes privados de parametros de erro;
- regras de negocio de um dominio consumidor.

Esses itens pertencem ao provider do host ou aos contratos de dominio do
consumidor.

## Aderencia Antes De Novo Contrato

Antes de criar qualquer classe, anotacao ou metadata publica, a implementacao
deve responder:

| Pergunta | Classificacao esperada |
| --- | --- |
| O comando ja pode ser expresso por `POST`, `PUT`, `DELETE`, `@ResourceIntent` ou `@WorkflowAction`? | `ja-suportado-so-ux` ou `ja-suportado-mal-nomeado-ou-mal-materializado` |
| A lacuna e apenas documentar melhor response/read-after-write nos guias? | `suportado-parcialmente` |
| A availability ja pode ser expressa pelo provider atual? | `ja-suportado-so-ux` |
| Falta um outcome publico estavel para execucao privada? | `lacuna-real-de-contrato` |
| Falta evidence/checker para impedir uso inseguro em escala? | `lacuna-real-de-contrato` ou `migration-skill-gap` |

Novo contrato so deve nascer para lacuna real comprovada.

## Impacto Esperado

Subprojeto canonico afetado:

- `praxis-metadata-starter`.

Consumidores impactados em caso de implementacao futura:

- `praxis-api-quickstart`, como prova operacional com provider fake ou host de
  referencia;
- `praxis-ui-angular`, se Cockpit/runtime precisar mostrar command policies;
- `praxis-ui-landing-page`, se a documentacao publica passar a ensinar a
  capacidade;
- hosts corporativos que hoje implementam mutacoes privadas atras de recursos
  Praxis.

Artefatos derivados a revisar em implementacao futura:

- `README.md`;
- `CHANGELOG.md`;
- `docs/guides/**`;
- `docs/spec/**`, se houver metadata publica nova;
- exemplos HTTP e playgrounds, se a superficie for publicada para consumo
  externo;
- skills Praxis e skills de migracao que orientam comandos de escrita.

Risco de breaking change:

- baixo para este RFC documental;
- medio/alto para uma implementacao futura se alterar shape de capabilities,
  schemas, actions ou public APIs. A estrategia recomendada em beta e migracao
  canonica limpa, com consumers atualizados no mesmo ciclo.

## Roadmap Recomendado

### Fase 0 - RFC e inventario

- Registrar este RFC no owner canonico.
- Inventariar mais dois consumidores ou casos equivalentes alem do caso
  original.
- Confirmar se a lacuna e SPI, guia, checker ou combinacao dos tres.

### Fase 1 - Spike Java neutro

- Criar um provider fake em teste ou fixture interna. **Feito.**
- Provar `READ_AFTER_WRITE`, `NO_CONTENT`, erro publico e availability dinamica.
  **Feito em teste unitario focal.**
- Provar sanitizacao de evidence privada. **Feito.**
- Nao alterar shape HTTP/discovery antes de aceite operacional. **Mantido.**

### Fase 2 - Integracao resource-oriented minima

- Criar adaptador de response publica para outcomes/policies. **Feito como
  building block opt-in.**
- Criar helper opt-in no controller resource-oriented para reduzir boilerplate
  de actions reais sem publicar dispatcher generico. **Feito.**
- Avaliar se `Abstract*ResourceController` deve delegar create/update/delete a
  `GovernedResourceCommandExecutor` por composicao opcional ou se o executor
  deve permanecer como building block para hosts. **Mantido como opt-in para
  evitar migracao automatica prematura do CRUD canonico.**
- Integrar ao baseline `resource + surfaces + actions + capabilities` somente
  se houver metadata publica nova necessaria. **Sem metadata nova nesta fase.**
- Garantir que actions/workflows continuem apontando para endpoints reais e
  schemas canonicos. **Provado com controller piloto em teste.**

### Fase 3 - Prova operacional

- Validar no `praxis-api-quickstart` com provider fake ou host de referencia.
- Validar impacto no Angular somente se houver metadata nova consumida pelo
  runtime.
- Publicar guia e exemplo apenas depois da prova operacional.

### Fase 4 - Tooling e skills

- Atualizar checkers para detectar locators privados e evidence faltante.
- Atualizar skills canônicas para orientar migradores por command contract,
  provider e evidence, em vez de repetir plumbing por tela.

## Criterios De Aceite

Um agente Praxis sem acesso a qualquer backend privado deve conseguir validar:

1. um recurso publica create/update/delete ou workflow action com schemas,
   links e capabilities canonicos;
2. a disponibilidade muda por provider sem vazar detalhe privado;
3. uma execucao `READ_AFTER_WRITE` devolve DTO publico;
4. uma execucao `NO_CONTENT` nao cria resposta ad hoc;
5. validation, permission, conflict, not-found e unexpected retornam shape
   publico estavel;
6. Cockpit/IA conseguem explicar a operacao sem conhecer o backend privado;
7. o schema publico nao expoe locators privados;
8. um host externo consegue implementar provider sem alterar o contrato
   publico Praxis.

## Validacao Desta Entrega

Validacao executada:

- leitura do `AGENTS.md` local do `praxis-metadata-starter`;
- leitura das superficies existentes de command service, legacy-backed command,
  duplicate draft, operation availability e workflow action;
- criacao deste RFC documental no owner canonico;
- implementacao do spike Java neutro em `org.praxisplatform.uischema.command`;
- testes focais de availability, response policy, outcomes publicos,
  sanitizacao de evidence e excecoes inesperadas;
- testes focais do adapter HTTP para `200`, `202`, `204`, `400`, `403`, `404`,
  `409`, `412` e `500` derivados dos outcomes governados.

Validacao nao executada:

- nenhuma suite Angular, HTTP ou browser;
- nenhuma alteracao em shape HTTP, `/schemas`, `/actions`, `/surfaces` ou
  `/capabilities`;
- nenhuma prova ainda no `praxis-api-quickstart`.
- nenhum teste com provider fake.

Motivo: esta entrega e uma formalizacao documental do P0 antes de qualquer
alteracao de contrato ou codigo.
