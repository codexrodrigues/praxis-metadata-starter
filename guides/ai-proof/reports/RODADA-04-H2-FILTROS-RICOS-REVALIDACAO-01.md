# Relatorio de Rodada de Prova

## Identificacao

- rodada: 4
- data: 2026-04-08
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: baseline atual com `resourceKey`, JDK 21 fixado no harness e trilha MapStruct ja revalidada
- projeto sandbox: `tmp/ai-guide-proof-h2-filters-r2`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - trilha H2 com relacao + MapStruct, agora explicitando a Rodada 4 com filtros ricos
- restricoes adicionais:
  - migrations fisicas
  - `MapStruct + CorporateMapperConfig`
  - validacao real de `LIKE`, `EQUAL` e `BETWEEN`
  - validacao sequencial de mutacao, filtro e `ETag`

## Resultado

- status geral: aprovado
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado
- CRUD baseline: aprovado
- filtro rico: aprovado
- options: aprovado
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `mvn -q clean package`
- comando de startup:
  - `java -jar target/ai-guide-proof-h2-filters-1.0.0-SNAPSHOT.jar`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK`, com campos `nome`, `ativo`, `categoriaId`, `precoBetween` e `dataCadastroRange`
  - `x-ui.resource.idField = "id"`
  - `x-ui.resource.idFieldValid = false`
- endpoint `POST /api/catalog/categorias/options/filter?page=0&size=20`:
  - `200 OK`
- endpoint `GET /api/catalog/categorias/options/by-ids?ids=1,2`:
  - `200 OK`
- endpoint `POST /api/catalog/produtos`:
  - `201 Created` para `Produto Alpha`, `Produto Beta` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"nome":"Produto"}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"ativo":true}`:
  - `200 OK`, retornando `Produto Alpha` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"categoriaId":2}`:
  - `200 OK`, retornando `Produto Alpha` e `Servico Gamma`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"precoBetween":[10,30]}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"dataCadastroRange":["2026-03-01","2026-03-15"]}`:
  - `200 OK`, retornando `Produto Alpha` e `Produto Beta`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"ativo":true,"categoriaId":2,"precoBetween":[0,20]}`:
  - `200 OK`, retornando apenas `Produto Alpha`
- endpoint `GET /schemas/filtered?...` com `If-None-Match`:
  - `304 Not Modified`
- endpoint `GET /api/catalog/produtos/capabilities`:
  - `200 OK`
- endpoint `GET /api/catalog/produtos/1/capabilities`:
  - `200 OK`
- endpoint `GET /schemas/surfaces?resource=catalog.produtos`:
  - `200 OK`
- endpoint `GET /schemas/actions?resource=catalog.produtos`:
  - `404` sem workflow, como esperado

## Falhas encontradas

- nenhuma bloqueante nesta revalidacao

## Divergencias entre guia e codigo

- o pacote `ai-proof` ainda nao tinha uma secao explicita de prompt para a Rodada 4

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta rodada

## Acoes apos a rodada

- ajustes no guia:
  - nenhum adicional obrigatorio nos guias principais
- ajustes no checklist:
  - incluir verificacao explicita de `LIKE`, `EQUAL` e `BETWEEN` quando a rodada pedir filtros ricos
- ajustes no prompt:
  - adicionar secao explicita da Rodada 4 ao `PROMPTS-DE-EXECUCAO.md`
- ajuste necessario no starter:
  - nenhum novo bloqueio funcional detectado nesta rodada

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar a rodada de consumo Angular usando o backend aprovado como fonte do contrato
