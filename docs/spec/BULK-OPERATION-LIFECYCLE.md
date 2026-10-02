# Lifecycle governado de operações em lote

## Estado e fronteira

Este documento descreve o candidato R2/V15 desta árvore. O artefato publicado de referência permanece rc.146; código e provas locais não certificam adoção no host, conclusão de R2/R3 ou liberação do Angular. O planejamento e as evidências versionadas ficam em `internal-planning/bulk-operations` no host de referência.

O Metadata é dono da semântica. Providers do host vinculam identidade, revisão, codec, limites e infraestrutura a operações reais. Metadata, capabilities e ações consomem a mesma composição; não definem uma segunda regra de negócio. Readiness não substitui autorização, tenant, validação do domínio ou o fence transacional de cada execução.

## Fotografia OpenAPI e produtor

A preparação captura raiz, todos os grupos publicados/necessários e `swagger-config`, preservando bytes imutáveis e árvores isoladas. O digest cobre os paths e os bytes exatos em ordem canônica. A interpretação exige JSON estrito sem duplicatas/trailing tokens, UTF-8 estrito, OpenAPI 3.0/3.1 e URLs de grupos correspondentes às rotas locais. UTF-16/32, redirects, status diferente de 200, Content-Type diferente de application/json ou charset diferente de UTF-8 são rejeitados.

O cliente oficial é owned, sem interceptors/initializers de produtor, com factory própria e sem redirects. A captura usa contexto, interface e porta da request local; não aceita `app.openapi.internal-base-url`. Requer `springdoc.cache.disabled=true`. Mudanças de factory/interceptors/initializers, inclusive ABA, invalidam a revisão de transporte. Não há promessa de detectar mutações arbitrárias dentro de customizers/helpers/converters: esses inputs precisam de invalidação e republicação governadas.

Cada rota recebe uma permissão aleatória de 256 bits, single-use, limitada por contexto/path/GET REQUEST e prazo monotônico. O filtro deve consumir a permissão exata e o transporte exige o atestado enquanto a lease está aberta e no prazo. HTTP 200 válido sem consumo, erro após consumo, query, redispatch, replay e close/expiry não produzem candidato. O header interno não é credencial IAM e não deve contornar a cadeia de segurança.

O registro oficial cobre `/*`, todos os dispatcher types e suporta async. Quando a cadeia Spring Security existe, exige o registro Boot canônico `securityFilterChainRegistration`, habilitado e com cobertura REQUEST completa, e usa sua ordem efetiva + 1. Assim uma ordem efetiva diferente de `SecurityProperties.filter.order` não coloca o produtor antes da segurança. Topologia incompleta, cobertura parcial ou overflow nega startup. Sem cadeia/registro, a ordem derivada da propriedade não atesta IAM. Targets arbitrários de proxy ou filtros customizados exigem prova própria do host; não há introspecção de campos protegidos. A inicialização real atesta o contexto; construir o filtro não o instala. Testes Boot com Spring Security real e HttpBasic de fixture não provam a política JWT/tenant/domínio do host, que continua gate obrigatório.

## Publicação, locks e commit

`publish(identity, expectedGeneration)` aceita somente UNCOMPOSED/SUSPENDED na geração esperada. Não recebe fingerprint/digest do operador. A primeira publicação suspende duravelmente o ledger global antes da captura; compõe os documentos fora de transações JDBC e recompõe o descritor antes da admissão. O fence local impede invalidação durante a admissão.

A transação de control plane aplica CAS global PUBLISHED e CAS operacional READY com a geração/digest da mesma fotografia. Falha do CAS operacional aborta também a publicação global. Namespace → global → operação é a ordem durável; um SHARE de consulta termina antes de um UPDATE de controle. Não converter SHARE em UPDATE no mesmo commit. V15 rejeita tuple NULL, geração divergente ou digest divergente. Consulte [Controle de operações em lote](BULK-OPERATION-CONTROL.md).

O descritor recomposto é comparado pela identidade, revisão estrutural canônica, fingerprint operacional e infraestrutura vinculada; instâncias diferentes do objeto estrutural não representam, por si só, drift. Uma segunda operação reutiliza a fotografia publicada e aplica somente seu CAS operacional, preservando as operações READY anteriores.

