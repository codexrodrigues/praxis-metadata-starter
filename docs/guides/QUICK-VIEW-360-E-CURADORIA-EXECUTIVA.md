# Quick View 360° e Curadoria Executiva Inteligente

## Objetivo

Este guia estabelece o padrão canônico de engenharia e de curadoria de negócio para os cartões **Quick View** e gavetas (*drawers*) de **Visão 360°** em aplicações corporativas construídas sobre o `praxis-metadata-starter` e `@praxisui/*` (`@praxisui/core`, `@praxisui/dialog`, `@praxisui/table`, `@praxisui/crud`).

Um cartão Quick View **não é** um despejo aleatório de colunas da tabela nem um mini-formulário técnico. Ele é uma **ficha executiva de inspeção instantânea** projetada para que um analista, gestor ou operador responda às 4 perguntas mais importantes sobre um registro ou relacionamento em **menos de 2 segundos** — e copie um identificador ou o resumo estruturado completo em **1 clique** (para colar no SEI, Jira, Teams, Slack ou e-mail), respeitando rigorosamente a governança LGPD (*fail-closed*).

---

## 1. Anatomia Real do Cartão Quick View no Runtime Angular

Para curar informações inteligentes no backend Java, o autor do recurso (`Controller` + `ResponseDTO`) precisa compreender como o `ResourceQuickViewService` (`@praxisui/core`) e o `PraxisQuickViewOverlayComponent` (`@praxisui/dialog`) materializam o contrato publicado em `/schemas/filtered`:

1. **Cabeçalho Coeso em 3 Níveis (`@ResourceIdentity` -> `x-ui.resource.identity`):**
   - **Nível 1 (Eyebrow + Pílula de Chave):** exibe `[icon] CONTEXT LABEL` (extraídos do título/ícone da `@UiSurface` ou do `@ApiResource`) acompanhado na mesma linha superior pela pílula compacta do `keyField` (`[data-testid="quick-view-key-badge"]`, ex.: `#1`, `#1001:1`), com tooltip `"${label}: ${badge}"` e deduplicação automática caso `key.value` seja idêntico ao título principal. O `<praxis-resource-identity>` recebe `[showKey]="false"` para eliminar linhas órfãs intermediárias entre o eyebrow e o título.
   - **Nível 2 (`titleField`):** título humano principal do registro em destaque (`1.04rem`, `font-weight: 700`, `line-height: 1.28`), acompanhado de `avatarField` validado quando disponível.
   - **Nível 3 (`subtitleField`):** subtítulo contextual imediatamente abaixo do título (`Helena Vasconcelos de Albuquerque` num vínculo ou averbação; `Nome social` numa pessoa; `Sigla` num órgão).
2. **Corpo Executivo do Cartão (`summaryItems`):**
   - Quando o cartão possui itens de resumo (`summaryItems.length > 0`), o cabeçalho `PraxisResourceIdentityComponent` **oculta os chips de `metadata`** para evitar o efeito "boneca russa" (duplicação entre cabeçalho e corpo).
   - O `ResourceQuickViewService.buildSummaryItems` constrói a lista de linhas do corpo nesta **ordem estrita**:
     1. **Primeiro (`Linhas 1..M`):** os campos declarados em `@ResourceIdentity(metadataFields = {...})` que tenham valor não-vazio no payload;
     2. **Depois (`Linhas M+1..N`):** os campos do `ResponseDTO` anotados com `@UISchema(quickViewVisible = true)`, ordenados por `quickViewOrder` crescente (`1, 2, 3...`).
3. **Barra de Ações Contextuais Rápidas (`contextualActions` / `[data-testid="quick-view-contextual-actions"]`):**
   - A partir do catálogo `GET /{id}/surfaces` já descoberto para o item, `ResourceQuickViewService.buildContextualActions` seleciona até **4 superfícies `ITEM` autorizadas (`availability.allowed === true`)** adicionais (excluindo a superfície de preview ativa e a superfície primária de `"Abrir detalhe"`).
   - A curadoria prioriza `@ResourceIntent`s (`PARTIAL_FORM` -> abre em `modal`), edição (`FORM` -> abre em `modal`) e projeções analíticas/360° (`VIEW` / `READ_PROJECTION` -> abre em `drawer`).
   - Ao clicar em uma pílula de ação contextual (`[data-testid="quick-view-contextual-action"]`), `openSurfaceFromActivePreview(surfaceId)` fecha o popover Quick View e abre diretamente a superfície escolhida com a apresentação apropriada (`modal` ou `drawer`).
