# Guia 06 - Redacao Semantica de Annotations para IA

## Objetivo

Este guia define como pessoas e LLMs devem preencher textos semanticos em
annotations e descricoes publicadas pelo `praxis-metadata-starter` e pelos hosts
que o consomem.

Ele complementa:

- `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.md`

O foco aqui nao e decidir se uma operacao deve existir. O foco e escrever os
campos `summary`, `description`, `title`, `intent`, `tags`, `@Schema` e
metadados equivalentes de forma util para:

- OpenAPI
- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `/schemas/domain`
- `capabilities`
- catalogos documentais
- RAG
- assistentes de IA
- geracao futura de UI sob demanda

## Regra central

Texto semantico e contrato de produto.

Nao trate `description` como legenda tecnica, traducao do nome do campo,
comentario cosmetico ou texto gerado por heuristica. A descricao deve explicar o
que o elemento representa no dominio, em qual contexto e usado, quais limites
tem e como se relaciona com o contrato real publicado pelo recurso.

## Contrato estrutural vs texto semantico

O texto semantico nao muda a fonte da verdade estrutural.

No baseline atual:

- `/schemas/filtered` e a fonte estrutural canonica de request e response.
- `/schemas/catalog` resume operacoes e liga para schemas filtrados; serve a
  discovery documental e a indexacao (por exemplo RAG).
- `/schemas/surfaces` publica discovery semantico de experiencias de UI.
- `/schemas/actions` publica discovery semantico de comandos de negocio.
- `/schemas/domain` agrega vocabulario de dominio derivado das fontes acima,
  em formato orientado a runtime, RAG e LLMs; nao substitui o filtrado nem os
  catalogos de surface/action.
- `GET /{resource}/capabilities` e `GET /{resource}/{id}/capabilities`
  agregam disponibilidade operacional (operacoes canonicas, surfaces e actions
  referenciadas); nao publicam um payload paralelo ao contrato HTTP.

### Mapa rapido de superficies

| Superficie | Papel principal | Consumo tipico |
| --- | --- | --- |
| `/schemas/filtered` | Fragmento estrutural da operacao + `x-ui` | UI dinamica, validacao de forma, hosts que hidratam schema para IA |
| `/schemas/catalog` | Lista enxuta de endpoints com links para o filtrado | Discovery, indice documental, pipelines RAG |
| `/schemas/surfaces` | Metadados de experiencia apontando para operacao e schema canonicos | Navegacao semantica, discovery de UI |
| `/schemas/actions` | Metadados de comando apontando para operacao e schemas canonicos | Discovery de workflow |
| `/schemas/domain` | Nos, arestas, evidencias e glossario derivados | Assistencia semantica, politicas, ingestao em stores de conhecimento |
| `.../capabilities` | Snapshot de disponibilidade agregada | Clientes que precisam saber o que e permitido no contexto |

### Pipeline de origem (visao de implementacao)

Em alto nivel: Springdoc gera o OpenAPI por **grupo**; o starter obtem e cacheia
esse JSON (`OpenApiDocumentService`), resolve a operacao canonica
(`CanonicalOperationResolver`) e deriva URLs e IDs de schema alinhados ao
filtrado (`SchemaReferenceResolver`). As annotations Praxis em metodos reais
alimentam registries que expoem surfaces e actions. O catalogo de dominio junta
esse material com propriedades do schema OpenAPI (por exemplo `description` de
campos). Ao revisar texto, pense em **onde ele entra nesse grafo**, nao apenas
no Swagger UI.

### Fallback automatico em `/schemas/catalog`

Se `summary` ou `description` da operacao OpenAPI estiverem vazios, o starter
preenche valores genericos a partir do verbo HTTP e do path (por exemplo
resumos do tipo "Filtrar X" ou "Endpoint para ..."). Isso **nao** e neutro:
entra no mesmo payload usado para discovery e pode competir com texto curado em
buscas. Prefira sempre `@Operation(summary=..., description=...)` conscientes.

### O que o snapshot de `capabilities` carrega

