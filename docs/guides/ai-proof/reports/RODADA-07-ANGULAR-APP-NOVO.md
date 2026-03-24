# Rodada 07 - App Angular novo do zero

Data: 2026-03-24
Status: aprovado para prova material do app Angular novo; trilha browser-level completa segue separada e pendente de harness canonico

## Objetivo

Provar o guia frontend em um cenario mais forte que a rodada anterior:

- criar um app Angular novo do zero
- instalar as libs `@praxisui` publicadas
- configurar o baseline necessario
- compilar o host CRUD
- subir o host e responder a rota principal

## Sandbox

- pasta: `tmp/ai-guide-proof-angular-crud-r5`
- backend de referencia: `https://praxis-api-quickstart.onrender.com`
- rota principal validada: `/funcionarios`

## Cenario implementado

Host canônico gerado:

- `praxis-crud` para lista principal
- `praxis-dynamic-form` para rota de visualizacao
- `API_URL` relativo com `path: '/api'`
- `proxy.conf.js` para `/api` e `/schemas`
- `resourcePath = api/human-resources/funcionarios`

## Achados reais da prova

### 1. Dependencias minimas do guia estavam incompletas

O guia dizia apenas:

- `@praxisui/core`
- `@praxisui/table`
- `@praxisui/dynamic-form`
- `@praxisui/crud`

Na pratica, um app Angular novo tambem precisou de:

- `@angular/animations`
- `@angular/cdk`
- `@angular/material`
- `@praxisui/ai`
- `@google/generative-ai`

Erros capturados:

- `Could not resolve "@praxisui/ai"`
- `Could not resolve "@google/generative-ai"`

### 2. O guia nao explicitava o bootstrap minimo do app novo

Foi necessario configurar:

- `provideHttpClient()`
- `provideAnimationsAsync()`
- `API_URL`
- `router-outlet` no root
- tema global Material em `src/styles.scss`

### 3. Proxy de desenvolvimento precisava estar mais prescritivo

Nao basta criar `proxy.conf.js`.

Tambem foi necessario ligar:

- `angular.json -> serve.options.proxyConfig = "proxy.conf.js"`

### 4. O budget default do Angular CLI falhou

O app ficou semanticamente correto, mas `ng build` falhou com:

- `bundle initial exceeded maximum budget`

Foi necessario ajustar o budget inicial para:

- warning: `2MB`
- error: `3MB`

### 5. `ng serve` travou no prompt de analytics

Na primeira subida, o Angular CLI pediu confirmacao de analytics e bloqueou a
execucao nao interativa.

Isso tambem precisou entrar como observacao operacional do guia.

### 6. `API_URL` do guia ainda estava otimista demais para app externo

Na primeira versao do host de prova, usar:

- `baseUrl: ''`
- `path: '/api'`
- `resourcePath = api/...`

fez alguns fluxos de schema montarem URLs como `/api/api/...`.

O guia foi corrigido para preferir:

- `baseUrl = window.location.origin`
- `path = '/api'`

para apps Angular novos fora do workspace oficial.

### 7. `providePraxisGlobalConfigBootstrap()` nao pertence ao baseline minimo

Quando o host novo incluiu esse bootstrap sem realmente integrar
`praxis-config-starter`, o runtime passou a tentar usar `/api/praxis/config/**`
e gerou ruido de:

- `403 Forbidden`
- tentativas de persistencia remota desnecessarias

O guia foi corrigido para tratar esse bootstrap como opcional, nao como parte
do setup minimo de CRUD.

### 8. O smoke browser-level expôs um bug real nas libs publicadas

Ao abrir o host no browser com as libs publicadas `@praxisui 3.0.0-beta.2`, o
runtime falhou com:

- `NG0201: No provider found for _DatePipe`

Diagnostico:

- o app host novo nao estava errado
- `@praxisui/table` vazava uma dependencia interna de pipes Angular pelo
  `DataFormattingService`
- isso obrigava um host externo a prover `DatePipe`, `DecimalPipe`,
  `CurrencyPipe`, `PercentPipe`, `UpperCasePipe`, `LowerCasePipe` e
  `TitleCasePipe`, o que nao e contrato aceitavel para um host canônico

Correcao feita na plataforma:

- `DataFormattingService` passou a encapsular os formatters internamente usando
  apenas `LOCALE_ID`
- teste focal aprovado em `praxis-table`
- smoke focal de `praxis-crud` continuou aprovado

## Validacoes executadas

### 1. Instalacao das dependencias

Executado com override local de `npm` para neutralizar o registry privado do
ambiente:

```bash
NPM_CONFIG_USERCONFIG=/dev/null npm_config_registry=https://registry.npmjs.org/ npm install ...
```

Resultado:

- dependencias instaladas com sucesso

### 2. Build do app

Comando:

```bash
npm run build
```

Resultado final:

- aprovado
- output em `dist/app`

### 3. Subida do host

Comando:

```bash
npm start -- --host 127.0.0.1 --port 4305
```

Resultado:

- dev server subiu
- rota `http://127.0.0.1:4305/funcionarios` respondeu `200`

### 4. Smoke browser-level

Executado com servidor estatico local sobre `dist/app/browser` e proxy para o
backend de referencia.

Resultado:

