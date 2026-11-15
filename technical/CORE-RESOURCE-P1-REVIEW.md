# Revisão ampliada da P1 — 2026-11-01

Escopo exclusivo: `praxis-metadata-starter`, sobre o trabalho local da P1 e HEAD
`53ab770a46` (`8.0.0-rc.188`). Não houve edição de hosts, Angular ou showcase.
As fontes de intenção são a Issue P1 de `plano-issues-evolucao-plataforma-3-ondas.md` e
`auditoria-evolucao-plataforma-ergon-showcase.md`, no diretório de planejamento
`585430d4-d36a-4c55-9c53-4a0359df92c9` informado na solicitação.

## Achados corrigidos

| Prioridade | Inconsistência anterior e efeito corporativo | Correção e prova |
| --- | --- | --- |
| P1 | Exportar `List<DTO>`/`Map<String,DTO>` permitia serializar filhos marcados como não exportáveis. Uma tipagem base também podia esconder subtipo restrito. | Percurso de generics, arrays, opcionais, getters/interfaces e valores concretos; JSON opaco/dinâmico exige projeção tipada. `ResourceCoreIntrospectionTest`. |
| P1 | Um recibo podia usar outro DataSource que o transaction manager. O isolamento efetivo da conexão podia ser `REPEATABLE READ` mesmo com default Spring indefinido. Atomicidade e visibilidade de retries não estavam garantidas. | Identidade do DataSource e vínculo/auto-commit/read-only/isolation da conexão verificados. Rollback conjunto JDBC/JPA, concorrência e rejeições em `JdbcResourceCommandReceiptStoreTest`. |
| P1 | A autorização do item para replay acontecia fora da transação do recibo. Uma consulta de autorização JPA também poderia fazer autoflush de alterações prévias. | Callback `authorize` dentro da transação, antes de ler o recibo, em execução nova e replay. Contexto JPA sujo é rejeitado antes da autorização. Testes de revogação e rollback. |
| P1 | Uma precondição construída para o item A podia ser passada à escrita do item B com a mesma revisão. | A base compara o ID alvo com o ID vinculado antes do mutator/mapper. Regressão real JPA em `CoreResourceEvolutionIntegrationTest`. |
| P2 | Inclusão de chaves compostas usava apenas o primeiro atributo; sort `id` sem paginação não era expandido; inclusão sem paginação acessava número de página inexistente. | Predicado completo de identidade, expansão em `Pageable.unpaged`, tratamento da página efetiva. Provas de `@IdClass` com escopo e `includeIds`. |
| P2 | Identidade embutida com um só componente comparava o valor escalar ao objeto inteiro. Leitura de campos por reflexão podia ler atributos vazios em proxies JPA. | Comparação pelo componente e resolução de proxies ao percorrer caminhos. Provas de seleção, cursor/locate e proxy existente no contexto. |
| P2 | A variante previous/current compartilhava elementos mutáveis em estruturas aninhadas, podendo alterar o estado anterior usado por regras. | Cópia independente de containers, arrays, datas, JSON e embeddables, preservando ciclos. Objetos específicos/lazy exigem override explícito antes de mutar. `ResourceStateCopySupportTest`. |
| P2 | Sort em coleção podia duplicar recursos; `ignoreCase` numérico falhava apenas em páginas posteriores. | Validação escalar da ordenação na primeira janela; projeção agregada exigida para `1:N`. Provas JPA no core. |
| P2 | O helper de filhos publicava a versão do dataset do pai. Isso prejudica invalidação de caches de master-detail. | `X-Data-Version` obtido do serviço filho. `VersionedItemCommandHelpersTest`. |
| P2 | O builder aceitava descrições vazias/duplicadas na lista intermediária; display, search e contrato final podiam apontar para caminhos diferentes. | Normalização única antes de compor subcontratos, com prova em `PlatformAuthoringBuildersTest`. |
| P2 | `CURRENCY_INPUT` fixava BRL em uma biblioteca universal, inclusive em outro locale. | Inferir semântica monetária sem escolher moeda de negócio; host cura moeda explicitamente em `CollectionExportFieldPresentation`. Teste de apresentação padrão. |
| P2 | Leituras by-ids omitiam silenciosamente alvos inexistentes, divergindo da distinção 404/403 pedida na P1. | Lookup estrito para os IDs requisitados; `includeIds` do filtro mantém a inclusão somente dentro do escopo. Testes de ausência e negação. |
| P3 | Exportação com limite podia ser apresentada como download completo sem aviso textual; locks de delete em ordens opostas elevavam o risco de deadlock entre lotes. | Aviso de corte usando os headers/envelope existentes e aquisição estável de locks, preservando ordem dos hooks. |
| P2 | A documentação afirmava que o POM fixava PG17 para todas as plataformas; essa configuração não está na árvore atual. | Registro histórico corrigido em `CORE-RESOURCE-P1.md`, sem alterar o POM ou mascarar o requisito do Bulk. |

