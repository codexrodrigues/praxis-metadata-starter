# Configuration-driven development

## Definicao curta

Evoluir funcionalidades atraves de configuracao e metadados, em vez de escrever
novo codigo para cada variacao.

## Onde aparece no Praxis

- Frontend: `@praxisui/core` para defaults e configuracoes globais
- Frontend: `@praxisui/settings-panel` para editores e persistencia de ajustes
- Frontend: `@praxisui/table` para comportamentos de tabela configuraveis
- Backend: `praxis-metadata-starter` para `x-ui` e contrato declarativo

## Como aplicar

1. defina defaults em configuracao global
2. publique contratos `x-ui` ricos no backend
3. use editores e painéis de configuracao em vez de forks de componente

## Referencias publicas

- repositório público: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacotes: `@praxisui/core`, `@praxisui/settings-panel`, `@praxisui/table`
