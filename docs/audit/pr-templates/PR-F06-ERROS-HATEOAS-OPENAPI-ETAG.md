# PR — Fase 6: Erros, HATEOAS, OpenAPI e ETag

Título sugerido: Auditoria Fase 6 — RestApiResponse, links HATEOAS e ETag em /schemas/filtered

Descrição
- Padroniza respostas de erro via `GlobalExceptionHandler`, valida links HATEOAS e evidencia ETag/If-None-Match para schemas.

Escopo
- Handlers globais e payloads
- HATEOAS e `X-Data-Version`
- ETag/If-None-Match no `/schemas/filtered`

Checklist de Aceite
- [ ] Handlers cobrem cenários 400/404/500 com `RestApiResponse.errors`
- [ ] Links HATEOAS presentes (self, all, filter, schema)
- [ ] `X-Data-Version` aplicado quando `getDatasetVersion()` existir
- [ ] ETag/If-None-Match documentados/testados (quando viável)

Evidências
- Trechos de handlers e exemplos de respostas
- Respostas de controllers com links e cabeçalhos
- Requisições/headers relacionados a ETag (ou plano de como ativar)

Configurações alteradas (se houver)
- 

Riscos e rollback
- Mudanças em handlers podem afetar consumidores; rollback: versionar contrato

Passos de revisão/teste
- Simular validação inválida (400), not found (404) e erro genérico (500)

Fora de escopo
- Alteração de contratos de negócio

Referências
- `src/main/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.java:1`
- `src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:867`
- `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1`
- `docs/SCHEMA-HASH-PLAN.md:337`

Checklist final
- [ ] Evidências anexadas
- [ ] Diffs revisados
- [ ] Validação manual efetuada
