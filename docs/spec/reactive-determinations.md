# Reactive Determinations

`x-ui.reactiveDeterminations` e a projecao estrutural e tenant-neutral que liga um draft de
formulario a uma operacao backend deterministica e nao persistente.

Ela nao e um motor de regras no frontend, um callback configuravel nem uma forma generica de
executar endpoints. O backend continua sendo responsavel por facts, autorizacao, avaliacao da
decisao e validacao final no submit.

## Fronteira canonica

- o host registra `ReactiveDeterminationDefinitionProvider` como bean estatico;
- o registry captura um snapshot no bootstrap, fora de qualquer request ou contexto de tenant;
- cada scope aponta para o `operationId` exato do request schema de create/edit;
- a capability executora e resolvida por `CanonicalOperationResolver` e deve ser `POST`;
- `href`, `requestSchemaUrl` e `responseSchemaUrl` sao derivados pelo starter, nunca fornecidos
  pelo provider;
- sources, inputs e outputs usam JSON Pointer e sao validados contra os schemas reais;
- ids duplicados, writers sobrepostos e ciclos falham fechado durante a materializacao;
- o bloco e publicado somente na variante `schemaType=request` de `/schemas/filtered`.

O schema filtrado permanece estrutural e cacheavel publicamente. Tenant, usuario, facts, valores,
status de uma materializacao Config ou identidade de uma decisao aplicada nao podem aparecer no
provider nem na projecao. Quando empresas diferentes usam politicas diferentes, a mesma capability
backend resolve a decisao aplicada sob o principal autenticado.

## Exemplo Java

```java
@Bean
ReactiveDeterminationDefinitionProvider customerDeterminations() {
    return () -> List.of(new ReactiveDeterminationDefinition(
        "address.by-postal-code",
        List.of(new ReactiveDeterminationScope(
            "createCustomer",
            ReactiveDeterminationFormMode.CREATE
        )),
        "determineCustomerAddress",
        ReactiveDeterminationTriggerMode.ON_CHANGE,
        List.of("/postalCode"),
        List.of(new ReactiveDeterminationInputBinding("/postalCode", "/postalCode")),
        List.of(
            new ReactiveDeterminationOutputBinding("/city", "/address/city"),
            new ReactiveDeterminationOutputBinding("/state", "/address/state")
        ),
        new ReactiveDeterminationProvenance(
            ReactiveDeterminationProvenanceKind.HOST,
            "customer-determinations",
            "1"
        )
    ));
}
```

O endpoint `determineCustomerAddress` deve ter `@Operation(operationId = ...)`, request/response
JSON documentados e mapping `POST` sem path variables. O submit de `createCustomer` continua
obrigado a recalcular ou validar os campos derivados; metadata e execucao reativa nao sao
enforcement de integridade.

## Limites da primeira versao

- somente `on-change`;
- somente formularios create/edit baseados no request schema exato;
- bindings aninhados de objetos sao suportados;
- cada determinacao aceita no maximo 64 bindings somando entradas e saidas;
- pointers duplicados ou hierarquicamente sobrepostos, inclusive entre campos de entrada e saida, falham fechado;
- arrays repetiveis e wildcards ainda nao possuem identidade canonica e falham fechado;
- recomendacoes probabilisticas, side effects/refresh e validacoes backend pertencem a contratos
  distintos e nao devem ser modelados como Reactive Determination.
