# RFC — `x-ui.optionSource` para Opções Derivadas em Filtros Metadata-Driven

## Status

- estado: `draft`
- versao proposta: `0.1.0`
- classe: `arquitetural`

## Objetivo

Definir a direção canônica para fontes de opções derivadas em filtros metadata-driven na plataforma Praxis, cobrindo cenários em que um campo precisa de seleção assistida, mas não possui um recurso CRUD próprio para `POST /options/filter`.

Esta RFC ainda não congela um JSON Schema final. O objetivo desta rodada é fechar:

- a lacuna de plataforma entre filtros, options e stats
- a fronteira entre recurso CRUD, dimensão categórica derivada e lookup leve
- a direção canônica de publicação em `/schemas/filtered`
- o modelo de evolução para hosts operacionais, runtime Angular e exemplos oficiais

## Fonte Canônica

`praxis-metadata-starter` é a fonte canônica do vocabulário `x-ui`, da semântica de `/schemas/filtered` e das superfícies públicas metadata-driven da plataforma.

Consequência:

- a semântica de `optionSource` deve nascer aqui
- `praxis-ui-angular` implementa o runtime oficial e seus adapters/normalizers
- hosts como `praxis-api-quickstart` apenas publicam instâncias governadas da capacidade

## Motivação

Hoje a plataforma resolve muito bem dois polos:

- descoberta de registros reais via `POST /filter`
- descoberta de opções quando existe um recurso próprio com `POST /options/filter`

E também resolve um terceiro polo, separado:

- dimensões categóricas e buckets via `praxis.stats`

O gap aparece nos casos em que o filtro precisa oferecer seleção assistida para valores como:

- `universo`
- `payrollProfile`
- `composicaoFolha`
- `faixaSalarioBruto`
- `faixaSalarioLiquido`
- `faixaPctDesconto`

Esses campos:

- não são entidades CRUD próprias
- já têm semântica corporativa relevante
- muitas vezes já são elegíveis como dimensão categórica analítica
- mas hoje acabam publicados como `INPUT`, empurrando a UX para digitação livre

Sem uma solução canônica, os consumidores ficam presos a alternativas erradas:

- criar endpoints ad hoc por recurso analítico
- inventar pseudo-recursos CRUD só para servir options
- acoplar frontend a `group-by` como se fosse API pública de seleção
- manter campos de texto onde a plataforma já conhece a cardinalidade e a semântica do valor

## Diagnóstico do Estado Atual

### O que já existe

- `@Filterable` governa predicados, relações e operações de filtro
- `@UISchema` já publica `endpoint`, `valueField` e `displayField` para selects remotos
- `BaseCrudService.filterOptions()` e `byIdsOptions()` padronizam `OptionDTO`
- `AbstractCrudController` e `AbstractReadOnlyController` expõem `POST /options/filter` e `GET /options/by-ids`
- `StatsFieldRegistry` já governa campos categóricos elegíveis para `group-by` e `distribution`

### O que falta

- uma semântica pública para “fonte de opções derivada”
- governança canônica de distinct values/buckets categóricos em contexto de filtro
- publicação explícita dessa capability em `/schemas/filtered`
- integração de cascata dependente sem hacks locais

## Princípios

### 1. Opção pública não deve depender de endpoint ad hoc

O consumidor não deve precisar conhecer URLs especiais por recurso analítico para descobrir opções de filtro.

### 2. `stats` não deve virar API pública de options

`group-by` e `distribution` podem ser backend interno da solução, mas não devem ser institucionalizados como contrato público de seleção para filtros.

### 3. `OptionDTO` continua sendo o payload público

A plataforma já tem um payload público leve e consistente para seleção remota. A evolução deve preservar isso.

### 4. Recurso CRUD próprio não é obrigatório

A ausência de uma entidade CRUD própria não deve forçar o campo a virar `INPUT`.

### 5. A solução deve ser governada por campo/fonte

Nem todo campo textual é elegível para distinct options. A plataforma precisa de allowlist explícita, limites e política de execução.

## Modelo Conceitual Proposto

Introduzir uma capacidade canônica de plataforma para fontes de opções derivadas:

- `x-ui.optionSource`
- `OptionSourceRegistry`
- `OptionSourceDescriptor`
- `OptionSourceType`
- `OptionSourcePolicy`

## Tipos Canônicos Iniciais

### `RESOURCE_ENTITY`

Fonte baseada em um recurso CRUD/read-only tradicional.