O snapshot agrega **disponibilidade**: operacoes canonicas, surfaces e actions.
Titulos e descricoes uteis para humanos e IA costumam vir dos itens de surface
e action embutidos, nao de um campo isolado inventado no conceito de capability.
Melhorar `@UiSurface`, `@WorkflowAction` e `@Operation` melhora o que esse
endpoint devolve em conjunto.

Consequencias:

- Nunca documente uma `surface` ou `action` como se ela tivesse payload proprio
  paralelo.
- Nunca use `description` para prometer campo, request, response ou efeito que
  nao existe no endpoint real.
- Nunca trate `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`,
  `/schemas/domain` ou `capabilities` como substitutos de `/schemas/filtered`.
- Sempre escreva textos que ajudam discovery sem redefinir contrato.

## Fontes obrigatorias antes de escrever

Antes de preencher ou revisar annotations, leia o recurso real.

No minimo:

1. Controller e metodo HTTP real.
2. DTO de request.
3. DTO de response.
4. FilterDTO, quando existir.
5. Entidade ou view/projecao.
6. Service ou regra de negocio relevante.
7. Relacionamentos expostos por `...Id`, campos desnormalizados e options.
8. Constraints de validacao.
9. Anotacoes de UI, governanca, filtro, analytics ou workflow ja existentes.

Se a descricao nao pode ser defendida a partir dessas fontes, ela nao deve ser
escrita como fato.

## O que nunca fazer

Nao gere descricoes em lote a partir de:

- `@UISchema(label = "...")`
- nomes de propriedade em camelCase
- nomes de metodo
- nomes de endpoint
- sinonimos genericos
- listas fixas de palavras-chave por dominio

Esse padrao produz texto aparentemente rico, mas semanticamente fraco. Ele
degrada OpenAPI, catalogos, RAG e assistentes, porque aumenta o vocabulario sem
aumentar a verdade de dominio.

## Principios de redacao

### 0. Preserve `resourceKey` como identidade semantica

Antes de redigir annotations do recurso, confira se o `resourceKey` representa a
semantica canonica do recurso, nao apenas a URL.

Exemplo:

```java
@ApiResource(
        value = ApiPaths.HumanResources.FUNCIONARIOS,
        resourceKey = "human-resources.funcionarios"
)
```

O `resourcePath` pode mudar por roteamento, versao operacional ou reorganizacao
de paths. O `resourceKey` so deve mudar quando a identidade semantica do recurso
mudar.

Isso importa porque `@ResourceIntent`, `@UiSurface`, `@WorkflowAction`,
`/schemas/surfaces`, `/schemas/actions` e `capabilities` usam essa identidade
para discovery.

### 1. Escreva a acao de negocio, nao a tecnica HTTP

Prefira:

```text
Filtrar folhas por competencia, colaborador e valores financeiros
```

Evite:

```text
Executar POST /filter com paginacao
```

O path, verbo HTTP, paginacao e schema ja aparecem no contrato tecnico. O texto
semantico deve explicar o motivo de negocio.

### 2. Seja especifico sem inventar regra

Boa descricao:

```text
Lista folhas de pagamento por funcionario, ano, mes, data de pagamento, salario
bruto, descontos e salario liquido para conferencia mensal, fechamento
financeiro e acompanhamento operacional da folha.
```

Descricao ruim:

```text
Lista folhas para auditoria completa, compliance trabalhista, provisoes,
beneficios e gestao bancaria.
```

A segunda pode ate parecer executiva, mas introduz temas que podem nao estar no
DTO, no filtro ou no service.

### 3. Nao polua busca semantica com negacoes fora de escopo

Evite descricoes como:

```text
Atualiza contato sem alterar salario, cargo, departamento, lotacao ou vinculo.
```

Mesmo sendo uma negacao, a descricao adiciona `salario`, `cargo`,
`departamento`, `lotacao` e `vinculo` ao contexto semantico da operacao. Um LLM
ou buscador vetorial pode recuperar essa operacao para pedidos que nao dizem
respeito a ela.

Prefira:

```text
Atualiza dados de contato e apresentacao do colaborador para manter canais de
comunicacao, identificacao visual e informacoes cadastrais de perfil.
```

