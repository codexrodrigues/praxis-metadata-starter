# Propostas, resultados e fingerprint de intenção — SDK Java

Este incremento complementa a entrada do protocolo com valores Java de proposta/execução/resultado, um snapshot protegido da intenção e schemas documentais. Não cria endpoints, capability executável, proposta durável, receipt, worker ou banco. A publicação do SDK não significa que avaliação, confirmação ou execução estejam disponíveis em um host.

## Fonte canônica e inventário

Mudança `contrato-publico` no pacote `org.praxisplatform.uischema.bulk` do Metadata. `CanonicalOperationRef`, `ActionCollectionAtomicity`, `ResourceCommandMessage` e o envelope `RestApiResponse._links` já existem e são reutilizados. `ResourceCommandExecutionResult` não representa o ciclo coletivo ou uma proposta imutável. Seus mapas arbitrários não são armazenamento de fatos/políticas. `SchemaHashUtil` mantém o comportamento atual de hash de schema; não normaliza intenção tipada de lote, por isso o digest de intenção é implementado no dono `bulk`.

Consumidores afetados: SDK Java e documentação de contrato; Quickstart deve provar regressão com o JAR candidato exato. A integração real de DTOs de domínio/OpenAPI/discovery pertence ao binding da operação, ainda não composto. Não há atualização de Angular, Config, exemplos HTTP executáveis ou playgrounds para operações inexistentes.

## Snapshot protegido e vínculo de intenção

`BulkIntentSnapshot.uniform`, `.command` e `.items` recebem requests tipados já validados e `BulkFingerprintContext`. O contexto vem do servidor: namespace operacional, sujeito autenticado, resourceKey, operação canônica, revisão do schema e atomicidade. Não preenchê-lo a partir de header livre, default de tenant ou parâmetro do operador. O codec entra diretamente na fábrica e valida a identidade wire antes de normalizar.

O snapshot copia a intenção e oferece `intent()` por cópia defensiva, `fingerprint()` e `toString()` sem conteúdo. É marcado com `@JsonIgnoreType` para não ser incorporado automaticamente como propriedade de resposta. O retorno explícito de `intent()` continua protegido: nunca enviá-lo como preview público ou registrá-lo em log. A cópia captura a entrada de avaliação; QUERY ainda contém filtro e exclusões, não um manifest congelado de alvos.

Encoders de `parameters` e `filter` serializam DTOs canônicos já validados em objetos JSON, sem efeitos e sem coerção para double. Não são validadores de domínio ou autorização. Float/Double, valores Java opacos, ciclos, Unicode com surrogate isolado e estruturas fora dos limites são rejeitados. A reserva de bindings de transporte em `parameters` é a mesma do reader, sem segundo catálogo divergente.

### Regra determinística

O fingerprint inclui namespace, sujeito, recurso, todos os campos de operationRef, schemaRevision, atomicidade, codecId, modalidade e a intenção completa com executionMode. Inclui parâmetros tipados, filtros, IDs, versões e SET/CLEAR/omissão. Não inclui tokens de sessão ou headers transitórios de correlação.

- Propriedades de objetos são ordenadas lexicograficamente por unidades UTF-16, sem locale ou normalização Unicode.
- Alvos explícitos e exclusões são conjuntos: ordenar pela representação textual canônica da identidade; duplicatas são erro antes de normalizar. Ordenação numérica não é presumida.
- Alterações de campos são ordenadas pelo nome literal do campo, que não pode se repetir.
- A ordem de `items` e dos arrays de negócio é preservada. Não reinterpretar arrays de filtros/parâmetros como conjuntos sem o contrato do domínio.
- Decimais usam BigDecimal exato, normalizado com remoção de zeros decimais finais. `1.0` e `1.00` têm o mesmo valor decimal; inteiro `1`, decimal `1.0` e string `"1"` permanecem categorias distintas. Não passar por double.

O algoritmo de framing é específico deste protocolo, identificado por `praxis.bulk.intent/1`, sem pretensão de ser RFC 8785. Strings são comprimento int32 big-endian seguido de UTF-8 válido. A sequência começa com a string de identificação. Nós usam marcadores ASCII `O` objeto, `A` array, `S` string, `I` inteiro, `D` decimal, `T`/`F` boolean e `N` null. Objetos/arrays acrescentam quantidade int32; objetos seguem com pares nome/string e nó em ordem canônica. Inteiros e decimais acrescentam string decimal (`BigInteger.toString()` ou `BigDecimal.stripTrailingZeros().toString()`). SHA-256 cobre toda a sequência; o resultado é `sha256:` seguido de 64 dígitos hexadecimais minúsculos.

O preflight confere profundidade, tipos e orçamento antes da cópia defensiva de valores e objetos de domínio. Ele não evita a alocação do objeto original pelo produtor/encoder; o binding deve limitar essa produção. O frame é limitado a 8 MiB, incluindo nomes, contexto e framing: um JSON próximo do limite de entrada pode exceder esse orçamento e ser rejeitado. Limites de 50 alterações por item e 10.000 alvos/itens/exclusões também se aplicam ao snapshot. O reader continua necessário para provar tokens originais e limitar o body HTTP; construir records diretamente não substitui o reader.

