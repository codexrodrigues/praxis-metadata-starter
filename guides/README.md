# Guias de Implementacao

Esta pasta reune os guias operacionais do `praxis-metadata-starter` para uso
por pessoas e por LLMs.

## Trilha principal para LLM

Use os guias nesta ordem:

1. [Guia 01 - Backend - Aplicacao Nova](GUIA-01-AI-BACKEND-APLICACAO-NOVA.md)
2. [Guia 02 - Backend - CRUD Metadata-Driven](GUIA-02-AI-BACKEND-CRUD-METADATA.md)
3. [Guia 03 - Frontend - Angular CRUD Completo](GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md)
4. [Checklist de Validacao](CHECKLIST-VALIDACAO-IA.md)

Essa trilha foi organizada para que a LLM consiga percorrer os passos sem
depender de app externo nem de conhecimento oral do time.

Se o objetivo for prova operacional repetivel, siga depois para
`ai-proof/README.md`.

## Quando usar cada guia

### Guia 01

Use para criar uma nova aplicacao Spring Boot minima e correta sobre o starter.

### Guia 02

Use para gerar um recurso CRUD metadata-driven dentro da aplicacao criada no
Guia 01.

### Guia 03

Use apenas quando o backend ja publicar corretamente o contrato metadata-driven
e a tarefa for gerar o host Angular canonico.

### Checklist

Use depois da geracao para validar build, endpoints, schemas e consumo.

## Guias complementares

- [CRUD com @ApiResource e @ApiGroup](CRUD-COM-APIRESOURCE.md)
- [Filtros e Paginacao](FILTROS-E-PAGINACAO.md)
- [Ordenacao Padrao](ORDEM-PADRAO.md)
- [Options (id/label)](OPTIONS-ENDPOINT.md)
- [Views / Somente Leitura](READ-ONLY-VIEWS.md)
- [Erros e Respostas](ERROS-E-RESPOSTAS.md)
- [Prova Operacional dos Guias de IA](ai-proof/README.md)

## Papel desta pasta

- `docs/README.md` e o hub geral da documentacao
- este arquivo organiza a trilha de leitura dos guias
- `ai-proof/README.md` organiza apenas a trilha de prova
- o Javadoc publico do starter e referencia tecnica complementar:
  `https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/`

## Criterio de qualidade

Os guias desta pasta devem permanecer aderentes a:

- codigo canonico do `praxis-metadata-starter`
- contrato realmente consumido pelo runtime Angular oficial da plataforma
- protocolo de prova documentado em `ai-proof/`

Quando houver divergencia entre guia, codigo canonicamente publicado e prova
operacional, o guia deve ser corrigido.
