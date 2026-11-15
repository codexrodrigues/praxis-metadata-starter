# Composição Bulk: índice MVC por fotografia

`BulkOperationStructuralCompiler` compõe todas as declarações do host antes de
admitir uma operação. O custo dessa validação também pertence ao prazo absoluto
da confirmação QUERY. Reconstituir o índice completo de handlers para cada DTO
de avaliação, confirmação e atualização multiplica trabalho determinístico pelo
número de bindings, mesmo dentro da mesma fotografia OpenAPI.

`CanonicalOpenApiGroupSnapshot.CaptureScope` compartilha o índice MVC entre seus
grupos e resoluções. O índice contém os handlers, IDs efetivos globais e rotas
exatas. A identidade do resolver delimita seu uso: outra instância não herda a
interpretação de bindings da primeira. Uma nova composição reconstrói o índice.

Os DTOs de avaliação, confirmação e atualização de um binding são resolvidos
num único batch estrito. Fora da composição completa, o registro MVC é verificado antes e depois desse batch;
nenhum resultado parcial escapa se qualquer identidade/tipo ou a fotografia mudar.
A resolução individual usa o mesmo caminho com um elemento. Resolvers próprios
mantêm seu contrato estrito através da implementação default do batch.

A sobrecarga `CanonicalOperationResolver.requireResourceRequestBody` que recebe
`CanonicalOpenApiGroupSnapshot` permite resolver o tipo real do DTO nesse mesmo
escopo. Resolvers próprios que não oferecem indexação por escopo continuam usando
a sua implementação estrita de resolução de body; o default não utiliza discovery
permissivo. A implementação canônica mantém as verificações de recurso, identidade,
método, rota, body obrigatório e tipo genérico concreto.

Na compilação completa, `CaptureScope.compose` delimita a leitura estrutural: usa
os mapas MVC capturados e compara todos os registros vivos antes de devolver o
resultado. Escopos já utilizados também são verificados na entrada. Nenhuma
admissão intermediária é produzida. Fora dessa delimitação, a comparação ocorre
antes e depois de cada batch de DTOs (inclusive resoluções individuais) e de cada
batch de operações. A dispensa das verificações intermediárias termina inclusive
quando a composição lança exceção; composições aninhadas são rejeitadas.
Mudança de registro, inclusive a adição de um
handler não relacionado, invalida aquela composição. A checagem de colisão de rota
continua considerando mappings sem método HTTP explícito. Os documentos publicados
mantêm seus próprios fences de geração, unicidade global, callbacks e webhooks.

Esse índice não armazena READY, autorização, resultados de provider ou atestação
de banco. Os providers continuam sendo capturados a cada composição, e os controles
duráveis, identidade dos papéis e publicação viva continuam sendo verificados.
O prazo operacional permanece em cinco segundos, sem retries.

As leituras request/response/bodyless compartilham a interpretação de caminho da
fotografia. O callback de caminho recebe uma cópia isolada; os leitores estritos
continuam verificando a operação e sua unicidade. As capacidades **baseline do
OpenAPI** são capturadas uma vez por resolver/recurso/grupo na composição e copiadas
na entrada e na saída. Isso é distinto de availability contextual: autorização e
admissão não passam por esse armazenamento. Uma nova fotografia consulta novamente
os callbacks, inclusive após alteração de provider ou documento.

`FilteredSchemaProjection.resolveWithCapabilities` recebe esse resultado estrutural
já capturado e lê somente a árvore interna. Os callbacks de referências recebem
apenas argumentos escalares e os valores de seleção/evidência retornados continuam
defensivamente copiados. Assim, uma projeção não exige clonar todo o catálogo de
componentes novamente para cada leitura do mesmo recurso.

Contratos request/response/bodyless e projeções já validados são reutilizados somente
na mesma fotografia imutável. A chave da projeção inclui operação, tipo de schema,
`idField`, `readOnly` e identidade dos dois resolvers. As referências canônicas são
valores estruturais; a fotografia seguinte resolve suas dimensões novamente. Os
getters de schema e evidência continuam retornando cópias defensivas, e a reutilização
não permite que um consumidor altere outro contrato. O projetor e seu ObjectMapper
configurado uma única vez não guardam documento, recurso ou resultado global.

As regressões focais estão em `PublishedOpenApiOperationIndexTest`,
`OpenApiCanonicalOperationResolverTest`, `BulkCrudStructuralCompilerTest` e
`BulkOperationStructuralCompilerTest`. Elas cobrem reutilização limitada ao escopo,
reconstrução em nova composição, tipos reais de DTO, mudança de MVC, colisão de
endereço, troca/suspensão de publicação e colisões globais de OpenAPI. A validação
de latência depende também de prova HTTP no consumidor com o artefato empacotado.

Na equivalência de templates, o prefixo literal normalizado é comparado antes de
substituir os nomes de parâmetros. Uma substituição de `{nome}` jamais altera esse
prefixo: a eliminação de candidatos de outros recursos é exata e preserva tanto a
precedência de matches exatos como a rejeição de aliases estruturais ambíguos.