Fingerprint de intenção **não é** assinatura, autenticação, idempotência durável, admissão de confirmação ou prova de efeitos. O store/orquestrador futuros deverão vincular proposalId, manifest fechado, fatos/políticas avaliados e seus digests à reserva e revalidar acesso/contexto. Não reconstruir esse fingerprint usando política/estado atuais ao servir replay histórico. A identidade/retenção da execução pertence a seu próprio ciclo, não à expiração da proposta.

## Projeções públicas

`BulkProposal` descreve a avaliação: identidade/operação/modalidade, modo de execução, atomicidade, READY/BLOCKED, datas, totais, intenção redigida, diagnósticos e referências tipadas de política/fato. A projeção recebe `redactedIntent` explicitamente; o construtor copia JSON, mas **não sabe redigir**. O provedor deve selecionar somente dados que o ator atual pode ver, inclusive em consultas posteriores. Não inserir automaticamente o snapshot protegido, tokens, IDs privados de política ou valores anteriores em uma projeção.

READY exige população positiva integralmente avaliada/executável, sem bloqueios. BLOCKED exige diagnóstico. Datas e totais coerentes são invariantes de valor; não provam captura consistente ou persistência. Uma instância Java READY construída manualmente não pode habilitar confirmação.

`BulkExecution` descreve progresso e resultado agregado. Totais discriminam `pending`, `confirmed`, `unchanged`, `denied`, `invalid`, `conflict`, `notProcessed` e `unknown`; sua soma corresponde a targetCount. Pending é ausência de resultado final; NOT_PROCESSED é conclusão explícita sem processamento. Esses valores deverão ser derivados de manifest/receipts duráveis, jamais usados como contador em memória fonte de verdade.

Estados: QUEUED, RUNNING, CANCEL_REQUESTED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, STOPPED e RECONCILIATION_REQUIRED. Somente os quatro estados COMPLETED/COMPLETED_WITH_ERRORS/CANCELLED/STOPPED são terminais, com terminalAt e sem pending/unknown. RECONCILIATION_REQUIRED não autoriza retry novo nem expurgo. STOPPED exige motivo seguro nos diagnósticos. CANCELLED e STOPPED exigem ao menos um item NOT_PROCESSED; se todos já terminaram, usar COMPLETED ou COMPLETED_WITH_ERRORS. ATOMIC não permite confirmed positivo misturado com pendência/resultado impeditivo; isso evita uma representação de confirmação parcial, mas não prova rollback de banco/outbox.

`BulkItemResult<WI>` usa a identidade wire (Integer/String), o estado CONFIRMED/UNCHANGED/DENIED/INVALID/CONFLICT/NOT_PROCESSED/UNKNOWN e diagnósticos. É resultado de execução, não avaliação: não emitir CONFIRMED em uma prévia. O endpoint futuro de resultados de avaliação deverá materializar elegibilidade e candidato sem fingir receipt. As páginas futuras usam o contrato canônico de paginação e o teto 200; este DTO não embute toda a população na execução.

Diagnósticos reutilizam `ResourceCommandMessage`, com metadata vazio obrigatório para impedir objetos arbitrários mutáveis. Código, categoria, mensagem e target continuam submetidos à política de redaction do provedor; o construtor não certifica segurança de texto livre. Listas são copiadas e valores de JSON têm cópia defensiva. `toString()` das projeções não imprime payload.

Links pertencem ao envelope `RestApiResponse._links`, sem `links` paralelo dentro das projeções. O binding HTTP futuro resolve links e schemas pela operação real e aplica autenticação/autorização, paginação, códigos HTTP e redaction.

Ao desserializar essas projeções em Java com JSON arbitrário, usar mapper isolado com suporte a Instant (`JavaTimeModule`, datas ISO), `USE_BIG_DECIMAL_FOR_FLOATS=true` e `JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES=false`, para manter números exatos no preview. Não mudar Jackson global do host. O binding de execução e a redaction continuam obrigatórios; esses DTOs não são endpoints de entrada do operador.

## Schemas e provas

[bulk-protocol.schema.json](bulk-protocol.schema.json) documenta a estrutura disponível no SDK. Perfis de identidade distintos evitam aceitar número Long pelo ramo Integer. Objetos de domínio (parameters/filter/preview) precisam da especialização do schema do DTO canônico ao registrar a operação: este documento não é fonte paralela da regra de negócio.

JSON Schema prova estrutura, campos permitidos, tipos, operadores e limites expressáveis. Totais aritméticos, ordenação de instantes, autorização, faixa int64 da string e consistência com receipts também exigem validação Java/domínio. A projeção deste contrato no OpenAPI e `/schemas/filtered` só será provada com bindings reais.

```text
mvn -DfailIfNoTests=true -Dtest=BulkProtocolContractTest,BulkIdentityCodecTest,BulkFieldChangeValidationTest,BulkIntentFingerprintTest,BulkResponseContractTest,BulkProtocolSchemaTest test
```

A bateria cobre partes de T02/T09: invariantes, tipos, round-trip, imutabilidade e digest. Banco/concorrência/recuperação/idempotência, HTTP bulk, binding de operação e UI exigem suas fases de implementação e testes próprios.
