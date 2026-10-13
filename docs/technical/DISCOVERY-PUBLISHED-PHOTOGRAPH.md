# Discovery sobre a fotografia OpenAPI publicada

`DefaultCapabilityService` e o caminho de related-resource de
`SurfaceCatalogService` separam a leitura estrutural da avaliação de
disponibilidade. No caminho governado, a resposta usa os documentos da publicação instalada; não
reconsulta o produtor nem transforma essa fotografia em autorização.

## Fronteiras da composição

1. A fase estrutural curta usa `withSchemaCacheReadLock`. Quando há publicação local governada, indicadores e operações
   do recurso são resolvidos a partir do mesmo documento. Nesse caminho, o suporte às operações
   de um filho é resolvido uma vez por `childResourcePath`, independentemente da
   quantidade de operações declaradas.
2. Os mapas privados são destacados dessa fase. Resolvers de contexto, snapshots
   de estado e regras de disponibilidade executam depois de liberar o lock.
3. Quando há uma fotografia publicada real, o fence existente é capturado dentro
   de seu escopo e verificado novamente após os callbacks. O fence do pai também
   rejeita mudanças de publicação entre a leitura do pai e a do filho. Todos os
   fences são fechados, inclusive quando a preparação, o callback ou a verificação
   final falham. A falha não provoca repetição do callback.

`hasLocalPublishedBulkOpenApiPublication()` indica presença, não autoridade.
Sua leitura dentro da fase estrutural escolhe o overload do resolver; a guarda
concreta e o fence final verificam a publicação. O hint externo serve apenas para
rejeitar antecipadamente uma transação ambiente incompatível. A condição é
reverificada dentro da fase para cobrir instalação concorrente.

Na fase governada, callbacks ficam fora dos locks e de transações operacionais.
Uma regra pode realizar sua própria consulta independente de READY. Suspensão,
republicação com os mesmos bytes mas outra geração, troca da fotografia,
invalidação ou mudança de transporte durante a composição impedem a resposta
bem-sucedida. Capturar a negativa dentro de uma regra não neutraliza o fence final.

A composição não mantém TTL, ACLs, memberships, grants, disponibilidade ou READY
entre respostas. Os frames e as verificações de provider do lifecycle bulk
continuam independentes e preservam suas obrigações.

## Wiring e implementações existentes

A auto-configuração injeta `OpenApiDocumentService` no construtor de cinco
argumentos de `SurfaceCatalogService`. Os construtores existentes continuam
servindo integrações que já os usam. Implementações genuinamente não governadas
preservam os getters de documento e os overloads de resolver existentes, sem
fabricar um fence de publicação nem impor a nova fronteira transacional governada.

Uma guarda instalada sem fotografia local falha na leitura concreta antes dos
callbacks do caminho doc-aware. Ausência de fotografia não autoriza servir uma
publicação substituta. Surfaces sem related-resource não recebem uma leitura
adicional de documento apenas por existirem.

Resolvers estruturais devem interpretar suporte declarado e os documentos
recebidos. Políticas, estado do registro e permissões atuais pertencem à fase de
disponibilidade, não aos mapas estruturais.

## Evidência e limites

`DiscoveryPublishedPhotographTest` usa captures e fotografia instalada concretos,
com seam de guarda durável. Cobre contagem constante de guards, igualdade dos
bytes, callbacks sem lock, republicação entre fases, ausência local, transação
ambiente governada, transações legadas, fechamento e próxima requisição limpa.
O caso suplementar em `BulkOperationStructuralCompilerTest` chama `requireReady`
real com doubles JDBC; não é prova de banco nem transforma a classe histórica
mista em uma seleção integral sem banco.

A seleção focal atual passou 47 casos sem banco. No cenário da prova com pai e
filho e seis operações relacionadas, há oito guards; isso mede chamadas desse
cenário, não latência ou throughput. Não certifica IAM, Neon, transporte HTTP,
Render, toda a suíte legada ou a jornada do Hero. A publicação do SDK exige as
seleções oficiais do mesmo commit; a adoção exige nova prova do consumidor e
validação após reinício real do Render.
