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


## Vínculo com o DTO de request

`requireResourceRequestBody(resourceKey, operationId, method, mapper.getTypeFactory())` retorna `CanonicalRequestBodyBinding`, com `operation()` e `bodyType()` (JavaType). O resolver usa a mesma entrada MVC estrita da resolução acima; o consumidor não fornece uma classe de DTO separada nem faz outro lookup de handler.

```java
CanonicalRequestBodyBinding binding = resolver.requireResourceRequestBody(
    resourceKey, updateOperationId, "PUT", mapper.getTypeFactory());
// Etapa posterior: a fonte padrão do documento exige o servidor disponível.
CanonicalRequestSchema request = documents.requireRequestSchema(binding.operation());
BulkEditableFields fields = BulkEditableFields.compile(
    mapper, binding.bodyType(), request.schema(), request.specVersion(), protectedWireNames);
```

O método requer exatamente um `@RequestBody` obrigatório e direto. Usa os parâmetros do HandlerMethod registrado (incluindo annotations herdadas) e resolve variáveis no contexto do controller concreto. Mantém parâmetros aninhados como `UpdateEnvelope<List<String>>`; não converte o tipo bruto ou uma variável não resolvida em Object. A TypeFactory deve vir do mapper configurado para essa composição.

O subconjunto exige DTO raiz concreto (classe ou record). Recusa body ausente/múltiplo/opcional, HttpEntity/RequestEntity/Optional, raiz array/coleção/mapa, Object/JsonNode, tipos simples definidos pelo Spring (incluindo UUID/datas), interface/abstrato e classe membro não estática. Tipos raw, wildcards e variáveis não vinculadas, inclusive parâmetros de método e ancestrais genéricos, falham explicitamente. Os argumentos genéricos também precisam estar fechados; Object como argumento é recusado. Resolução/herança tem limite de 32 níveis. Não executa construtores, converters nem código de domínio.

A garantia é sobre o **tipo declarado pelo handler MVC** e sua operação. Não comprova que custom HttpMessageConverters, desserializadores polimórficos ou customizações SpringDoc tenham semântica idêntica. Esses pontos continuam na composição/prova do consumidor; não use uma anotação Swagger para substituir o tipo que o MVC recebe. Também não infere quais campos de identidade/versão/workflow devem ser protegidos: protectedWireNames segue obrigatório na compilação de campos.

O método requer o registro MVC inicializado e não busca OpenAPI. `requireRequestSchema` é uma etapa posterior, com a mesma operação retornada. O warmup atual é opcional, assíncrono e tolera falha, portanto não prova readiness de execução. Um futuro registry precisa bloquear admissão até validar schema/infraestrutura/providers e definir revalidação quando sua fonte mudar; este SDK não instala esse gate nem anuncia capability de lote.

Resolvers substitutos implementam a mesma prova de handler/tipo; o default deste método lança UnsupportedOperationException. O valor público permite integração desses resolvers confiáveis, não valida um handler apenas por ser construído. Argumento TypeFactory nulo falha com IllegalArgumentException; ausência/ambiguidade/tipo fora do subconjunto falha com IllegalStateException. Essas falhas são de composição, não respostas HTTP de negócio prontas.

Prova focal: CanonicalRequestBodyBindingTest usa MVC real e casos diretos/herdados/interfaces/genéricos; CanonicalRequestSchemaHttpIntegrationTest usa o novo vínculo, o SpringDoc servido e a compilação de BulkEditableFields. A regressão mantém CanonicalResourceOperationBindingTest, OpenApiCanonicalOperationResolverTest e ReactiveDeterminationMetadataCompilerTest. No host, declarar operationId explícito nos endpoints de update será parte da adoção; não relaxar a exigência quando overrides antigos só tenham summary.
