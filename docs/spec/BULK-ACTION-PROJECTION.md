# Projeção P1 de execução bulk nas actions

Classificação: contrato público, arquitetural e transversal. Fonte canônica: Metadata. O inventário encontrou `BulkOperationStructuralDescriptor`, perfil operacional, referências de action, snapshot OpenAPI e lifecycle existentes; a lacuna de materialização era `ActionExecutionContract.bulk`. A seleção de variante já existia em `/schemas/filtered`, mas o atalho do resolver não capturava os defaults derivados pelo endpoint. O corte reutiliza essas fontes, sem registry, endpoint ou autorização paralela.

## Contrato publicado

`ActionExecutionContract.bulk` é opcional e ausente do JSON de actions comuns ou indisponíveis. Neste corte ele descreve somente `DOMAIN_COMMAND`, seleção `EXPLICIT`, execução `SYNC` e atomicidade `PER_ITEM` do perfil operacional já aceito. Não publica `CapabilityOperation.bulk`; esse contrato pertence às operações concretas de bulk update em corte próprio.

`BulkExecutionContract` contém `mode`, `evaluationOperation`, `confirmationOperation`, `proposalOperation`, `proposalResultsOperation`, `executionOperation`, `resultsOperation`, `cancelOperation`, `selectionModes`, `executionModes`, `atomicity`, `limits` e, quando presente no schema resolvido da avaliação, `parametersPointer=/properties/parameters`. `editableFields` pertence aos futuros modos de update e não é publicado pelo P1.

Cada entrada de operação contém `operation: CanonicalOperationRef`, `responseSchema: CanonicalSchemaRef` e `requestSchema` apenas para avaliação e confirmação. A confirmação reutiliza exatamente as referências capturadas da action; os links `ActionCatalogItem.requestSchemaUrl/responseSchemaUrl` permanecem os da confirmação. Limites são os valores efetivos do provider: `maxTargets`, `maxRequestBytes`, `proposalLifetimeMillis` e `unitDeadlineMillis` (durações inteiras em milissegundos).

## Variante UI e transporte

`CanonicalSchemaRef` identifica a variante UI de `/schemas/filtered`, que pode desembrulhar `RestApiResponse<T>`, enriquecer `x-ui` e publicar o DTO de apresentação. Não promete igualdade com o schema HTTP bruto. O compiler continua verificando todos os 2xx explícitos, mídia JSON e igualdade canônica dos schemas brutos na mesma cópia imutável do grupo OpenAPI.

`FilteredSchemaProjection` concentra a regra existente do endpoint: seleção de componente/inline request, extração de wrapper/filtro, base de recurso, `idField` e default de `readOnly`. Endpoint e composição consomem essa implementação. Cada uma das seis roles além da confirmação resolve suas próprias dimensões; não recebe os overrides de CRUD da confirmação. O resolver canônico recebe as dimensões explicitamente, mantendo URL e `schemaId` coerentes. Nenhum default global de `SchemaReferenceResolver` ou do endpoint foi alterado.

Bulk exige `x-ui.responseSchema` ou a seleção declarada suportada de 200/201; a inferência documental por nome de path que o endpoint já permite não habilita uma referência bulk. Componente ausente, seleção indisponível ou referência local ausente/cíclica/incompatível torna a role não projetável. O reader estrutural existente resolve separadamente as dependências do componente UI para que uma alteração nelas não escape do fingerprint. A evidência estrutural de transporte continua separada; sem projeção UI completa, a composição operacional falha antes de `publish`/`requireReady`. Portanto uma linha READY antiga não habilita uma action direta quando o contrato de avaliação ficou indisponível.

O digest estrutural passa a `praxis.bulk.structure/3`, incluindo a referência UI, seleção, componente resolvido, metadados de operação e capabilities que determinam a variante, separadamente dos schemas HTTP brutos. A migração é beta limpa: um controle publicado com a revisão anterior não passa no matching; o host deve suspender, recompor e publicar via CAS do lifecycle. Não se mantém uma lane de compatibilidade ou READY sintético.

## Fence e limites

`ActionCatalogService` consulta o lifecycle uma vez por resposta. O lifecycle recompõe sobre uma única fotografia fresca sob o lock de composição/invalidação, compara as mesmas linhas duráveis antes/depois (estado, geração, fingerprint e revisão) e entrega o contrato derivado dessa composição. Action diferente, provider ausente, composição parcial, seleção UI indisponível, `UNCOMPOSED`, `SUSPENDED`, revisão/fingerprint stale, geração alterada durante a composição ou falha de leitura omitem `bulk`.

Essa metadata é descritiva. Availability contextual permanece independente, e avaliação/confirmação precisam revalidar o gate e a autorização dentro da transação. A projeção não certifica mutação de domínio, receipt, autorização empresarial, deploy, release, B7 ou backend completo. Angular, Config e bindings/handlers do host não mudam neste corte.

