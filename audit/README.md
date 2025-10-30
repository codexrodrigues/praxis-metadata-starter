# Auditoria de Projetos que Usam o Praxis Metadata Starter

Este pacote de documentação orienta uma auditoria faseada para projetos que utilizam o `praxis-metadata-starter`, com checklists detalhados, referências e prompts prontos por fase para outro agente executar.

Estrutura:

- docs/audit/README.md (este índice)
- docs/audit/CHECKLIST-GERAL.md (visão consolidada)
- docs/audit/fases/FASE-01-BUILD-E-DEPENDENCIAS.md
- docs/audit/fases/FASE-02-CONTROLLERS-E-GRUPOS-OPENAPI.md
- docs/audit/fases/FASE-03-DTOS-VALIDACAO-UISCHEMA.md
- docs/audit/fases/FASE-04-SERVICES-REPOS-OPTIONS-SORT.md
- docs/audit/fases/FASE-05-FILTROS-PAGINACAO-OPCOES.md
- docs/audit/fases/FASE-06-ERROS-HATEOAS-OPENAPI-ETAG.md
- docs/audit/fases/FASE-07-MAPSTRUCT-FAIL-FAST.md
- docs/audit/fases/FASE-08-AUTO-CONFIG-GRUPOS-FALLBACK.md

Como usar:

1) Leia o checklist consolidado em docs/audit/CHECKLIST-GERAL.md
2) Execute cada fase na ordem, usando o arquivo da fase correspondente (cada um contém:
   - Objetivo, Checklist, Verificações, Correções comuns, Referências, Prompt para agente)
3) Registre evidências (trechos de código, logs, saídas de comandos) antes de seguir à próxima fase

Requisitos do ambiente (resumo):

- Java 21; Maven Wrapper; rede habilitada para baixar dependências quando necessário
- Use os comandos em batch: `./mvnw -B -DskipTests` (e `-T 1C` quando pertinente)

Referências úteis do starter neste módulo:

- README do starter: README.md:1
- Auto-configuração: src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java:1
- Controller base: src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:1
- Resolução de grupos e schemas: src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1
- Filtros: src/main/java/org/praxisplatform/uischema/filter
- Services base: src/main/java/org/praxisplatform/uischema/service/base
- MapStruct config: src/main/java/org/praxisplatform/uischema/mapper/config/CorporateMapperConfig.java:1