4. **Ações de Cópia e Rodapé (`Copiar` por linha + `Copiar resumo` + `Abrir detalhe`):**
   - **Cópia pontual por linha (`.pdx-quick-view__copy-btn`):** ícone discreto de cópia à direita de cada linha copiável, revelado em `:hover` / `:focus-within` (ou em modo mobile/narrow), com confirmação visual imediata (`check` verde + `data-copied="true"` por `1800ms`).
   - **Cópia executiva do cartão (`Copiar resumo` / `[data-testid="quick-view-copy-summary"]`):** botão secundário no rodapé que copia uma ficha multi-linha estruturada (`[Contexto] Título (#Chave)`, `Subtítulo` e `• Rótulo: Valor Formatado` para todos os itens não mascarados).
   - **Proteção LGPD Fail-Closed:** qualquer campo cujo valor esteja mascarado (`masked = true`, seja por `visibleForAuthorities` negado em `x-ui.fieldAccess` ou por token mascarado `••` / `**`) exibe o badge **`LGPD`** (`[data-testid="quick-view-masked-indicator"]`), recebe `copyable = false`, não exibe botão de cópia na linha e **é estritamente omitido do texto gerado por `Copiar resumo`**.

---

## 2. As 7 Regras de Ouro da Curadoria Executiva (`Quick View Curation Gate`)

### Regra 1 — Proibição Absoluta de Overlap entre Identidade e Corpo
- **Nunca** repita `keyField`, `titleField`, `subtitleField` ou `avatarField` dentro de `metadataFields` (ex.: `keyField = "id"` com `metadataFields = {"id", ...}` é **erro de auditoria** em `audit-java-resource-completeness.py`).
- **Nunca** marque um campo listado em `metadataFields` com `quickViewVisible = true`.
- O `@ResourceIdentity` declarado em `@ApiResource(identity = ...)` no `Controller` e o `@ResourceIdentity` na classe `ResponseDTO` devem ser **idênticos**.

### Regra 2 — Hierarquia Executiva (`metadataFields` = Topo; `quickViewOrder = 1..8` = Corpo)
Como `metadataFields` ocupa as **primeiras linhas** do corpo do cartão Quick View, escolha seus campos com critério executivo:
- **`metadataFields` (2 a 3 campos de enquadramento imediato):**
  - *Pessoa / Colaborador:* `{"cpf", "idadeAnos"}` (identificação fiscal governada por LGPD + idade calculada);
  - *Vínculo Funcional:* `{"setorNome", "regimejur", "categoria"}` (lotação + regime jurídico + categoria funcional);
  - *Cargo / Função:* `{"cargoFuncao", "cbo"}` (natureza cargo/função + código CBO);
  - *Órgão / Empresa:* `{"razao", "telefone", "email"}` (razão social + canais institucionais).
- **`quickViewVisible = true` (4 a 8 campos com `quickViewOrder = 1..N` sequencial):**
  Ordene os campos pela pirâmide de decisão operacional:
  1. **`quickViewOrder = 1` — Situação / Status Operacional:** (`situacao`, `ativa`, `status` — com `UISchemaPreset.ENTERPRISE_STATUS` ou `tone` e `icon = "verified"`), permitindo ver imediatamente o badge colorido (`Ativo`, `Afastado`, `Cedido`, `Homologado`);
  2. **`quickViewOrder = 2..3` — Papel, Cargo, Lotação ou Classificação Principal:** (`cargoNome`, `tipovinc`, `estcivil`, `escolaridade` — sempre usando o campo de nome resolvido ou `SELECT` com `options`, nunca IDs brutos);
  3. **`quickViewOrder = 4..5` — Marcos Temporais, Vigência ou Canais de Contato Direto:** (`dtexerc`, `dtnasc`, `dtini`, `dtfim`, `eMail`, `numtelCelular`);
  4. **`quickViewOrder = 6..8` — Indicadores Quantitativos, Contadores Derivados ou Escopo Institucional:** (`jornadaSemanal`, `orgaoNome`, `diasDesdeExercicio`, `diasReconhecidos`, `saldoVagasDisponiveis`, `dtRecadast`).
