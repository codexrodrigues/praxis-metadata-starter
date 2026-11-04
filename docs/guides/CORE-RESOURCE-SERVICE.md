# Core resource service e comandos versionados

`AbstractBaseQueryResourceService` concentra consultas, enriquecimento e exportação tanto para
serviços mutáveis quanto read-only. `AbstractBaseResourceService` acrescenta o ciclo transacional
de escrita. O host fornece regras de domínio, escopo autenticado e mapeamento; não precisa
reimplementar a infraestrutura descrita abaixo.

## Identidade, cursor e posição

O opt-in continua sendo `supportsCursorPagination()`. `getIdentityPaths()` detecta `@Id`,
`@EmbeddedId` e identidades com múltiplos componentes; pode ser sobrescrito para mapeamentos
explícitos. O sort público `id` expande para esses componentes. O cursor acrescenta os componentes
da identidade como desempate às demais ordenações.

Os tokens usam `CursorEncoder.BASE64_URL` sem padding, com valores separados por `:`; `%`, `:`
e `~` literais são escapados e `~` representa null. Na ordenação apenas pela identidade, o token
contém exatamente seus componentes. Nas demais ordenações contém a tupla completa de ordenação,
inclusive o desempate. Isso permite continuar depois de excluir a linha que originou o token.
Use o mesmo filtro e sort na navegação; reinicie os tokens quando qualquer um mudar. Os tokens
são opacos para consumidores e não concedem acesso: o servidor reaplica o escopo em cada consulta.
A ordenação determinística não cria um snapshot MVCC entre requests. Inserções e alterações
concorrentes nos campos de sort podem mudar o conjunto observado. Relatórios de auditoria que
exijam um instante contábil precisam de uma projeção/snapshot governado pelo domínio.
Ordenação por coleção `1:N` é rejeitada; publique uma projeção com valor escalar agregado.

No fallback com repositório JPA e sem EntityManager no serviço, a consulta usa
a mesma ordenação Criteria explícita do boundary, incluindo precedência de
nulos e navegação reversa; o Sort nativo do banco não decide a ordem. Consultas
`distinct` nesse fallback exigem a composição com EntityManager, pois o caminho
canônico precisa projetar as expressões de ordenação junto da entidade. Essa
composição é obrigatória para filtros relacionais que deduplicam resultados.

`after` e `before` são exclusivos. A consulta usa predicado lexicográfico, janela de `size + 1`
e ordenação invertida para navegação anterior. Não usa posição em memória nem deslocamento SQL.
Nulls são ordenados explicitamente: por padrão ao final, ou conforme `Sort.NullHandling` explícito.
`locate` consulta o item dentro do filtro/escopo e conta em SQL os registros anteriores; seu índice
é baseado em zero. Não materializa a coleção inteira.

## Autorização e enriquecimento

- `resolveResourceFilterAccessScope()` é obrigatório para hosts que isolam linhas. Governa
  coleções, inclusão de IDs no filtro, cursor, locate, option sources, stats e exportação.
- `assertEntityAccessAllowed(E)` executa depois do lookup por chave em leituras de item, by-ids,
  update e exclusões. O host lança sua exceção de acesso negado (`403`). Linha inexistente é `404`;
  leituras by-ids e exclusões exigem existência de todos os IDs informados. Isso não se aplica a
  `includeIds` do filtro, que ignora chaves fora do escopo. Exclusão múltipla valida os alvos antes
  de executar hooks, adquire locks em ordem estável e preserva a ordem solicitada ao executar `beforeDelete`/`afterDelete` por item na mesma transação.
- `enrichResponses(List<ResponseDTO>)` recebe um lote de projeções para enriquecimento em batch.
  É chamado uma vez por resultado de item/coleção, filtro, cursor e exportação; também integra
  as respostas de create/update. Não substitui autorização nem pode alterar a revisão persistida.

## Exportação governada

