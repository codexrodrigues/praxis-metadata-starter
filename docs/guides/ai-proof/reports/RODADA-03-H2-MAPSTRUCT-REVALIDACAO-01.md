# Relatorio de Rodada de Prova

## Identificacao

- rodada: 3
- repeticao: 1
- data: 2026-04-08
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos endurecimento do pacote `ai-proof` com rodada explicita para `MapStruct + CorporateMapperConfig`
- projeto sandbox: `tmp/ai-guide-proof-h2-mapstruct-r2`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
  - `docs/guides/OPTIONS-ENDPOINT.md`
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 3: H2 com MapStruct`
- restricoes adicionais:
  - sandbox limpo
  - migrations fisicas
  - `CorporateMapperConfig`
  - annotation processor de MapStruct ativo no build
  - startup com JDK 21 explicito

## Resultado

- status geral: aprovado com ressalvas
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado com ressalva
- CRUD baseline: aprovado
- options: aprovado
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `mvn clean package`
- comando de startup:
  - `D:\Developer\JAVA\graalvm-jdk-21.0.2+13.1\bin\java.exe -jar target/ai-guide-proof-h2-mapstruct-1.0.0-SNAPSHOT.jar`
- `MapStruct`:
  - `CategoriaMapperImpl` e `ProdutoMapperImpl` foram gerados em `target/generated-sources/annotations`
- endpoint `GET /v3/api-docs`:
  - `200 OK`
- endpoint `POST /api/catalog/categorias`:
  - `201 Created`
- endpoint `POST /api/catalog/categorias/options/filter?page=0&size=20`:
  - `200 OK`, retornando `Categoria A`
- endpoint `GET /api/catalog/categorias/options/by-ids?ids=1`:
  - `200 OK`
- endpoint `POST /api/catalog/produtos`:
  - `201 Created`
- endpoint `POST /api/catalog/produtos/filter?page=0&size=20` com `{"categoriaId":1}`:
  - `200 OK`
- endpoint `GET /api/catalog/produtos/all`:
  - `200 OK`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `GET /api/catalog/produtos/capabilities`:
  - `200 OK`
- endpoint `GET /api/catalog/produtos/10/capabilities`:
  - `200 OK`
- endpoint `GET /schemas/actions?resource=catalog.produtos`:
  - `404 Not Found` sem workflow, conforme esperado
- revalidacao `If-None-Match`:
  - `304 Not Modified`

## Falhas encontradas

### Falha 1

- categoria: erro-da-execucao-da-prova
- sintoma: uma primeira chamada imediata a `POST /api/catalog/categorias/options/filter` retornou vazia logo apos o `create categoria`
- evidencia:
  - a segunda tentativa, feita em sequencia alguns segundos depois, retornou `Categoria A`
- causa provavel:
  - ruído transitório da execução, não falha estrutural do guia nem do starter
- impede aprovacao: nao
- ajuste proposto:
  - manter a prova com mutacao e leitura em sequencia, sem disparos prematuros

### Falha 2

- categoria: contrato-com-ressalva
- sintoma: no request schema de `Produto`, `x-ui.resource.idField` veio como `id`, mas `idFieldValid=false`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`
- causa provavel:
  - o schema de filtro não expõe `id`, embora continue referenciando o `idField` canônico do recurso
- impede aprovacao: nao
- ajuste proposto:
  - manter este comportamento documentado no checklist para não confundir leitura de request schema com bug de relação

### Falha 3

- categoria: problema-real-do-starter
- sintoma: os logs de bootstrap ainda exibem mojibake em mensagens do `DynamicSwaggerConfig`
- evidencia:
  - caracteres corrompidos nas mensagens de validacao de `@ApiResource`
- causa provavel:
  - residuos de encoding no starter
- impede aprovacao: nao
- ajuste proposto:
  - limpar as strings de log do pacote de configuracao do starter

## Divergencias entre guia e codigo

- nenhuma bloqueante na trilha `MapStruct + CorporateMapperConfig + ResourceMapper`

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta revalidacao

## Acoes apos a rodada

- ajustes no guia:
  - adicionada rodada explicita de `MapStruct` nos prompts de execucao
- ajustes no checklist:
  - nenhum adicional obrigatorio alem do que ja havia sido endurecido na rodada anterior
- ajustes no prompt:
  - novo prompt canonico para `H2 com MapStruct`
- ajuste necessario no starter:
  - backlog para limpar mojibake nos logs

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar `Rodada 4: H2 com filtros ricos`
