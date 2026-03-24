# Guia 03 - IA Frontend - Angular CRUD Completo

## Objetivo

Este guia orienta uma LLM a gerar o frontend Angular canonico para um recurso
CRUD que ja publica corretamente o contrato metadata-driven do
`praxis-metadata-starter`.

O objetivo nao e montar "qualquer tela Angular que compile". O objetivo e gerar
um host alinhado ao runtime oficial da plataforma:

- `@praxisui/crud` como shell canonico de CRUD
- `@praxisui/table` como superficie canonica de listagem e filtro
- `@praxisui/dynamic-form` como superficie canonica de formulario
- `GenericCrudService` consumindo `GET {resource}/schemas` e `/schemas/filtered`
- `resourcePath`, `idField`, `ETag` e `X-Schema-Hash` usados de forma coerente

## Ordem de leitura para a LLM

Este guia so deve ser usado depois que o backend ja publicar corretamente o
contrato metadata-driven.

Use esta ordem:

1. `GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
2. `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
3. este guia
4. `CHECKLIST-VALIDACAO-IA.md`

Nenhum passo deste guia depende de consultar app externo.

## Hierarquia canonica

1. `praxis-metadata-starter`
   - define `x-ui`, `/schemas/filtered`, `idField` e o contrato metadata-driven
2. `praxis-ui-angular`
   - define o runtime oficial:
     `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form` e
     `@praxisui/crud`

O starter nao redefine semantica de frontend. Ele orienta o consumo correto do
runtime oficial Angular.

## Quando usar este guia

Use este guia quando a LLM precisar gerar a parte frontend de um recurso CRUD
ja exposto pelo backend Praxis.

Este guia cobre:

- lista com `praxis-table`
- filtro remoto metadata-driven
- formulario metadata-driven
- acoes `create`, `view`, `edit` e `delete`
- host unificado com `praxis-crud`

## Quando nao usar este guia

Este guia nao e o lugar correto para:

- page builder
- editor visual de metadados
- regras corporativas avancadas de drawer
- UX fora do fluxo CRUD canonico

Se o pedido for apenas uma lista remota simples, `praxis-table` pode bastar.
Se o pedido for um CRUD completo, prefira `praxis-crud`.

## O que a LLM deve receber como entrada

No minimo:

1. nome do recurso frontend
2. `resourcePath` canonico do backend
3. `idField`
4. estrategia de abertura: `modal`, `route` ou `drawer`
5. rota Angular desejada
6. `endpointKey` quando houver mais de uma API configurada

Exemplo:

```text
Gere o frontend Angular de CRUD completo para o recurso abaixo.

Entrada:
- Recurso: Funcionarios
- resourcePath: api/human-resources/funcionarios
- idField: id
- endpointKey: HumanResources
- rota da lista: /funcionarios
- rota de visualizacao: /funcionarios/view/:id
- openMode padrao: modal
```

## Dependencias frontend minimas

Para um CRUD completo:

```bash
npm i @angular/animations @angular/cdk @angular/material
npm i @praxisui/core @praxisui/table @praxisui/dynamic-form @praxisui/crud
```

Para um app Angular novo, a prova operacional desta rodada mostrou que o
baseline real tambem precisa incluir:

```bash
npm i @praxisui/ai @google/generative-ai
```

Motivo:

- as libs publicadas em `@praxisui 3.0.0-beta.3` ainda puxam `@praxisui/ai`
  no grafo de bundle de algumas libs
- `@praxisui/ai` referencia `@google/generative-ai`

Nao assumir que as quatro libs principais bastam sozinhas em um app Angular
novo.

Referencia operacional validada nesta rodada:

- `praxis-metadata-starter 5.0.0-rc.2`
- `@praxisui 3.0.0-beta.3`

## Contratos canonicos de runtime

### 1. `resourcePath`

No runtime Angular atual, o `resourcePath` segue o formato:

```text
api/human-resources/funcionarios
```

Nao remova o prefixo `api/` por conta propria.

### 2. `API_URL`

O app host precisa fornecer o token `API_URL`.

Exemplo minimo:

```ts
import { ApplicationConfig } from '@angular/core';
import { API_URL, type ApiUrlConfig } from '@praxisui/core';

export const appConfig: ApplicationConfig = {
  providers: [
    {
      provide: API_URL,
      useFactory: (): ApiUrlConfig => {
        const origin =
          typeof window !== 'undefined' && window.location
            ? window.location.origin
            : 'http://127.0.0.1:4200';
        return {
          default: { baseUrl: origin, path: '/api', version: '' },
        } as ApiUrlConfig;
      },
    },
  ],
};
```