- **O que é PROIBIDO colocar em `metadataFields` ou `quickViewVisible`:**
  - Campos protocolares de baixíssimo valor decisório (ex.: `tratamento` = `"Sra."` no topo do cartão);
  - Campos quase sempre nulos ou constantes em 99% da base (ex.: `observacao` livre, `nacionalidade = 10` quando não é recorte internacional, `ufender` solto sem município);
  - Chaves estrangeiras numéricas brutas (`empCodigo`, `titularAtualId`, `setorId`, `cargoId`) quando o DTO possui (ou deve possuir) o campo de exibição humano (`orgaoNome`, `titularAtualNome`, `setorNome`, `cargoNome`).

### Regra 3 — Tradução Obrigatória de Códigos de Domínio (`options` vs. `OptionSource`)
Nenhum campo exibido no Quick View, na tabela ou na gaveta 360° pode apresentar códigos técnicos crus do banco (`MESTRADO`, `CASADO`, `10`, `1`, `C`, `F`) sem tradução semântica:
- **Domínios fechados / tabelas de código estáveis:** declare `controlType = FieldControlType.SELECT` e `options = "[{\"value\":\"MESTRADO\",\"label\":\"Pós-Graduação Stricto Sensu (Mestrado)\"}, ...]"` no `@UISchema`. O `ResourceQuickViewService` resolve automaticamente `rawValue -> option.label` tanto na exibição do cartão quanto no texto de `Copiar resumo`.
- **Entidades relacionais ou catálogos dinâmicos por tenant:** exponha o campo de exibição resolvido (`cargoNome`, `setorNome`, `orgaoNome`) anotado com `@ResourceReference(...)` ou vincule o campo via `OptionSourceDescriptor`.

### Regra 4 — Formatação Numérica Estrita (`NumericFormat.INTEGER` para Inteiros e Contadores)
Contadores de dias (`diasReconhecidos`, `diasDesdeExercicio`), quantidades de vagas (`vagasAutorizadas`, `saldoVagasDisponiveis`), cargas horárias (`jornadaSemanal`), anos e idades (`idadeAnos`) **jamais** podem ser exibidos com casas decimais artificiais (`1.826,00`).
- Declare explicitamente `numericFormat = NumericFormat.INTEGER` no `@UISchema` de campos inteiros;
- O runtime `@praxisui/core` também preserva `precision = 0` por padrão para qualquer propriedade cujo schema OpenAPI seja `integer` / `int32` / `int64`.

### Regra 5 — Ícones Semânticos em Todos os Campos do Quick View
Todo atributo listado em `metadataFields` ou com `quickViewVisible = true` deve declarar `icon = "..."` no `@UISchema` usando ícones Material semânticos (`verified`, `badge`, `work`, `event`, `cake`, `favorite`, `school`, `email`, `smartphone`, `location_city`, `apartment`, `timelapse`, `schedule`). Isso cria âncoras visuais imediatas em cada linha do `<dl class="pdx-quick-view__summary-list">`.

### Regra 6 — Navegação Cross-Resource em 1 Clique (`@ResourceReference` + `previewSurfaceId`)
- O campo `titleField` do próprio recurso **e** todos os campos de exibição de chaves estrangeiras (`pessoaNome`, `cargoNome`, `setorNome`, `orgaoNome`, etc.) no `ResponseDTO` devem declarar:
  ```java
  @ResourceReference(
      resourceKey = "administracao-pessoal.cargos",
      valueField = "cargo",
      surfaceId = "ficha-cargo",
      previewSurfaceId = "ficha-cargo",
      detailPresentation = "drawer")
  ```
