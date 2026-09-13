# Entrada do protocolo de operações em lote — SDK Java

Estado: fundação Java em desenvolvimento, ainda sem release. Este incremento fornece leitura estrita de requests, codecs de identidade e construção de candidatos de alteração. Não registra endpoints, capabilities, propostas duráveis, executor, jobs ou tabelas. O SDK complementar de [campos editáveis](BULK-EDITABLE-FIELDS.md) fornece @BulkEditable e compilação estrutural de allowlists. O complemento de [resultados e fingerprint de intenção](BULK-PROTOCOL-RESULTS.md) publica os tipos adicionais do SDK. Aceitar estruturalmente `ASYNC` ou `QUERY` não significa que uma operação ofereça esses modos.

## Fonte e impacto

Classificação: contrato público Java, aditivo, no pacote `org.praxisplatform.uischema.bulk` do Metadata Starter. O inventário do fluxo existente encontrou suporte parcial em seleção de actions e precondições de versão; faltava distinguir a identidade wire da identidade Java e representar SET/CLEAR/omissão nas três modalidades. O pacote cobre essa lacuna sem alterar `WorkflowAction`, `ActionExecutionContract`, CRUD resource-oriented ou Jackson global.

O consumidor Java deve usar os tipos disponíveis no JAR candidato. A integração futura de discovery deverá fixar WI e DTOs concretos no binding real e publicar o schema correspondente em `/schemas/filtered`. O schema retornado por um codec neste incremento é um fragmento Java, ainda não conectado ao OpenAPI de uma operação. Nenhuma alteração de contrato HTTP, Angular, Config ou corpus HTTP executável faz parte deste corte.

## Três entradas e confirmação

`BulkCommandEvaluationRequest<P,WI,F>` recebe `executionMode`, `selection` e `parameters` do DTO de negócio. `BulkUniformEvaluationRequest<WI,F>` recebe o mesmo modo/seleção e `changes`. `BulkItemEvaluationRequest<WI>` recebe modo e `items`, cada item com `id`, `expectedVersion` e suas próprias `changes`. Versão é obrigatória, textual e não vazia; o reader não verifica a assinatura/semântica de ETag, que pertence às precondições canônicas da execução.

Seleção explícita:

```json
{"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":"9007199254740993","expectedVersion":"\"opaque-etag\""}]},"changes":[{"field":"resultado","operator":"CLEAR"}]}
```

Seleção por consulta usa `mode: QUERY`, `filter` objeto obrigatório e `excludedIds` opcional (lista vazia quando omitida). Não pode conter `targets`. A seleção explícita não aceita `filter` ou `excludedIds`, nem mesmo nulos. IDs duplicados são rejeitados em alvos, itens e exclusões; o reader não transforma uma consulta em população autorizada.

`BulkConfirmationRequest` contém somente `proposalId` textual não vazio. Ler esse request não valida existência, acesso ou vigência de proposta. Esses controles e a idempotência pertencem a incrementos posteriores.

## Identidade sem coerção

| ID Java | WI no JSON | Validação e fragmento de schema |
|---|---|---|
| Integer | inteiro | int32 com limites explícitos; rejeita string, fracionário e overflow |
| Long | string | decimal canônico, formato `int64-decimal`, até 20 caracteres; codec exige faixa −9223372036854775808 a 9223372036854775807 |
| String | string | não vazia; preserva case, espaços e zeros |
| UUID | string | 36 caracteres, representação canônica em minúsculas |

O padrão/comprimento do schema Long não prova sua faixa sozinho: `decode` valida a faixa. `readWire` valida o token original antes da conversão. `encode` retorna a representação wire. O formato customizado não autoriza consumidores a usar número JavaScript. Cada `wireSchema()` entrega um objeto independente, sem cache mutável compartilhado.

```java
var codec = BulkIdentityCodecs.longs();
var reader = new BulkProtocolReader<>(codec);
var request = reader.readUniform(bodyBytes);
Long domainId = codec.decode(request.selection().targets().getFirst().id());
```

Use esse reader sobre os bytes originais. Desserializar primeiro com `ObjectMapper`, `Map` ou DTO e depois validar perde evidência de tokens/propriedades duplicadas. Não registrar coerção global nem desserializar diretamente os records como substituto do reader.

## Intenção parcial e candidatos

- `SET` exige `value` não nulo. `false`, `0`, `""` e `[]` são valores reais.
- `CLEAR` proíbe a propriedade `value`, inclusive `null`; sua serialização omite essa propriedade.
- Campo omitido permanece como estava. Um campo não pode aparecer duas vezes na mesma lista de alterações.
- `field` é uma chave literal da raiz, não JSONPath/Pointer ou expressão. Um nome com ponto só pode alterar a chave literal se a allowlist a contiver.

