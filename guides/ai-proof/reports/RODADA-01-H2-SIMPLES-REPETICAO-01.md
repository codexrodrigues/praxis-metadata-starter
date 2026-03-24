# Relatorio de Rodada de Prova

## Identificacao

- rodada: 1
- repeticao: 1
- data: 2026-03-24
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos ajuste de bootstrap inicial do banco local
- projeto sandbox: `tmp/ai-guide-proof-h2-simple-r2`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 1: H2 simples`
- restricoes adicionais:
  - bootstrap do schema por migration inicial

## Resultado

- status geral: aprovado
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado
- CRUD baseline: aprovado
- options: nao aplicavel
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `./mvnw -B -f /mnt/d/Developer/praxis-plataform/tmp/ai-guide-proof-h2-simple-r2/pom.xml clean package`
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
  - `201 Created`
- endpoint `POST /{resource}/filter`:
  - `200 OK`
- endpoint `GET /{resource}/all`:
  - `200 OK`
- endpoint `GET /{resource}/{id}`:
  - `200 OK`
- revalidacao `If-None-Match`:
  - `304 Not Modified`

## Falhas encontradas

### Falha 1

- categoria: erro-da-execucao-da-prova
- sintoma: um teste inicial de `POST /filter` retornou vazio logo apos o `POST /create`
- evidencia:
  - a chamada foi executada em paralelo com a criacao do registro
- causa provavel:
  - artefato do teste em paralelo, nao falha do guia nem do starter
- impede aprovacao: nao
- ajuste proposto:
  - executar validacoes de mutacao e leitura em sequencia nas proximas rodadas

## Divergencias entre guia e codigo

- nenhuma bloqueante apos o ajuste de bootstrap do banco local

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta repeticao

## Acoes apos a rodada

- ajustes no guia:
  - mantido o novo bloco de bootstrap inicial do banco local
- ajustes no checklist:
  - nenhum adicional por enquanto
- ajustes no prompt:
  - mantida a exigencia de bootstrap fisico do schema
- ajuste necessario no starter:
  - nenhum identificado nesta repeticao

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar `Rodada 2: H2 com relacao`
