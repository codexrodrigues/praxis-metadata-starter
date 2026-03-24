# Relatorio de Rodada de Prova

## Identificacao

- rodada: 2
- data: 2026-03-24
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos ajuste de bootstrap local e mantendo baseline sem `@OptionLabel` explicito
- projeto sandbox: `tmp/ai-guide-proof-h2-relation-r1`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 2: H2 com relacao`
- restricoes adicionais:
  - migrations fisicas para `Categoria` e `Produto`
  - sem bulk

## Resultado

- status geral: aprovado com ressalva
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado com ressalva
- CRUD baseline: aprovado
- options: aprovado
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `./mvnw -B -f /mnt/d/Developer/praxis-plataform/tmp/ai-guide-proof-h2-relation-r1/pom.xml clean package`
- comando de startup:
  - `java -jar target/ai-guide-proof-h2-simple-1.0.0-SNAPSHOT.jar`
- endpoint `GET /api/catalog/produtos/schemas`:
  - `302`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/all&operation=get&schemaType=response`:
  - `200 OK`
- endpoint `POST /api/catalog/categorias`:
  - `201 Created`
- endpoint `POST /api/catalog/categorias/options/filter?page=0&size=20`:
  - `200 OK`, retornando `[{id:1,label:\"Categoria A\"}]`
- endpoint `GET /api/catalog/categorias/options/by-ids?ids=1`:
  - `200 OK`
- endpoint `POST /api/catalog/produtos`:
  - `201 Created`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20`:
  - `200 OK`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"categoriaId\":1}`:
  - `200 OK`

## Falhas encontradas

### Falha 1

- categoria: problema-real-do-starter
- sintoma: no schema `request` de `Produto`, `x-ui.resource.idField` veio como `categoriaId`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`
  - retorno: `"idField":"categoriaId","idFieldValid":true`
- causa provavel:
  - heuristica de `idField` no schema de filtro esta elegendo o primeiro campo `*Id` do request em vez do id canônico do recurso
- impede aprovacao: nao
- ajuste proposto:
  - tratar no starter a resolucao de `idField` do request schema para nao promover `categoriaId` como id do recurso

## Divergencias entre guia e codigo

- nenhuma bloqueante para o fluxo proposto de relacao + options

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta rodada

## Acoes apos a rodada

- ajustes no guia:
  - nenhum obrigatorio identificado
- ajustes no checklist:
  - considerar item explicito para validar `idField` do request schema em cenarios com `...Id` relacional
- ajustes no prompt:
  - nenhum obrigatorio identificado
- ajuste necessario no starter:
  - investigar heuristica de `idField` em request schemas com relacoes

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar `Rodada 3: PostgreSQL simples`
