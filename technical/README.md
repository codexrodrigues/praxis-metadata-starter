# Technical Docs

Esta area concentra material tecnico de engenharia do `praxis-metadata-starter`.
Ela mistura referencias atuais, registros historicos de fechamento e planos
arquivados. Nao deve ser lida como onboarding principal.

Para onboarding e narrativa publica atual, use primeiro:

- [README principal](../README.md)
- [Hub dos guias](../guides/)
- [Visao arquitetural publica](../architecture-overview.md)

## Como ler esta area

### Referencias tecnicas atuais

Estes documentos ainda descrevem comportamento vivo do starter, mas podem citar
compatibilidade com o core legado quando isso fizer parte da implementacao atual.

- [Auto-configuracao](AUTO-CONFIGURACAO.md)
- [Piloto resource-oriented em src/test](RESOURCE-ORIENTED-PILOT-IN-SRC-TEST.md)

### Legado e migracao do core antigo

Estes documentos continuam uteis para quem ainda toca o core legado, mas nao
descrevem o baseline recomendado para recursos novos.

- [Estrategia de grupos OpenAPI do core legado e da transicao](ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md)
- [Validacao de @ApiResource no core legado](VALIDACAO-API-RESOURCE.md)

### Fechamentos historicos do baseline atual

Registros do que foi implementado e endurecido nas fases que consolidaram o
baseline `resource + surfaces + actions + capabilities`.

- [Fechamento da Fase 4 - Surfaces](PHASE-4-SURFACES-CLOSURE.md)
- [Fechamento da Fase 5 - Workflow Actions](PHASE-5-ACTIONS-CLOSURE.md)
- [Fechamento da Fase 6 - Capabilities Unificadas](PHASE-6-CAPABILITIES-CLOSURE.md)
- [Checklist de prontidao do piloto](PILOT-READINESS-CHECKLIST.md)
- [Rollback e observabilidade do piloto](ROLLBACK-E-OBSERVABILIDADE-DO-PILOTO.md)

### Planos e backlog arquivados ou em evolucao

Documentos de planejamento tecnico. Sao uteis para contexto e continuidade de
engenharia, mas nao substituem a fonte canonica do contrato publico atual.

- [Plano de reescrita do core resource/surface/action](RESOURCE-SURFACE-ACTION-ARCHITECTURE-PLAN.md)
- [Plano de cursor pagination / keyset](CURSOR-PAGINATION-KEYSET-PLAN.md)
- [Backlog executavel de cursor pagination / keyset](CURSOR-PAGINATION-KEYSET-BACKLOG.md)
- [Plano de filtered stats](FILTERED-STATS-PLAN.md)
- [Roadmap de filtros - lotes 2 e 3](FILTROS-ROADMAP.md)
- [Backlog E2E do starter](E2E-TEST-BACKLOG.md)
- [Stats response schema platform fix plan](STATS-RESPONSE-SCHEMA-PLATFORM-FIX-PLAN.md)

## Regras de leitura

- O baseline canonico do starter e `resource + surfaces + actions + capabilities + HATEOAS`.
- `AbstractCrudController`, `BaseCrudService` e afins sao superficie legada.
- Planos e fechamentos historicos ajudam a entender como o baseline atual surgiu,
  mas nao devem rebaixar a fonte canonica atual para a semantica antiga.
- Quando houver duvida de contrato publico, a prioridade e:
  1. `README.md`
  2. `docs/architecture-overview.md`
  3. `docs/guides/**`
  4. `docs/spec/**`
  5. esta area tecnica

## Referencias relacionadas

- [Hub dos guias](../guides/)
- [Examples](../examples/)
- [Conformance](../spec/CONFORMANCE.md)
- [Architecture overview](../architecture-overview.md)
- [Heuristica de control type](../concepts/CONTROLTYPE-HEURISTICA.md)
- Javadoc: [Visao geral](../apidocs/index.html), [Pacotes](../apidocs/allpackages-index.html)
