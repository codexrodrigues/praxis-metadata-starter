# Erros e Envelope de Respostas

Como lidar com respostas padronizadas e tratamento de erros.

- Javadoc: [`RestApiResponse`](../apidocs/org/praxisplatform/uischema/rest/response/RestApiResponse.html)
- Javadoc: [`CustomProblemDetail`](../apidocs/org/praxisplatform/uischema/rest/response/CustomProblemDetail.html)
- Javadoc: [`GlobalExceptionHandler`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.html)
- Javadoc: [`ErrorCategory`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/ErrorCategory.html)

<a id="estrutura-de-sucesso"></a>
<details>
<summary><strong>Estrutura de sucesso</strong></summary>

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

</details>

<a id="estrutura-de-falha"></a>
<details>
<summary><strong>Estrutura de falha</strong></summary>

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

</details>

<a id="tratamento-global-de-excecoes"></a>
<details>
<summary><strong>Tratamento global de exceções</strong></summary>

`GlobalExceptionHandler` converte exceções comuns (validação, not found, regra de negócio, etc.) em respostas padronizadas.

- `MethodArgumentNotValidException` → 400 com lista de `CustomProblemDetail`
- `MissingRequestHeaderException` → 400 (header obrigatório ausente)
- `MissingServletRequestParameterException` → 400 (parâmetro obrigatório ausente)
- `ResponseStatusException` → preserva status original (ex.: 400/403/404/409/410/429/503), sem rebaixar para 500
- `InvalidFilterPayloadException` → 400 (payload de filtro inválido)
- `BusinessException` → 400 com categoria `BUSINESS_LOGIC`
- `EntityNotFoundException` → 404
- `IllegalArgumentException` fora de validação explícita de schema → 500 (evita mascarar erro interno como erro do cliente)
- `Exception` → 500

</details>

<a id="boas-praticas"></a>
<details>
<summary><strong>Boas práticas</strong></summary>

- Preencha mensagens claras nos `ProblemDetail` para facilitar a UX
- Categorize adequadamente para telemetria/observabilidade
- Padronize os links `type` para catálogos internos de erros

</details>

<a id="referencias"></a>
<details>
<summary><strong>Referências</strong></summary>

- [`RestApiResponse`](../apidocs/org/praxisplatform/uischema/rest/response/RestApiResponse.html)
- [`GlobalExceptionHandler`](../apidocs/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.html)
- [`CustomProblemDetail`](../apidocs/org/praxisplatform/uischema/rest/response/CustomProblemDetail.html)

</details>
