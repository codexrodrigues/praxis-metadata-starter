# Dynamic Component Rendering

## Definição

**Dynamic Component Rendering** é a capacidade do cliente de selecionar, instanciar e configurar componentes visuais em tempo de execução a partir de metadados abstratos do contrato (`controlType`, `type`, `valuePresentation`, `optionSource`), sem templates HTML estáticos acoplados a cada campo.

## Como o `praxis-metadata-starter` suporta a renderização dinâmica

O starter publica em `/schemas/filtered` todos os sinais necessários para que qualquer runtime escolha o componente correto:

- **Controles de formulário (`FieldControlType`)**: mapeia intenções como `INPUT`, `TEXTAREA`, `SELECT`, `AUTOCOMPLETE`, `DATE_PICKER`, `DATE_TIME_PICKER`, `CURRENCY_INPUT`, `CPF_CNPJ_INPUT`, `SWITCH`, `CHECKBOX` e `FILE_UPLOAD`.
- **Apresentação de leitura e células de tabela (`valuePresentation` e `presentation`)**: informa ao cliente como formatar valores read-only (moeda, data, booleano, texto mascarado) e como aplicar invólucros visuais (`badge`, `chip`, `status`, `iconValue`) sem alterar o valor bruto usado em ordenação ou filtro.
- **Fontes dinâmicas de opções (`optionSource`)**: conecta campos de seleção e filtros a endpoints `/options/filter` e `/options/by-ids` ou a `OptionSourceProvider` dedicados, incluindo cascatas declarativas via `dependsOn`.
- **Prova imediata no backend**: o **`/praxis/cockpit`** utiliza exatamente esses metadados para renderizar filtros, tabelas, formulários de criação/edição e diálogos de `@WorkflowAction` em tempo real.

## Referências internas

- [Conceito: `@UISchema` e metadados `x-ui`](ui-schema.md)
- [Exemplos de DTOs e Filtros](../examples/DTO-FILTROS-EXEMPLOS.md)

## Referências públicas

- [Angular Dynamic Component Loader](https://angular.dev/guide/components/programmatic-rendering)
- [Web Components Specification](https://developer.mozilla.org/en-US/docs/Web/API/Web_components)
