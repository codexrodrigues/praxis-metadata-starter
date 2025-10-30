# Declarative UI

## Definição curta
Descrever o que renderizar e o seu comportamento em termos de metadados, configurações e estados (schemas, `x-ui`, `GlobalConfig`, `TableConfig`), deixando a infraestrutura decidir como renderizar.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-form` — definição de layout/estados (readonly/disabled/presentation) a partir de schema.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:140` — colunas e ações derivadas do contrato e configuráveis no Settings Panel.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/global-config.service.ts:68` — `GlobalConfigService` para defaults declarativos.
- Backend: `backend-libs/praxis-metadata-starter/README.md:172` — mapeamento declarativo de tipos→controles via `@UISchema`.

## Como aplicar (passo a passo)
1) Publique metadados ricos no backend (`@UISchema`).
2) No front, use componentes que interpretam schema (`<praxis-table>`, `@praxis/dynamic-form`).
3) Ajuste comportamentos via `GlobalConfig` e `SettingsPanel` em vez de duplicar componentes.

## Exemplos mínimos
### Imperativo vs Declarativo (mesmo caso)

Imperativo (anti‑padrão):
```ts
// Monta colunas manualmente, labels hardcoded, sem contrato
columns = [{ field: 'id', header: 'Código' }, { field: 'valor', header: 'Valor' }];
```

Declarativo:
```html
<praxis-table resourcePath="employees"></praxis-table>
```

Global Config (providers):
```ts
providers: [
  provideGlobalConfig({ table: { filteringUi: { advancedOpenMode: 'drawer' } } })
]
```

## Anti‑padrões
- Imperativo excessivo no componente para tratar casos que já existem via metadados.
- Clonar componente para mudar apenas labels/ordem (faça via config/Settings Panel).

## Referências internas
- frontend-libs/praxis-ui-workspace/docs/global-config.md
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/global-config.service.ts:27
- backend-libs/praxis-metadata-starter/README.md:189
## Acessibilidade & i18n
- Labels vindos do `x-ui` devem ser traduzíveis; conecte seu sistema de i18n no ponto de renderização de labels/mensagens.
- Use estados e semântica de componentes (readonly/disabled/presentation) para refletir acessibilidade (atributos `aria-*`).

## Veja também
- [Schema‑driven UI](./schema-driven-ui.md)
- [Headless UI & Design Systems](./headless-ui-and-design-systems.md)
- [Configuration‑driven development](./configuration-driven-development.md)