### 4. Use o vocabulario real do contrato

Se o DTO possui `ativo`, nao escreva `status` como se houvesse um enum de status.
Use `situacao ativa`, `indicador ativo` ou `flag de atividade`, conforme o
dominio.

Se o DTO possui `pago: Boolean`, prefira `situacao de pagamento` ou
`indicador de liquidacao`, nao `workflow de pagamento`, a menos que exista fluxo
real.

### 5. Preserve a fronteira entre resource, surface e action

- `resource` define payload e schema.
- `@ResourceIntent` nomeia uma intencao parcial ainda resource-oriented.
- `@UiSurface` descreve uma experiencia de UI descoberta semanticamente.
- `@WorkflowAction` descreve comando explicito de negocio.
- `capabilities` agrega disponibilidade; nao redefine contrato.

Nao use uma `surface` para esconder action. Nao use `WorkflowAction` para
rotular CRUD comum. Nao descreva `capability` como se fosse fonte de payload.

### 6. Escreva em linguagem de dominio

Evite termos internos quando houver termo de negocio:

| Evite | Prefira |
| --- | --- |
| endpoint | operacao, consulta, comando |
| payload | dados enviados, contrato de entrada |
| lookup | selecao, busca, catalogo, opcoes |
| dashboard | painel |
| surface | experiencia, formulario, visao, area de trabalho |
| CRUD | cadastro, manutencao, consulta, remocao |

Use termos tecnicos apenas quando eles fazem parte do contrato publico ou quando
a precisao tecnica e necessaria.

### 7. Mantenha PT-BR consistente

Use acentos e portugues correto em textos publicos. O contrato pode conter nomes
tecnicos em ingles (`resourceKey`, `id`, enum, path), mas `title`, `summary` e
`description` devem ser escritos como documentacao de produto.

## Como preencher `@Operation`

### `summary`

O `summary` deve ser curto, orientado a acao e especifico o bastante para
diferenciar operacoes semelhantes.

Formato recomendado:

```text
<verbo de negocio> <objeto de dominio> <recorte relevante>
```

Exemplos:

```text
Filtrar funcionarios por identificacao, lotacao e situacao ativa
Obter detalhe analitico de folha de um colaborador
Agrupar salario liquido por universo, perfil ou composicao de folha
Aprovar eventos pendentes de folha em lote
```

Evite:

```text
Buscar por ID
Listar registros
Atualizar dados
Executar action
Time-series stats
```

### `description`

A `description` deve responder:

1. Quais campos ou dimensoes reais participam da operacao?
2. Qual caso de uso a operacao atende?
3. Quais limites, pre-condicoes ou efeitos reais importam?
4. Que leitura de negocio o consumidor pode fazer?
5. Ha relacao com outro recurso ou projection?

Modelo:

```text
<O que retorna/altera/executa> com <campos reais relevantes> para
<casos de uso reais>, <decisoes ou fluxos apoiados> e <contexto operacional>.
```

Exemplo:

```text
Retorna a folha mensal de um funcionario com competencia, valores consolidados,
descontos, salario liquido e data de pagamento para inspecao financeira,
conciliacao e composicao de visoes de RH.
```

## Como preencher `@ResourceIntent`

Use `@ResourceIntent` quando o metodo representa uma escrita parcial
resource-oriented, com DTO proprio e semantica propria.

### `id`

Deve ser estavel, curto e semanticamente local ao recurso.

Bom:

```java
id = "profile"
id = "payment-schedule"
id = "contact-preferences"
```

Ruim:

```java
id = "patch1"
id = "custom-update"
id = "form-profile-v2"
```

### `title`

Use um titulo humano que indique a finalidade.

Prefira verbos de negocio claros:

```text
Atualizar perfil de contato
Ajustar agenda de pagamento
Manter preferencias de notificacao
```

`Manter` pode ser usado quando a operacao cobre criacao e atualizacao conceitual
ou quando o dominio fala em manutencao cadastral. Para um `PATCH` especifico,
`Atualizar` ou `Ajustar` costuma ser mais preciso.

