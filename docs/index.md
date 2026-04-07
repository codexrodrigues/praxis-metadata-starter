# Praxis Metadata Starter Docs

Documentacao publica do `praxis-metadata-starter`.

Esta home deve orientar pessoas, LLMs e indexadores para a semantica atual da
plataforma, sem misturar onboarding novo com APIs legadas removidas.

## O que este site publica

- a trilha principal do backend canonico resource-oriented
- o contrato estrutural e documental publicado pelo starter
- a semantica de `surfaces`, `actions` e `capabilities`
- referencia tecnica complementar em Javadoc

## Comece por objetivo

### Quero adotar o baseline atual

- [Guides hub](guides/index.html)
- [Architecture overview](architecture-overview.html)
- [Conformance](spec/CONFORMANCE.html)

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

## Regra De Leitura

Se houver divergencia entre material historico de CRUD generico e a trilha atual:

- priorize os guias desta home
- priorize `architecture-overview`
- trate material legado como referencia arquivada, nao como API ainda disponivel
