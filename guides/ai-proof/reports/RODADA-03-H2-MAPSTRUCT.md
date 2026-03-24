# Relatorio de Rodada de Prova

## Identificacao

- rodada: 3
- data: 2026-03-24
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: com trilha explicita de MapStruct no guia de aplicacao nova
- projeto sandbox: `tmp/ai-guide-proof-h2-mapstruct-r1`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
- prompt usado:
  - derivado das rodadas H2 anteriores, endurecendo a trilha para `MapStruct + CorporateMapperConfig`
- restricoes adicionais:
  - migrations fisicas
  - uso de `CorporateMapperConfig`

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
  - `./mvnw -B -f /mnt/d/Developer/praxis-plataform/tmp/ai-guide-proof-h2-mapstruct-r1/pom.xml clean package`
- comando de startup:
  - `java -jar target/ai-guide-proof-h2-simple-1.0.0-SNAPSHOT.jar`
- `MapStruct`:
  - build passou com annotation processor ativo e `CorporateMapperConfig`
- endpoint `POST /api/catalog/categorias`:
  - `201 Created`
- endpoint `POST /api/catalog/categorias/options/filter?page=0&size=20`:
  - `200 OK`, retornando `Categoria A`
- endpoint `POST /api/catalog/produtos`:
  - `201 Created`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20`:
  - `200 OK`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{\"categoriaId\":1}`:
  - `200 OK`
- endpoint `GET /api/catalog/produtos/all`:
  - `200 OK`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK`

## Falhas encontradas

### Falha 1

- categoria: problema-real-do-starter
- sintoma: no schema `request` de `Produto`, `x-ui.resource.idField` continuou sendo `categoriaId`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`
- causa provavel:
  - heuristica do starter para `idField` em request schema com campos relacionais `...Id`
- impede aprovacao: nao
- ajuste proposto:
  - investigar e corrigir a resolucao canônica de `idField` no starter

### Falha 2

- categoria: erro-da-execucao-da-prova
- sintoma: uma primeira tentativa de `options/filter` e `create produto` falhou/voltou vazia
- evidencia:
  - requests disparadas em paralelo logo apos `create categoria`
- causa provavel:
  - corrida de commit na execucao da prova
- impede aprovacao: nao
- ajuste proposto:
  - manter validacoes de mutacao e leitura em sequencia nas proximas rodadas

## Divergencias entre guia e codigo

- nenhuma bloqueante na trilha MapStruct

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta rodada

## Acoes apos a rodada

- ajustes no guia:
  - nenhum adicional obrigatorio
- ajustes no checklist:
  - opcionalmente explicitar um item de validacao para `MapStruct + CorporateMapperConfig`
- ajustes no prompt:
  - nenhum adicional obrigatorio
- ajuste necessario no starter:
  - investigar `idField` do request schema em recursos com relacao

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar a rodada H2 com filtros mais ricos e validacao de operacoes `LIKE`, `EQUAL`, `BETWEEN`
