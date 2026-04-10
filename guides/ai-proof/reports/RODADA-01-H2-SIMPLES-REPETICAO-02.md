# Relatorio de Rodada de Prova

## Identificacao

- rodada: 1
- repeticao: 2
- data: 2026-04-08
- executor: Codex
- llm: GPT-5 Codex
- versao do guia: apos revisao editorial dos guias de adocao e alinhamento fino de `/schemas/filtered` vs `/schemas/catalog`
- projeto sandbox: `tmp/ai-guide-proof-h2-simple-r3`
- banco usado: H2 em memoria

## Material entregue a LLM

- guias liberados:
  - `docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
  - `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
  - `docs/guides/CHECKLIST-VALIDACAO-IA.md`
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md`
- prompt usado:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` -> `Rodada 1: H2 simples`
- restricoes adicionais:
  - sandbox limpo
  - migrations fisicas via Flyway
  - sem reaproveitar codigo de app consumidor

## Resultado

- status geral: aprovado com ressalvas
- build: aprovado
- startup: aprovado
- contrato de schemas: aprovado
- CRUD baseline: aprovado
- options: nao aplicavel
- consumo Angular: nao executado

## Evidencias minimas

- comando de build:
  - `mvn -q clean package`
- comando de startup:
  - `D:\Developer\JAVA\graalvm-jdk-21.0.2+13.1\bin\java.exe -jar target/ai-guide-proof-h2-simple-1.0.0-SNAPSHOT.jar`
- endpoint `GET /v3/api-docs`:
  - `200 OK`
- endpoint `GET /{resource}/schemas`:
  - `200 OK`
- endpoint `GET /schemas/filtered?...response`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `GET /schemas/filtered?...request`:
  - `200 OK` com `ETag` e `X-Schema-Hash`
- endpoint `POST /{resource}`:
  - `201 Created`
- endpoint `POST /{resource}/filter`:
  - `200 OK`
- endpoint `POST /{resource}/options/filter`:
  - nao aplicavel
- endpoint `GET /{resource}/options/by-ids`:
  - nao aplicavel
- endpoint `GET /schemas/catalog`:
  - `200 OK`
- endpoint `GET /schemas/surfaces?resource=catalog.categorias`:
  - `200 OK`
- endpoint `GET /schemas/actions?resource=catalog.categorias`:
  - `404 Not Found` sem workflow, conforme esperado
- endpoint `GET /{resource}/capabilities`:
  - `200 OK`
- endpoint `GET /{resource}/{id}/capabilities`:
  - `200 OK`
- revalidacao `If-None-Match`:
  - `304 Not Modified`

## Falhas encontradas

### Falha 1

- categoria: prompt-incompleto
- sintoma: o prompt canonico da `Rodada 1: H2 simples` nao informava `resourceKey`, embora os guias tratem esse campo como entrada obrigatoria
- evidencia:
  - `docs/guides/ai-proof/PROMPTS-DE-EXECUCAO.md` listava `resourcePath` e `Api group`, mas nao `resourceKey`
- causa provavel:
  - drift entre o pacote `ai-proof` e os guias principais apos a consolidacao do baseline resource-oriented atual
- impede aprovacao: nao
- ajuste proposto:
  - adicionar `Resource key` explicitamente no prompt da rodada

### Falha 2

- categoria: harness-incompleto
- sintoma: a primeira tentativa de startup do jar falhou porque o launcher caiu em um `java` do PATH diferente do JDK 21 usado no build
- evidencia:
  - erro inicial: `UnsupportedClassVersionError` ao subir o jar com runtime Java 8
- causa provavel:
  - o protocolo e o sandbox nao exigiam de forma explicita que build e startup usassem o mesmo `JAVA_HOME`
- impede aprovacao: nao
- ajuste proposto:
  - fixar no protocolo e no sandbox que build e startup devem usar explicitamente JDK 21

### Falha 3

- categoria: problema-real-do-starter
- sintoma: logs de inicializacao do starter ainda exibem mojibake em mensagens do `DynamicSwaggerConfig`
- evidencia:
  - saida com caracteres corrompidos em mensagens como validacao de `@ApiResource`
- causa provavel:
  - residuos de encoding em logs do starter
- impede aprovacao: nao
- ajuste proposto:
  - revisar strings de log do pacote `configuration` e classes relacionadas

## Divergencias entre guia e codigo

- os guias principais permitiram chegar a um backend funcional com H2, Flyway, schema, CRUD e capabilities sem correcao estrutural no codigo gerado

## Divergencias entre guia e comportamento da LLM

- nenhuma bloqueante nesta repeticao; os gaps encontrados estavam no pacote operacional `ai-proof`, nao no baseline principal dos guias

## Acoes apos a rodada

- ajustes no guia:
  - nenhum obrigatorio nos guias principais desta rodada
- ajustes no checklist:
  - corrigir residuo de mojibake em referencia publica
- ajustes no prompt:
  - adicionar a entrada `Resource key` na rodada H2 simples
- ajuste necessario no starter:
  - registrar backlog para limpar mojibake em logs do starter

## Veredito

- pode repetir a mesma rodada sem mudar prompt: sim
- pronto para proxima rodada: sim
- ponto exato de retomada:
  - iniciar `Rodada 2: H2 com relacao` com o prompt atualizado e o mesmo harness fixando JDK 21