O executor é fornecido pela auto-configuração existente, com CSV, JSON e Excel. O serviço suporta
export quando há executor e pelo menos um campo permitido. O limite padrão síncrono é 10.000
linhas por formato; sobrescreva `maxExportRows(format)` para um limite explícito do recurso.
`maxRows` deve ser positivo e não pode ultrapassar o limite publicado em capabilities.

| Escopo | Conjunto consultado |
| --- | --- |
| `AUTO` | `SELECTED` se há seleção; caso contrário `FILTERED` |
| `SELECTED` | Chaves explícitas dentro do escopo obrigatório; seleção de todos os correspondentes reaplica o filtro; subtrai `excludedKeys` |
| `FILTERED` | Filtro funcional e escopo obrigatório |
| `CURRENT_PAGE` | Filtro e escopo com `pageIndex` baseado em zero (ou `pageNumber` baseado em um) e `pageSize` positivo |
| `ALL` | Todo o escopo obrigatório, independentemente do filtro funcional |

Chaves simples e compostas são convertidas pelo contrato canônico. A resposta publica
`metadata.effectiveScope`, `metadata.maxRows` e `metadata.truncated`. Não há carga integral para
aplicar o limite depois. `resolveExportSort` aceita `Sort`, strings `campo,asc|desc`, listas dessas
strings e entradas `{property, direction}`. Caminhos inválidos falham na execução JPA.

`defaultExportFields()` resolve o tipo de resposta e seleciona somente propriedades anotadas com
`@UISchema(tableHidden = false)`, por `order`. Infere data (`dd/MM/yyyy`), data/hora
(`dd/MM/yyyy HH:mm`), moeda (`CURRENCY_INPUT`, sem inferir uma moeda de negócio) e labels de booleanos. `exportFields()` é o
hook de curadoria explícita. `governedExportFields()` reaplica a restrição das propriedades do DTO,
mesmo a listas sobrescritas: `@DomainGovernance(exportable = false)` e a tag `NON_EXPORTABLE`
impedem exportação. O executor reconcilia os campos do cliente com essa allowlist, preservando o
value path e a apresentação canônicos. `tableHidden` controla a seleção padrão; não é autorização.

A governança percorre tipos genéricos em listas, mapas, arrays, opcionais e objetos de valor,
inclusive getters e subtipos concretos. Exportar um objeto pai inteiro é bloqueado se algum filho
for restrito; um caminho explícito para um filho permitido continua válido. Tipos opacos
(`Object`, mapas sem tipo, `JsonNode`, `@JsonAnyGetter`/`@JsonValue`) exigem projeção tipada de
exportação. Serializadores customizados do host devem respeitar a mesma governança.

A curadoria em `exportFields()` deve declarar moeda, locale e fuso quando houver significado
de negócio. Sem moeda explícita, o engine usa a moeda do locale efetivo da exportação;
`BigDecimal` isolado não é classificado automaticamente como valor monetário.
Quando há corte por limite, `warnings`/`X-Export-Warnings` e `X-Export-Truncated` informam o corte.
O consumidor deve mostrar o aviso junto ao download e permitir refinar filtro/seleção. Em hosts
com CORS, exponha esses headers na configuração existente; o starter não redefine o CORS do host.

## Draft efetivo para validação de campos dinâmicos

O request é capturado e suas guardas primitivas são verificadas antes dos hooks.
Quando JSON Logic observa `draft`, `prepareCreateValidationDraft(dto, candidate)`
pode hidratar o candidato novo e devolver uma projeção DTO autorizada.
`prepareUpdateValidationDraft(id, candidate, dto)` recebe uma cópia destacada,
após acesso e If-Match, conservando o estado persistido em `entity`. Os defaults
preservam o request; as regras são avaliadas antes dos hooks de negócio/save.
Os preparos não podem persistir, alterar entidades gerenciadas ou executar efeitos.

Escritas especializadas reaproveitam
`validatePreparedCustomFieldsForUpdate(prepared, id, existing, dto)` e preservam
os valores read-only retornados. A avaliação de lote usa a mesma preparação.
O [guia de regras](GOVERNED-RULE-BRIDGE.md) define omissão opcional, raízes e a
admissão compartilhada `CustomFieldValidationRules.validate`.

