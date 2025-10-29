# PR — Fase 2: Controllers e Grupos OpenAPI

Título sugerido: Auditoria Fase 2 — @ApiResource, validação e resolução automática de grupos

Descrição
- Garante uso de `@ApiResource` em controllers base (`AbstractCrudController`), ajusta política de validação e valida `/schemas/filtered` sem `document`.

Escopo
- Conformidade de controllers com `@ApiResource`
- Política `praxis.openapi.validation.api-resource-required`
- Verificação de resolução automática em `/schemas/filtered`

Checklist de Aceite
- [ ] Todos os controllers base usam `@ApiResource("/api/...")`
- [ ] Política definida (DEV=WARN; PROD=FAIL sugerido)
- [ ] Amostra validada em `/schemas/filtered?path=/api/<...>/all` (quando possível)
- [ ] Patches propostos para não conformes

Evidências
- Inventário de controllers e status
- Comando/rota de schema testada e resultado (ou justificativa de impossibilidade)
- Diffs propostos dos controllers

Configurações alteradas (se houver)
- `application-*.properties`: `praxis.openapi.validation.api-resource-required=...`

Riscos e rollback
- Mudança de anotação pode afetar interceptadores; rollback: manter `@RestController + @RequestMapping` temporariamente

Passos de revisão/teste
- Verificar Swagger UI e dropdown de grupos
- Exercitar `/schemas/filtered` com paths de exemplo

Fora de escopo
- Refatorações de negócio fora de anotações/paths

Referências
- `src/main/java/org/praxisplatform/uischema/annotation/ApiResource.java:1`
- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:242`
- `docs/technical/VALIDACAO-API-RESOURCE.md:1`
- `docs/technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md:1`
- `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Validado no ambiente alvo
