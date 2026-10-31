# Conceitos Fundamentais da Plataforma Praxis

Conceitos arquiteturais da **Praxis Platform** — uma plataforma semanticamente orientada para aplicações corporativas AI-native, em que recursos, regras, ações, dados e políticas são descritos uma vez e podem ser operados por interfaces tradicionais, interfaces generativas, agentes de IA e integrações. Em vez de construir IA ao lado da aplicação, esses conceitos sustentam aplicações que, por definição, já nascem legíveis e operáveis por IA.

## Navegação

- [Home da documentação](../index.html)
- [Visão geral funcional](../overview/index.html)
- [Arquitetura e pacotes](../architecture-overview.html)
- [Hub dos guias operacionais](../guides/index.html)

## Tópicos Conceituais

| Conceito | O que esclarece |
| :--- | :--- |
| [UI Schema (`@UISchema` e `x-ui`)](ui-schema.html) | Vocabulário `x-ui`, precedência do `CustomOpenApiResolver`, validações e `valuePresentation`. |
| [UI Schema vs Data Schema](ui-schema-vs-data-schema.html) | Separação entre contrato de dados/transporte e intenção de apresentação, edição e descoberta. |
| [Self-describing APIs](self-describing-apis.html) | Como o backend publica estrutura (`/schemas/filtered`), semântica (`/schemas/domain`), surfaces, actions, capabilities e HATEOAS. |
| [Schema-driven UI](schema-driven-ui.html) | Geração e materialização de tabelas, formulários, filtros, diálogos de comando e gráficos a partir de contratos publicados. |
| [Metadata-driven e Backend-agnostic](metadata-driven-backend-agnostic.html) | Desacoplamento entre a semântica publicada em OpenAPI + `x-ui` e o runtime consumidor (`@praxisui/*`, cockpit ou agentes de IA). |
| [Declarative UI](declarative-ui.html) | Declaração de intenção (`@UISchema`, `@UiSurface`, `@WorkflowAction`) em vez de código imperativo repetitivo por tela. |
| [Configuration-driven Development](configuration-driven-development.html) | Fronteira entre metadados canônicos de código (`praxis-metadata-starter`) e variações governadas por tenant/usuário (`praxis-config-starter`). |
| [Dynamic Component Rendering](dynamic-component-rendering.html) | Resolução em runtime de `controlType`, `valuePresentation`, `optionSource` e surfaces semânticas no cliente e no `/praxis/cockpit`. |
| [Headless UI and Design Systems](headless-ui-and-design-systems.html) | Separação entre comportamento/semântica do contrato (`x-ui`) e identidade visual/componentes corporativos. |
| [Rules Engines and Specifications](rules-engines-and-specifications.html) | Filtros dinâmicos (`@Filterable` + `GenericSpecificationsBuilder`), Json Logic canônico e determinações reativas. |

## Quando consultar esta seção

- Para alinhar o vocabulário arquitetural da plataforma antes de modelar um novo domínio.
- Para decidir se uma regra pertence ao contrato estrutural (`praxis-metadata-starter`), à configuração governada (`praxis-config-starter`) ou ao runtime visual (`@praxisui/*`).
- Para complementar a árvore de decisão prática em [Quando usar Resource, Surface, Action e Capability](../guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.html).
