# Form effects and determinations

Use `x-ui.formEffects` when a valid change in one or more form fields must call a typed backend query and use its response to enrich other fields before submit. Typical examples are postal-code lookup, tax-code classification and governed price or eligibility determinations.

This is not a generic callback facility. The source operation declares a closed mapping to a concrete POST operation annotated with `@FormDetermination`. The starter resolves the actual OpenAPI path and request/response schemas, validates every binding at startup and publishes the normalized contract on the request schema returned by `/schemas/filtered`.

```java
@PostMapping
@FormEffect(
    id = "address-from-postal-code",
    triggerFields = "cep",
    operationId = "determinePostalAddress",
    inputs = @FormEffectInput(formField = "cep", operationField = "cep"),
    outputs = {
        @FormEffectOutput(operationField = "logradouro", formField = "logradouro"),
        @FormEffectOutput(operationField = "cidade", formField = "cidade")
    }
)
public ResponseEntity<?> create(@Valid @RequestBody CreateAddressDTO command) { /* ... */ }

@PostMapping("/determinations/postal-address")
@Operation(operationId = "determinePostalAddress")
@FormDetermination
public ResponseEntity<?> determine(@Valid @RequestBody PostalAddressRequest request) { /* ... */ }
```

The default output policy is `if-pristine`: user-edited values win. `if-empty` only fills an empty target. `replace` is restricted to targets published as read-only. The runtime applies all available response bindings atomically, cancels stale requests with latest-wins semantics and emits diagnostics without field values.

Guardrails:

- only `value-change` and POST determinations are supported;
- source, request and response fields must exist and have compatible OpenAPI types;
- two effects cannot target the same field on one source operation;
- effect chains and cycles are rejected;
- no scripts, expressions, arbitrary headers, arbitrary methods or consumer-authored URLs are accepted;
- determinations do not run during initial form hydration;
- authorization, tenant scope and data minimization remain backend responsibilities.

For a select whose options depend on another field, continue using `optionSource.dependsOn`. For a persisted business transition, use `@WorkflowAction`. A form effect is specifically a non-persisting, typed determination whose result enriches the current form draft.
