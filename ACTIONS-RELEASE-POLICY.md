# Actions no fechamento de versões


O padrão durante desenvolvimento é zero execuções remotas. Valide localmente o escopo alterado; commits, PRs, documentação interna e conclusão de tarefas não são motivos para iniciar Actions. Use os workflows manuais somente no fechamento autorizado de uma versão/publicação ou na prova necessária do host já implantado. Não use `[skip ci]` como mecanismo principal nem desabilite checks/proteções para economizar.

Antes de push, tag ou dispatch, confira os gatilhos reais de `.github/workflows/`. Tags de release publicam artefatos: não criá-las para testar a automação. Diagnostique localmente antes de repetir um job; conserve a evidência da revisão e dos artefatos usados. Monitores operacionais explicitamente mantidos são independentes do CI de commits. Consulte [ACTIONS-RELEASE-POLICY.md](ACTIONS-RELEASE-POLICY.md) para os pontos de entrada e recuperação.

## Fluxo deste repositório

`release.yml` é iniciado por dispatch em main com `create_tag=true` e RELEASE_PAT configurado. A preparação agora persiste a versão no POM e envia commit/tag atomicamente. A publicação exige que a tag pertença a main e que POM e tag já coincidam; não corrige a versão somente no checkout temporário.

O job de tag executa quatro fases de teste do wrapper: `clean test` da seleção completa sem DB inventariada; `test` da classe Neon managed-owner B/C/H; e `verify` da classe Neon optional-policies P4 na mesma child atribuída, com assinatura; e `test` de `resource-contracts`, com cinco métodos HTTP de custom fields e o método integral de composite IDs sobre PostgreSQL remoto. A assinatura não dispensa essa última fase antes do recibo/publicação. B/C/H vem primeiro porque exige ausência inicial de roles bulk; falha ou cleanup incompleto impede P4. O perfil `release-neon-gate` separa diretórios Surefire; logs brutos, skips/falhas, casos parametrizados, snapshots das classes de teste e identidade byte a byte das classes/recursos de produção com o JAR staged são conferidos antes do ZIP. O assignment da child é preparado e revisado antes do dispatch, vinculado a base SHA/tag/versão e ao recibo do control plane, e revalidado antes do Neon; o artefato inclui projeção pública sem senha, run/tentativa, Java/Maven e dependências efetivas. A role owner da child precisa ser diretamente atribuída e sem arestas recursivas na direção roleid=owner que permitam a outro login assumir esse owner; o membership administrativo do próprio owner gerenciado em neon_superuser tem direção oposta. Uma role SQL com aresta ADMIN automática concedida ao criador não satisfaz o guard. O scanner cobre URI owner, as duas senhas P4 e as duas novas senhas ordinárias de recursos, em arquivos privados separados, antes dos uploads e do recibo. Cada classe de recurso usa database/schema distintos da child, preflight PG17/TLS/role ordinária antes do DDL e cleanup por OID após Spring fechar o contexto. Sem secrets, matriz B–H fechada ou os seis casos e marcadores de recursos o job falha antes de usar banco ou publicar. P4 também exige a leitura pública COMPLETE pelo novo batch autorizado e uma negação de escopo sem projeção. Este é gate por impacto, não execução integral das fixtures H2/Embedded históricas.

Depois, o mesmo job constrói e valida um ZIP explícito, preserva seus bytes e realiza um upload único pela API oficial, seguido da comparação pública de POM/JAR/SHA-512. Upload, status e disponibilidade compartilham o orçamento restante do job90min, com180s reservados para custódia final; sem janelas independentes ou reupload automático. A documentação é um workflow reutilizável dependente do sucesso da release. Commits de main/PR não iniciam build ou Javadoc. Não republique uma versão por demora de propagação: confirme primeiro a disponibilidade do artefato. Consulte RELEASING.md.

## Diagnóstico operacional necessário antes da release

`create_tag=false` seleciona a matriz B–H de fonte exata (managed owner antes
de optional policies), com assignment e secrets próprios, sem tag/GPG/publicação.
É uma execução focal para distinguir limitações
da topologia local de defeitos no ambiente remoto, não rerun exploratório nem
aprovação integral. O checkout deve ser exatamente o commit revisado, as bases
Neon são exclusivas e logs/credenciais seguem a custódia descrita em RELEASING.md.
O recibo dessa prova registra `releaseApproved=false`; não substitui o inventário
por impacto, o noDB completo nem a verificação do JAR staged da release.
O caminho `create_tag=true` continua limitado à main e à publicação autorizada.