## Atualizações e comandos versionados

Serviços optam pelo contrato `VersionedCreateUpdateResourceService`; a base fornece o método
concreto `update(id, dto, precondition)`. `getEntityResourceVersion` detecta `@Version` numérico
em field ou getter, inclusive herdado, com cache de introspecção por classe. Um recurso versionado
sem revisão persistida falha e reverte a mutação.

O ciclo exige transação de escrita e contexto de persistência limpo antes das primeiras leituras,
adquire `PESSIMISTIC_WRITE`, verifica acesso e compara a revisão sob o lock. Executa os hooks e
o mapper, preserva custom fields readonly, salva, faz flush/refresh e captura corpo e revisão
após o último hook modificador. `usePessimisticLockOnVersionedWrite()` permite optar por controle
otimista, mantendo a comparação e o flush antes de sucesso. Sem EntityManager, a porta de
repositório continua utilizável, mas o adaptador deve fornecer sua garantia transacional.

Comandos/intents implementam um método público `@Transactional` no serviço e chamam
`executeVersionedMutation(id, precondition, Consumer<E>)`. A variante `BiConsumer<E,E>` recebe
uma cópia anterior e a entidade atual. A cópia padrão separa coleções/mapas/arrays aninhados,
JSON, datas e `@Embeddable`, preservando ciclos. Associações lazy e objetos mutáveis não
suportados falham antes do mutator; sobrescreva `snapshotBeforeMutation` com uma cópia anterior
independente e limitada ao domínio. Evite inicializar grafos indiscriminadamente. O helper não inventa um
  `UpdateDTO` e não executa hooks de edição ordinária em nome de um comando de negócio.
  Com `@CustomFields` e o validador nativo configurado, o helper captura o estado anterior e
  valida a projeção DTO resultante antes do save final; `entity` representa o estado anterior
  autorizado e `draft` a projeção atual. Efeitos do callback devem participar da mesma transação
  para que uma rejeição também reverta eventuais flushes intermediários.
  Para intents tipados, `ResourceStateCopySupport.copySuppliedPatchProperties(patch, dto, objectMapper)`
  copia apenas propriedades fornecidas, preservando omissão, `null`, `false` e zero. A
  presença usa o nome público JSON, incluindo `@JsonProperty`, aliases admitidos e a
  estratégia de nomes do mapper HTTP. Os setters de `PartialUpdateInput` registram
  o nome público canônico, mesmo quando o JSON usa um alias. A variante de dois
  argumentos usa a configuração Jackson padrão. A autorização dos campos e sua
  nulabilidade continuam no contrato e no serviço do domínio.

Guardas de coleção podem sobrescrever `assertEntitiesAccessAllowed(Collection<E>)`
para carregar fatos compartilhados de autorização em lote. O default chama a
guarda individual para cada entidade. Página, cursor, includeIds e exportação
usam essa fronteira; o escopo SQL obrigatório de `ResourceFilterAccessScope` e a
guarda de lookup individual continuam exigidos. Não mantenha grants num cache
global entre requisições ou órgãos.

No controller, use `executeVersionedItemCommand(actionId, id, ifMatch, idempotencyKey, payload,
mutation)` ou `executeVersionedItemPatch(intentId, id, ifMatch, mutation)`. O executor governado
aplica availability; a mutação continua responsável pela autorização transacional. O envelope
mantém o `ETag` forte da revisão capturada e `Cache-Control: no-transform`, sem releitura para
obter uma revisão mais recente. Preconditions ausentes, inválidas e obsoletas preservam
respectivamente `428`, `400` e `412`. A base verifica também que a precondição está vinculada
ao mesmo ID passado ao método; uma precondição de outro item nunca autoriza a escrita.

### Recibos idempotentes opcionais

