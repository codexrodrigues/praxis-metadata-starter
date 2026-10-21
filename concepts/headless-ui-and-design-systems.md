# Headless UI and Design Systems

## Definição

**Headless UI** separa a lógica de estado, validação, acessibilidade e semântica de interação da camada visual concreta (CSS, tokens de design e biblioteca de componentes). Assim, o mesmo contrato comportamental pode ser materializado em diferentes Design Systems.

## Como o `praxis-metadata-starter` aplica esse princípio

O `praxis-metadata-starter` é estritamente **agnóstico de framework CSS ou biblioteca de componentes visuais**:

- **Semântica pura em `x-ui`**: quando o backend publica `controlType = CURRENCY_INPUT`, `group = "remuneracao"`, `validation.required = true` ou `presentation.type = "badge"`, ele descreve a intenção semântica e as restrições do campo, nunca classes CSS ou seletores de um toolkit específico.
- **Múltiplos materializadores sobre o mesmo contrato**:
  - As bibliotecas corporativas **`@praxisui/*`** (Angular Material + tokens de Design System corporativo) materializam o contrato em aplicações enterprise completas.
  - O **`/praxis/cockpit`** embutido no starter materializa o mesmo contrato em uma interface leve de inspeção e homologação.
  - Consumidores mobile, CLIs ou agentes conversacionais podem consumir o mesmo `/schemas/filtered` e `/{resource}/capabilities` adaptando a experiência ao seu próprio canal.

## Referências internas

- [Metadata-driven & Backend-agnostic](metadata-driven-backend-agnostic.md)
- [Dynamic Component Rendering](dynamic-component-rendering.md)

## Referências públicas

- [Martinfowler.com — Headless Component](https://martinfowler.com/articles/modularizing-react-apps.html)
- [WAI-ARIA Authoring Practices](https://www.w3.org/WAI/ARIA/apg/)
