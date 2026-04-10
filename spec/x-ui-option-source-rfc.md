# RFC â€” `x-ui.optionSource` para OpÃ§Ãµes Derivadas em Filtros Metadata-Driven

## Status

- estado: `draft`
- versao proposta: `0.1.0`
- classe: `arquitetural`

## Objetivo

Definir a direÃ§Ã£o canÃ´nica para fontes de opÃ§Ãµes derivadas em filtros metadata-driven na plataforma Praxis, cobrindo cenÃ¡rios em que um campo precisa de seleÃ§Ã£o assistida, mas nÃ£o possui um recurso CRUD prÃ³prio para `POST /options/filter`.

Esta RFC ainda nÃ£o congela um JSON Schema final. O objetivo desta rodada Ã© fechar:

- a lacuna de plataforma entre filtros, options e stats
- a fronteira entre recurso CRUD, dimensÃ£o categÃ³rica derivada e lookup leve
- a direÃ§Ã£o canÃ´nica de publicaÃ§Ã£o em `/schemas/filtered`
- o modelo de evoluÃ§Ã£o para hosts operacionais, runtime Angular e exemplos oficiais

## Fonte CanÃ´nica

`praxis-metadata-starter` Ã© a fonte canÃ´nica do vocabulÃ¡rio `x-ui`, da semÃ¢ntica de `/schemas/filtered` e das superfÃ­cies pÃºblicas metadata-driven da plataforma.

ConsequÃªncia:

- a semÃ¢ntica de `optionSource` deve nascer aqui
- `praxis-ui-angular` implementa o runtime oficial e seus adapters/normalizers
- hosts como `praxis-api-quickstart` apenas publicam instÃ¢ncias governadas da capacidade

## MotivaÃ§Ã£o

Hoje a plataforma resolve muito bem dois polos:

- descoberta de registros reais via `POST /filter`
- descoberta de opÃ§Ãµes quando existe um recurso prÃ³prio com `POST /options/filter`

E tambÃ©m resolve um terceiro polo, separado:

- dimensÃµes categÃ³ricas e buckets via `praxis.stats`

O gap aparece nos casos em que o filtro precisa oferecer seleÃ§Ã£o assistida para valores como:

- `universo`
- `payrollProfile`
- `composicaoFolha`
- `faixaSalarioBruto`
- `faixaSalarioLiquido`
- `faixaPctDesconto`

Esses campos:

- nÃ£o sÃ£o entidades CRUD prÃ³prias
- jÃ¡ tÃªm semÃ¢ntica corporativa relevante
- muitas vezes jÃ¡ sÃ£o elegÃ­veis como dimensÃ£o categÃ³rica analÃ­tica
- mas hoje acabam publicados como `INPUT`, empurrando a UX para digitaÃ§Ã£o livre

Sem uma soluÃ§Ã£o canÃ´nica, os consumidores ficam presos a alternativas erradas:

- criar endpoints ad hoc por recurso analÃ­tico
- inventar pseudo-recursos CRUD sÃ³ para servir options
- acoplar frontend a `group-by` como se fosse API pÃºblica de seleÃ§Ã£o
- manter campos de texto onde a plataforma jÃ¡ conhece a cardinalidade e a semÃ¢ntica do valor

## DiagnÃ³stico do Estado Atual

### O que jÃ¡ existe

- `@Filterable` governa predicados, relaÃ§Ãµes e operaÃ§Ãµes de filtro
- `@UISchema` jÃ¡ publica `endpoint`, `valueField` e `displayField` para selects remotos
- `BaseResourceQueryService.filterOptions()` e `byIdsOptions()` padronizam `OptionDTO`
- `AbstractResourceQueryController` expÃµe `POST /options/filter` e `GET /options/by-ids`
- `StatsFieldRegistry` jÃ¡ governa campos categÃ³ricos elegÃ­veis para `group-by` e `distribution`

### O que falta

