# Checklist de Validacao - Codigo Gerado por IA

Use esta lista apos o agente gerar codigo para uma aplicacao nova, um recurso
CRUD metadata-driven ou um frontend Angular.

## 1. Build e execucao

- `mvn clean package` executa sem erros no backend
- aplicacao Spring Boot sobe na porta esperada
- `npm run build` executa sem erros no frontend, quando aplicavel
- host Angular sobe sem erro de bootstrap, quando aplicavel

## 2. OpenAPI e grupos

- `GET /v3/api-docs` responde
- grupos OpenAPI esperados existem
- Swagger UI esta disponivel

## 3. Endpoints CRUD

- `GET /{id}`, `GET /all`, `POST /filter`, `POST /filter/cursor`,
  `POST /locate`, `GET /by-ids`
- `POST /`, `PUT /{id}`, `DELETE /{id}`, `DELETE /batch`
  ou `405` quando read-only

## 4. Options

- `POST /options/filter` retorna `OptionDTO` paginado quando houver select remoto
- `GET /options/by-ids` reidrata labels preservando ordem
- campos com `endpoint=".../options/filter"` usam `valueField=id` e
  `displayField=label`

## 5. Filtros e paginacao

- `FilterDTO` usa `@Filterable` corretamente
- `/filter` e `/filter/cursor` respeitam sort e limites
- `/locate` retorna resposta coerente

## 6. Schemas e x-ui

- `GET /schemas/filtered` responde com `x-ui`
- `ETag` esta presente
- `If-None-Match` retorna `304` quando o schema nao muda
- `x-ui.resource.idField` esta resolvido corretamente

## 7. DTOs, mapeamentos e service

- DTO reflete os campos visiveis do recurso
- `FilterDTO` modela criterios reais de busca
- `CorporateMapperConfig` e usado quando houver MapStruct
- `mergeUpdate(...)` preserva a semantica do aggregate

## 8. Infra e organizacao

- `ApiPaths` centraliza os paths
- propriedades SpringDoc estao ativas
- `app.openapi.internal-base-url` esta configurado quando necessario
- pacotes seguem a organizacao por modulo

## 9. Frontend Angular

- existe um host `praxis-crud` para CRUD completo
- `resource.path` e `idField` estao corretos
- `API_URL` esta coerente
- o recurso remoto carrega schema sem ajuste local de contrato
- `POST /api/.../filter?page=0&size=10` responde `200` no mesmo origin do host

## 10. Testes manuais rapidos

- cadastrar, editar e remover registro quando o recurso nao for read-only
- filtrar por texto, numero, data, boolean e listas
- consumir `/schemas/filtered` e verificar `x-ui`
- testar options quando houver relacao remota

## Referencias publicas

- guias principais: `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`, `GUIA-02-AI-BACKEND-CRUD-METADATA.md`, `GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`
- repositÃ³rio Git do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes npm relevantes: `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`, `@praxisui/crud`
