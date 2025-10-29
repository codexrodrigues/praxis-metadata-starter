# PR — Fase 3: DTOs, Bean Validation e @UISchema

Título sugerido: Auditoria Fase 3 — Validações Jakarta, @Valid e metadados @UISchema

Descrição
- Reforça validação na borda (DTOs), garante `@Valid` em `create`/`update`, e orienta UI via `@UISchema` quando necessário.

Escopo
- DTOs de entrada com Jakarta Validation
- Assinaturas de controller com `@Valid`
- Ajustes de `FieldDataType` e uso consciente de `@UISchema`

Checklist de Aceite
- [ ] DTOs possuem validações mínimas em campos críticos
- [ ] `create`/`update` usam `@Valid @RequestBody`
- [ ] Não há uso de `FieldDataType.STRING` (substituído por `TEXT`)
- [ ] `@UISchema` aplicado apenas onde agrega valor

Evidências
- Lista de DTOs e anotações aplicadas (com referências `path:line`)
- Trechos de controllers com `@Valid`
- Diffs para correções propostas
- (Opcional) Schema gerado via `/schemas/filtered` evidenciando `x-ui.validation`

Configurações alteradas (se houver)
- Dependências de validação (se faltavam) e mensagens padronizadas

Riscos e rollback
- Rejeições 400 podem aumentar; rollback: escopo das validações por grupos

Passos de revisão/teste
- Enviar payloads inválidos e validar 400 + estrutura de `RestApiResponse.errors`

Fora de escopo
- Regras de negócio/processo (422)

Referências
- `README.md:58`
- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:682`
- `src/main/java/org/praxisplatform/uischema/FieldDataType.java:1`
- `src/main/java/org/praxisplatform/uischema/extension/CustomOpenApiResolver.java:31`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Validação manual via chamadas HTTP
