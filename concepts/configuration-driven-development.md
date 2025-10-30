# Configuration‑driven development

## Definição curta
Evoluir funcionalidades através de configuração e metadados (contratos, `GlobalConfig`, `SettingsPanel`) ao invés de escrever novo código para cada variação. Permite customização segura e auditável em runtime.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/README.md:44` — providers para `GlobalConfig` (seed/remoto/tenant).
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-settings-panel` — painel genérico com contrato `onSave()` para editores.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:120` — edição visual de colunas/toolbar/comportamentos.
- Backend: `backend-libs/praxis-metadata-starter/README.md:66` — regras de validação e hints de UI injetados como `x-ui`.

## Como aplicar (passo a passo)
1) Defina defaults em `GlobalConfig` e permita overrides por tela via Settings Panel.
2) Modele contratos `x-ui` para que componentes leiam preferências de interação/visual.
3) Registre editores que implementam `onSave()` para persistir configurações.

## Exemplos mínimos
- Abrir Settings Panel:
  - `frontend-libs/praxis-ui-workspace/README.md:59`
- Tabela em modo de edição:
  - `frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:108`

## Anti‑padrões
- Forkar componente para alterar preferências de renderização que são configuráveis.
- Persistir configs ad‑hoc sem integração com os serviços do workspace.

## Referências internas
- frontend-libs/praxis-ui-workspace/docs/metadata-editors-architecture.praxis.md
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/global-config.service.ts:43
- frontend-libs/praxis-ui-workspace/projects/praxis-list/src/lib/editors/list-config-editor.component.ts:877
## Governança (quem pode mudar o quê)
- Hierarquia de overrides: `defaults → produto → tenant → usuário`.
- Operações seguras via Settings Panel: editores implementam `onSave()` com persistência em `ConfigStorage` ou backend.
- Multi‑tenant: isole `GlobalConfig` por `tenant` (ex.: `provideGlobalConfigTenant('tenant-acme')`).

## Veja também
- [Schema‑driven UI](./schema-driven-ui.md)
- [Declarative UI](./declarative-ui.md)
- [Rules Engines & Specifications](./rules-engines-and-specifications.md)