Se houver endpoint dedicado por dominio:

```ts
{
  provide: API_URL,
  useFactory: (): ApiUrlConfig => {
    const origin =
      typeof window !== 'undefined' && window.location
        ? window.location.origin
        : 'http://127.0.0.1:4200';
    return {
      default: { baseUrl: origin, path: '/api', version: '' },
      humanResources: { baseUrl: origin, path: '/api', version: '' },
    } as ApiUrlConfig;
  },
}
```

Para app Angular novo fora do workspace oficial, prefira
`baseUrl = window.location.origin`.

Para app novo, o bootstrap tambem deve prover:

- `provideHttpClient()`
- `provideAnimationsAsync()` ou equivalente

### 3. `providePraxisGlobalConfigBootstrap()`

Nao faz parte do baseline minimo de um host CRUD novo.

So use esse bootstrap quando o host realmente integrar
`praxis-config-starter` e precisar de config remota em
`/api/praxis/config/**`.

### 4. `GenericCrudService`

Regras canonicas:

- `praxis-table` e self-hosted para `GenericCrudService`
- `praxis-dynamic-form` e host-driven para `GenericCrudService`
- `praxis-crud` e o shell recomendado para CRUD completo

## Fluxo de schema

O frontend oficial consome:

- `GET {resource}/schemas`
- `/schemas/filtered?path=.../all&operation=get&schemaType=response`
- `/schemas/filtered?path=.../filter&operation=post&schemaType=request`

Tambem usa:

- `If-None-Match`
- `ETag`
- `X-Schema-Hash`
- `x-ui.resource.idField`

## Padrao canonico para CRUD completo

```text
lista + acoes:
  praxis-crud
    -> praxis-table
    -> crudContext estavel
    -> create/view/edit/delete

formulario:
  praxis-dynamic-form
    -> schema-driven
    -> usa GenericCrudService quando depende de resourcePath/resourceId
```

## Arquivos minimos do frontend

Para um app Angular novo, gere no minimo:

- `src/app/app.config.ts`
- `src/app/app.routes.ts`
- componente host da lista com `praxis-crud`
- componente de view/edit roteado com `praxis-dynamic-form`, quando houver rota
- `src/styles.scss` com tema Material
- `proxy.conf.js`
- `angular.json` com `serve.options.proxyConfig`

## Prompt recomendado para IA

```text
Voce esta gerando o frontend Angular canonico para um recurso CRUD Praxis.

Use apenas o contrato metadata-driven ja publicado pelo backend.
Considere praxis-ui-angular como runtime canonico.

Gere:
- host com praxis-crud para a lista principal
- praxis-dynamic-form para view/edit quando houver rota
- API_URL coerente
- resource.path e idField corretos
- create/view/edit/delete conectados ao fluxo canonico

Nao invente wrappers locais nem duplicacoes de contrato.

Entrada:
- resourcePath: {resource-path}
- idField: {id-field}
- endpointKey: {endpoint-key}
- rota da lista: {list-route}
- rota de view: {view-route}
- openMode padrao: {open-mode}
```

## Checklist minimo

Antes de concluir:

- existe um host `praxis-crud` para a lista principal
- `resource.path` e `idField` estao corretos no metadata
- `GenericCrudService` foi provido ou configurado no host correto
- o formulario roteado usa `praxis-dynamic-form` quando o fluxo exigir `route`
- o recurso remoto carrega schema sem ajuste local de contrato
- `create`, `view`, `edit` e `delete` estao conectados ao fluxo correto

## Validacao minima recomendada

Quando o workspace Angular tiver `node_modules` de Windows, prefira executar via
`cmd.exe /c`.

Quando a prova usar um app Angular novo fora do workspace oficial, a validacao
minima deve incluir:

```bash
npm run build
npm start -- --host 127.0.0.1 --port 4305
```

Evidencias esperadas:

- build fecha sem erro de bundle ou peer dependency
- `/funcionarios` responde `200`
- o host sobe com proxy ativo para `/api` e `/schemas`
- `POST /api/.../filter?page=0&size=10` responde `200` no mesmo origin do host

Se houver trilha browser-level complementar, trate-a como validacao separada do
baseline material acima.

## Referencias publicas

- repositório público do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes publicados: `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`, `@praxisui/crud`
- `praxis-metadata-starter/docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `praxis-metadata-starter/docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
