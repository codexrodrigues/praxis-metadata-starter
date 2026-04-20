# Praxis Metadata Starter Docs

Documentacao publica do `praxis-metadata-starter`.

Esta home orienta pessoas, LLMs e indexadores para a semantica atual da
plataforma.

## O que este site publica

- a trilha principal do backend canonico resource-oriented
- o contrato estrutural e documental publicado pelo starter
- a semantica de `surfaces`, `actions` e `capabilities`
- a operacao canonica de exportacao de colecao
- referencia tecnica complementar em Javadoc

## Comece por objetivo

### Quero adotar o baseline atual

- [Guides hub](guides/index.html)
- [Architecture overview](architecture-overview.html)
- [Conformance](spec/CONFORMANCE.html)
- [Options e option-sources](guides/OPTIONS-ENDPOINT.html)
- [Exportacao de colecoes](guides/COLLECTION-EXPORT.html)

### Quero gerar uma aplicacao nova

1. [Guia 01 - Backend - Aplicacao Nova](guides/GUIA-01-AI-BACKEND-APLICACAO-NOVA.html)
2. [Guia 02 - Backend - Recurso Metadata-Driven](guides/GUIA-02-AI-BACKEND-CRUD-METADATA.html)
3. [Guia 04 - Quando usar Resource, Surface, Action e Capability](guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.html)
4. [Guia 05 - Do CRUD ao Contrato Semantico](guides/GUIA-05-DO-CRUD-AO-CONTRATO-SEMANTICO.html)

### Quero integrar um runtime Angular

- [Guia 03 - Frontend - Angular CRUD Completo](guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.html)
- [Checklist de Validacao](guides/CHECKLIST-VALIDACAO-IA.html)

### Quero referencia tecnica Java

- [Javadoc publico](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/)
- [Indice humano do Javadoc](api/index.html)
- [Documentacao tecnica](technical/index.html)

## Baseline Atual

O baseline canonico atual do starter e:

- `resource`
- `surface`
- `action`
- `capability`
- HATEOAS

Isso significa:

- `/schemas/filtered` segue como contrato estrutural
- `/schemas/catalog` segue como catalogo documental
- `/schemas/surfaces` e `/schemas/actions` publicam discovery semantico
- `/{resource}/capabilities` agrega as capacidades do recurso sem redefinir o contrato estrutural
- `POST /{resource}/export` executa exportacao de colecao a partir de escopo, selecao, filtros, ordenacao e campos; o resultado pode ser binario inline ou `202 Accepted` com `status=deferred`, `downloadUrl` e `jobId`
- detalhes de exportacao em `/capabilities` sao derivados do suporte real do service e podem publicar `formats`, `scopes`, `maxRows` e `async`
- resultados inline podem publicar headers de linhas, truncamento, limite efetivo e warnings para UI corporativa

## Regra De Leitura

Quando houver duvida sobre a superficie publicada:

- priorize os guias desta home
- priorize `architecture-overview`
- use a trilha desta home como referencia principal do baseline atual
