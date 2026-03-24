# Relatorio de Rodada de Prova

## Identificacao

- rodada: 4
- data: 2026-03-24
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: com bootstrap fisico explicito e trilha MapStruct ja validada
- projeto sandbox: `tmp/ai-guide-proof-h2-filters-r1`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - derivado da rodada H2 com relacao, exigindo filtros reais com `LIKE`, `EQUAL` e `BETWEEN`
- restricoes adicionais:
  - migrations fisicas
  - uso de `MapStruct + CorporateMapperConfig`
  - validacao sequencial de mutacao e leitura

## Resultado

- status geral: aprovado com ressalva
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado com ressalva
- CRUD baseline: aprovado
- filtro rico: aprovado
- options: aprovado
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `./mvnw -B -f /mnt/d/Developer/praxis-plataform/tmp/ai-guide-proof-h2-filters-r1/pom.xml clean package`
- comando de startup:
  - `java -jar target/ai-guide-proof-h2-simple-1.0.0-SNAPSHOT.jar`
- migration adicional:
  - `src/main/resources/db/migration/V3__produto_filter_fields.sql`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK`, com campos `nome`, `ativo`, `categoriaId`, `precoBetween` e `dataCadastroRange`
- endpoint `POST /api/catalog/produtos`:
  - `201 Created` para `Produto Alpha`, `Produto Beta` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"nome\":\"Produto\"}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"ativo\":true}`:
  - `200 OK`, retornando `Produto Alpha` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"categoriaId\":2}`:
  - `200 OK`, retornando `Produto Alpha` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"precoBetween\":[10,30]}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"dataCadastroRange\":[\"2026-03-01\",\"2026-03-15\"]}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"ativo\":true,\"categoriaId\":2,\"precoBetween\":[0,20]}`:
  - `200 OK`, retornando apenas `Produto Alpha`

## Falhas encontradas

### Falha 1

- categoria: problema-real-do-starter
- sintoma: no schema `request` de `Produto`, `x-ui.resource.idField` continuou sendo `categoriaId`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`
- causa provavel:
  - heuristica do starter para `idField` em request schema com campo relacional `...Id`
- impede aprovacao: nao
- ajuste proposto:
  - investigar e corrigir a resolucao canônica de `idField` no starter

## Divergencias entre guia e codigo

- nenhuma bloqueante para a trilha de filtros ricos em H2

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta rodada

## Acoes apos a rodada

- ajustes no guia:
  - nenhum adicional obrigatorio
- ajustes no checklist:
  - opcionalmente incluir um item explicitando validacao minima de `LIKE`, `EQUAL` e `BETWEEN`
- ajustes no prompt:
  - manter a orientacao de executar requests sequencialmente nas proximas rodadas
- ajuste necessario no starter:
  - investigar `idField` do request schema em recursos com relacao

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar a rodada H2 com consumo Angular real do recurso gerado e validacao de `resourcePath`, `getSchema()`, `ETag` e `If-None-Match`
