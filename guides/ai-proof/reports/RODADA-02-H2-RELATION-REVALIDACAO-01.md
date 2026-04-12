# Relatorio de Rodada de Prova

## Identificacao

- rodada: 2
- repeticao: 1
- data: 2026-04-08
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos endurecimento do pacote `ai-proof` com `resourceKey` explicito e harness fixando JDK 21
- projeto sandbox: `tmp/ai-guide-proof-h2-relation-r2`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
  - `docs/guides/OPTIONS-ENDPOINT.md`
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 2: H2 com relacao`
- restricoes adicionais:
  - sandbox limpo
  - migrations fisicas para `Categoria` e `Produto`
  - startup com JDK 21 explicito
  - validacao sequencial de mutacao e leitura

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
  - `mvn -q clean package`
- comando de startup:
  - `D:\Developer\JAVA\graalvm-jdk-21.0.2+13.1\bin\java.exe -jar target/ai-guide-proof-h2-relation-1.0.0-SNAPSHOT.jar`
- endpoint `GET /v3/api-docs`:
  - `200 OK`
- endpoint `GET /api/catalog/produtos/schemas`:
  - nao validado nesta repeticao; o contrato estrutural foi validado direto em `/schemas/filtered`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `GET /schemas/filtered?path=/api/catalog/produtos/all&operation=get&schemaType=response`:
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

- categoria: contrato-com-ressalva
- sintoma: no request schema de `Produto`, `x-ui.resource.idField` permaneceu como `id`, mas `idFieldValid=false`
- evidencia:
  - `GET /schemas/filtered?path=/api/catalog/produtos/filter&operation=post&schemaType=request`
  - retorno: `"idField":"id","idFieldValid":false,"idFieldMessage":"idField not found in schema properties"`
- causa provavel:
  - o request schema de filtro nao expoe a propriedade `id`, o que e coerente, mas a superficie ainda publica o `idField` do recurso com validade falsa
- impede aprovacao: nao
- ajuste proposto:
  - manter esse comportamento como esperado de filtro ou documentar explicitamente que `idFieldValid=false` no request schema nao e falha quando o schema nao precisa expor `id`

### Falha 2

- categoria: achado-revalidado-sem-reproducao
- sintoma: o achado da rodada anterior de que `categoriaId` sequestrava `x-ui.resource.idField` nao se reproduziu no estado atual do starter
- evidencia:
  - nesta revalidacao, o request schema veio com `idField="id"`, nao com `categoriaId`
- causa provavel:
  - o starter foi revalidado em relacao ao relatorio de 2026-03-24
- impede aprovacao: nao
- ajuste proposto:
  - registrar esta revalidacao e tratar o relatorio anterior como evidencia tecnica de rodada, nao como estado atual

### Falha 3

- categoria: problema-real-do-starter
- sintoma: os logs de inicializacao ainda exibem mojibake em mensagens do `DynamicSwaggerConfig`
- evidencia:
  - caracteres corrompidos nas mensagens de validacao de `@ApiResource`
- causa provavel:
  - residuos de encoding no pacote de configuracao do starter
- impede aprovacao: nao
- ajuste proposto:
  - limpar as strings de log do starter em rodada dedicada

## Divergencias entre guia e codigo

- nenhuma bloqueante para a trilha de relacao simples com options de recurso proprio

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta revalidacao

## Acoes apos a rodada

- ajustes no guia:
  - nenhum obrigatorio nos guias principais
- ajustes no checklist:
  - adicionada validacao explicita para request schemas com campos relacionais `...Id`
- ajustes no prompt:
  - rodada 2 agora recebe `resourceKey`, mutabilidade e detalhes de entidades
- ajuste necessario no starter:
  - backlog para limpar mojibake nos logs de bootstrap

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar `Rodada 3: H2 com MapStruct`
