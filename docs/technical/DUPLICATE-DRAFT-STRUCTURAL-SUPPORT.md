# Suporte estrutural de duplicate-draft

O suporte de duplicação em `canonicalOperations["duplicate-draft"]` e
`operations["duplicate-draft"].supported` exige simultaneamente:

1. `POST /{resource}/{id}/duplicate-draft` publicado no documento OpenAPI consultado;
2. `ResourceStructuralCapabilities.duplicateDraft == true` fornecido pelo serviço do recurso.

`BaseResourceQueryService.getStructuralCapabilities()` deriva esse sinal da presença de
`DuplicateDraftResourceCommandService` e de seu `supportsDuplicateDraft()`. Implementar
a porta é opt-in explícito; seu default é `true`, e o serviço pode sobrescrevê-lo para
`false`. Serviços diretos e adaptadores externos não precisam herdar JPA.
`DuplicateDraftResourceService` agrega essa mesma porta ao contrato CRUD.

`AbstractBaseQueryResourceService` reaproveita a derivação do default, preservando seu
cálculo próprio de options, option-sources, stats e export. Assim, subclasses de
`AbstractBaseResourceService` que implementam a porta também publicam o sinal.
Não se executa `duplicateDraft` para descobrir suporte.

| Mapping POST | Opt-in estrutural | Indicador e supported | preferredMethod |
|---|---|---|---|
| Presente | true | true | POST |
| Presente | false | false | null |
| Ausente ou somente GET | true ou false | false | null |
| Presente | NoOp/serviço não identificado | false | null |

O descriptor conserva `scope=ITEM` e `preferredRel=duplicate-draft`, inclusive quando
sem suporte. `preferredRel` é identidade de relação, não um link executável.
`NoOpResourceStructuralCapabilityResolver` retorna `unsupported()`, nunca uma aprovação
inferida do path. Usar o construtor do resolver sem infraestrutura estrutural também
produz ausência de suporte. Fixtures positivas devem fornecer infraestrutura explícita.

## Migração Java

O construtor canônico de `ResourceStructuralCapabilities` passa a receber dez argumentos:

```java
new ResourceStructuralCapabilities(
    options, optionSources, statsGroupBy, statsTimeSeries,
    statsDistribution, statsComparison, export, duplicateDraft,
    stats, exportCapability
);
```

É uma quebra de assinatura Java, sem overload de compatibilidade. Chamadas externas
precisam ser migradas antes de integrar a nova versão. Ao sobrescrever
`getStructuralCapabilities`, preservar o sinal da porta junto aos demais suportes;
uma implementação que retorne `unsupported()` explicitamente continuará negando tudo.
Para um recurso sem a porta, usar `false`; não habilitar duplicação apenas para fazer
uma fixture ou compilação passar. A linha do POM não foi alterada neste corte.

## Fronteiras desta entrega

OpenAPI é estrutural e independente do usuário. O mapping herdado do controller opt-in
continua no documento mesmo quando o serviço declara suporte false. O resolver de
capabilities não remove paths, não modifica schemas e não desregistra handlers MVC.
O schema de resposta do preparo continua sendo o `DraftDTO` concreto; o schema de
request da criação continua pertencendo ao POST de criação.

T02 trata apenas suporte estrutural. A composição de disponibilidade com `create`,
autorização da origem, guardas, links e o catálogo de actions é dependência de T03.
A annotation existente de workflow não é removida nem seu catálogo certificado aqui.
`supported=true` não concede autorização, prova ausência de persistência no adaptador
ou aceita globalmente a jornada. Criação deve ser revalidada no POST final.

Os testes deste corte são locais, com fixtures, mocks e conversão de schema; não
certificam HTTP implantado, banco, pilotos de domínio ou execução Angular.
Migrações dos pilotos, guidance amplo e validação integrada pertencem aos cortes
T05–T12 do plano de duplicação.
