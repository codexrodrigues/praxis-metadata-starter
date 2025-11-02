# ✅ Checklist de Validação — Código Gerado por IA (Praxis Platform)

Use esta lista após o agente (Claude/LLM) gerar código para sua aplicação ou recurso CRUD+Bulk.

## 1) Build e Execução
- mvn clean package executa sem erros
- Aplicação sobe (Spring Boot) na porta esperada
- Swagger UI disponível em /swagger-ui.html
- API Docs em /v3/api-docs e grupos habilitados

## 2) Grupos OpenAPI
- Grupos individuais por recurso existem (ex.: api-{modulo}-{recurso})
- Grupos agregados via @ApiGroup existem (ex.: {modulo})
- Endpoints não categorizados aparecem apenas no fallback “application”

## 3) Endpoints CRUD e Read-only
- GET /{id}, GET /all, POST /filter, POST /filter/cursor, POST /locate, GET /by-ids
- POST /, PUT /{id}, DELETE /{id}, DELETE /batch (ou 405 se read-only)

## 4) Options (id/label)
- POST /options/filter retorna OptionDTO paginado
- GET /options/by-ids reidrata labels preservando ordem
- @OptionLabel presente ou heurísticas de label funcionam

## 5) Filtros e Paginação
- FilterDTO usa @Filterable corretamente (operation, relation)
- /filter e /filter/cursor respeitam sort e limites (size ≤ praxis.pagination.max-size)
- /locate retorna LocateResponse coerente

## 6) Schemas e x-ui
- GET /schemas/filtered responde com x-ui enriquecido
- ETag presente; If-None-Match retorna 304 quando schema não muda
- idField resolvido/anotado (x-ui.resource.idField) ou mensagem de validação presente
- includeInternalSchemas=true retorna payload com $ref expandidos (quando desejado)

## 7) DTOs, MapStruct e Mapeamentos
- DTO reflete campos do recurso (com @UISchema nos campos visíveis)
- FilterDTO modela critérios de busca (incluindo ranges e relações)
- Mapeador manual para entidades simples; MapStruct para relacionamentos
- Métodos @Named para ID ↔ Entidade quando aplicável

## 8) Propriedades e Infra
- ApiPaths centraliza os paths
- Propriedades SpringDoc ativas (api-docs, grupos e Swagger UI)
- Perfis dev/prod configurados; H2 em dev se aplicável

## 9) Convenções e Organização
- Pacotes por módulo: entity, dto, mapper, repository, service, controller
- @ApiResource(ApiPaths...) presente; @ApiGroup coerente
- Nomes de classes, paths e grupos consistentes

## 10) Testes manuais rápidos
- Cadastrar/editar/remover registro (ou 405 para read-only)
- Filtrar por texto, número (between), data (between), boolean, IN/NOT_IN
- Paginar por cursor (after/before)
- Reidratar opções por IDs
- Consumir /schemas/filtered e verificar x-ui

---

Referências úteis:
- Guia Aplicação Nova: GUIA-CLAUDE-AI-APLICACAO-NOVA.md
- Guia CRUD+Bulk: GUIA-CLAUDE-AI-CRUD-BULK.md
