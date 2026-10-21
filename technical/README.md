# Technical Docs

Esta área concentra a referência técnica de engenharia do `praxis-metadata-starter`. Ela não é o onboarding principal.

Para onboarding e narrativa pública atual, use primeiro:

- [README principal](../../README.md)
- [Hub dos guias](../guides/README.md)
- [Visão arquitetural pública](../architecture-overview.md)

## O que permanece aqui

### Referências técnicas atuais

- [Auto-configuração](AUTO-CONFIGURACAO.md)
- [Guia de composição de operações em lote (Bulk)](BULK-COMPOSITION-GUIDE.md)
- [Status consolidado do SDK Bulk e candidatos de release](BULK-AND-RELEASE-CANDIDATES-STATUS.md)
- [Estudo de mercado e roadmap competitivo](ESTUDO-MERCADO-ROADMAP-METADATA-STARTER.md)
- [Estudo complementar - Praxis UI Angular metadata-driven](ESTUDO-PRAXIS-UI-ANGULAR-METADATA-DRIVEN.md)
- [Roadmap reconciliado de Skills Java e Praxis](CODEX-SKILLS-JAVA-PRAXIS-ROADMAP.md)
- [Roadmap de filtros - lotes 2 e 3](FILTROS-ROADMAP.md)
- [RFC - GraphQL Support](GRAPHQL-SUPPORT-RFC.md)
- [RFC - Governed Resource Command Execution](GOVERNED-RESOURCE-COMMAND-EXECUTION-RFC.md)

### Registros técnicos de rollout e piloto

- [Plano do Semantic Host Cockpit](SEMANTIC-HOST-COCKPIT-PLAN.md)
- [Spike de composição do Semantic Host Cockpit](SEMANTIC-HOST-COCKPIT-COMPOSITION-SPIKE.md)
- [Catálogo experimental de diagnostics do Semantic Host Cockpit](SEMANTIC-HOST-COCKPIT-DIAGNOSTICS-CATALOG.md)
- [Checklist de prontidão do piloto](PILOT-READINESS-CHECKLIST.md)
- [Rollback e observabilidade do piloto](ROLLBACK-E-OBSERVABILIDADE-DO-PILOTO.md)
- [Estado do worker durável B5b.2](BULK-DURABLE-WORKER-B5B2-STATUS.md)
- [Migração owner e cutover do worker durável — B5b.2](BULK-DURABLE-WORKER-OWNER-UPGRADE.md)
- [Composição explícita do worker — candidato B5b.3](BULK-WORKER-EXPLICIT-COMPOSITION.md)

## Regras de leitura

- O baseline canônico do starter é `resource + surfaces + actions + capabilities + HATEOAS`.
- Quando houver dúvida de contrato público, a prioridade é:
  1. `README.md`
  2. `docs/architecture-overview.md`
  3. `docs/guides/**`
  4. `docs/spec/**`
  5. esta área técnica
- Materiais de piloto, rollout e revalidação técnica não redefinem a narrativa pública principal.

## Referências relacionadas

- [Hub dos guias](../guides/README.md)
- [Examples](../examples/README.md)
- [Conformance](../spec/CONFORMANCE.md)
- [Architecture overview](../architecture-overview.md)
- [Heurística de control type](../concepts/CONTROLTYPE-HEURISTICA.md)
- Javadoc publicado: [GitHub Pages](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)