Registre um bean `ResourceCommandIdempotencyStore`. A implementação PostgreSQL
`JdbcResourceCommandReceiptStore` recebe `JdbcTemplate`, o **mesmo transaction manager da
mutação** e `ObjectMapper`. Instale explicitamente `classpath:db/praxis-command-receipts.sql`
como migração do host; a biblioteca não cria tabelas nem aplica migrações automaticamente.
Um construtor adicional aceita um nome validado de tabela, inclusive qualificado por schema.

O recibo isola chave por escopo confiável, ator autenticado, recurso, item e comando. O fingerprint
inclui payload e If-Match. Um advisory lock transacional PostgreSQL serializa retries antes do lock
de domínio. Mutação e gravação do corpo/revisão são uma única transação `READ COMMITTED`.
O construtor exige o mesmo objeto DataSource do transaction manager JDBC/JPA, além do mesmo
banco; a execução verifica a conexão efetivamente vinculada, writable e `READ COMMITTED`.
Transações externas readonly ou com outro isolamento são rejeitadas, inclusive quando o
isolamento efetivo difere do default declarado no Spring. Com JPA, um contexto sujo é rejeitado
antes da consulta de autorização para impedir autoflush anterior à governança.

Replay devolve corpo e revisão históricos antes das guardas de uma nova transição; não renova o
ETag. Ainda verifica o acesso atual ao item, dentro da transação do recibo. Implementações da
SPI devem executar `authorize` antes de consultar/devolver recibos, tanto na primeira execução
quanto no replay, e executar `mutation` apenas quando não houver recibo. A mesma chave com payload ou If-Match diferente
retorna `409`. Uma chave enviada sem store configurado retorna `503`, sem executar a mutação.
`resourceCommandActor()` usa o principal Servlet; hosts com outro mecanismo de autenticação
devem fornecer sua identidade confiável nesse hook. O fallback `anonymous` só distingue o
escopo público, sem separar pessoas; recursos corporativos autenticados devem validar o principal
real e nunca aceitar ator/tenant livremente no payload. Retenção/limpeza de recibos e coordenação
de efeitos externos pertencem ao host; não remova um recibo enquanto o retry ainda for válido.

## Snapshots, stats e authoring

`toResourceStateSnapshot(E)` fornece o estado de domínio para availability. O provider padrão
`ResourceServiceDelegatingStateSnapshotProvider` procura um serviço pelo seu `getResourceKey()`
(derivado de `resolveResourceKey()`), converte o ID e delega ao método transacional que verifica
existência e acesso. Declare a mesma chave semântica do controller no serviço/entidade ou no hook
de resolução. Chaves ambíguas falham explicitamente. O provider é substituível por bean do host;
`ResourceStateSnapshotScope` mantém a deduplicação por request/recurso.

Os quatro modos de stats retornam `AUTO` quando o registry não é vazio. Elegibilidade dos campos,
infraestrutura e limites continuam determinando as capabilities executáveis.

`EntityLookupDescriptor.directoryBuilder(entityKey, codePath, descriptionPaths...)` fornece
selection policy, capabilities de leitura, drawer de detalhe, display directory e filtering.
Informe status, política de seleção, dependências e surfaces reais do domínio. A surface default
`detail` precisa existir no recurso referenciado. `LookupCapabilities.standardDirectory()` não
anuncia create/edit, favoritos nem histórico privado.

`ReactiveDeterminationDefinition.forCrudResource(id, createOperationId, updateOperationId,
determinationOperationId)` inicia o builder com os dois modos de formulário e IDs explícitos.
Informe fontes, inputs, outputs e provenance. Os bindings usam JSON Pointer; as rotas e schemas
continuam sendo resolvidos pelo compilador canônico, sem heurística de nomes.

`filterRelatedChildResource` valida o pai, limita `page >= 0` e `size` entre 1 e 200, vincula o
FilterDTO filho e retorna a página com link `collection`. O serviço filho mantém seu próprio
escopo de acesso. `X-Data-Version` vem do serviço filho, não do pai. `childPath` é o caminho local canônico fornecido pelo host.