- uma semÃ¢ntica pÃºblica para â€œfonte de opÃ§Ãµes derivadaâ€
- governanÃ§a canÃ´nica de distinct values/buckets categÃ³ricos em contexto de filtro
- publicaÃ§Ã£o explÃ­cita dessa capability em `/schemas/filtered`
- integraÃ§Ã£o de cascata dependente sem hacks locais

## PrincÃ­pios

### 1. OpÃ§Ã£o pÃºblica nÃ£o deve depender de endpoint ad hoc

O consumidor nÃ£o deve precisar conhecer URLs especiais por recurso analÃ­tico para descobrir opÃ§Ãµes de filtro.

### 2. `stats` nÃ£o deve virar API pÃºblica de options

`group-by` e `distribution` podem ser backend interno da soluÃ§Ã£o, mas nÃ£o devem ser institucionalizados como contrato pÃºblico de seleÃ§Ã£o para filtros.

### 3. `OptionDTO` continua sendo o payload pÃºblico

A plataforma jÃ¡ tem um payload pÃºblico leve e consistente para seleÃ§Ã£o remota. A evoluÃ§Ã£o deve preservar isso.

### 4. Recurso CRUD prÃ³prio nÃ£o Ã© obrigatÃ³rio

A ausÃªncia de uma entidade CRUD prÃ³pria nÃ£o deve forÃ§ar o campo a virar `INPUT`.

### 5. A soluÃ§Ã£o deve ser governada por campo/fonte

Nem todo campo textual Ã© elegÃ­vel para distinct options. A plataforma precisa de allowlist explÃ­cita, limites e polÃ­tica de execuÃ§Ã£o.

## Modelo Conceitual Proposto

Introduzir uma capacidade canÃ´nica de plataforma para fontes de opÃ§Ãµes derivadas:

- `x-ui.optionSource`
- `OptionSourceRegistry`
- `OptionSourceDescriptor`
- `OptionSourceType`
- `OptionSourcePolicy`

## Tipos CanÃ´nicos Iniciais

### `RESOURCE_ENTITY`

Fonte baseada em um recurso CRUD/read-only tradicional.

Exemplos:

- funcionÃ¡rio
- cargo
- departamento
- equipe
- base

### `DISTINCT_DIMENSION`

Fonte baseada em valores distintos reais de um campo categÃ³rico derivado do recurso atual.

Exemplos:

- `universo`
- `payrollProfile`
- `composicaoFolha`
- `severidade`

### `CATEGORICAL_BUCKET`

Fonte baseada em buckets categÃ³ricos governados, inclusive faixas semÃ¢nticas jÃ¡ calculadas.

Exemplos:

- `faixaSalarioBruto`
- `faixaSalarioLiquido`
- `faixaPctDesconto`
- `faixaValorAdicionais`

### `LIGHT_LOOKUP`

Fonte leve de id/label quando a semÃ¢ntica Ã© lookup, mas a origem nÃ£o Ã© um CRUD clÃ¡ssico exposto como recurso principal.

Exemplos:

- labels relacionais especializadas
- identificadores derivados com descriÃ§Ã£o curta

### `STATIC_CANONICAL`

Fonte estÃ¡tica governada pelo contrato canÃ´nico, sem necessidade de consulta dinÃ¢mica.

Exemplos:

- listas corporativas fixas
- classificaÃ§Ãµes internas de baixa variabilidade

## Fronteira Entre Tipos

### `DISTINCT_DIMENSION` versus `CATEGORICAL_BUCKET`

`DISTINCT_DIMENSION`:

- retorna os valores reais distintos do campo
- a cardinalidade nasce dos dados
- a label costuma ser o prÃ³prio valor normalizado

`CATEGORICAL_BUCKET`:

- retorna buckets governados e semanticamente estÃ¡veis
- a label pode nÃ£o coincidir com o valor bruto
- Ã© adequado para faixas, bandas e taxonomias jÃ¡ consolidadas

Essa distinÃ§Ã£o Ã© importante para evitar que â€œdistinct de stringâ€ e â€œbucket semÃ¢nticoâ€ sejam tratados como o mesmo contrato.