Exemplos:

- funcionário
- cargo
- departamento
- equipe
- base

### `DISTINCT_DIMENSION`

Fonte baseada em valores distintos reais de um campo categórico derivado do recurso atual.

Exemplos:

- `universo`
- `payrollProfile`
- `composicaoFolha`
- `severidade`

### `CATEGORICAL_BUCKET`

Fonte baseada em buckets categóricos governados, inclusive faixas semânticas já calculadas.

Exemplos:

- `faixaSalarioBruto`
- `faixaSalarioLiquido`
- `faixaPctDesconto`
- `faixaValorAdicionais`

### `LIGHT_LOOKUP`

Fonte leve de id/label quando a semântica é lookup, mas a origem não é um CRUD clássico exposto como recurso principal.

Exemplos:

- labels relacionais especializadas
- identificadores derivados com descrição curta

### `STATIC_CANONICAL`

Fonte estática governada pelo contrato canônico, sem necessidade de consulta dinâmica.

Exemplos:

- listas corporativas fixas
- classificações internas de baixa variabilidade

## Fronteira Entre Tipos

### `DISTINCT_DIMENSION` versus `CATEGORICAL_BUCKET`

`DISTINCT_DIMENSION`:

- retorna os valores reais distintos do campo
- a cardinalidade nasce dos dados
- a label costuma ser o próprio valor normalizado

`CATEGORICAL_BUCKET`:

- retorna buckets governados e semanticamente estáveis
- a label pode não coincidir com o valor bruto
- é adequado para faixas, bandas e taxonomias já consolidadas

Essa distinção é importante para evitar que “distinct de string” e “bucket semântico” sejam tratados como o mesmo contrato.

## Contrato Canônico no Metadata

Direção inicial:

- `@UISchema` passa a aceitar referência a uma fonte canônica de opções
- `/schemas/filtered` publica isso como `x-ui.optionSource`

Shape conceitual mínimo do bloco:

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

Direção conceitual mínima:

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

Observação:

`endpoint` continua válido como mecanismo legado/compatível, mas não deve seguir sendo a única forma de modelar opções remotas no nível canônico.

## Compatibilidade com o legado

Esta evolução deve ser estritamente aditiva no primeiro ciclo.

Permanece válido:

- `endpoint`
- `valueField`
- `displayField`
- `POST /options/filter`
- `GET /options/by-ids`

Direção de convivência:

- campos tradicionais podem continuar publicando apenas `endpoint/valueField/displayField`
- campos novos ou migrados podem publicar `x-ui.optionSource`
- durante a transição, um mesmo campo pode publicar ambos os blocos quando isso for útil para compatibilidade
- o runtime oficial deve preferir `x-ui.optionSource` quando disponível, sem quebrar consumidores antigos
- quando o campo do filtro não coincidir com a `key` da source, o contrato deve publicar `filterField` para evitar auto-restrição incorreta em cascata

## Não objetivos desta fase

Esta RFC não tenta resolver ainda:

- descontinuação imediata de `endpoint/valueField/displayField`
- suporte completo no runtime Angular antes da publicação canônica no starter
- geração automática de `optionSource` para todo campo textual com baixa cardinalidade
- transformação de `praxis.stats` em superfície pública de options
- definição de um motor distribuído de cache para options derivadas

## Endpoints Canônicos Propostos

### Filtro de opções por source

- `POST /{resource}/option-sources/{sourceKey}/options/filter`

### Reidratação de selecionados por source

- `GET /{resource}/option-sources/{sourceKey}/options/by-ids`

Esses endpoints:

- retornam `OptionDTO`
- preservam paginação, lookup e `includeIds`
- mantêm alinhamento conceitual com o modelo atual
- evitam criar APIs paralelas por campo

## Request e Cascata

O `options/filter` derivado deve operar sobre o mesmo universo filtrado do recurso base.

Direção recomendada:

- aceita o mesmo `FD extends GenericFilterDTO` do recurso
- aceita `search`
- aceita `includeIds`
- aceita paginação
- aplica `excludeSelfField` quando configurado para evitar auto-restrição indevida

Isso é especialmente importante em filtros dependentes, como:

- departamento dependente de universo
- equipe dependente de departamento
- faixas dependentes de período selecionado

## Relação com `StatsFieldRegistry`

`StatsFieldRegistry` já governa campos categóricos elegíveis para:

- `group-by`
- `distribution` em modo categórico

