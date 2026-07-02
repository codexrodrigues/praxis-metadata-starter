# Semantic Host Cockpit Diagnostics Catalog

## Status

Catalogo experimental para a issue #39.

Classificacao: `transversal`.

Este documento nao cria endpoint, DTO, schema ou contrato canonico novo. As
regras abaixo sao diagnosticos derivados no cockpit a partir de superficies ja
publicadas pelo `praxis-metadata-starter`. Uma promocao futura para
`/schemas/diagnostics`, readiness score versionado ou outro contrato publico
deve ocorrer em issue separada, com mapa de impacto proprio.

## Premissa

O cockpit deve ensinar se um host esta pronto para materializacao runtime e
grounding de IA. Ele nao deve sugerir remendo em consumidor quando a origem
correta e recurso, schema, domain catalog, surface, action, capability,
HATEOAS ou governanca canonica.

## Shape Experimental

Cada diagnostico experimental deve publicar, no minimo:

- `id`
- `title`
- `severity`: `info`, `warn` ou `error`
- `category`: `structural`, `semantic`, `runtime`, `ai` ou `governance`
- `resourceKey/path`
- `canonical source`
- `evidence`
- `impact`
- `recommended canonical fix`
- `minimal validation`
- `experimental: true`

## Regras Iniciais

| ID | Categoria | Severidade | Fonte canonica | Evidencia | Impacto | Fix canonico | Validacao minima |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `semantic.resource-key-pivot` | semantic | `error` quando ausente | `/schemas/domain`, `/schemas/surfaces`, `/schemas/actions`, `@ApiResource(resourceKey=...)` | `resourceKey` estavel ou fallback por path | Sem `resourceKey`, cockpit, runtime e IA dependem de path/operationId menos estavel | Publicar `resourceKey` no recurso canonico | Confirmar o mesmo `resourceKey` em domain/surfaces/actions |
| `structural.filtered-schema-resolvable` | structural | `error` quando sem schema/link | `/schemas/filtered` | schema filtrado resolvido ou links de request/response | Forms, tabelas e option sources ficam incompletos sem schema estrutural | Corrigir operacao/schema no controller resource-oriented ou no starter | Abrir `/schemas/filtered?path=...&operation=...&schemaType=...` |
| `semantic.framework-endpoints-separated` | semantic | `warn` quando sem evidencia de separacao | `/schemas/catalog` | endpoints infra filtrados e operacoes derivadas colapsadas | Auth, actuator, swagger, config infra ou helpers nao devem inflar recursos de dominio | Manter classificacao derivada no cockpit; promover contrato apenas se houver lacuna real | Confirmar que `/auth/**` nao aparece como resource top-level |
| `semantic.surface-discovery` | semantic | `error` quando indisponivel/vazio para recurso rico | `/schemas/surfaces` | surfaces por `resourceKey`/path | Runtime UI perde materializacao explicita | Publicar `@UiSurface` ou surfaces automaticas no recurso canonico | Chamar `/schemas/surfaces?resource={resourceKey}` |
| `semantic.action-discovery` | semantic | `error` quando indisponivel/vazio para recurso com workflow | `/schemas/actions` | actions por `resourceKey`/path e schema URLs | Workflows ficam invisiveis para runtime e IA | Publicar `@WorkflowAction` e schemas de payload quando aplicavel | Chamar `/schemas/actions?resource={resourceKey}` |
| `runtime.capabilities-readable` | runtime | `error` quando indisponivel | `GET {resource}/capabilities` | snapshot de operations, surfaces e actions | Operacoes atuais nao podem ser reconciliadas com UI, HATEOAS e OpenAPI | Corrigir controller resource-oriented ou composicao de capabilities | Comparar capabilities com OpenAPI do recurso |
| `ai.domain-grounding-evidence` | ai | `error` quando sem domain/governance | `/schemas/domain`, `x-domain-governance` | itens de dominio e sinais de governanca | IA tem menos contexto governado para explicar, simular e authorar decisoes | Melhorar catalogo de dominio, descricoes e governanca na fonte canonica | Consultar `/schemas/domain` e schema filtrado do recurso |

## Recursos De Prova

### Recurso Rico

Usar `operations.missoes` no `praxis-api-quickstart` como recurso rico quando
disponivel. Ele deve exercitar:

- resource-oriented controller;
- surfaces/actions;
- capabilities;
- schemas filtrados;
- operacoes derivadas como actions, stats ou export quando publicadas.

### Recurso Simples

Usar um recurso CRUD simples do quickstart, como
`human-resources.habilidades` ou equivalente publicado pelo host, para validar
que diagnosticos opcionais nao geram falso positivo de arquitetura quando o
recurso legitimamente nao possui workflows ricos.

## Tratamento De Falsos Positivos

- Surface/action vazia e erro apenas quando a ausencia impede materializacao
  esperada para aquele tipo de recurso.
- Recurso CRUD simples pode receber warning didatico, mas nao deve ser tratado
  como defeito canonico por nao ter workflow.
- Endpoint protegido deve ser diagnosticado como policy/readiness do host, nao
  como falha do contrato de metadata.
- Catalogo documental ruidoso deve ser corrigido na composicao do cockpit antes
  de criar novo endpoint agregado.

## Validacao

Validacao minima no starter:

- `node --check src/main/resources/META-INF/resources/praxis/cockpit/assets/cockpit.js`
- `mvn -q -Dtest=PraxisCockpitControllerTest test`

Validacao downstream recomendada no quickstart:

- consumir a versao publicada do starter;
- abrir `/praxis/cockpit` em host real;
- confirmar que `/auth/**` e endpoints tecnicos nao aparecem como resources;
- selecionar um recurso rico e um simples;
- registrar falsos positivos antes de estabilizar qualquer regra.