## Coerência dos documentos servidos e hashes

Antes de `publish`, `requireReady` ou da projeção de actions, a fotografia fresca dos grupos OpenAPI deve corresponder exatamente aos documentos públicos que `/schemas/filtered` serve naquela instância. A comparação ocorre antes do callback da composição e antes de instalar a fotografia no contexto da requisição. Se houver divergência, o guard avança primeiro o fence durável para `SUSPENDED`; só depois limpa os caches locais de documento e `schemaHash`. A operação falha fechada: não devolve `READY`, não executa o callback de publicação/projeção e não tenta republicar automaticamente. Depois de corrigir a fonte, uma nova publicação explícita precisa vencer a geração atual.

Isso também cobre outra réplica com cache antigo: ao observar que seu documento público não corresponde à fonte fresca, ela suspende a geração compartilhada e limpa os próprios caches; as outras réplicas passam a falhar no fence até uma republicação explícita. O caminho HTTP de `/schemas/filtered` mantém o lock de leitura desde a resolução/materialização do schema até a escrita do hash, `ETag` e resposta `304`; refresh estrito substitui o documento e invalida o hash sob lock exclusivo. Assim, uma resposta iniciada antes da invalidação não pode repovoar um hash antigo depois dela.

Implementações customizadas de `OpenApiDocumentService` só podem habilitar a composição declarando tanto `supportsFreshBulkLifecycleComposition()` quanto `supportsFreshBulkLifecyclePublicCacheCoherence()`. A segunda garantia significa comparar a fonte recém-gerada com o documento que o endpoint público efetivamente serve, e proteger a operação HTTP completa de schema/hash com o mesmo protocolo de lock da invalidação. Os valores default não constituem prova dessas capacidades. Na query de `/schemas/filtered`, valores literais de `idField` com `+` são codificados como `%2B` (e espaços como `%20`) para que URL, `schemaId`, corpo, hash e `ETag` preservem a mesma identidade após a decodificação HTTP.

## Validação e artefatos derivados

Os testes adicionados cobrem seleção/variantes, overrides e falha de seleção; composição única, estados e geração concorrente; ausência de `bulk` no JSON comum; e HTTP real de `/schemas/filtered` para as sete operações (nove referências contando os dois requests), com identidade derivada do corpo, URL, `ETag`, `X-Schema-Hash` e 304. A fixture HTTP compara o corpo default com a variante explícita nas seis roles, além de um recurso comum com id não default e overrides explícitos. As provas de coerência de cache cobrem igualdade, divergência que suspende antes do callback, duas instâncias com cache divergente, lock de leitura contra invalidação e invalidação de hash após refresh; a regressão de `idField` com `+` confere corpo, hash e 304. A fixture HTTP valida apenas schema/discovery: não executa os handlers de domínio nem prova provider/autorização reais. Os testes isolados de lifecycle podem construir controles `READY` sintéticos para validar sua semântica; isso não publica nem comprova um provider operacional.

Comando focal sugerido (execução e evidência ficam a cargo do coordenador):

```sh
mvn '-Dtest=FilteredSchemaProjectionTest,BulkOperationStructuralCompilerTest,BulkActionSchemaProjectionHttpTest,ActionExecutionContractTest,ActionCatalogServiceTest,CachedOpenApiDocumentServiceRefreshTest,ApiDocsControllerTest,ApiDocsControllerReadOnlyMetaTest,ApiDocsControllerPathResolutionTest,ApiDocsControllerSchemaHashTest,ApiDocsControllerAllOfTest,DomainCatalogControllerTest,FilteredSchemaReferenceResolverTest' test
```

README, índice, changelog e especificação do lifecycle foram sincronizados. Não mudou o vocabulário `x-ui` nem os defaults da referência; os schemas `docs/spec/*.schema.json`, exemplos de componentes e guia de consumo Angular não exigem atualização neste corte. A regressão do Quickstart e do consumidor de artefato permanece gate separado do coordenador. Landing e corpus HTTP não publicam este campo neste recorte.

Impacto na skill `praxis-metadata-schema-contracts`: `atualizar-existente`. O guidance deve ensinar a derivação compartilhada por role, distinguir variante UI de transporte bruto, exigir dimensões explícitas em referências compostas e explicar digest `/3`, seleção materializável e composição/fence em lote. O guidance foi integrado pelo PR canônico praxis-codex-skills #628 (merge `d1ac8d25fedc24896ed263c29e432493b211e7a4`), validado com hashes de arquivo/árvore no manifesto e sincronizado seletivamente para a skill instalada. Dois drifts locais alheios foram preservados.
