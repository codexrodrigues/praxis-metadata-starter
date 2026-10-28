# Guia 03 - IA Frontend - Angular CRUD Completo

## Objetivo

Este guia orienta uma LLM a gerar o frontend Angular canonico para um recurso
CRUD que ja publica corretamente o contrato metadata-driven do
`praxis-metadata-starter`.

O objetivo nao e montar "qualquer tela Angular que compile". O objetivo e gerar
um host **fino (zero-boilerplate)** alinhado ao runtime oficial da plataforma,
onde colunas, mascaras, moeda, filtros, opcoes remotas, agrupamento de formulario
e acoes de CRUD sao materializados automaticamente a partir de `/schemas/filtered`:

- `@praxisui/crud` como shell canonico de CRUD (com suporte a `openMode = 'drawer'`, `'modal'` ou `'route'`)
- `@praxisui/table` como superficie canonica de listagem e filtro
- `@praxisui/dynamic-form` como superficie canonica de formulario (`layoutPolicy.source = 'schema'`)
- `@praxisui/list` como superficie alternativa de cards/lista
- `GenericCrudService` e `SchemaMetadataClient` consumindo `GET {resource}/schemas` e `/schemas/filtered`
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
   - define `x-ui`, `/schemas/filtered`, `idField`, capabilities, HATEOAS e o contrato metadata-driven
2. `praxis-ui-angular`
   - define o runtime oficial:
     `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`, `@praxisui/crud` e `@praxisui/list`

O starter nao redefine semantica de frontend. Ele orienta o consumo correto do
runtime oficial Angular.

## Quando usar este guia

Use este guia quando a LLM precisar gerar a parte frontend de um recurso CRUD
ja exposto pelo backend Praxis.

Este guia cobre:

- lista com `praxis-table`
- filtro remoto metadata-driven
- formulario metadata-driven com `layoutPolicy.source = 'schema'`
- acoes `create`, `view`, `edit` e `delete`
- host unificado com `praxis-crud` (`drawer`, `modal` ou `route`)

## Quando nao usar este guia

Este guia nao e o lugar correto para:

- page builder
- editor visual de metadados
- UX fora do fluxo CRUD canonico

Se o pedido for apenas uma lista remota simples, `praxis-table` pode bastar.
Se o pedido for um CRUD completo, prefira `praxis-crud`.

## O que a LLM deve receber como entrada

No minimo:

1. nome do recurso frontend
2. `resourcePath` canonico do backend (sem prefixo `/api`)
3. `idField`
4. estrategia de abertura: `drawer`, `modal` ou `route`
5. rota Angular desejada
6. `endpointKey` quando houver mais de uma API configurada

Exemplo:

```text
Gere o frontend Angular de CRUD completo para o recurso abaixo.

Entrada:
- Recurso: Funcionarios
- resourcePath: human-resources/funcionarios
- idField: id
- endpointKey: default
- rota da lista: /funcionarios
- rota de visualizacao: /funcionarios/view/:id
- openMode padrao: drawer
```

## Dependencias frontend minimas

Para um CRUD completo na linha ativa `@praxisui/*` (`9.0.0-beta.*` / `latest` correspondente ao trem Angular do host):

```bash
npm i @angular/animations @angular/cdk @angular/material
npm i @praxisui/core @praxisui/table @praxisui/dynamic-form @praxisui/crud @praxisui/dynamic-fields
```

Para um app Angular novo, resolva tambem o fecho completo de peer dependencies da linha `@praxisui/*` adotada (que pode incluir `@praxisui/settings-panel`, `@praxisui/metadata-editor`, `@praxisui/dialog`, `@praxisui/rich-content`, `@praxisui/visual-builder`, `@praxisui/table-rule-builder`, `@praxisui/ai` e `@google/generative-ai`).

Referencia de linha ativa:

- `praxis-metadata-starter`: linha `9.x` (`9.0.0-rc.*` ou estavel mais recente no Maven Central)
- `@praxisui/*`: linha `9.0.0-beta.*` no npm (`dist-tag=latest`)

