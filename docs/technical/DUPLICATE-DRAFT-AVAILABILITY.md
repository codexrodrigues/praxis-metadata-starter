# Disponibilidade de duplicate-draft

Complementa [suporte estrutural](DUPLICATE-DRAFT-STRUCTURAL-SUPPORT.md). O contrato
continua sendo `POST /{resource}/{id}/duplicate-draft`, retornando um draft editável,
sem persistência. O documento OpenAPI e os schemas são estruturais e não variam
por usuário. `canonicalOperations` informa suporte; `operations.*.availability`,
as actions e os links representam a disponibilidade contextual.

## Composição canônica

`ResourceOperationAvailabilityEvaluator` compartilha a avaliação entre as guardas
de `DefaultCapabilityService`, os snapshots e `ActionCatalogService`. Não chama
capabilities para produzir um catálogo. A ordem da duplicação é:

1. POST publicado e `ResourceStructuralCapabilities.duplicateDraft=true`.
2. Criação estruturalmente suportada e permitida no escopo COLLECTION.
3. Identificador de origem presente e operação `view` suportada/permitida em ITEM.
4. Disponibilidade própria da operação `duplicate-draft` em ITEM.
5. Regras declaradas da action real descoberta por `@WorkflowAction`.

Uma negação em qualquer etapa impede a disponibilidade executável. `create` e
`view` incorporam também a availability de suas surfaces, como os snapshots já
faziam. O snapshot de item incorpora a surface de criação quando há duplicação
suportada; a surface de coleção não é publicada como surface de item. A seleção
de create/view é compartilhada, preservando a precedência existente dos IDs
create/detail/view e a alternativa de primeira surface elegível.

O contexto do provider recebe `preferredMethod`, `preferredRel` e `supported`
extraídos da operação, tanto em guardas diretas quanto no snapshot. Criação mantém
`scope=COLLECTION`, sem ID ou estado da origem, inclusive dentro do snapshot de
item. `view` recebe GET/self e duplicação POST/duplicate-draft. Não há inferência
de permissões a partir de nomes de roles ou de botões.

As regras da action podem restringir adicionalmente o resultado. A avaliação
restrita `evaluateDeclaredDuplicateDraftAvailability` consulta apenas registry,
contexto e evaluator; não monta outro catálogo nem chama capabilities. A
annotation, operação real, schemas e demais dados úteis da action são preservados.
No catálogo documental `/schemas/actions`, não há ID: a definição continua visível,
mas não é executável (`resource-context-required` quando as condições anteriores
permitem chegar a essa etapa). O catálogo contextual `/{id}/actions` avalia a origem.

## Falhas, estado e fronteiras

- Serviço com suporte false continua produzindo 404 antes de preparar qualquer
  draft. Mapping sozinho, NoOp ou ausência de opt-in não habilitam suporte.
- Negações ou falhas de provider/action na composição da duplicação retornam a
  razão pública `duplicate-draft-unavailable`, sem transportar dados privados da
  política, da origem ou detalhes de exceções. Ausência de suporte tem a razão
  estrutural `operation-not-supported`; ausência de contexto tem
  `resource-context-required`. Razões não descrevem a existência de uma origem.
- Resultado nulo do provider não autoriza um pré-requisito de duplicação. A
  semântica anterior de resultado nulo para outras operações permanece preservada.
- `ResourceStateSnapshotScope` é compartilhado por guardas/links/preparo do
  controller e por cada composição do catálogo/snapshot. Não há cache de decisões
  ou estado entre requisições. Implementações customizadas de resolvers devem
  respeitar a mesma fronteira para manter a deduplicação.
- A captura de operações usa o documento e o read fence existentes. Providers e
  regras executam fora do lock de schema e de transações abertas pela descoberta;
  o fence final rejeita mudança da publicação. Não se abre transação de persistência.
- O controller exige CapabilityService configurado, resourceKey/path e ID presentes,
  além de decisões não nulas e permitidas de create, view e duplicate-draft antes do
  preparo e da publicação do link. Os helpers opcionais das demais operações não
  são utilizados para autorizar duplicação. Suporte false retorna 404; indisponibilidade retorna 403 com razão
  genérica. Não há chamada automática a create/update/delete.

A autorização efetiva por objeto, tenant/escopo e campo continua no backend do
host. O serviço `duplicateDraft(sourceId)` deve carregar apenas uma origem
permitida, recusar origens inexistentes/inacessíveis de forma indistinguível e
copiar apenas campos autorizados segundo a política de domínio. Um snapshot
permitido não prova existência nem substitui essas verificações. O starter não
pode impedir uma implementação incorreta da porta de persistir ou revelar dados.
O POST final de criação revalida autorização, unicidade, invariantes e campos;
nenhuma decisão de discovery constitui autorização reutilizável.

## Wiring e adoção

A auto-configuração existente fornece ao catálogo o mesmo conjunto canônico de
resolver OpenAPI, provider de operações, provider de estado e catálogo de surfaces
usado pelas capabilities quando esse colaborador está presente. Um registry
customizado de actions sem MVC/surfaces continua compondo seu catálogo comum; nesse
contexto o avaliador operacional de duplicação não é criado e duplicate-draft fica
negada. Não se passa surfaces null para criar um avaliador permissivo. Um catálogo
customizado substitui o default, sem segunda instância; surfaces ambíguas produzem
erro explícito de composição. Não há novo bean de auto-configuração/import ou payload
HTTP. Construtores manuais de `ActionCatalogService` sem o avaliador operacional
continuam úteis para outras actions, mas duplicação falha fechada; consumidores
que construam esse serviço manualmente devem fornecer a composição pelo construtor
completo. Não substituir o NoOp estrutural por fallback permissivo.

CapabilityService ausente ou suas decisões nulas são diferentes de um
ResourceOperationAvailabilityProvider NoOp intencional: o segundo pode integrar uma
composição canônica completa para um host sem política dinâmica, mantendo suporte,
identidade, regras das actions e as verificações do serviço de domínio. O controller
de duplicação não muda os fallbacks das demais operações CRUD.

Quickstart (`LegacyPayCode` e `Funcionario`) e Showcase (`Cargo`) precisam adotar
os tipos neutros e a coordenada candidata do starter nos cortes próprios. O
provider de Quickstart usa `preferredMethod`: sua política passa a receber essa
metadata nas guardas, mas o host ainda precisa demonstrar a política por objeto,
campos e escopo, não persistência do preparo, descarte sem gravação e revalidação
no POST final. A prova com mocks/MockMvc no starter não certifica esses pilotos.

Angular deve respeitar ausência de link e availability negada, preservar o schema
de criação e não executar fallback que contorne a negação. Robustez, experiência
visual e modos de abertura pertencem aos cortes de runtime próprios. Config,
corpus HTTP, landing e guidance amplo devem ser reconciliados no fechamento do
plano; esta nota não representa adoção, publicação ou aceite integrado.