- o smoke revelou primeiro o leak de `DatePipe` nas libs publicadas
- depois da correcao local da plataforma, os testes focais das libs passaram
- a tentativa de revalidar o sandbox com pacotes locais montados a partir de
  `dist/` expôs um problema separado de consumo local ainda nao canonizado
  (`NG0203` em `_HighContrastModeDetector` com colisões `NG0912`)

Conclusao operacional desta etapa:

- o guia ficou correto para bootstrap e build do app novo
- a prova browser-level foi suficiente para localizar e corrigir um bug real de
  runtime nas libs
- a repeticao browser-level completa contra pacote publicado depende de nova
  publicacao das libs com essa correcao

### 5. Revalidacao com `@praxisui 3.0.0-beta.3`

Depois da publicacao de `3.0.0-beta.3`, o sandbox foi realinhado para usar os
pacotes publicados do registry, sem `dist` local.

Resultado observado:

- `npm install` com `3.0.0-beta.3`: aprovado
- `npm run build -- --configuration development`: aprovado
- o runtime em browser passou do erro anterior de `NG0201: No provider found for _DatePipe`
- o console mostrou bootstrap real do CRUD e fetch de schema em
  `/schemas/filtered?path=/api/human-resources/funcionarios/all...`

Ponto ainda pendente:

- no smoke browser-level, a rota `/funcionarios` entrou em execucao pesada
  apos o fetch de schema e o renderer nao devolveu nem uma leitura simples do
  DOM no tempo esperado da prova

Interpretacao operacional:

- `beta.3` corrigiu o leak de pipes, como esperado
- a rodada nao produziu evidencia suficiente para aprovar o fluxo browser-level
  completo de lista/detalhe
- isso nao derruba a prova material do app novo, porque o bloqueio remanescente
  foi posteriormente isolado na automacao usada pela rodada

### 6. Diagnostico adicional da automacao browser-level

A investigacao seguinte mostrou que o gargalo remanescente nao esta no guide
bootstrap nem no contrato HTTP principal do app novo:

- o browser envia `POST /api/human-resources/funcionarios/filter?page=0&size=10`
  com `Content-Type: application/json` e `postData = {}`
- o mesmo endpoint responde `200` via `curl`, tanto direto no backend quanto
  passando pelo `ng serve` oficial do app

Dois fatores de harness apareceram durante a prova:

1. o proxy Python artesanal usado sobre `dist/app/browser` nao era confiavel
   para requests browser-level de `POST /filter`, produzindo `403` espurio
2. mesmo com `ng serve` canonico do app, a automacao Playwright headless nao
   conseguiu observar a resposta do `POST /filter` dentro da janela usada pela
   prova, apesar de o endpoint responder `200` via `curl` no mesmo origin

Conclusao refinada:

- o fluxo de app Angular novo esta provado para instalacao, build, bootstrap,
  schema e chamada canônica de `/filter`
- o bloqueio restante ficou caracterizado como problema da automacao de prova
  browser-level, nao como falha do guia nem regressao confirmada das libs

Evidencia complementar importante:

- com `ng serve` do proprio sandbox em `http://127.0.0.1:4305`, o `curl` para
  `POST /api/human-resources/funcionarios/filter?page=0&size=10` com body `{}`
  respondeu `200`
- isso elimina o backend e o proxy oficial do Angular como causa raiz do
  bloqueio
- o restante do problema ficou restrito ao harness Playwright/headless usado na
  rodada

## Ajustes feitos no guia

O guia foi endurecido para explicitar:

- baseline real de dependencias
- `provideHttpClient()` e `provideAnimationsAsync()`
- `API_URL` com `window.location.origin` para host externo
- proxy dev + ligacao no `angular.json`
- budget minimo do build
- cuidado com prompt de analytics em execucao nao interativa
- `providePraxisGlobalConfigBootstrap()` como opcional, nao baseline

## Conclusao

O guia frontend agora esta mais proximo do mesmo nivel de operacionalidade ja
atingido pelos guias backend.

Antes desta rodada, uma LLM conseguiria montar boa parte do host, mas ainda
quebraria com alta probabilidade em:

- dependencias faltantes
- bootstrap de app novo
- proxy nao ligado
- budget default do Angular

Depois dos ajustes, o fluxo de app Angular novo ficou materialmente provado ate
o ponto de:

- instalar
- compilar
- subir
- responder a rota principal do CRUD

E a prova adicional em browser foi suficiente para endurecer o guia e revelar
uma correcao de plataforma necessaria em `@praxisui/table`.

Com `3.0.0-beta.3`, a prova confirmou a correcao de `DatePipe`, mas ainda nao
fechou o smoke browser-level completo do host CRUD novo.

## Veredito operacional consolidado

- prova material do app Angular novo: aprovada
- correcao de plataforma revelada pela rodada: aprovada e publicada em
  `@praxisui 3.0.0-beta.3`
- E2E browser-level completo de lista/detalhe: nao aprovado nesta rodada
- causa mais provavel do bloqueio restante: harness Playwright/headless ad hoc,
  nao falha confirmada do guia ou regressao confirmada das libs publicadas

Proximo passo recomendado:

- nao criar novos probes artesanais no sandbox
- tratar o browser-level completo como trilha propria, com harness minimo e
  canonico antes de voltar a usar esse criterio como gate de prova
