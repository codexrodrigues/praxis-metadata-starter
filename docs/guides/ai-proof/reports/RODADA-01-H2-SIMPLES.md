# Relatorio de Rodada de Prova

## Identificacao

- rodada: 1
- data: 2026-03-24
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos ajuste documental de 2026-03-24, antes da repeticao da rodada
- projeto sandbox: `tmp/ai-guide-proof-h2-simple`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 1: H2 simples`
- restricoes adicionais:
  - sem bulk
  - sem relacoes
  - sem correcao manual de codigo antes do veredito

## Resultado

- status geral: reprovado
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado
- CRUD baseline: reprovado
- options: nao aplicavel
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `./mvnw -B -f /mnt/d/Developer/praxis-plataform/tmp/ai-guide-proof-h2-simple/pom.xml clean package`
- comando de startup:
  - `java -jar target/ai-guide-proof-h2-simple-1.0.0-SNAPSHOT.jar`
- endpoint `GET /v3/api-docs`:
  - `200 OK`
- endpoint `GET /{resource}/schemas`:
  - `302` para `/schemas/filtered?path=/api/catalog/categorias/all&operation=get&schemaType=response&idField=id&readOnly=false`
- endpoint `GET /schemas/filtered?...response`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `GET /schemas/filtered?...request`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `POST /{resource}`:
  - `500 Internal Server Error`
- endpoint `POST /{resource}/filter`:
  - `500 Internal Server Error`
- endpoint `POST /{resource}/options/filter`:
  - nao aplicavel
- endpoint `GET /{resource}/options/by-ids`:
  - nao aplicavel

## Falhas encontradas

### Falha 1

- categoria: guia-incompleto
- sintoma: a aplicacao compilou, subiu e publicou schemas, mas o CRUD real falhou em persistencia e consulta
- evidencia:
  - `POST /api/catalog/categorias` retornou `500`
  - `POST /api/catalog/categorias/filter` retornou `500`
  - log bruto: `Table "CATEGORIA" not found`
- causa provavel:
  - o guia orientava datasource e Flyway, mas nao mandava a LLM criar migration inicial nem configurar `ddl-auto` temporario para a primeira prova local
- impede aprovacao: sim
- ajuste proposto:
  - explicitar no guia a necessidade de bootstrap fisico do schema para o primeiro CRUD
  - preferir migration inicial
  - aceitar `ddl-auto` apenas como contingencia explicita de sandbox/prova local

### Falha 2

- categoria: contrato-nao-explicito
- sintoma: o schema `request` respondeu com `x-ui.resource.idFieldValid=false` e mensagem de `idField not found`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/categorias/filter&operation=post&schemaType=request`
- causa provavel:
  - o request schema de filtro nao contem `id`, embora o recurso publique `idField=id`
- impede aprovacao: nao
- ajuste proposto:
  - tratar como comportamento esperado do request schema de filtro; nao e blocker desta rodada

## Divergencias entre guia e codigo

- o guia permitia chegar a uma aplicacao que sobe, mas ainda sem tabela fisica para o primeiro CRUD

## Divergencias entre guia e comportamento da LLM

- nenhuma relevante nesta rodada; a falha principal decorreu de lacuna do guia

## Acoes apos a rodada

- ajustes no guia:
  - adicionada secao de bootstrap inicial do banco local no guia de aplicacao nova
- ajustes no checklist:
  - nenhum ainda
- ajustes no prompt:
  - rodada H2 simples agora exige bootstrap fisico do schema
- ajuste necessario no starter:
  - nenhum identificado nesta rodada

## Veredito

- pode repetir a mesma rodada sem mudar prompt: nao
- pronto para proxima rodada: nao
- ponto exato de retomada:
  - recriar sandbox limpo da rodada 1 com migration inicial ou `ddl-auto` local explicitamente escolhido pela LLM
