# Headless UI & Design Systems

## Definição curta
Separar comportamento/estado de UI da aparência/tema, permitindo que componentes sigam contratos de metadados e configurações (headless), enquanto o design/tema é plugável. Integra com um design system (ex.: Angular Material) sem acoplamento rígido.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core` — modelos/metadados unificados independentes de tema.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-fields` — componentes Material implementam o contrato headless dos metadados.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-settings-panel` — editores headless com contrato `onSave()`; aparência via DS.
- Frontend: `frontend-libs/praxis-ui-workspace/docs/global-config.md` — governança de comportamento via config (não por tema).

## Como aplicar (passo a passo)
1) Defina metadados/contratos no `@praxis/core` e mantenha os componentes de UI como adaptações desses contratos.
2) Evite estilos/temas embutidos no domínio; prefira camadas de estilo do DS.
3) Use o Settings Panel e Global Config para comportamento, e o DS apenas para aparência.

## Exemplos mínimos
- Field metadata → componente Material:
  - `frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-fields/src/lib/components/text-input/text-input.metadata.spec.ts:13`
- Governança via GlobalConfig:
  - `frontend-libs/praxis-ui-workspace/docs/global-config.md:27`

Snippet (metadado headless → render Material):
```ts
const field = { name: 'nome', label: 'Nome', controlType: FieldControlType.INPUT };
const comp = await registry.getComponent(field.controlType);
container.createComponent(comp, { inputs: { field } });
```

## Anti‑padrões
- Embutir decisões de design (cores/layout) no contrato de domínio.
- Duplicar componentes para trocar apenas tema.

## Referências internas
- frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/models/material-field-metadata.interface.ts:980
- frontend-libs/praxis-ui-workspace/projects/praxis-dynamic-fields/README.md
- frontend-libs/praxis-ui-workspace/README.md

## Veja também
- [Declarative UI](./declarative-ui.md)
- [Dynamic Component Rendering](./dynamic-component-rendering.md)
- [Configuration‑driven development](./configuration-driven-development.md)

