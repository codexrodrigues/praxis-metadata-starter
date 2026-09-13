# Binding estrutural de operações por recurso

O `CanonicalOperationResolver` é a fonte canônica para referências de operação usadas por schema e discovery. Para um vínculo obrigatório entre operação e recurso, o SDK Java oferece:

```java
CanonicalOperationRef evaluation = resolver.requireResourceOperation(
        "payroll.events", "payroll.events.evaluateApproval", "POST");
```

O exemplo identifica uma operação que o host precisa registrar de fato. O método não cria rota, annotation de lote, schema, tabela ou executor. Não resolve intenção de usuário por nomes ou palavras; recebe identidades canônicas já escolhidas pelo consumidor.

## Garantias do resolver MVC

A resolução estrita exige um único mapping com o ID solicitado em todo o registro MVC, antes de filtrar recurso ou método. Depois exige `@Operation(operationId=...)` explícita, `@ApiResource(resourceKey=...)` correspondente, uma única rota e um único método HTTP igual ao esperado. Operações com `@Operation(hidden=true)` ou `@Hidden` no método/classe são rejeitadas.

Não aceita fallback para nome do método Java, escolha entre aliases de rota, métodos HTTP implícitos/múltiplos, ou condições de params/headers/custom que `CanonicalOperationRef` não consegue transportar. Também rejeita uma rota cuja normalização alteraria o path registrado, como slash final, barras duplicadas ou percent-encoding. Outro mapping que compartilhe o mesmo path e método também é rejeitado, mesmo com ID distinto e condições de mídia diferentes: a referência não poderia distinguir esses handlers. Condições de mídia de um único mapping continuam sendo responsabilidade do contrato OpenAPI/HTTP; este vínculo não valida request/response nem negociação de conteúdo.

`resourceKey` e `operationId` são comparados exatamente; método HTTP aceita normalização de case e espaços. Argumentos vazios ou método HTTP inválido produzem `IllegalArgumentException`. Ausência de registro, ambiguidade, referência a outro recurso ou shape incompatível produzem `IllegalStateException`. Resolvers substitutos precisam implementar as mesmas garantias: o default da interface lança `UnsupportedOperationException`, sem degradar silenciosamente para lookup permissivo.

## Lookup existente e compatibilidade

`resolveByOperationId` mantém o fallback legado para nome Java e o retorno vazio para ID ausente. Entretanto, um ID efetivo presente em mais de um mapping agora falha com `IllegalStateException`, em vez de escolher a primeira ocorrência. Uma colisão entre ID explícito e nome Java legado também falha; filtrar primeiro pelo recurso esconderia uma ambiguidade global.

Essa mudança é intencional e pode revelar configurações antes ambíguas em determinações reativas ou consumidores próprios. Corrigir os IDs dos handlers e suas referências na fonte. Não depender da ordem do Spring MVC para selecionar uma operação. A sobrecarga geral `resolve(handler, mapping)` preserva sua escolha documentada de rota/método; ela não substitui a garantia do método estrito.

## Limites e composição

Invocar após a inicialização do registro MVC. O método consulta os handlers e a resolução de grupo; não busca o documento `/v3/api-docs`. Não há validação automática de bootstrap neste incremento. O futuro registry declarativo deve chamar a resolução estrita no seu gate de inicialização.

A referência obtida pode alimentar o `SchemaReferenceResolver` existente, mas gerar uma URL de `/schemas/filtered` não prova que esse schema existe ou tem o conteúdo correto. A composição ainda precisa verificar o OpenAPI real, request/response concretos, identidade wire, papel de avaliação versus confirmação, providers, autorização e infraestrutura. Um binding estrutural válido não anuncia nem certifica uma operação de lote executável.

## Prova

`OpenApiCanonicalOperationResolverTest` cobre lookup e rejeições estruturais; `CanonicalResourceOperationBindingTest` usa registro MVC real. A regressão do consumidor deve usar o JAR candidato exato, incluindo schemas, ações e capabilities existentes. Não usar esses testes para declarar T01 completo: bootstrap declarativo, schemas reais e rejeição de infraestrutura ausente permanecem pendentes em B1-B.
