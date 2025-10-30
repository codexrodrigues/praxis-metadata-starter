# Fase 6 — Erros, HATEOAS, OpenAPI e Cache de Schema

Objetivo: respostas padronizadas de erro, HATEOAS funcional e documentação otimizada com cache/ETag.

Saídas esperadas:

- Confirmação de que handlers globais retornam `RestApiResponse` com `errors` estruturado nos cenários 400/404/500
- HATEOAS habilitado por padrão (`praxis.hateoas.enabled=true`) e links presentes nas respostas; `X-Data-Version` quando `getDatasetVersion()` existir
- `/schemas/filtered` suportando ETag/If‑None‑Match (ou evidência/descrição de como funcionaria no projeto)

Checklist

- `GlobalExceptionHandler` cobre: validação (400), regra de negócio, not found (404), erro genérico (500)
- Controllers base retornam Links úteis (self, all, filter, schema)
- `X-Data-Version` presente quando o service expõe `getDatasetVersion()`
- ApiDocsController resolve grupo automaticamente, cacheia documentos pequenos e responde com ETag

Verificações e evidências

- Revisar handlers e payloads
- Validar respostas do controller base (links, cabeçalhos)
- Testar mentalmente `/schemas/filtered` (ETag, 304) ou descrever a integração pretendida

Correções comuns

- Padronizar payloads de erro em endpoints customizados fora do controller base
- Habilitar/desabilitar HATEOAS por propriedade quando necessário

Referências rápidas

- Handlers: src/main/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.java:1
- Controller base (links/versão): src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java:867
- Schemas/ETag: src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java:1
- Plano de hash/ETag: docs/SCHEMA-HASH-PLAN.md:337

Prompt para agente

```
Tarefa: Auditar tratamento de erros, HATEOAS e cache/ETag de schemas.

Passos:
1) Revise o GlobalExceptionHandler e confirme que respostas seguem RestApiResponse com 'errors'
2) Valide links HATEOAS nas respostas e cabeçalho X-Data-Version quando aplicável
3) Verifique a presença de ETag/If-None-Match no /schemas/filtered (ou descreva como ficaria)

Entregue:
- Lacunas identificadas e diffs propostos
- Evidências de links e cabeçalhos nas respostas
- Evidência/descrição da estratégia de ETag
```