### `description`

Explique a finalidade da escrita parcial sem listar campos fora de escopo por
negacao.

Boa:

```text
Atualiza dados de contato e apresentacao do colaborador para manter canais de
comunicacao e informacoes cadastrais de perfil.
```

Ruim:

```text
Atualiza contato sem alterar salario, cargo ou departamento.
```

Nao omita o recorte afetado. A descricao deve deixar claro qual parte do mesmo
recurso esta sendo mantida, mas sem transformar campos fora de escopo em
palavras-chave da operacao.

### `order`

Use um valor estavel quando o recurso expuser varias intencoes de escrita
parcial. A ordem sugere prioridade em catalogos e discovery documental futuros;
evite reordenar sem motivo de produto.

## Como preencher `@UiSurface`

`@UiSurface` descreve uma experiencia de UI sobre uma operacao real.

Ela nao define payload. Ela aponta para operacao e schema canonicos.

### `id`

Identificador estavel da experiencia dentro do recurso.

Bom:

```java
id = "profile"
id = "payment-schedule"
id = "payroll-history"
```

Evite ids que expressem tecnologia, layout temporario ou versao visual:

```java
id = "modal1"
id = "new-screen"
id = "profile-card-v2"
```

### `kind`

Escolha o tipo semantico real da experiencia:

- formulario parcial
- detalhe
- lista
- projection de leitura
- painel analitico

Nao use `PARTIAL_FORM` apenas porque a tela e pequena. Use quando a operacao
real recebe DTO parcial e altera parte do recurso.

### `scope`

Use:

- `COLLECTION` quando a experiencia opera sobre colecao.
- `ITEM` quando depende de um recurso especifico.

Lembre que catalogos de `ITEM` sao discovery. Disponibilidade real por item vem
dos endpoints contextuais e de `capabilities`.

O registry tambem pode derivar **surfaces baseline** (por exemplo listagem,
detalhe, criacao ou edicao) para controllers na hierarquia metadata-driven,
alem das explicitamente anotadas com `@UiSurface`. Ao revisar discovery,
confira essas entradas derivadas: `@Operation` e `@Schema` continuam sendo a
fonte textual principal para a operacao por tras da surface.

### `title`

Titulo curto e orientado a usuario.

Bom:

```text
Atualizar perfil de contato
Reagendar pagamento
Consultar historico de folha
```

### `description`

Descreva a experiencia e a finalidade, nao o layout.

Boa:

```text
Permite ajustar a data operacional de pagamento da folha para alinhamento com
tesouraria, fechamento financeiro e comunicacao interna.
```

Ruim:

```text
Surface parcial para editar o campo dataPagamento no formulario.
```

Se a experiencia depende de request/response, o texto deve remeter ao caso de
uso. O schema continua vindo da operacao HTTP real resolvida via
`/schemas/filtered`.

### `intent`

Use intent como rotulo semantico estavel para descoberta por assistentes.

Bom:

```java
intent = "profile"
intent = "payment-schedule"
intent = "payroll-history"
```

Nao use frases longas, labels de UI ou nomes acoplados ao frontend.

### `tags`

Tags devem ajudar agrupamento e discovery, sem virar saco de palavras-chave.

Boas tags:

```java
tags = {"profile", "contact"}
tags = {"payroll", "schedule"}
tags = {"analytics", "read-projection"}
```

Evite adicionar tags de assuntos que a operacao apenas nega ou tangencia.

## Como preencher `@WorkflowAction`

Use para comandos explicitos de negocio.

### `id`

Verbo estavel de comando:

```java
id = "approve"
id = "reject"
id = "resubmit"
id = "bulk-approve"
```

### `title`

Verbo humano claro:

```text
Aprovar
Rejeitar
Reenviar
Aprovar eventos pendentes
```

### `description`

Explique transicao, escopo e efeito observavel.

Boa:

```text
Executa a transicao de aprovacao para eventos de folha selecionados, retornando
totais e resultado por item para apoiar fechamento de competencia, conferencia
financeira e tratamento de falhas parciais.
```

Ruim:

```text
Executa workflow.
```

Quando a action tiver `allowedStates` ou `requiredAuthorities`, confira se a
descricao nao contradiz essas restricoes. A action pode mencionar condicoes de
disponibilidade quando elas forem parte relevante do negocio, mas a fonte
operacional da disponibilidade continua nas propriedades da annotation e no
runtime.

### `successMessage`

Deve dizer o resultado esperado sem prometer efeitos que o service nao garante.

Bom:

```text
Eventos pendentes aprovados com sucesso.
```

Ruim:

```text
Folha fechada, contabilizada e enviada ao banco.
```

### `scope`, `allowedStates` e `requiredAuthorities`

Ao revisar uma action, valide tambem:

- `scope`: item ou colecao, conforme o endpoint real.
- `allowedStates`: estados reais em que o comando faz sentido.
- `requiredAuthorities`: autoridades exigidas pelo dominio ou seguranca do host.

Nao use texto para compensar `scope`, estado ou autoridade mal modelados.

## Como preencher `@Schema`

Use `@Schema.description` em DTOs como documentacao de dominio do contrato
publico.

### Campo

Cada campo deve explicar:

1. O que representa.
2. Como e usado no dominio.
3. Limites ou validacoes importantes.
4. Relacoes reais com outros recursos.
5. Sensibilidade, quando aplicavel.

Exemplo:

```java
@Schema(description = "Data de inicio do vinculo empregaticio; ancora requisitos de experiencia, ferias e historicos de folha.")
private LocalDate dataAdmissao;
```

Evite:

```java
@Schema(description = "Data de admissao.")
```

### DTO

A descricao do DTO deve explicar o papel do contrato.

Boa:

```text
Cadastro de colaborador no dominio de RH: identificacao civil, contato,
remuneracao, vinculo funcional e sinalizadores operacionais.
```

Ruim:

```text
DTO de funcionario.
```

## Como preencher `@UISchema`

`@UISchema` descreve comportamento e apresentacao de campo. Ele nao substitui
`@Schema`.

Use:

- `label` para rotulo curto.
- `helpText` para micro-orientacao de preenchimento.
- `description` quando houver explicacao de apresentacao ou uso visual.
- `controlType` quando o controle e conhecido.
- `group`, `order`, `icon`, `tableHidden`, `formHidden` para UX.

Nao use `@UISchema(label)` como fonte automatica para `@Schema.description`.
Label e rotulo; description e semantica.

## Governanca de dominio e dados sensiveis

Quando o host usar anotacoes de governanca, o texto deve ser ainda mais preciso.

Descreva:

- categoria real do dado
- finalidade legitima
- motivo de restricao
- uso esperado por IA
- relacao com compliance ou politica interna

Exemplo:

```text
Documento pessoal usado para identificacao fiscal do colaborador.
```

Evite:

```text
Dado sensivel.
```

Se o starter ou host publicar catalogo de dominio, essas descricoes passam a
alimentar descoberta semantica e politicas de uso por IA.

### Heuristica em `/schemas/domain` e texto em propriedades

No vocabulario agregado em `/schemas/domain`, classificacoes de governanca para
campos podem considerar **nome do campo**, **`title` e `description` do schema
OpenAPI**, `format` e padroes textuais (por exemplo termos que indicam credencial,
saude, documento pessoal ou financeiro). Isso implica:

- Evite metaforas ou jargao que **acionem** uma classe de governanca sem que o
  dado seja realmente daquela categoria.
- Quando o dado for sensivel, use vocabulario **alinhado ao contrato** (o que o
  campo realmente representa) para que classificacao e auditoria facam sentido.
- `description` vazio ou generico reduz explicabilidade no grafo de dominio; o
  nome do campo sozinho pode nao bastar para contexto de IA.

## Catálogos sem schema inline

Surfaces, actions e capabilities devem referenciar operacoes e schemas
canonicos. Elas nao devem duplicar payload. O mesmo vale para o material
derivado em `/schemas/domain`: ele **interpreta** schemas e annotations ja
publicados, nao corrige DTO mal modelado.

Ao escrever descricoes:

