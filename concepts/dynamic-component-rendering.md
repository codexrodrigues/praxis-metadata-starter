# Dynamic Component Rendering

## Definição curta
Renderização dinâmica de componentes baseada em metadados (schema + `x-ui`) e configuração, com registro/descoberta de componentes por tipo de controle e injeção de dependências.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-fields` — registro e resolução de componentes por `FieldControlType`.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table` — colunas e células geradas dinamicamente a partir do contrato.
- Backend: `backend-libs/praxis-metadata-starter/README.md` — metadados que mapeiam tipos e controles.

## Como aplicar (passo a passo)
1) Defina o contrato de campos (`FieldDefinition`) e tipos (`FieldControlType`).
2) Mantenha um registro (`ComponentRegistry`) que mapeia `FieldControlType` → componente.
3) Em runtime, resolva o componente pelo tipo e crie dinamicamente, passando metadados e contexto.

Exemplo (pseudo‑código):
```ts
const compType = registry.resolve(field.controlType);
viewContainerRef.createComponent(compType, { inputs: { field, value } });
```

## Anti‑padrões
- Branching manual longo por tipo de campo (ex.: `switch(controlType)` em todo lugar).
- Tipos/nomes de controle inconsistentes entre contrato e registro de componentes.

## Referências internas
- frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-fields/src/lib/registry/field-component.registry.ts
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/models/field-definition.model.ts

## Veja também
- [Headless UI & Design Systems](./headless-ui-and-design-systems.md)
- [Schema‑driven UI](./schema-driven-ui.md)