## Contratos canonicos de runtime

### 1. `resourcePath`

No runtime Angular atual, o `resourcePath` segue o formato:

```text
human-resources/funcionarios
```

Se `API_URL` ja aponta para `/api`, mantenha o `resourcePath` relativo ao
dominio do recurso e **nunca** repita o prefixo `/api`, `/filter` ou `/all`.

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
`baseUrl = window.location.origin` com proxy de desenvolvimento para `/api` e `/schemas`.

Para app novo, o bootstrap tambem deve prover:

- `provideHttpClient()`
- `provideAnimationsAsync()` ou equivalente
- estilos Material, Material Icons/Symbols, CDK Overlay e `@praxisui/core/theme-bridge.css`

### 3. `providePraxisGlobalConfigBootstrap()`

Nao faz parte do baseline minimo de um host CRUD novo.

So use esse bootstrap quando o host realmente integrar
`praxis-config-starter` e precisar de config remota em
`/api/praxis/config/**`.

### 4. `GenericCrudService` e `layoutPolicy` Schema-Driven

Regras canonicas:

- `praxis-table` e self-hosted para `GenericCrudService`
- `praxis-dynamic-form` e host-driven para `GenericCrudService` e deve usar `layoutPolicy` orientada por schema:
  - formularios de criacao/edicao (`command`): `layoutPolicy: { source: 'schema', intent: 'command', preset: 'groupedCommand' }`
  - formularios de detalhe/leitura (`detail`): `layoutPolicy: { source: 'schema', intent: 'detail', preset: 'compactPresentation' }`
  - nao declare `FormConfig.sections` manuais ou `sections: []` vazias apenas para fazer um formulario schema-driven funcionar; deixe o runtime materializar os grupos (`group`) e a ordem (`order`) publicados em `@UISchema` no backend
- `praxis-crud` e o shell recomendado para CRUD completo (usando `CrudLauncherService` com `openMode = 'drawer'`, `'modal'` ou `'route'` sem criar drawers/modais manuais no host)

## Fluxo de schema

O frontend oficial consome:

- `GET {resource}/schemas`
- `/schemas/filtered?path=.../filter&operation=post&schemaType=response`
- `/schemas/filtered?path=.../filter&operation=post&schemaType=request`
- `/schemas/filtered?path=...&operation=post&schemaType=request` (create)
- `/schemas/filtered?path=.../{id}&operation=put&schemaType=request` (update)

Tambem usa:

- `If-None-Match`
- `ETag`
- `X-Schema-Hash`
- `x-ui.resource.idField`

## Concorrencia em recursos versionados

Recursos que implementam `VersionedCreateUpdateResourceService` publicam update condicional:

1. a leitura do item retorna um `ETag` forte;
2. o cliente envia esse valor em `If-Match` no `PUT`;
3. o service bloqueia ou le a versao persistida e valida a precondicao dentro da mesma
   transacao do update;
4. a resposta bem-sucedida devolve o novo `ETag`.

Quando `withResourceVersion` emite o ETag da revisão capturada, a resposta inclui
`Cache-Control: no-transform`: preserva todas as políticas existentes e, se não
houver política, usa `no-cache, no-store, max-age=0, must-revalidate` junto à diretiva.
Isso protege o validator forte contra transformação por intermediários sem tornar
respostas privadas cacheáveis. GET e PUT versionados e replay pelo helper usam essa
regra; respostas sem revisão e ETags emitidos manualmente por outros caminhos não
são alterados. O cliente deve reenviar o token original forte, sem remover `W/` ou
normalizar a assinatura. Após release/adoção, conferir o header efetivamente servido
nas variantes identity/gzip/br e o uso do token intacto; testes MVC não certificam
um proxy ou deployment específico.

