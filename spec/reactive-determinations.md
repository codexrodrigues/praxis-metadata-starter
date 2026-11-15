# Reactive Determinations

A publicação derivada em `/schemas/domain` preserva esta configuração integralmente por `schemaOperationId` e `formMode`. O [guia de ontologia](../guides/DOMAIN-CATALOG-ONTOLOGY.md) define os bindings `api_operation`/`form_config`, identidade do nó, caminhos aninhados, governança e testes de equivalência HTTP. Não há mudança no wire format de `x-ui.reactiveDeterminations`.

`x-ui.reactiveDeterminations` e a projecao estrutural e tenant-neutral que liga um draft de
formulario a uma operacao backend deterministica e nao persistente.

Ela nao e um motor de regras no frontend, um callback configuravel nem uma forma generica de
executar endpoints. O backend continua sendo responsavel por facts, autorizacao, avaliacao da
decisao e validacao final no submit.

## Fronteira canonica

- o host declara determinacoes diretamente no metodo `POST` nao-persistente com `@ReactiveDetermination` (`@DeterminationInput`, `@DeterminationOutput`, `@DeterminationScope` — auto-descoberto por `AnnotationDrivenReactiveDeterminationDefinitionProvider`, `PLAT-EVO-16`) ou registra `ReactiveDeterminationDefinitionProvider` como bean estatico quando precisar resolver `provenance` dinamica de snapshots governados (`GovernedRuleFamilyRegistry`);
- o registry captura um snapshot no bootstrap, fora de qualquer request ou contexto de tenant;
- cada scope aponta para o `operationId` exato do request schema de create/edit;
- a capability executora e resolvida por `CanonicalOperationResolver` e deve ser `POST`;
- `href`, `requestSchemaUrl` e `responseSchemaUrl` sao derivados pelo starter, nunca fornecidos
  pelo provider;
- sources, inputs e outputs usam JSON Pointer e sao validados contra os schemas reais;
- cada `@DeterminationInput` deve preservar **fidelidade estrita `1:1` de sinal e semantica** entre o campo do formulario e o campo do DTO de determinacao (proibido reaproveitar uma propriedade de acrescimo/vantagem no request DTO para transportar uma deducao/desconto do formulario; adicione propriedades tipadas explicitas, inclusive separando data/competencia do fato gerador da competencia de processamento em calculos efetivos no tempo);
- ids duplicados, writers sobrepostos e ciclos falham fechado durante a materializacao;
- um output que sobrepoe o `inputs.fieldPath` de uma determinacao estabelece uma dependencia,
  inclusive quando o input fica fora de `sourcePaths`, que lista os gatilhos de mudanca;
- as determinacoes de cada operacao sao publicadas em ordem topologica, com desempate
  estavel por `id` entre as determinacoes prontas;
- o bloco e publicado somente na variante `schemaType=request` de `/schemas/filtered`.

O schema filtrado permanece estrutural e cacheavel publicamente. Tenant, usuario, facts, valores,
status de uma materializacao Config ou identidade de uma decisao aplicada nao podem aparecer no
provider nem na projecao. Quando empresas diferentes usam politicas diferentes, a mesma capability
backend resolve a decisao aplicada sob o principal autenticado.

## Valores informativos e contexto de edição

Um output somente informativo pode existir no request schema para que o compiler
e o formulário encontrem seu `fieldPath`. `@Schema(READ_ONLY)` e
`@UISchema(readOnly=true)` descrevem essa apresentação; não controlam sozinhos a
desserialização Java. Para resultados que não são entrada de escrita, publique
também `@JsonProperty(access=READ_ONLY)`: o servidor ignora o valor recebido e
recalcula os dados de negócio. Não dependa de remover campos por nome no frontend
nem de omitir globalmente todo read-only, pois outros contratos permitem seu
round-trip inalterado. Um `@Null` defensivo não pode rejeitar um output enviado
pelo runtime oficial depois que a prévia o preencheu.

Uma prévia de EDIT que depende do consumo atual precisa da identidade carregada
do item, além dos campos em edição. Ela pode usar um input adicional `/id` do
request schema, somente leitura, sem torná-lo uma source de mudança. O endpoint
resolve esse ID no servidor, confere autorização e pertencimento ao agregado e
recupera o consumo anterior. Não aceite um valor de crédito fornecido pelo cliente
nem desconte duas vezes o consumo já incorporado ao saldo persistido. Na criação,
esse contexto permanece nulo; a prévia nunca autoriza a gravação final.

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

### Autoria declarativa zero-boilerplate (`@ReactiveDetermination` — `PLAT-EVO-16`)

Como padrao de primeira escolha no controller do recurso, anote diretamente o handler `POST` nao-persistente com `@ReactiveDetermination`, `@DeterminationInput`, `@DeterminationOutput` e (para formularios parciais `@ResourceIntent` ou escopos customizados) `@DeterminationScope(schemaOperationId = ..., formMode = ...)`:

- `AnnotationDrivenReactiveDeterminationDefinitionProvider` descobre automaticamente todos os metodos anotados no boot (`OpenApiUiSchemaAutoConfiguration`) e expoe `fromControllerClasses(...)` para testes unitarios sem contexto Spring;
- `@DeterminationInput` e `@DeterminationOutput` aceitam tanto nomes simples de propriedade (`"zipCode"`) quanto JSON Pointers com barra inicial (`"/zipCode"`), normalizando ambos para RFC 6901 sem aceitar barra final (`"zipCode/"` falha fechado). Ja os construtores programaticos `ReactiveDeterminationInputBinding` e `ReactiveDeterminationOutputBinding` exigem explicitamente barra inicial (`"/zipCode"`);
- preserve fidelidade `1:1` de sinal aritmetico e semantica de dominio entre `field` e `request` (nunca mapear campo de deducao/desconto para propriedade de acrescimo/vantagem ou vice-versa) e vincule separadamente a data do fato gerador e a competencia de processamento ao consultar tabelas progressivas temporais (`EffectiveDatedBracketTable`, `PLAT-EVO-18`).

## Limites da primeira versao

- somente `on-change`;
- somente formularios create/edit baseados no request schema exato;
- bindings aninhados de objetos sao suportados;
- cada determinacao aceita no maximo 64 bindings somando entradas e saidas;
- pointers duplicados ou hierarquicamente sobrepostos, inclusive entre campos de entrada e saida, falham fechado;
- arrays repetiveis e wildcards ainda nao possuem identidade canonica e falham fechado;
- recomendacoes probabilisticas, side effects/refresh e validacoes backend pertencem a contratos
  distintos e nao devem ser modelados como Reactive Determination.