## Contrato CanÃ´nico no Metadata

DireÃ§Ã£o inicial:

- `@UISchema` passa a aceitar referÃªncia a uma fonte canÃ´nica de opÃ§Ãµes
- `/schemas/filtered` publica isso como `x-ui.optionSource`

Shape conceitual mÃ­nimo do bloco:

```json
{
  "key": "payrollProfile",
  "type": "DISTINCT_DIMENSION",
  "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
  "filterField": "payrollProfile",
  "dependsOn": ["competenciaBetween", "universo"],
  "excludeSelfField": true,
  "searchMode": "contains",
  "pageSize": 25,
  "includeIds": true,
  "cachePolicy": "request-scope"
}
```

DireÃ§Ã£o conceitual mÃ­nima:

- `key`
- `type`
- `resourcePath`
- `filterField`
- `dependsOn`
- `excludeSelfField`
- `searchMode`
- `pageSize`
- `includeIds`
- `cachePolicy`

ObservaÃ§Ã£o:

`endpoint` continua vÃ¡lido como mecanismo legado/compatÃ­vel, mas nÃ£o deve seguir sendo a Ãºnica forma de modelar opÃ§Ãµes remotas no nÃ­vel canÃ´nico.

## Compatibilidade com o legado

Esta evoluÃ§Ã£o deve ser estritamente aditiva no primeiro ciclo.

Permanece vÃ¡lido:

- `endpoint`
- `valueField`
- `displayField`
- `POST /options/filter`
- `GET /options/by-ids`

DireÃ§Ã£o de convivÃªncia:

- campos tradicionais podem continuar publicando apenas `endpoint/valueField/displayField`
- campos novos ou migrados podem publicar `x-ui.optionSource`
- durante a transiÃ§Ã£o, um mesmo campo pode publicar ambos os blocos quando isso for Ãºtil para compatibilidade
- o runtime oficial deve preferir `x-ui.optionSource` quando disponÃ­vel, sem quebrar consumidores antigos
- quando o campo do filtro nÃ£o coincidir com a `key` da source, o contrato deve publicar `filterField` para evitar auto-restriÃ§Ã£o incorreta em cascata

## NÃ£o objetivos desta fase

Esta RFC nÃ£o tenta resolver ainda:

- descontinuaÃ§Ã£o imediata de `endpoint/valueField/displayField`
- suporte completo no runtime Angular antes da publicaÃ§Ã£o canÃ´nica no starter
- geraÃ§Ã£o automÃ¡tica de `optionSource` para todo campo textual com baixa cardinalidade
- transformaÃ§Ã£o de `praxis.stats` em superfÃ­cie pÃºblica de options
- definiÃ§Ã£o de um motor distribuÃ­do de cache para options derivadas

## Endpoints CanÃ´nicos Propostos

### Filtro de opÃ§Ãµes por source

- `POST /{resource}/option-sources/{sourceKey}/options/filter`

### ReidrataÃ§Ã£o de selecionados por source

- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`

Esses endpoints:

- retornam `OptionDTO`
- preservam paginaÃ§Ã£o, lookup e `includeIds`
- mantÃªm alinhamento conceitual com o modelo atual
- evitam criar APIs paralelas por campo

## Request e Cascata

O `options/filter` derivado deve operar sobre o mesmo universo filtrado do recurso base.

DireÃ§Ã£o recomendada:

- aceita o mesmo `FD extends GenericFilterDTO` do recurso
- aceita `search`
- aceita `includeIds`
- aceita paginaÃ§Ã£o
- aplica `excludeSelfField` quando configurado para evitar auto-restriÃ§Ã£o indevida

Isso Ã© especialmente importante em filtros dependentes, como:

- departamento dependente de universo
- equipe dependente de departamento
- faixas dependentes de perÃ­odo selecionado

## RelaÃ§Ã£o com `StatsFieldRegistry`

`StatsFieldRegistry` jÃ¡ governa campos categÃ³ricos elegÃ­veis para:

- `group-by`
- `distribution` em modo categÃ³rico

DireÃ§Ã£o recomendada:

- `OptionSourceRegistry` governa a publicaÃ§Ã£o pÃºblica de options
- `StatsFieldRegistry` pode ser backend auxiliar para fontes do tipo `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`
- `stats` nÃ£o vira o contrato pÃºblico de seleÃ§Ã£o

Isso preserva uma separaÃ§Ã£o saudÃ¡vel:

- options = UX de filtro
- stats = UX analÃ­tica

## OptionDTO

`OptionDTO` permanece como contrato pÃºblico.

Pode ser enriquecido por `extra`, quando necessÃ¡rio, para suportar:

- `count`
- `badge`
- `group`
- `description`
- `meta`

sem quebrar o payload base:

- `id`
- `label`
- `extra`

## PublicaÃ§Ã£o em `/schemas/filtered`

`/schemas/filtered` deve publicar a capability de fonte de opÃ§Ã£o por campo.

DireÃ§Ã£o mÃ­nima:

- manter o shape legado para `endpoint/valueField/displayField`
- adicionar `x-ui.optionSource`
- explicitar capability de options derivadas no nÃ­vel certo da operaÃ§Ã£o/recurso/campo

Com isso, o runtime consumidor pode:

- continuar funcionando no caminho legado
- migrar progressivamente para a semÃ¢ntica canÃ´nica

## Compatibilidade com Angular

O runtime Angular jÃ¡ estÃ¡ mais prÃ³ximo de `resourcePath` base do que de â€œendpoint arbitrÃ¡rio finalâ€.

ConsequÃªncia:

- fase 1: o Angular pode consumir `option-sources/{sourceKey}` como resourcePath derivado
- fase 2: o Angular deve ganhar suporte first-class a `x-ui.optionSource`
- fase 3: a documentaÃ§Ã£o pÃºblica deve reduzir a ambiguidade histÃ³rica do uso direto de `endpoint="/options/filter"`

## Alternativas Consideradas

### 1. Exigir sempre recurso CRUD prÃ³prio

Vantagem:

- reaproveita a infraestrutura atual

Desvantagem:

- cria pseudo-recursos artificiais
- duplica semÃ¢ntica

ConclusÃ£o:

- nÃ£o Ã© a soluÃ§Ã£o correta de plataforma

### 2. Criar endpoint ad hoc por recurso analÃ­tico

Vantagem:

- rÃ¡pido localmente

Desvantagem:

- fragmenta contrato, docs e runtime

ConclusÃ£o:

- deve ser evitado

### 3. Expor `stats/group-by` como API pÃºblica de options

Vantagem:

- reaproveita governanÃ§a jÃ¡ existente

Desvantagem:

- mistura semÃ¢ntica analÃ­tica com semÃ¢ntica de filtro

ConclusÃ£o:

- aceitÃ¡vel apenas como backend interno de execuÃ§Ã£o

### 4. Criar contrato unificado de `optionSource`

Vantagem:

- separa bem a semÃ¢ntica pÃºblica
- reaproveita `OptionDTO`
- permite governanÃ§a e rollout gradual

ConclusÃ£o:

- recomendaÃ§Ã£o principal desta RFC

## Casos PrioritÃ¡rios

Casos iniciais que justificam a evoluÃ§Ã£o:

- `universo`
- `payrollProfile`
- `composicaoFolha`
- `faixaSalarioBruto`
- `faixaSalarioLiquido`
- `faixaPctDesconto`
- `faixaValorAdicionais`
- `severidade`
- `equipePrincipal`
- `basePrincipal`

## SeguranÃ§a e GovernanÃ§a

Cada `OptionSourceDescriptor` deve declarar polÃ­tica explÃ­cita para:

- elegibilidade do campo
- busca textual permitida
- tamanho mÃ¡ximo de pÃ¡gina
- ordenaÃ§Ã£o padrÃ£o
- reidrataÃ§Ã£o por IDs
- dependÃªncias de cascata
- cache
- possibilidade de excluir o prÃ³prio campo do filtro aplicado

Isso evita transformar â€œdistinct valuesâ€ em superfÃ­cie frouxa e nÃ£o governada.

## Versionamento

Antes de endurecer schema definitivo, a capacidade deve nascer com versionamento explÃ­cito.

RecomendaÃ§Ã£o:

- `version` no bloco `x-ui.optionSource`
- rollout aditivo
- sem remoÃ§Ã£o do modelo legado `endpoint/valueField/displayField` na primeira fase

## Plano de EvoluÃ§Ã£o

### Fase 1 â€” Contrato

- definir `OptionSourceType`
- definir `OptionSourceDescriptor`
- definir `OptionSourceRegistry`
- documentar `x-ui.optionSource`

### Fase 2 â€” Starter

- implementar `option-sources/{sourceKey}/options/filter`
- implementar `option-sources/{sourceKey}/options/by-ids`
- reusar `Specification<E>` do recurso base
- integrar com `StatsFieldRegistry` quando aplicÃ¡vel

### Fase 3 â€” Host de referÃªncia

- modelar casos reais em `praxis-api-quickstart`
- priorizar:
  - `payrollProfile`
  - `composicaoFolha`
  - `faixaPctDesconto`
  - `universo`
  - `severidade`

### Fase 4 â€” Runtime Angular

- consumo compatÃ­vel via resourcePath derivado
- suporte first-class a `x-ui.optionSource`
- documentaÃ§Ã£o do contrato correto para selects remotos

### Fase 5 â€” Exemplos e Conformidade

- atualizar exemplos oficiais
- atualizar guias de filtros/options
- publicar matriz â€œquando usar recurso prÃ³prio versus optionSource derivadoâ€

# IntroduÃ§Ã£o DidÃ¡tica para Iniciantes

Se vocÃª Ã© novo na plataforma Praxis, pense no OptionSource como uma "receita" para criar listas de opÃ§Ãµes (como um dropdown) em formulÃ¡rios ou filtros, sem precisar programar tudo do zero. 

**Analogia Simples**: Imagine um restaurante onde o cardÃ¡pio (metadata) diz: "Para o prato 'Pizza', use ingredientes de 'Queijos DisponÃ­veis'". OptionSource Ã© a receita que explica como buscar esses "ingredientes" (opÃ§Ãµes) de forma padronizada, em vez de inventar uma nova cozinha para cada prato.

**Por Que Importa?** Em apps metadata-driven, campos como "Perfil Salarial" ou "Faixas de Idade" precisam de opÃ§Ãµes inteligentes. Sem OptionSource, o usuÃ¡rio digita tudo manualmente â€” ruim para UX. Com ele, o sistema "sabe" buscar opÃ§Ãµes automaticamente.

**Fluxo BÃ¡sico**:
1. Defina o tipo de fonte (ex.: valores distintos de dados).
2. Registre no serviÃ§o com um descritor.
3. O frontend lÃª do schema e chama endpoints canÃ´nicos.
4. Resultado: OpÃ§Ãµes aparecem no select!

Se ainda confuso, leia os exemplos abaixo antes de mergulhar no spec tÃ©cnico.

## Exemplos PrÃ¡ticos Expandidos

### Exemplo 1: DISTINCT_DIMENSION para "Universo"
- **CenÃ¡rio**: Campo "universo" em uma view de analytics de folha de pagamento.
- **Como Funciona**: Busca valores Ãºnicos reais do campo "universo" na tabela, sem buckets artificiais.
- **CÃ³digo no Service**:
  ```java
  OptionSourceDescriptor universoDescriptor = new OptionSourceDescriptor(
      "universo",
      OptionSourceType.DISTINCT_DIMENSION,
      "/api/human-resources/vw-analytics-folha-pagamento",
      "universo", // filterField
      "universo", // propertyPath
      "universo", // labelPropertyPath
      "universo", // valuePropertyPath
      List.of(), // dependsOn (nenhuma)
      OptionSourcePolicy.defaults()
  );
  ```
- **Resultado no Schema (/schemas/filtered)**:
  ```json
  {
    "universo": {
      "x-ui.optionSource": {
        "key": "universo",
        "type": "DISTINCT_DIMENSION",
        "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
        "filterField": "universo"
      }
    }
  }
  ```
- **Como o UsuÃ¡rio VÃª**: Dropdown com opÃ§Ãµes como "Empresa A", "Empresa B", baseadas em dados reais.

### Exemplo 2: CATEGORICAL_BUCKET para "Faixa Salarial"
- **CenÃ¡rio**: Campo "faixaSalarioBruto" para filtros em relatÃ³rios.
- **Como Funciona**: Define buckets semÃ¢nticos (ex.: "0-1000", "1001-2000") com labels amigÃ¡veis, nÃ£o valores brutos.
- **CÃ³digo no Service**:
  ```java
  OptionSourceDescriptor faixaDescriptor = new OptionSourceDescriptor(
      "faixaSalarioBruto",
      OptionSourceType.CATEGORICAL_BUCKET,
      "/api/human-resources/vw-analytics-folha-pagamento",
      "faixaSalarioBruto",
      "faixaSalarioBruto",
      "label", // labelPropertyPath (ex.: "AtÃ© R$ 1.000")
      "value", // valuePropertyPath (ex.: "0-1000")
      List.of("competenciaBetween"), // depende do perÃ­odo
      new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
  );
  ```
- **Resultado**: OpÃ§Ãµes como "AtÃ© R$ 1.000" (label) com valor "0-1000" para filtro.

### Exemplo 3: DependÃªncias em Cascata
- **CenÃ¡rio**: "Equipe" depende de "Departamento".
- **Como Funciona**: O `dependsOn` filtra opÃ§Ãµes baseadas em seleÃ§Ãµes anteriores.
- **CÃ³digo**:
  ```java
  List.of("departamento") // dependsOn
  ```
- **Fluxo**: Selecione departamento primeiro; equipe filtra automaticamente.

## DecisÃµes em Aberto (Resolvidas para Esta VersÃ£o)
- **Contrato em NÃ­vel de Campo vs. OperaÃ§Ã£o**: Recomendamos nÃ­vel de campo para granularidade, mas suporte ambos.
- **LIGHT_LOOKUP**: Nasce como subtipo de `RESOURCE_ENTITY` na fase 1; evolui se necessÃ¡rio.
- **Busca Textual**: Aceita parcial ("contains") para flexibilidade.
- **PublicaÃ§Ã£o em Schema**: Copia referÃªncia da fonte, nÃ£o descriptor completo, para evitar duplicaÃ§Ã£o.

## SeÃ§Ã£o de Pitfalls Comuns
- **Erro: Campo NÃ£o Aparece no Schema**: Verifique se o nome do campo coincide com a `key` no registry.
- **Erro: OpÃ§Ãµes Vazias**: Confirme `resourcePath` correto e permissÃµes no endpoint.
- **Erro: DependÃªncias NÃ£o Funcionam**: Use `excludeSelfField: true` para evitar loops.
- **Dica**: Sempre teste com `POST /option-sources/{key}/options/filter` manualmente.

## ConclusÃ£o

A plataforma Praxis jÃ¡ possui os blocos necessÃ¡rios para resolver o problema:

- filtro governado
- `OptionDTO`
- endpoints de options
- governanÃ§a analÃ­tica via `StatsFieldRegistry`

O que falta Ã© a camada canÃ´nica intermediÃ¡ria:

- uma semÃ¢ntica pÃºblica de `optionSource`

Essa camada permite cobrir o caso atual de payroll analytics e outros cenÃ¡rios prÃ³ximos sem cair em:

- endpoints ad hoc
- pseudo-recursos CRUD
- ou acoplamento indevido da UI a `stats`

Essa Ã© a evoluÃ§Ã£o correta de plataforma para filtros metadata-driven corporativos em cenÃ¡rios analÃ­ticos e derivados.
