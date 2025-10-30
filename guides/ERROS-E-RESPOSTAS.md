# Erros e Envelope de Respostas

Como lidar com respostas padronizadas e tratamento de erros.

- Javadoc: [`RestApiResponse`](../apidocs/org/praxisplatform/uischema/rest/response/RestApiResponse.html)
- Javadoc: [`CustomProblemDetail`](../apidocs/org/praxisplatform/uischema/rest/response/CustomProblemDetail.html)
- Javadoc: [`GlobalExceptionHandler`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.html)
- Javadoc: [`ErrorCategory`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/ErrorCategory.html)

## Estrutura de sucesso

Use o helper `RestApiResponse.success(data, links)` nos controllers base.

```json
{
  "status": "success",
  "message": "Requisição realizada com sucesso",
  "data": { /* payload */ },
  "links": { /* HATEOAS (quando habilitado) */ },
  "timestamp": "2024-01-01T10:00:00"
}
```

## Estrutura de falha

Erros são padronizados com `CustomProblemDetail` e categorias (`ErrorCategory`).

```json
{
  "status": "failure",
  "message": "Erro de validação",
  "errors": [
    {
      "message": "Campo obrigatório",
      "status": 400,
      "title": "nome",
      "type": "https://example.com/probs/validation-error",
      "instance": "/api/human-resources/funcionarios",
      "category": "VALIDATION"
    }
  ],
  "timestamp": "2024-01-01T10:00:00"
}
```

## Tratamento global de exceções

`GlobalExceptionHandler` converte exceções comuns (validação, not found, regra de negócio, etc.) em respostas padronizadas.

- `MethodArgumentNotValidException` → 400 com lista de `CustomProblemDetail`
- `BusinessException` → 400 com categoria `BUSINESS_LOGIC`
- `EntityNotFoundException` → 404
- `Exception` → 500

## Boas práticas

- Preencha mensagens claras nos `ProblemDetail` para facilitar a UX
- Categorize adequadamente para telemetria/observabilidade
- Padronize os links `type` para catálogos internos de erros

## Referências

- [`RestApiResponse`](../apidocs/org/praxisplatform/uischema/rest/response/RestApiResponse.html)
- [`GlobalExceptionHandler`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.html)
- [`CustomProblemDetail`](../apidocs/org/praxisplatform/uischema/rest/response/CustomProblemDetail.html)