Direção recomendada:

- `OptionSourceRegistry` governa a publicação pública de options
- `StatsFieldRegistry` pode ser backend auxiliar para fontes do tipo `DISTINCT_DIMENSION` e `CATEGORICAL_BUCKET`
- `stats` não vira o contrato público de seleção

Isso preserva uma separação saudável:

- options = UX de filtro
- stats = UX analítica

## OptionDTO

`OptionDTO` permanece como contrato público.

Pode ser enriquecido por `extra`, quando necessário, para suportar:

- `count`
- `badge`
- `group`
- `description`
- `meta`

sem quebrar o payload base:

- `id`
- `label`
- `extra`

## Publicação em `/schemas/filtered`

`/schemas/filtered` deve publicar a capability de fonte de opção por campo.

Direção mínima:

- manter o shape legado para `endpoint/valueField/displayField`
- adicionar `x-ui.optionSource`
- explicitar capability de options derivadas no nível certo da operação/recurso/campo

Com isso, o runtime consumidor pode:

- continuar funcionando no caminho legado
- migrar progressivamente para a semântica canônica

## Compatibilidade com Angular

O runtime Angular já está mais próximo de `resourcePath` base do que de “endpoint arbitrário final”.

Consequência:

- fase 1: o Angular pode consumir `option-sources/{sourceKey}` como resourcePath derivado
- fase 2: o Angular deve ganhar suporte first-class a `x-ui.optionSource`
- fase 3: a documentação pública deve reduzir a ambiguidade histórica do uso direto de `endpoint="/options/filter"`

## Alternativas Consideradas

### 1. Exigir sempre recurso CRUD próprio

Vantagem:

- reaproveita a infraestrutura atual

Desvantagem:

- cria pseudo-recursos artificiais
- duplica semântica

Conclusão:

- não é a solução correta de plataforma

### 2. Criar endpoint ad hoc por recurso analítico

Vantagem:

- rápido localmente

Desvantagem:

- fragmenta contrato, docs e runtime

Conclusão:

- deve ser evitado

### 3. Expor `stats/group-by` como API pública de options

Vantagem:

- reaproveita governança já existente

Desvantagem:

- mistura semântica analítica com semântica de filtro

Conclusão:

- aceitável apenas como backend interno de execução

### 4. Criar contrato unificado de `optionSource`

Vantagem:

- separa bem a semântica pública
- reaproveita `OptionDTO`
- permite governança e rollout gradual

Conclusão:

- recomendação principal desta RFC

## Casos Prioritários

Casos iniciais que justificam a evolução:

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

## Segurança e Governança

Cada `OptionSourceDescriptor` deve declarar política explícita para:

- elegibilidade do campo
- busca textual permitida
- tamanho máximo de página
- ordenação padrão
- reidratação por IDs
- dependências de cascata
- cache
- possibilidade de excluir o próprio campo do filtro aplicado

Isso evita transformar “distinct values” em superfície frouxa e não governada.

## Versionamento

Antes de endurecer schema definitivo, a capacidade deve nascer com versionamento explícito.

Recomendação:

- `version` no bloco `x-ui.optionSource`
- rollout aditivo
- sem remoção do modelo legado `endpoint/valueField/displayField` na primeira fase

## Plano de Evolução

### Fase 1 — Contrato

- definir `OptionSourceType`
- definir `OptionSourceDescriptor`
- definir `OptionSourceRegistry`
- documentar `x-ui.optionSource`

### Fase 2 — Starter

- implementar `option-sources/{sourceKey}/options/filter`
- implementar `option-sources/{sourceKey}/options/by-ids`
- reusar `Specification<E>` do recurso base
- integrar com `StatsFieldRegistry` quando aplicável

### Fase 3 — Host de referência

- modelar casos reais em `praxis-api-quickstart`
- priorizar:
  - `payrollProfile`
  - `composicaoFolha`
  - `faixaPctDesconto`
  - `universo`
  - `severidade`

### Fase 4 — Runtime Angular

- consumo compatível via resourcePath derivado
- suporte first-class a `x-ui.optionSource`
- documentação do contrato correto para selects remotos

### Fase 5 — Exemplos e Conformidade

- atualizar exemplos oficiais
- atualizar guias de filtros/options
- publicar matriz “quando usar recurso próprio versus optionSource derivado”

# Introdução Didática para Iniciantes

