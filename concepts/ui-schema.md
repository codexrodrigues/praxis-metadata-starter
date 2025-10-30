# Conceito: @UISchema e Metadados x-ui

A anotação `@UISchema` é o ponto de entrada para descrever **como a UI deve renderizar** campos definidos em DTOs ou entidades. Ela trabalha em conjunto com `FieldConfigProperties`, `ValidationProperties` e o `CustomOpenApiResolver` para produzir um `OpenAPI` enriquecido com metadados `x-ui`.

## Por que usar @UISchema?

* **Documentação viva**: os mesmos metadados alimentam a documentação OpenAPI, a UI dinâmica e a indexação em mecanismos de busca.
* **Coerência visual**: todos os campos seguem um vocabulário único (`controlType`, `group`, `order`, etc.).
* **Validações sincronizadas**: anotações Jakarta (`@NotBlank`, `@Size`, `@Pattern`) são convertidas em mensagens e flags `x-ui.validation` automaticamente.

## Estrutura resumida

```java
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UISchema {
    String label();
    String controlType() default "text";
    String group() default "default";
    boolean required() default false;
    FieldDataType dataType() default FieldDataType.TEXT;
    String numericFormat() default ""; // usa NumberFormatStyle
    String[] extraProperties() default {};
}
```

*Campos numéricos podem usar enums como `NumberFormatStyle.PERCENT`.*

## Ordem de precedência aplicada pelo `CustomOpenApiResolver`

1. **Valores padrão da anotação** — garantem consistência mínima mesmo sem configuração explícita.
2. **Detecção automática** — examina tipo/format OpenAPI e nomes de campo (`descricao`, `observacao` etc.).
3. **Valores definidos na anotação** — sobrepõem heurísticas com decisões do desenvolvedor.
4. **Validações Jakarta** — convertem restrições em propriedades `x-ui.validation` (mensagens amigáveis incluídas).
5. **`extraProperties`** — recebem prioridade máxima para cenários específicos ou integrações customizadas.

> Consulte o diagrama completo de precedência em [docs/architecture-overview.md](../architecture-overview.md#fluxo-de-enriquecimento-x-ui).

## Convenções recomendadas

* **Ordenação (`order`)**: sempre defina para manter consistência nos formulários.
* **Agrupamento (`group`)**: organize campos em blocos semânticos (por exemplo, `dadosPessoais`, `endereco`).
* **Visibilidade**: utilize `formHidden`, `tableHidden`, `filterable` para controlar cada contexto.
* **Mensagens**: personalize `requiredMessage`, `rangeMessage` via `ValidationProperties` quando necessário.

## Validations & `ValidationProperties`

`ValidationProperties` define o vocabulário aceito em `x-ui.validation`. Exemplos:

| Propriedade | Uso |
|-------------|-----|
| `required` | Campo obrigatório | 
| `minLength` / `maxLength` | Limites de tamanho para texto |
| `pattern` | Expressão regular aplicada pelo frontend |
| `fileTypeMessage` | Mensagem customizada para validação de upload |

Essas chaves são preenchidas automaticamente pelo resolver, mas podem ser sobrescritas via `extraProperties`.

## Integrações avançadas

* **Schemas externos**: use `dataEndpoint` para conectar combos a endpoints `/options` padronizados.
* **Condicionais**: combine `conditionalDisplay` e `dependentField` para interfaces dinâmicas.
* **Internacionalização**: preencha labels com `MessageSource` e resolva em runtime usando `LocaleUtils`.

## Referências cruzadas

* [Visão arquitetural](../architecture-overview.md)
* [Visão de pacotes](../packages-overview.md)
* [Exemplo de DTO](../examples/spring-integration.md)