- nao descreva estrutura de request como se ela nascesse em `/schemas/surfaces`,
  `/schemas/actions` ou `/schemas/domain`
- nao prometa resposta que nao exista no metodo HTTP real
- nao invente contrato alternativo para facilitar UI
- nao use catalogo semantico para corrigir DTO mal modelado

Se a descricao precisar explicar campos de entrada ou saida, confira primeiro o
schema real em `/schemas/filtered` e a operacao OpenAPI.

## Filtros e options

Filtros devem descrever criterio de busca real, nao apenas o tipo tecnico.

Boa:

```text
Filtrar funcionarios por cargo atualmente atribuido para recortes de lotacao e
relatorios de RH.
```

Ruim:

```text
Filtro por cargoId.
```

Options devem explicar a finalidade da selecao:

```text
Produz opcoes compactas de departamentos para campos de selecao, busca,
filtros de estrutura organizacional e composicao de vinculos de funcionarios.
```

## Analytics e projections

Para views read-only, analytics e projections:

- deixe claro que e leitura ou projecao
- cite dimensoes reais
- cite metricas reais
- nao transforme projection em comando
- use nomes de metricas que batem com DTO/anotacao
- quando a experiencia primaria for visualizacao analitica, use
  `@UiSurface(kind = SurfaceKind.CHART)` e mantenha `@UiAnalytics` como fonte
  dos detalhes de projection, dimensoes, metricas e apresentacao

Boa:

```text
Agrupar salario liquido por universo, perfil ou composicao de folha.
```

Ruim:

```text
Agrupar analytics.
```

### `@UiAnalytics` e `x-ui.analytics`

Operacoes de estatisticas podem ser anotadas com `@UiAnalytics`. O starter publica
um bloco **`x-ui.analytics`** no OpenAPI com projections, bindings e hints de
apresentacao. O texto em `@Operation` deve estar **coerente** com ids, intents e
fontes declaradas na anotacao; caso contrario, assistentes e clientes veem
descricao e metadata tecnica divergentes.

## operationId, tags e exemplos OpenAPI

### `operationId`

Mantenha **estavel e unico** dentro do escopo do grupo quando exposto. Ferramentas,
clientes gerados e ligacoes entre catalogos usam essa identidade. Renomear sem
necessidade quebra referencias e confunde discovery.

### `tags`

Use tags como **eixo de agrupamento** no OpenAPI e no `/schemas/catalog`. Evite
tags decorativas, duplicadas ou excessivamente genericas que nao reflitam dominio
ou modulo real.

### Exemplos (`@ExampleObject`, `examples` no OpenAPI)

Exemplos entram no payload do `/schemas/catalog` e embutem contexto semantico
para humanos e para pipelines que os indexam. Prefira exemplos **alinhados a
validacao e ao caso de uso**; dados ficticios inconsistentes com o DTO geram
ruido para IA e para conferencia humana.

## Checklist de revisao para LLM/Codex

Antes de concluir uma alteracao de annotations, responda:

1. Li DTO, FilterDTO, controller, service e entidade/projecao?
2. O texto menciona somente campos, relacoes e efeitos reais?
3. Alguma negacao introduz palavras de outro dominio e pode poluir RAG?
4. O `summary` diferencia a operacao de endpoints semelhantes?
5. A `description` explica finalidade de negocio, nao apenas tecnica HTTP?
6. `ResourceIntent`, `UiSurface` e `WorkflowAction` respeitam suas fronteiras?
7. `title` e `intent` sao estaveis e reutilizaveis por assistentes?
8. `tags` ajudam discovery sem virar keyword stuffing?
9. `@Schema.description` explica o campo no dominio, e nao o label?
10. `resourceKey` continua representando a identidade semantica do recurso?
11. O texto preserva `/schemas/filtered` como fonte estrutural canonica?
12. O texto evita schema inline em catalogos semanticos e nao confunde
    `/schemas/domain` com fonte estrutural?
13. Revisei se `@Operation` evita depender só do fallback automatico do
    `/schemas/catalog`?
