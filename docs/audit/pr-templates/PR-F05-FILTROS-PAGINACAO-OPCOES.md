# PR — Fase 5: Filtros, Paginação e Opções

Título sugerido: Auditoria Fase 5 — @Filterable, limites de paginação/IDs e endpoints de opções

Descrição
- Valida filtros tipados com `@Filterable`, respeito aos limites de paginação/IDs e disponibilidade de endpoints de opções.

Escopo
- Filter DTOs com `@Filterable` e `relation`
- Limites `praxis.pagination.max-size` e `praxis.query.by-ids.max`
- Endpoints `/options/filter` e `/options/by-ids`

Checklist de Aceite
- [ ] Campos filtráveis anotados com `@Filterable`
- [ ] Joins indicados via `relation` (`rel1.rel2.campo`) quando necessário
- [ ] Tipos compatíveis com operação: IN usa coleção; BETWEEN usa lista de 2; LIKE em String
- [ ] Controles de limite aplicados (422 quando excedidos)
- [ ] Endpoints de opções operacionais
- [ ] Campos relacionais de SELECT/MULTI_SELECT usam `endpoint=/{resource}/options/filter`, `valueField=id`, `displayField=label`
- [ ] Reidratação por `GET /{resource}/options/by-ids` para estado inicial de selects (ordem preservada)
- [ ] Listagens ricas usam `POST /{resource}/filter` (DTO completo), não `/options/filter`

Evidências
- Referências `path:line` dos FilterDTOs e anotações
- Diffs para inclusão de `@Filterable`/`relation`
- Exemplos de requests (filter/by-ids) e respostas
- (Opcional) Evidência de UI: `@UISchema` nos filtros quando aplicável (DATE_RANGE/RANGE_SLIDER/SELECT)
 - Print/trecho de schema x-ui mostrando `endpoint` correto para selects

Configurações alteradas (se houver)
- Ajustes de `application-*.properties` para limites

Riscos e rollback
- Consultas podem mudar seletividade; rollback: refinar operações/relations

Passos de revisão/teste
- Exercitar POST `/filter` e validar limites; testar `/options/*`

Fora de escopo
- Implementação de lógica de negócio além de filtros

Referências
- `src/main/java/org/praxisplatform/uischema/filter/annotation/Filterable.java:71`
- `src/main/java/org/praxisplatform/uischema/filter/specification/GenericSpecificationsBuilder.java:21`
- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:188`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Funcional validado
