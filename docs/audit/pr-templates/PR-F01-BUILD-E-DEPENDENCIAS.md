# PR — Fase 1: Build e Dependências

Título sugerido: Auditoria Fase 1 — Build (JDK 21), Maven Wrapper e Starter no POM

Descrição
- Valida JDK 21, Maven Wrapper e inclusão da dependência `praxis-metadata-starter` nos módulos que a utilizam. Executa build `./mvnw -B -DskipTests package` e relata correções.

Escopo
- Confirmação de ambiente (Java/Maven)
- Verificação de POM(s) com o starter
- Execução de build sem testes

Checklist de Aceite
- [ ] `java -version` reporta 21.x
- [ ] `./mvnw -v` executa com sucesso
- [ ] Starter presente no(s) `pom.xml` alvo(s)
- [ ] Build `./mvnw -B -DskipTests package` finaliza sem erros

Evidências
- Comandos e saídas
  - `java -version` →
  - `./mvnw -v` →
  - `./mvnw -B -DskipTests package` →
- Trechos de POM (adicione referências de arquivo/linha)
  - `path/to/module/pom.xml:line` → bloco `<dependency>` com `io.github.codexrodrigues:praxis-metadata-starter`
- Diffs propostos (se aplicável)
  - Explique a mudança e anexe o patch

Configurações alteradas (se houver)
- 

Riscos e rollback
- Risco: versão incorreta de JDK/toolchain → rollback: restaurar toolchain anterior e reverter mudança de POM

Passos de revisão/teste
- Reproduzir os comandos e validar a ausência de erros

Fora de escopo
- Execução de testes unitários/integrados

Referências
- `README.md:1`
- `docs/technical/AUTO-CONFIGURACAO.md:1`

Checklist final
- [ ] Evidências anexadas
- [ ] Revisado por outro par
- [ ] Build verde no CI