Em `ResourceRepresentationResult`, corpo e `persistedVersion` sao capturados da
**mesma entidade**. `findById` projeta ambos na transação de leitura; o PUT versionado
de três argumentos captura ambos após o último hook e `flush`, sem reconsulta tardia
para formar o header. Serviços versionados implementam `getEntityResourceVersion(entity)`
e exigem `result.requirePersistedVersion()` dentro da mesma transação de escrita antes
de retornar, para que ausência de versão reverta domínio e versão juntos. A entrada
Java de dois argumentos permanece protegida por `428`. Em replay histórico de action,
reutilize revisão historicamente capturada ou omita ETag; nunca assine a versão atual
para um corpo antigo.

O `ETag` tambem e vinculado ao escopo de isolamento resolvido pelo servidor. Por padrao o
starter usa o escopo global; hosts multi-tenant devem publicar um
`ResourceVersionScopeProvider` baseado no contexto autenticado de tenant e ambiente. O provider
nao deve confiar diretamente em valores livres do payload ou em headers nao validados. Assim, um
token emitido para outro tenant ou ambiente nunca e aceito como precondicao, mesmo quando id e
versao numerica coincidem.

Ausencia de `If-Match` retorna `428 RESOURCE_VERSION_REQUIRED`; uma versao valida, mas
desatualizada, retorna `412 STALE_RESOURCE_VERSION`; um header malformado retorna
`400 INVALID_RESOURCE_VERSION`. O frontend deve recarregar o registro e pedir uma decisao
explicita do usuario antes de tentar novamente. Nao deve sobrescrever silenciosamente nem
construir o `ETag` a partir de campos de negocio.

Se outra transacao vencer a corrida depois da validacao inicial, o bloqueio otimista da
persistencia tambem e projetado como `412 STALE_RESOURCE_VERSION`. Detalhes de entidade, SQL e
estado interno nao sao devolvidos. Esse resultado exige a mesma UX de conflito: recarregar,
comparar e obter uma decisao explicita; nunca repetir automaticamente o `PUT`.

Recursos nao versionados continuam usando `BaseCreateUpdateResourceService` e nao passam a
exigir `If-Match` por efeito colateral.

## Padrao canonico para CRUD completo

```text
lista + acoes:
  praxis-crud
    -> praxis-table
    -> crudContext estavel
    -> create/view/edit/delete (drawer | modal | route)

formulario:
  praxis-dynamic-form
    -> schema-driven (layoutPolicy.source = 'schema')
    -> usa GenericCrudService quando depende de resourcePath/resourceId
```

## Arquivos minimos do frontend

Para um app Angular novo, gere no minimo:

- `src/app/app.config.ts`
- `src/app/app.routes.ts`
- componente host da lista com `praxis-crud`
- componente de view/edit roteado com `praxis-dynamic-form`, quando houver rota
- `src/styles.scss` com tema Material + `@praxisui/core/theme-bridge.css`
- `proxy.conf.js`
- `angular.json` com `serve.options.proxyConfig`

## Prompt recomendado para IA

```text
Voce esta gerando o frontend Angular canonico para um recurso CRUD Praxis.

Use apenas o contrato metadata-driven ja publicado pelo backend.
Considere praxis-ui-angular como runtime canonico.

Gere:
- host com praxis-crud para a lista principal (sem criar servicos HTTP customizados nem colunas hardcoded)
- praxis-dynamic-form com layoutPolicy.source='schema' (preset 'groupedCommand' para escrita e 'compactPresentation' para detalhe) quando houver rota
- API_URL coerente (sem duplicar /api no resourcePath)
- resource.path e idField corretos
- create/view/edit/delete conectados ao fluxo canonico

Nao invente wrappers locais, FormConfig.sections manuais nem duplicacoes de contrato.

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
- `resource.path` e `idField` estao corretos no metadata (sem `/api` no `resourcePath`)
- `GenericCrudService` foi provido ou configurado no host correto
- o formulario usa `praxis-dynamic-form` com `layoutPolicy.source = 'schema'` sem `sections` manuais redundantes
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
- pacotes publicados: `@praxisui/core`, `@praxisui/table`, `@praxisui/dynamic-form`, `@praxisui/crud`, `@praxisui/list`
- `praxis-metadata-starter/docs/guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.md`
- `praxis-metadata-starter/docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
