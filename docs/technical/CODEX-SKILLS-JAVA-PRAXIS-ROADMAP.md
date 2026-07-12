# Roadmap Reconciliado De Skills Java E Praxis

Data: 2026-07-12

Classificacao: `docs-apenas`

Status: `reconciliado-com-repositorio-canonico-de-skills`

## Objetivo

Registrar como o `praxis-metadata-starter` deve interpretar o roadmap de skills
Codex para desenvolvimento Java/Praxis, sem transformar este repositorio na
fonte canonica das skills.

O objetivo nao e criar documentacao adicional por volume. O objetivo e garantir
que o conhecimento operacional do starter seja codificado em skills, scripts,
referencias e gates repetiveis no repositorio canonico
`praxis-codex-skills`, para que um Codex novo consiga:

- descobrir dominio, recurso, operacoes e contratos sem depender de tentativa;
- criar APIs Java obrigatorias com `praxis-metadata-starter`;
- preencher annotations semanticas com evidencia de negocio;
- publicar metadata, schemas, surfaces, actions, capabilities, options, stats,
  export, erros e governanca sem vazamento de detalhes privados;
- provar localmente que o contrato esta pronto para consumo runtime;
- em migracoes corporativas, expor uma borda Java/Praxis correta mesmo quando a
  regra de negocio ainda permanece em um legado privado durante a primeira fase.

## Premissa Corrigida

O roadmap anterior era util como inventario, mas ficou obsoleto ao afirmar que o
agregador de skills possuia apenas poucas skills e nenhuma familia Java/Praxis
central versionada.

Em 2026-07-12, o repositorio canonico `praxis-codex-skills` ja possui uma
familia Java/Praxis substancial. Portanto, a classificacao correta para a lacuna
observada e:

`ja-suportado-mal-nomeado-ou-mal-materializado`

A acao correta nao e abrir uma nova onda numerica de skills Java. A acao
correta e usar a familia existente em implementacoes reais, registrar falhas no
ledger de evidencia e promover ajustes apenas quando houver drift de plataforma
ou uma lacuna repetivel comprovada.

## Fonte Canonica

Este documento e uma referencia tecnica do `praxis-metadata-starter`. Ele nao
redefine o portfolio de skills.

Use como fonte canonica:

- `praxis-codex-skills/codex-skills/praxis-skills.manifest.json`
- `praxis-codex-skills/docs/praxis-java-skill-coverage-reconciliation-2026-07.md`
- o ledger ativo de evidencias `praxis-codex-skills#254`

## Familia Java/Praxis Atual

A familia canonica atual ja cobre:

| Skill | Uso principal |
| --- | --- |
| `praxis-java-host-project` | Bootstrap de host Java/Spring e composicao de starters |
| `praxis-java-resource-authoring` | Authoring completo de APIs Java/Praxis resource-oriented |
| `praxis-dto-annotations` | DTOs, filtros, comandos, `x-ui`, governanca e annotations semanticas |
| `praxis-java-filter-query-authoring` | Filtros, predicates, cursor, locate, sort e schemas filtrados |
| `praxis-java-option-source-provider-authoring` | Option sources provider-backed por contrato canonico |
| `praxis-resource-entity-lookup-backend` | `RESOURCE_ENTITY` option sources e contratos de lookup |
| `praxis-java-availability-discovery-authoring` | Actions, surfaces, capabilities e availability contextual |
| `praxis-java-command-concurrency-authoring` | Commands governados, idempotencia, ETag e conflitos |
| `praxis-java-error-response-contracts` | Falhas estruturadas de API Java/Praxis |
| `praxis-java-stats-export-authoring` | Stats, export, analytics, filtros e capabilities |
| `praxis-java-domain-governance-field-access` | Governanca de dominio, field access, mascaramento e AI policy |
| `praxis-java-autoconfiguration-starter-maintenance` | Auto-configuracao e extension points do starter |
| `praxis-java-config-boundary-integration` | Fronteira metadata/config e materializacoes governadas |
| `praxis-java-quickstart-proof` | Prova operacional no quickstart |
| `praxis-java-http-corpus-publication` | Publicacao de corpus HTTP apos prova Java |
| `praxis-java-contract-conformance` | Evidence pack final antes de handoff de API ou migracao |

## Como Usar Este Roadmap

Antes de propor nova skill ou contrato, um agente deve perguntar:

- o que o starter ja sabe e o consumidor ainda nao materializou bem?
- a lacuna e apenas UX, nome/materializacao ruim, suporte parcial ou contrato
  real?
- qual e a fonte canonica?
- qual prova local demonstra o contrato?

Novas follow-ups devem nascer apenas quando uma implementacao real provar que:

- uma tarefa Java roteia consistentemente para a skill ou dono canonico errado;
- uma skill deixa uma API Java fechar sem provar `/schemas/filtered`, actions,
  surfaces, capabilities, options, errors, stats/export ou fronteiras config;
- uma mudanca real em `praxis-metadata-starter` ou `praxis-config-starter`
  diverge da guidance atual;
- uma tarefa recorrente de migracao precisa de guidance Java/Praxis reutilizavel
  que nao seja especifica de um legado privado e nao esteja coberta pela familia
  atual.

## Regras De Fronteira

`praxis-metadata-starter` publica a fronteira semantica e estrutural que
runtimes, Cockpit, IA e consumidores usam para materializar decisoes. Ele nao e
apenas um gerador de JSON para Angular.

Skills `praxis-java-*` devem permanecer genericas de plataforma corporativa.
Elas podem apoiar migracoes, mas nao devem incorporar semantica de um legado
privado como contrato publico.

Quando a necessidade for especifica de uma fabrica de migracao, discovery
legado, ponte privada de dados, paridade com sistema antigo ou cleanup de massa,
a guidance deve ficar em skills de migracao dedicadas, consumindo Praxis sem
amarrar Praxis ao legado.

## Proximo Passo Operacional

Manter o ledger `praxis-codex-skills#254` como mecanismo ativo de evidencia.

Nao criar uma nova onda de skills Java por contagem. Usar a familia atual contra
mudancas reais de plataforma e abrir PR focado somente quando o uso revelar
drift, prova ausente ou lacuna concreta de contrato.