- O recurso de destino deve publicar a `@UiSurface(id = "ficha-cargo", kind = SurfaceKind.READ_PROJECTION, scope = SurfaceScope.ITEM, ...)` correspondente.

### Regra 7 — Performance da Gaveta 360° e Descoberta sem `enrichResponses` Duplicado
- No `praxis-metadata-starter`, os endpoints de descoberta contextual de item (`GET /{id}/surfaces`, `GET /{id}/actions`, `GET /{id}/capabilities` e `filterRelatedChildResource(parentId, ...)`) invocavam historicamente `service.findById(id)`, disparando `toResourceRepresentation` + `enrichResponses` (queries agregadas de contagens/coleções) apenas para checar existência/autorização.
- **Padrão Canônico:** o `AbstractResourceQueryController` utiliza `service.assertItemAccessible(id)`, que carrega apenas a entidade via `findEntityById(id)`, valida existência (`404`) e escopo/tenant (`403` via `assertEntityAccessAllowed`), e popula o `ResourceStateSnapshotScope` compartilhado sem executar `enrichResponses`.
- **Reuso de Payload (`sameAsItemEndpoint`):** quando a superfície `READ_PROJECTION` / `VIEW` de um recurso retorna o próprio `ResponseDTO` sem projeção reduzida distinta, prefira vinculá-la ao `GET /{id}` canônico (ou usar um DTO de projeção leve dedicado sem `enrichResponses` pesado), permitindo que o `ResourceRecordOpenService` reutilize o payload de `GET /{id}` e hidratando a gaveta 360° na metade do tempo.
- **Deduplicação In-Flight no Frontend:** `ResourceDiscoveryService.getCapabilitiesByUrl` e `loadActionCatalog` deduplicam requisições concorrentes em voo (`shareReplay(1)` + limpeza em `finalize`) para a mesma chave de contexto/URL, evitando chamadas HTTP duplicadas na abertura de tabelas e gavetas 360°.

---

## 3. Exemplo Canônico Completo (`ResponseDTO` + `Controller`)

```java
// No Controller:
@ApiResource(
    value = ApiPaths.PESSOAS,
    resourceKey = "administracao-pessoal.funcionarios",
    title = "Pessoas servidoras",
    description = "Cadastro civil único das pessoas titulares de vínculos funcionais com órgãos públicos.",
    icon = "badge",
    identity = @ResourceIdentity(
        keyField = "id",
        titleField = "nome",
        displayLabelField = "nome",
        subtitleField = "nomeSocial",
        metadataFields = {"cpf", "idadeAnos"}))

// No ResponseDTO:
@ResourceIdentity(
    keyField = "id",
    titleField = "nome",
    displayLabelField = "nome",
    subtitleField = "nomeSocial",
    metadataFields = {"cpf", "idadeAnos"})
public class ServidorPessoaResponseDTO extends ServidorPessoaDados {

  @Schema(description = "Idade civil em anos completos na data atual.", accessMode = Schema.AccessMode.READ_ONLY)
  @UISchema(label = "Idade", icon = "cake", group = "Identificação civil",
      numericFormat = NumericFormat.INTEGER, tableHidden = true, readOnly = true, formHidden = true)
  private Integer idadeAnos;

  // Em ServidorPessoaDados:
  // quickViewOrder = 1: dtnasc (Nascimento, icon = "event", DATE_PICKER)
  // quickViewOrder = 2: estcivil (Estado civil, icon = "favorite", SELECT com options legíveis)
  // quickViewOrder = 3: escolaridade (Escolaridade, icon = "school", SELECT com options legíveis)
  // quickViewOrder = 4: eMail (E-mail, icon = "email", EMAIL_INPUT)
  // quickViewOrder = 5: numtelCelular (Celular, icon = "smartphone")
  // quickViewOrder = 6: cidadeender (Município, icon = "location_city")
  // quickViewOrder = 7: dtRecadast (Último recadastramento, icon = "event", DATE_PICKER)
}
```
