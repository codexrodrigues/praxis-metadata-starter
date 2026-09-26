# Leitura estrita do schema de request

`OpenApiDocumentService.requireRequestSchema(CanonicalOperationRef)` lê o request JSON de uma operação explícita no documento OpenAPI canônico. O método default usa `getDocumentForGroupStrict`; fallback ao documento sem grupo não prova a publicação do grupo nomeado e falha fechado. Implementações substitutas precisam fornecer essa operação estrita. `CachedOpenApiDocumentService` fornece o documento/cache já existente. A saída `CanonicalRequestSchema` mantém operação, mídia, `SpecVersion` e schema isolado; cada acesso a `schema()` retorna uma cópia.

```java
// IDs declarados no handler real. Não inferir update pelo verbo ou nome do método.
CanonicalRequestBodyBinding binding = operations.requireResourceRequestBody(
    resourceKey, updateOperationId, "PUT", mapper.getTypeFactory());
CanonicalRequestSchema request = documents.requireRequestSchema(binding.operation());
BulkEditableFields fields = BulkEditableFields.compile(
    mapper, binding.bodyType(), request.schema(), request.specVersion(), protectedWireNames);
```

O exemplo usa o [binding MVC tipado](CANONICAL-OPERATION-BINDING.md) para obter o JavaType do `@RequestBody` concreto, incluindo genéricos. O leitor de schema, isoladamente, não recupera ou compara tipos Java. Em lote, o request da avaliação contém seleção/changes; ele não é o DTO de atualização unitária que declara `@BulkEditable`. Não usar esse wrapper para compilar a allowlist, nem escolher o primeiro PUT/PATCH do recurso. O futuro registry deve compor esse vínculo explícito com a operação unitária de update.

## Garantias e subconjunto suportado

- Exige grupo, ID explícito, path e método; captura o documento por grupo estrito, resolve o path pelo serviço canônico e confere o mesmo ID na operação daquele documento. IDs repetidos nas operações de `paths` daquele documento falham. A unicidade global dos handlers/recurso pertence a `requireResourceOperation`; o leitor não a substitui. O overload interno com snapshot permite que um compositor compartilhe a mesma cópia defensiva entre bindings e leitura dos dois schemas.
- Exige `openapi` 3.0.x ou 3.1.x. Não deduz dialeto por `nullable` ou pelo tipo. Em 3.1, aceita o dialeto padrão OpenAPI e JSON Schema 2020-12; dialectos customizados falham. Esse suporte é um subconjunto estrutural, não um validador completo de OpenAPI/JSON Schema.
- Exige um único media type JSON concreto: `application/json` ou `application/<subtype-token>+json`, com subtype formado por caracteres ASCII `tchar` válidos; a comparação de nome é case-insensitive. Wildcards, parâmetros, espaços, tabs e caracteres Unicode não válidos não são aceitos como chaves de media type. Múltiplos candidatos JSON são ambíguos, inclusive `application/json` junto de outro `+json`; não escolhe uma allowlist arbitrária entre representações. XML e wildcard não provam um contrato JSON. A seleção documental permissiva continua a mesma para os controllers existentes, compartilhando a preferência na camada OpenAPI.
- Resolve referências locais a `#/components/requestBodies/` e `#/components/schemas/`, com escapes JSON Pointer `~0`/`~1`. Não busca rede para resolver referências. Referência ausente, externa, cíclica, com siblings ou encoding não suportado falha explicitamente. Path items por referência não são suportados.
- Resolve posições estruturais de schema (propriedades, itens, mapas/defs e demais subschemas suportados). `default`, `examples` e `x-ui` permanecem dados literais: uma chave `$ref` nesses valores não causa resolução. Preserva constraints e metadados; não remove restrições para aceitar a compilação.
- `allOf`, `oneOf`, `anyOf`, negações/condicionais, dependências e referências dinâmicas/relativas com mudança de base não são materializadas neste corte. A interseção de constraints de `allOf` não equivale a juntar propriedades. Schemas compostos legítimos precisam de suporte semântico e provas adicionais antes de aceitos por esta leitura.
- Expansão limitada a 64 níveis e 10.000 nós visitados, incluindo valores documentais copiados; ciclo ou expansão excessiva falha antes de produzir o resultado. Não é limite do corpo HTTP recebido pelo executor nem proteção do fetch inicial do documento.

Estas escolhas são deliberadamente mais restritas que o padrão: [OpenAPI 3.0 — Reference Object](https://spec.openapis.org/oas/v3.0.3.html#reference-object) e [OpenAPI 3.1 — Schema Object/dialetos](https://spec.openapis.org/oas/v3.1.0.html#schema-object) explicam as diferenças. Não descartar siblings ou a composição de schemas 3.1 silenciosamente.

## Fonte e ciclo de vida

O leitor trabalha sobre o request wire documentado. `/schemas/filtered` continua a projeção estrutural de UI, com suas referências, ETag, X-Schema-Hash e enriquecimentos; este SDK não cria outro endpoint, ID, cache de schema ou payload de capability. `SchemaReferenceResolver` mantém seu papel de gerar identidade/URL. Não substituir a projeção pública inteira pelo resultado deste leitor.

A implementação padrão obtém SpringDoc por HTTP do próprio host. Portanto deve ser chamada **após o servidor e o documento estarem disponíveis**, nunca como prova antecipada durante a criação de beans. Um futuro registry precisa definir validação/readiness que bloqueie admissão até as dependências reais estarem prontas, ou outra leitura local canônica comprovada. Este incremento não instala esse ciclo automaticamente. Implementações substitutas de OpenApiDocumentService herdam o mesmo algoritmo; personalizam a fonte por getDocumentForGroupStrict/resolveDocumentPath. Não precisam fabricar snapshots nem reimplementar a resolução.

Entradas inválidas de operação produzem IllegalArgumentException; inconsistências/limites de documento produzem IllegalStateException. São falhas de composição, não respostas públicas prontas para o operador. O host deve usar seus diagnostics/erros seguros quando integrar o runtime. O leitor não autoriza atores, valida valores de domínio, executa atualização nem cria storage/providers.

## Provas

`CanonicalRequestSchemaTest` cobre operações/mídias/dialetos, referências/escapes/composição, cópias/cache e limites. `CanonicalRequestSchemaHttpIntegrationTest` usa MVC e SpringDoc servidos em HTTP real, um handler update/DTO explícito, o binding MVC tipado e BulkEditableFields; verifica também que a projeção filtrada continua disponível com headers. A fixture é uma operação de update sem persistência, não um executor de lote.

Regressões focais: OpenApiDocsSupportTest, ApiDocsControllerTest, ApiDocsControllerPathResolutionTest, ApiDocsControllerSchemaHashTest e BulkEditableFieldsTest. Validar o JAR candidato exato no Quickstart. Registry, schemas de avaliação/execução, autorização, PostgreSQL e runtime bulk continuam pendentes.