Se você é novo na plataforma Praxis, pense no OptionSource como uma "receita" para criar listas de opções (como um dropdown) em formulários ou filtros, sem precisar programar tudo do zero. 

**Analogia Simples**: Imagine um restaurante onde o cardápio (metadata) diz: "Para o prato 'Pizza', use ingredientes de 'Queijos Disponíveis'". OptionSource é a receita que explica como buscar esses "ingredientes" (opções) de forma padronizada, em vez de inventar uma nova cozinha para cada prato.

**Por Que Importa?** Em apps metadata-driven, campos como "Perfil Salarial" ou "Faixas de Idade" precisam de opções inteligentes. Sem OptionSource, o usuário digita tudo manualmente — ruim para UX. Com ele, o sistema "sabe" buscar opções automaticamente.

**Fluxo Básico**:
1. Defina o tipo de fonte (ex.: valores distintos de dados).
2. Registre no serviço com um descritor.
3. O frontend lê do schema e chama endpoints canônicos.
4. Resultado: Opções aparecem no select!

Se ainda confuso, leia os exemplos abaixo antes de mergulhar no spec técnico.

## Exemplos Práticos Expandidos

### Exemplo 1: DISTINCT_DIMENSION para "Universo"
- **Cenário**: Campo "universo" em uma view de analytics de folha de pagamento.
- **Como Funciona**: Busca valores únicos reais do campo "universo" na tabela, sem buckets artificiais.
- **Código no Service**:
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
- **Como o Usuário Vê**: Dropdown com opções como "Empresa A", "Empresa B", baseadas em dados reais.

### Exemplo 2: CATEGORICAL_BUCKET para "Faixa Salarial"
- **Cenário**: Campo "faixaSalarioBruto" para filtros em relatórios.
- **Como Funciona**: Define buckets semânticos (ex.: "0-1000", "1001-2000") com labels amigáveis, não valores brutos.
- **Código no Service**:
  ```java
  OptionSourceDescriptor faixaDescriptor = new OptionSourceDescriptor(
      "faixaSalarioBruto",
      OptionSourceType.CATEGORICAL_BUCKET,
      "/api/human-resources/vw-analytics-folha-pagamento",
      "faixaSalarioBruto",
      "faixaSalarioBruto",
      "label", // labelPropertyPath (ex.: "Até R$ 1.000")
      "value", // valuePropertyPath (ex.: "0-1000")
      List.of("competenciaBetween"), // depende do período
      new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
  );
  ```
- **Resultado**: Opções como "Até R$ 1.000" (label) com valor "0-1000" para filtro.

### Exemplo 3: Dependências em Cascata
- **Cenário**: "Equipe" depende de "Departamento".
- **Como Funciona**: O `dependsOn` filtra opções baseadas em seleções anteriores.
- **Código**:
  ```java
  List.of("departamento") // dependsOn
  ```
- **Fluxo**: Selecione departamento primeiro; equipe filtra automaticamente.

## Decisões em Aberto (Resolvidas para Esta Versão)
- **Contrato em Nível de Campo vs. Operação**: Recomendamos nível de campo para granularidade, mas suporte ambos.
- **LIGHT_LOOKUP**: Nasce como subtipo de `RESOURCE_ENTITY` na fase 1; evolui se necessário.
- **Busca Textual**: Aceita parcial ("contains") para flexibilidade.
- **Publicação em Schema**: Copia referência da fonte, não descriptor completo, para evitar duplicação.

## Seção de Pitfalls Comuns
- **Erro: Campo Não Aparece no Schema**: Verifique se o nome do campo coincide com a `key` no registry.
- **Erro: Opções Vazias**: Confirme `resourcePath` correto e permissões no endpoint.
- **Erro: Dependências Não Funcionam**: Use `excludeSelfField: true` para evitar loops.
- **Dica**: Sempre teste com `POST /option-sources/{key}/options/filter` manualmente.

## Conclusão

A plataforma Praxis já possui os blocos necessários para resolver o problema:

- filtro governado
- `OptionDTO`
- endpoints de options
- governança analítica via `StatsFieldRegistry`

O que falta é a camada canônica intermediária:

- uma semântica pública de `optionSource`

Essa camada permite cobrir o caso atual de payroll analytics e outros cenários próximos sem cair em:

- endpoints ad hoc
- pseudo-recursos CRUD
- ou acoplamento indevido da UI a `stats`

Essa é a evolução correta de plataforma para filtros metadata-driven corporativos em cenários analíticos e derivados.