Cinco defeitos iniciais foram reproduzidos antes das correções em
`target/p1-review-reproduction.log`: 32 testes, 4 falhas e 1 erro (governança em containers,
DataSource diferente, isolamento efetivo, precondição de outro item e alias `id` unpaged).
Os demais casos receberam testes adicionais ou revisão direta do fluxo, conforme a tabela.

## Planejado e executado

| Bloco P1 | Situação da implementação |
| --- | --- |
| Keyset simples/composto, sort público `id`, navegação anterior e locate SQL | Na raiz comum, incluindo read-only; COUNT não materializa a coleção. Ordenação total não equivale a snapshot entre requests. |
| Escopo de coleções e autorização de item | Hooks distintos; filtro do cliente não decide tenant. 404 para ausência, 403 quando o hook rejeita. Hosts precisam implementar ambos conforme a política real. |
| Enriquecimento | Um lote por resultado materializado nas superfícies previstas; não pode alterar revisão nem substituir autorização. |
| Exportação cinco escopos, campos de grade e governança | Implementação compartilhada e limite no SQL. `ALL` remove filtro funcional e preserva o escopo obrigatório. `SELECTED` explícito preserva os IDs selecionados dentro do escopo. |
| Update/commands versionados | Lock, acesso, identidade da precondição, revisão, lifecycle, flush/refresh e corpo/revisão capturados. Adaptações sem EntityManager devem fornecer garantias equivalentes. |
| Idempotência | Store PostgreSQL opt-in; replay histórico e conflito 409, sem reinstanciar uma nova transição. Não há migração automática nem garantia sobre efeitos externos. |
| Stats/snapshots/builders | AUTO depende do registry; provider delega ao serviço; builders não inventam regras ou surfaces de domínio. |
| Ajuste deliberado da auditoria | A sugestão `CURRENCY_INPUT -> BRL` foi refinada para não codificar moeda de um host na biblioteca universal. Recursos brasileiros podem curar BRL explicitamente. |
| Adoção e remoção de duplicação no showcase | Permanecem na Onda 4; não foram executadas nem certificadas por esta revisão. |

## Cenários corporativos e UX

| Cenário | Garantia ou requisito de adoção |
| --- | --- |
| Dois operadores atualizam a mesma revisão | No lock pessimista padrão testado, apenas um writer vence; o outro recebe 412. A UI deve recarregar e permitir revisar alterações antes de reenviar. |
| Retry após timeout de resposta | Mesma chave e intenção retornam corpo/ETag históricos sem novo efeito. Não gerar nova chave automaticamente para uma tentativa ainda indeterminada. |
| Acesso revogado entre execução e retry | A autorização atual impede revelar o recibo. O host deve usar principal e escopo confiáveis, não valores livres do payload. |
| Exportação de dados pessoais aninhados | Campo pai com descendente restrito é bloqueado; exponha caminhos seguros ou uma projeção curada. Serializadores customizados devem preservar governança. |
| Download limitado | Mostrar `X-Export-Truncated`/`X-Export-Warnings`, limite e orientação para refinar a seleção. Configuração CORS do host precisa expor os headers. |
| Empresa com moedas/fusos diferentes | Curar apresentação por recurso; locale sozinho não determina a moeda de um contrato financeiro. |
| Paginação com mudanças concorrentes | Reiniciar cursor após mudar filtro/sort; não tratar páginas como fotografia contábil imutável. |
| Lookup de entidade inativa | Builder não conhece estados válidos. Host fornece política, mensagens, cascatas e surfaces reais; o default `detail` precisa existir. |
| Regra compara estado anterior e atual | Snapshot específico de associações deve ser independente e limitado. Falha explícita precede mutação para valores não suportados. |
| Retenção e efeitos externos | Host define janela de retries, limpeza, criptografia/acesso aos corpos e outbox/coordenação para efeitos fora do banco. |