Commit incerto admite apenas reconciliação por leitura independente do tuple global e da operação exatos. Não repete CAS nem mutação de domínio. A instalação local ocorre depois do commit confirmado/reconciliado, verificando owner/época/transporte/contexto e o guard JDBC de geração/digest. Uma falha de instalação pode deixar um commit durável válido e o nó local fechado; não declarar rollback apenas pela exceção de instalação.

## Leituras, suspensão e recuperação

`requireReady`, projeções, documentos e hashes usam a fotografia instalada, com guard durável antes/depois. Leituras não regeneram SpringDoc nem fazem fallback para a origem dinâmica quando o lifecycle governado está instalado. Nó frio ou fotografia divergente falha fechado. A indisponibilidade reconhecida no guard de leitura publicada é materializada pelo handler HTTP canônico como `503`, envelope `failure`, categoria `SYSTEM` e código `GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE`, sem expor a causa privada. Isso não autoriza recaptura automática nem retry de mutação; publicação ou reconciliação continuam explícitas. Erros inesperados fora dessa fronteira preservam sua classificação.

Dentro de uma unidade operacional writable, a leitura estrutural captura a referência local publicada sem lock de cache e valida sua tupla pela mesma conexão atestada da unidade, antes e depois da composição. O SHARE global permanece até commit/rollback; não abrir REQUIRES_NEW nem adquirir CACHE READ nesse caminho. Transações read-only, rollback-only ou vinculadas a outro datasource são negadas antes de construir payload. A consulta ao receipt continua anterior aos gates exclusivos de nova mutação.

Cada getter copia defensivamente apenas o grupo solicitado. Isso não promete resolução de custo constante: a verificação canônica de grupos/colisões pode percorrer o conjunto publicado e deve respeitar o orçamento existente. `requireReady`, captura/instalação/reconciliação e frames de resposta começam fora da transação operacional; a leitura estrutural vinculada não autoriza esses fluxos nem substitui autorização, tenant ou gates de domínio.

A resposta de capabilities/actions usa um frame síncrono isolado. O builder do host roda fora dos locks de cache/preparação e de transações; o gate final revalida fotografia, providers e gerações, inclusive quando o host capturou uma negação intermediária. Falha de preparação pode gerar projeção bulk vazia uma vez; após começar o builder, erro propaga sem retry ou resposta duplicada. O resultado não promete estabilidade após o retorno HTTP.

`suspend(identity, expectedGeneration)` suspende a operação e depois o ledger global antes de limpar os caches. O ledger suspende operações READY de todas as namespaces do deployment. `clearCaches`/refresh estrito explícito usam esse guard antes de mutar caches; não são ferramentas de recuperação local de uma réplica. Falha do guard conserva dados locais, mas a época invalida a autoridade anterior. Nova publicação exige a geração resultante.

`reconcilePublished(identity, readyGeneration)` recaptura a fotografia explicitamente fora do caminho de request, mesmo quando o nó mantém uma fotografia antiga. Exige digest igual ao ledger atual, descritor igual à operação READY e nova verificação durável antes de instalar. Não escreve controle, não suspende a publicação de outra réplica nem executa domínio. Fonte divergente é negada. `publish` em nó warm divergente também nega: reconciliar explicitamente antes de publicar outra operação; não usar `clearCaches` para isso.

## Orçamentos e aceite

Preparação e composição têm budgets de admissão separados; não são prazo acumulado ponta a ponta. Locks/HTTP respeitam o prazo, mas callbacks arbitrários não são interrompidos. Um commit admitido não vira rollback declarado porque o prazo de nova admissão expirou. A instalação mantém os fences locais/duráveis próprios.

Provas candidatas incluem Servlet/HTTP de captura, Boot/Tomcat de registro e PostgreSQL real. Evidências precisam identificar a árvore e as fontes testadas. Recovery local simulada não equivale a duas instâncias; IAM de fixture não equivale à segurança do host. Permanecem gates de fechamento: revisão independente do corte integrado, cadeia real de segurança, HTTP + PostgreSQL no host com budget de 8s nos dois modos, documentação/skill canônica, integração e adoção conforme autorização. As provas antigas do protocolo fresh V6/V7 não certificam V15. Nenhuma publicação/deploy ou Angular é autorizada pela aprovação de um teste isolado.