`BulkFieldChanges.validate` recebe allowlists estruturais de campos graváveis e limpáveis. Os limpáveis precisam estar contidos nos graváveis. `applyTo` retorna cópia do candidato e representa CLEAR por null. Não persiste nem substitui Bean Validation, política governada, autorização por ator/campo/referência, invariantes do agregado ou verificação de concorrência. BulkEditableFields compila @BulkEditable contra o DTO/schema de update, dialeto OpenAPI e nomes protegidos fornecidos pelo binding. Exclui declarações inválidas de ID, versão, readonly e workflow; BulkFieldChanges sozinho não pode descobrir essas regras. A composição automática do binding e do registry continua pendente.

`BulkFieldChange` copia valores na entrada/saída e rejeita nós opacos Java, binários, missing, nós Float/Double (inclusive finitos) e estruturas excessivamente profundas. Na construção programática, use `BigDecimal`/`DecimalNode` para valores decimais. `toString()` não imprime campo ou valor. Os requests são entradas de avaliação, não snapshots imutáveis de proposta: DTOs `P` e `F` são de responsabilidade do binding e podem ser mutáveis.

## Limites e validação dos DTOs

`BulkProtocolReader` usa parser isolado, rejeita propriedades duplicadas em toda a árvore, tokens adicionais após o documento e campos desconhecidos nos envelopes de transporte. Confere tamanho antes de parsear: teto 8 MiB, profundidade JSON 16, até 50 alterações por item, 10.000 alvos/itens e 10.000 exclusões. `BulkProtocolLimits` permite reduzir esses tetos, não aumentá-los. O adaptador HTTP também precisará limitar a leitura do stream antes de alocar o body; receber um byte array aqui não protege a camada de transporte contra alocação prévia excessiva.

Números decimais são lidos diretamente dos tokens como BigDecimal, também quando o expoente excede a faixa de double; não usar o tree reader de Jackson como garantia de precisão. Preservam valor exato: até 256 dígitos de precisão e escala absoluta 256; tokens numéricos têm limite de comprimento 256. O reader não calcula fingerprint. As quotas efetivas de uma operação (como limites síncronos/atômicos), autorização e orçamento de execução são gates adicionais posteriores.

O binding fornece funções puras para ler e validar `parameters` e `filter` nos DTOs canônicos. Elas devem rejeitar propriedades desconhecidas do domínio, aplicar constraints/tipos sem coerção indevida e não produzir efeitos. A validação do envelope não conhece propriedades do DTO. Mesmo o overload de uniforme que conserva `JsonNode` não valida o filtro de negócio. Nunca executar domínio dentro desses callbacks.

Reservados na raiz de `parameters`: `proposalId`, `selection`, `targets`, `items`, `executionMode`, `atomicity`, `changes`, `filter`, `excludedIds`, `expectedVersion`, `id` e `mode`. Não se permite sobrescrever transporte com parâmetro. Objetos aninhados do DTO seguem seu schema de negócio; a reserva não faz busca textual recursiva.

As exceções de parsing não incluem corpo/cause original. Isso não transforma requests ou DTOs de domínio em objetos seguros para logging: não registrar seus `toString()`, parâmetros ou IDs. Erros HTTP deverão usar o handler público canônico na integração futura.

## Provas e fronteira restante

```text
mvn -DfailIfNoTests=true -Dtest=BulkProtocolContractTest,BulkIdentityCodecTest,BulkFieldChangeValidationTest test
```

Os testes verificam identidades, entradas válidas/inválidas, limites, serialização e preservação da intenção. Esse conjunto prova apenas a entrada do protocolo (parte de T02/T09 do plano). Fingerprint de intenção e tipos de respostas/propostas têm prova complementar em [BULK-PROTOCOL-RESULTS.md](BULK-PROTOCOL-RESULTS.md). A persistência protegida e a participação transacional PostgreSQL têm provas próprias em [BULK-PROPOSAL-STORAGE.md](BULK-PROPOSAL-STORAGE.md) e [BULK-EVALUATION-EVIDENCE.md](BULK-EVALUATION-EVIDENCE.md). Registry/BulkOperation, segurança contextual de execução, atomicidade dos efeitos de domínio, recuperação, idempotência durável e prova HTTP bulk continuam pendentes. Não tratar esse teste focal como aceite integral de backend ou de B1-A.
