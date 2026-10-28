# Integração de host e Bulking — candidato da Issue 308

Este candidato usa a coordenada local distinta `8.0.0-ergon-bulk.308-SNAPSHOT`.
Não é release público e não substitui os bytes da rc.179 já instalada. O host
consumidor usa override explícito e repositório Maven isolado durante a prova.
Publicação e adoção de uma versão pública continuam sendo gates separados.
O POM versionado conserva a linha rc.179. Para reproduzir a coordenada de prova,
use um checkout isolado e `mvn versions:set -DnewVersion=8.0.0-ergon-bulk.308-SNAPSHOT
-DgenerateBackupPoms=false` antes do package; instale o resultado somente no
cache isolado do consumidor. Não instale esse build como rc.179.

## Intervalos canônicos em filtros QUERY

O leitor estrutural de requests recusava o `oneOf` produzido pelo próprio
`CustomOpenApiResolver` para filtros BETWEEN. Reduzir o FilterDTO do host
eliminaria capacidades reais de consulta.

`CanonicalRangeSchema` centraliza a emissão já existente de intervalos. O leitor
aceita a composição somente quando ela corresponde exatamente à forma emitida
por essa fábrica, em OpenAPI 3.0 ou 3.1: array ou objeto, com as mesmas restrições
de limites e de intervalo vazio. Uniões arbitrárias, branches extras, referências
estranhas e alterações nas guardas continuam recusadas. Os demais limites de
profundidade e tamanho permanecem vigentes.

## Transporte da atestação de funções

Cada chamada continua lendo os catálogos e verificando identidades, atributos,
privilégios, memberships, funções e triggers. Não há cache de autorização ou de
resultado de atestação e nenhum prazo foi ampliado.

A leitura LIVE calcula SHA-256 de cada `prosrc` no PostgreSQL. Corpos cujos bytes
são idênticos a SQLs empacotados podem ser omitidos do transporte; o leitor os
reconstrói dos mesmos bytes antes dos validadores existentes. Corpo desconhecido
é transferido integralmente, tem o digest conferido e passa pela comparação
normalizada anterior. Alterações de whitespace continuam tendo o mesmo critério
de aceitação. Digest ausente, malformado, desconhecido sem corpo ou incompatível
com o corpo transferido falha fechado. O dicionário contém somente recursos
imutáveis do artefato, nunca fatos consultados no banco.

Na cópia Neon de prova, os 58 corpos instalados, somando 80.142 bytes,
corresponderam exatamente ao dicionário. A prova opt-in
`BulkLiveRoleAttestationNeonTest` aquece a atestação, concede temporariamente
UPDATE adicional na tabela técnica de binding, exige recusa e remove o grant no
`finally`, exigindo nova aprovação depois da restauração. Essa prova deve ser
executada sozinha contra banco descartável, sem avaliações concorrentes.

## Recusas HTTP tipadas

O `GlobalExceptionHandler` conserva o envelope `RestApiResponse` e mensagens
fixas sem SQL, planos, parâmetros, identidades de alvo ou causas privadas.

| Recusa do kernel | HTTP |
|---|---:|
| NOT_FOUND | 404 |
| CONFLICT | 409 |
| CAPACITY | 429 |
| EXPIRED / RESULT_PURGED | 410 |
| NOT_EXECUTABLE / INVALID_SELECTION | 422 |
| FENCED / DEADLINE_EXCEEDED / RECONCILIATION_REQUIRED / CORRUPT / UNAVAILABLE | 503 |

Os códigos são `BULK_EXECUTION_<motivo>` ou `BULK_PROPOSAL_<motivo>`. Uma recusa
não autoriza retry automático nem repetição de mutação. A política de replay
permanece vinculada ao contrato de confirmação e à chave idempotente.

## Evidência e limites

A suíte focal composta executou 138 casos distintos sem falhas, erros ou skips:
leitores canônicos de request/response, emissão de intervalos, compilador de CRUD,
captura de ACL, dicionário de corpos e mapeamento de falhas HTTP. A prova opt-in
Neon acrescentou um caso de privilégio excessivo após aquecimento, também aprovado.
São provas do candidato; não certificam release, execução de todos os cenários do
host, disputa concorrente de 200 alvos ou consumo de pacote Angular publicado.

O host está exercitando as oito declarações operacionais e os três recursos
mutáveis. Registros de READY e descoberta são evidência distinta de efeitos de
domínio e recibos duráveis. O relatório do host deve registrar o resultado final
da campanha, sem promover essas verificações parciais a aceite integral.
