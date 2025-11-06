# Guia de Conformidade — Especificação Praxis x‑ui v1.0.0

Objetivo
- Descrever como validar JSONs gerados por qualquer backend contra os JSON Schemas desta pasta.
- Definir critérios mínimos (Core) e opcionais (Extended) de conformidade.
- Mapear, de forma resumida, quais chaves a UI consome e onde.

Como validar localmente (AJV CLI)
- Pré‑requisitos: Node.js 18+
- Instalação local do ajv-cli (projeto host):
  - npm i -D ajv ajv-formats ajv-cli
- Exemplos de execução:
  - Validar x‑ui de campo:
    - npx ajv -s ./x-ui-field.schema.json -d ./examples/x-ui-field.valid.json -c ajv-formats
    - npx ajv -s ./x-ui-field.schema.json -d ./examples/x-ui-field.invalid.json -c ajv-formats || echo "(esperado: inválido)"
  - Validar x‑ui de operação:
    - npx ajv -s ./x-ui-operation.schema.json -d ./examples/x-ui-operation.valid.json
  - Validar x‑ui.resource:
    - npx ajv -s ./x-ui-resource.schema.json -d ./examples/x-ui-resource.valid.json

Critérios de Conformidade
- Core (mínimo obrigatório)
  - x‑ui por campo segue o esquema e utiliza chaves canônicas para tipo, controle e validações básicas (ex.: `type`, `controlType`, `required`, `minLength`, `maxLength`, `pattern`).
  - x‑ui.resource presente no payload de `/schemas/filtered` contendo: `idField`, `idFieldValid`, `readOnly`, `capabilities` (valores boolean). `idFieldMessage` é opcional quando `idFieldValid=false`.
  - ETag forte no `/schemas/filtered` (header `ETag`) e hash determinístico em `X-Schema-Hash`. Ambos expostos via `Access-Control-Expose-Headers`.
  - `schemaId` estável conforme composição: `path|operation|schemaType|internal|tenant|locale`.
- Extended (recomendado)
  - Preencher `displayColumns`/`displayFields` no x‑ui de operação (padrões de grid/forms).
  - Popular mensagens de validação (`*Message`) para melhor UX.
  - Incluir `capabilities.options|byId|all|filter|cursor` quando aplicável.
  - Adotar `custom.*` para extensões privadas do host (sem colisão com canônicas).

Matriz — Chave da Spec → Consumo na UI
- x‑ui por campo
  - `type`, `controlType` → Mapeadas por `SchemaNormalizerService` ao `FieldDefinition` (projetos/praxis-core/.../schema-normalizer.service.ts: normalizeSchema)
  - `label` → Cabeçalho/rotulagem de inputs e colunas
  - `placeholder`, `hint`, `helpText` → Renderização de formulário (@praxisui/dynamic-fields)
  - `readOnly`, `disabled` → `field-state.util.ts` e componentes; normalizador unifica `readOnly` → `readonly`
  - Validações (`required`, `minLength`, `maxLength`, `pattern`, `min`, `max`, `range`, `*Message`) → `validators.helper.ts` e parsers do normalizador
  - Seleção (`options`, `endpoint`, `valueField`, `displayField`, `multiple`, `emptyOptionText`) → componentes select/multiselect/autocomplete
  - Numérico (`numericFormat`, `numeric*`) → componentes numéricos
- x‑ui por operação
  - `displayColumns` → Padrão de colunas iniciais (grid). Pode ser usado por reconciliadores/config inicial.
- x‑ui.resource (no payload `/schemas/filtered`)
  - `idField` → Usado por `GenericCrudService` e `PraxisTable` para chave primária (projects/praxis-core/.../generic-crud.service.ts; projects/praxis-table/README.md)
  - `idFieldValid`/`idFieldMessage` → Diagnóstico/alertas em hosts e ferramentas
  - `readOnly` → UI pode bloquear edição (campos e ações)
  - `capabilities` → Habilita/desabilita ações de CRUD e utilitários (filter/cursor/options)

Boas práticas e Notas
- `custom.*`: prefixo reservado para extensões de fornecedores/hosts. Não colida com chaves canônicas.
- O backend deve canonicalizar o payload antes de calcular o hash do schema (ETag forte ou `X-Schema-Hash`).
- Locale/Tenant variam o `schemaId` e os headers de cache (ETag/hash). Enviar `Accept-Language` e `X-Tenant`.

Compatibilidade (anotações temporárias)
- `filterOptions`: a spec define `array`, porém o starter atualmente serializa como `string` (JSON bruto) quando configurado via `@UISchema(filterOptions="{...}")`. Mantido assim por compatibilidade com consumidores atuais. Planejado ajuste futuro para emitir `array` (parse de JSON/CSV → array) com teste dedicado.

Suíte de Fixtures
- Esta pasta traz exemplos válidos e inválidos para facilitar a automação no CI.
- Use estes arquivos como base para gerar casos específicos do seu domínio.
