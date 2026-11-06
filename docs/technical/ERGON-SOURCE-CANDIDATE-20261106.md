# Candidato privado de fonte — 2026-11-06

Coordenada: `io.github.codexrodrigues:praxis-metadata-starter:8.0.0-rc.188-ergon.20261106.1`.
Base Git: `1b8e0a0006726124caf003fc313f1b993c7976f4`. As fontes pendentes foram copiadas de uma
árvore estável, verificada por nomes e SHA-256 antes/depois, em worktree própria.
A árvore original não recebeu edições, stage ou commit por esta tarefa.

O commit deste candidato é a nova origem dos builds; não representa identidade
com JARs históricos remontados e rotulados como releases do Maven Central.
As transformações após a captura são versão própria, alinhamento das dependências
Praxis privadas e timestamp de build fixo. O inventário registra os bytes capturados
antes dessas transformações. Git registra integralmente o resultado efetivo.

Linha: pré-lançamento na major existente, sem publicação Central. A tag de custódia
`ergon-source-20261106-r1` não é tag `v*` de release. A publicação pública exige
workflow oficial, gates locais/downstream e disponibilidade verificada dos bytes.
Não inferir adoção pública de build ou install em repositório de teste.

Impacto: Rules atual implementa contrato de engine 1.7, incluindo dependências de
controle nos slots; Contracts usa esse engine; Config/Metadata consomem o mesmo
conjunto. Hosts precisam compor a API canônica correspondente. Não adicionar aliases,
reativar fallbacks nem alterar versões de compatibilidade para contornar admissão.
O alvo direto desta reconstrução é o candidato isolado do Ergon Showcase; Quickstart
e gates oficiais de release continuam necessários antes de publicação pública.

Critério: JAR/POM/sources de build limpo, toolchain registrada, reconstrução com bytes
repetíveis, testes aplicáveis e consumidor empacotado contra o conjunto exato. Este
documento não afirma aceite dos gates de persistência ou publicação ainda pendentes.
