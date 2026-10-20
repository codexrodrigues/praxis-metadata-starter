# Documentação do Praxis Metadata Starter

Este diretório reúne a documentação oficial do `praxis-metadata-starter` para desenvolvedores, arquitetos e assistentes de IA (LLMs).

## Estrutura das Pastas

- [`overview/`](overview/VISAO-GERAL.md) — visão geral funcional e executiva para quem está conhecendo o projeto
- [`guides/`](guides/README.md) — trilha principal passo a passo (do primeiro serviço Spring Boot ao consumo Angular e governança para IA)
- [`concepts/`](concepts/index.md) — conceitos fundamentais (`@UISchema`, *self-describing APIs*, *schema-driven UI*)
- [`examples/`](examples/README.md) — exemplos práticos de filtros, integração Spring Boot e estatísticas
- [`spec/`](spec/CONFORMANCE.md) — especificações normativas, JSON Schemas de `x-ui`, determinações reativas e protocolo de operações em lote (Bulk)
- [`technical/`](technical/README.md) — arquitetura interna de auto-configuração, RFCs, checklists de piloto e histórico técnico de release candidates

## Ponto de Entrada por Objetivo

- **Visão geral rápida:** [overview/VISAO-GERAL.md](overview/VISAO-GERAL.md)
- **Arquitetura e fluxo de enriquecimento:** [architecture-overview.md](architecture-overview.md)
- **Responsabilidade de cada pacote Java:** [packages-overview.md](packages-overview.md)
- **Trilha principal de implementação (Pessoas e LLMs):** [guides/README.md](guides/README.md)
- **Conceito de anotações Java para metadata de UI (`@UISchema`):** [concepts/ui-schema.md](concepts/ui-schema.md)
- **Contrato de exportação de coleções:** [guides/COLLECTION-EXPORT.md](guides/COLLECTION-EXPORT.md)
- **Disponibilidade contextual corporativa:** [guides/ENTERPRISE-AVAILABILITY-ADOPTION.md](guides/ENTERPRISE-AVAILABILITY-ADOPTION.md)
- **Status do SDK Bulk (`rc.154` e candidatos):** [technical/BULK-AND-RELEASE-CANDIDATES-STATUS.md](technical/BULK-AND-RELEASE-CANDIDATES-STATUS.md), [armazenamento](spec/BULK-PROPOSAL-STORAGE.md), [lifecycle](spec/BULK-OPERATION-LIFECYCLE.md) e [execução durável](spec/BULK-DURABLE-EXECUTION.md)
- **Prova operacional dos guias:** [guides/ai-proof/README.md](guides/ai-proof/README.md)
- **Exemplos complementares:** [examples/README.md](examples/README.md)
- **Referência técnica pública complementar:** [GitHub Pages / Javadoc](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)

## Hierarquia Canônica da Documentação

1. [`README.md` (raiz)](../README.md) e [`architecture-overview.md`](architecture-overview.md) apresentam a proposta de valor e as fronteiras da plataforma.
2. [`guides/`](guides/README.md) define a trilha principal de implementação passo a passo.
3. [`spec/CONFORMANCE.md`](spec/CONFORMANCE.md) valida a conformidade técnica dos contratos gerados.
4. [`technical/`](technical/README.md) e `apidocs/` oferecem suporte de engenharia aprofundado.

## Critério de Qualidade

Esta documentação permanece estritamente aderente ao:
- código canônico do `praxis-metadata-starter`
- contrato consumido pelo runtime Angular oficial (`@praxisui/*`) e pelo Praxis Cockpit
- protocolo de prova publicado em [`docs/guides/ai-proof/`](guides/ai-proof/README.md)
