# Schema‑driven UI

## Definição curta
Interfaces geradas e configuradas a partir de esquemas de dados e metadados (ex.: OpenAPI + `x-ui`). A UI interpreta o contrato em runtime para derivar colunas, formulários, validações e interações, em vez de código fixo.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:88` — uso de `resourcePath` para gerar colunas e carregar dados automaticamente.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:242` — `getSchema()` e `getFilterSchema()` com derivação do `/schemas/filtered` e cache.
- Backend: `backend-libs/praxis-metadata-starter/README.md:164` — ordem de precedência e auto‑detecção de controles (tabela de mapeamento).
- Exemplo: `examples/praxis-backend-libs-sample-app/src/main/java/...Controller.java` — controllers CRUD padrão com `@ApiResource`/`@ApiGroup`.

## Como aplicar (passo a passo)
1) No backend, anotar DTOs com `@UISchema` e expor o starter (`praxis-metadata-starter`).
2) No frontend, usar `<praxis-table [resourcePath]="'employees'">` ou `GenericCrudService.configure('employees')`.
3) Para filtros/Forms, usar `@praxis/dynamic-form` com `schemaSource='filter'` quando a UI for um filtro.
4) Evitar hardcode de colunas/labels; preferir overrides via `SettingsPanel` ou `GlobalConfig`.

## Exemplos mínimos
### Exemplo colável (resourcePath → render → override → persistir)

Template:
```html
<praxis-table resourcePath="employees" [editModeEnabled]="true"></praxis-table>
```

Comportamento:
- Gera colunas via `/schemas/filtered` (c/ ETag e cache); carrega dados com paginação/sort.
- Abre o Settings Panel (ícone engrenagem) para: ocultar/reordenar colunas, sobrescrever labels.
- Persistência: configurações salvas via `ConfigStorage` (local) e podem ser reaplicadas.

Serviço (carregar schema manualmente, opcional):
```ts
crud.configure('employees');
crud.getSchema().subscribe(def => console.log(def));
```

## Anti‑padrões
- Renderizar colunas/inputs manualmente ignorando metadados do contrato.
- Copiar schema estaticamente para o front (drift e versionamento quebrado).

## Referências internas
- backend-libs/praxis-metadata-starter/README.md:172
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/services/generic-crud.service.ts:1003
- frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:151
- frontend-libs/praxis-ui-workspace/README.md:103
## Testabilidade
- Front: use `HttpTestingController` para mockar `/schemas/filtered` e validar geração de colunas/ações.
- Verifique que mudanças no Settings Panel refletem no `ConfigStorage` e no próximo render.

## Veja também
- [Self‑describing APIs](./self-describing-apis.md)
- [Configuration‑driven development](./configuration-driven-development.md)
- [Declarative UI](./declarative-ui.md)