Nenhuma UX visual foi alterada ou testada em browser. Melhorias deste corte são no contrato HTTP,
previsibilidade de erros/headers e orientação de adoção. Não se aplica o scorecard de colunas de
recursos de domínio: este corte altera infraestrutura, com entidades de teste locais.

## Validação e limites

JDK: `C:\Users\rodrigo.moreira\.jdks\openjdk-21.0.2`. A compilação de fechamento refez todos
os arquivos Java alterados a partir de seus bytes finais. A opção do compiler abaixo controla
recompilação, sem desativar testes ou guardas.

| Execução | Resultado | Evidência local em `target/` |
| --- | --- | --- |
| Reprodução inicial de cinco defeitos | 32 testes, 4 falhas e 1 erro | `p1-review-reproduction.log` |
| Bateria ampliada intermediária | 198 testes, 2 falhas; interfaces de governança e classe intermediária de proxy | `p1-review-affected.log` |
| Bateria final, 23 classes | **198 testes, 0 falhas, 0 erros, 0 skips — BUILD SUCCESS** | `p1-review-final.log` |
| `mvn install -DskipTests` com a mesma opção de compilação | **BUILD SUCCESS**, instalação local de `8.0.0-rc.188` | `p1-review-install.log` |

`git diff --check` passou. As fontes/documentos alterados foram conferidos como UTF-8.

Os recibos foram provados em PostgreSQL **14.22** real conforme o POM atual, e as consultas do
core em H2/JPA. Isso prova o store de recibos neste baseline; não satisfaz o requisito PG17 do
kernel Bulk. O teste de introspecção também cobre recursão de tipos genéricos sem stack overflow.

Reprodução (PowerShell, com o JDK acima em `JAVA_HOME`/`PATH`):

```powershell
$tests = @(
  'CoreResourceEvolutionIntegrationTest', 'ResourceCoreIntrospectionTest', 'ResourceStateCopySupportTest',
  'ResourceServiceStateSnapshotProviderTest', 'AbstractBaseResourceServiceTest', 'VersionedBaseResourceServiceTest',
  'VersionedItemCommandHelpersTest', 'PlatformAuthoringBuildersTest', 'JdbcResourceCommandReceiptStoreTest',
  'CustomOpenApiResolverTest', 'SemanticDomainCatalogServiceTest', 'BaseResourceQueryServiceOptionMapperTest',
  'AbstractResourceControllerMappedCrudTest', 'AbstractResourceControllerJpaWriteIntegrationTest',
  'AbstractReadOnlyResourceControllerLinksTest', 'AbstractResourceControllerLinksTest',
  'AbstractResourceQueryControllerHateoasTest', 'AbstractResourceQueryControllerBasePathDetectionTest',
  'VersionedCreateUpdateResourceControllerTest', 'OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest',
  'OpenApiUiSchemaAutoConfigurationActionAvailabilityTest', 'DefaultCollectionExportExecutorTest',
  'CustomFieldCollectionExportTest'
)
mvn '-Dmaven.compiler.useIncrementalCompilation=false' "-Dtest=$($tests -join ',')" test
mvn '-Dmaven.compiler.useIncrementalCompilation=false' install -DskipTests
```

O `mvn test` integral anterior não foi aprovado: há requisito PG17 não atendido pelo baseline
embedded Windows, fixture POSIX incompatível com Windows e falhas isoladas do kernel Bulk
registradas em `CORE-RESOURCE-P1.md`. Esta revisão não modifica SQL, hashes, timeouts nem guardas
do kernel para obter aprovação. Não houve certificação downstream, integração no showcase,
execução de workflow, publicação ou release.

As provas concorrentes nativas cobrem o lock pessimista padrão. Opt-out para controle otimista,
managers transacionais customizados, adaptadores sem EntityManager, pools/proxies específicos de
hosts e efeitos distribuídos precisam de prova própria no ambiente de adoção.