14. Para campos sensiveis, o texto em `@Schema` nao dispara governanca heuristic
    incorreta nem mascara o real tipo de dado?
15. `@UiAnalytics` (se houver) esta alinhado ao `summary`/`description` da mesma
    operacao?
16. `operationId` e `tags` permanecem coerentes com agrupamento e ferramentas?
17. Exemplos OpenAPI refletem o contrato validado?
18. O texto esta em PT-BR correto e coerente com o restante do recurso?
19. Nao foi usado script ou heuristica de preenchimento em lote?
20. A mudanca exige atualizar README, indice, guia ou exemplo derivado?

## Criterios objetivos de rejeicao

Nao aprove uma revisao semantica se qualquer item abaixo for verdadeiro:

- O texto serviria igualmente para qualquer recurso.
- A descricao apenas repete o nome do campo, label ou metodo.
- O texto contradiz DTO, validacao, mapper, service ou workflow real.
- O texto inventa regra, impacto, integracao ou efeito nao implementado.
- O texto omite efeito colateral relevante da operacao real.
- O texto menciona dominios fora de escopo por negacao e polui discovery.
- `@ResourceIntent` descreve workflow em vez de escrita parcial do recurso.
- `@UiSurface` descreve payload ou contrato paralelo.
- `@WorkflowAction` descreve CRUD comum ou patch de manutencao.
- `successMessage` promete resultado que o service nao garante.
- `tags` foram usadas como lista de palavras-chave para melhorar busca.
- `@Schema.description` foi derivado de `@UISchema(label)` ou camelCase.
- `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`,
  `/schemas/domain` ou `capabilities` aparecem como fonte estrutural em vez de
  discovery ou agregacao derivada.
- `resourceKey` foi alterado por causa de path, nome de controller ou
  conveniencia local, sem mudanca semantica real do recurso.

## Padrao recomendado de trabalho para agentes

1. Classifique a mudanca.
2. Identifique a fonte canonica.
3. Leia o recurso inteiro antes de editar.
4. Escreva ou revise poucos textos por vez.
5. Compare cada texto com DTO, filtro e service.
6. Faça busca por termos problemáticos:
   - `sem alterar`
   - `nao altera`
   - `status`
   - `lookup`
   - `dashboard`
   - `surface`
   - `CRUD`
   - `Endpoint para` (sinal tipico de fallback do `/schemas/catalog`)
   - termos sem acento quando o texto publico deve estar em PT-BR
7. Peça auditoria de outro agente quando a revisao for ampla.
8. Corrija problemas apontados.
9. Valide diff e links de documentacao.

## Anti-exemplos comuns

### Texto generico

Ruim:

```text
Atualiza o registro informado.
```

Bom:

```text
Mantem dados cadastrais do dependente, parentesco, data de nascimento e vinculo
com o funcionario titular para beneficios, conferencia familiar e atendimento
administrativo.
```

### Texto tecnicamente correto, semanticamente pobre

Ruim:

```text
Retorna dados paginados.
```

Bom:

```text
Navega por periodos de ausencia usando cursor, preservando filtros por
funcionario, tipo e janela de datas em calendarios de RH, tabelas operacionais
e paineis de disponibilidade.
```

### Texto rico demais e falso

Ruim:

```text
Calcula provisoes, encargos, beneficios, repasses bancarios e conformidade
trabalhista.
```

Bom:

```text
Lista folhas de pagamento por funcionario, ano, mes, data de pagamento,
salario bruto, descontos e salario liquido para conferencia mensal e fechamento
financeiro.
```

## Relacao com os demais guias

- Use o Guia 01 para criar uma aplicacao nova.
- Use o Guia 02 para adicionar um recurso metadata-driven.
- Use o Guia 04 para decidir se algo e resource, surface, action ou capability.
- Use o Guia 05 para explicar o contrato semantico alem do CRUD.
- Use este Guia 06 para escrever os textos que tornam esse contrato legivel por
  humanos, LLMs e consumidores documentais, incluindo o que flui para
  `/schemas/domain` e para snapshots de `capabilities` sem substituir o
  contrato estrutural do `/schemas/filtered`.
