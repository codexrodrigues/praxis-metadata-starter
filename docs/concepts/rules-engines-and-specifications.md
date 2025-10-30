# Rules Engines & Specifications

## Definição curta
Regras declarativas de negócio/visual expressas como especificações avaliáveis (Specification Pattern) e/ou mini‑DSL, com ferramentas visuais para construir, serializar e avaliar condições em tempo de execução.

## Onde aparece no Praxis
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-visual-builder/README.md:1` — Visual Builder, mini‑DSL, Context Variables, SpecificationBridge.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-specification-core/src/lib/specification/specification.ts:3` — núcleo de Specifications.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-table/src/lib/praxis-table.ts:259` — cache e uso de SpecificationBridge para regras visuais.
- Frontend: `frontend-libs/praxis-ui-workspace/projects/praxis-core/src/lib/models/form/form-layout.model.ts:154` — condições de visibilidade/hidden via string/Specification.

## Como aplicar (passo a passo)
1) Modele regras no Visual Builder ou via mini‑DSL; converta para Specification com o `SpecificationBridgeService`.
2) Associe a Specification ao contexto (dados/estado) e avalie no ponto de decisão (ex.: visibilidade de coluna, estado de campo).
3) Para backend, converta filtros para Specifications JPA (`GenericSpecificationsBuilder`).

## Exemplos mínimos
- Tabela usando regras visuais:
  - `frontend-libs/praxis-ui-workspace/projects/praxis-table/src/lib/praxis-table.ts:599`
- Mini‑DSL + bridge:
  - `frontend-libs/praxis-ui-workspace/projects/praxis-visual-builder/README.md:416`

## Anti‑padrões
- Codificar regras dispersas no componente sem centralizar/serializar.
- Não prover contexto consistente para avaliação (variáveis de contexto inconsistentes).

## Referências internas
- frontend-libs/praxis-ui-workspace/INTEGRATION-PLAN.md:163
- frontend-libs/praxis-ui-workspace/README.md:518
- frontend-libs/praxis-ui-workspace/projects/praxis-table/README.md:524

## Security hooks e escopo
- Modele regras de visibilidade/escopo como Specifications; avalie com variáveis de contexto (role, tenant, attributes) no Visual Builder.
- Policy‑as‑code: serialize, versionar e auditar regras; aplique tanto em UI (condições de exibição) quanto no backend (Specifications JPA).

## Veja também
- [Configuration‑driven development](./configuration-driven-development.md)
- [Self‑describing APIs](./self-describing-apis.md)
- [Declarative UI](./declarative-ui.md)

