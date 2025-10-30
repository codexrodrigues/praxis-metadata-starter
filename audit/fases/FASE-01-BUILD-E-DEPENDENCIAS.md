# Fase 1 — Build e Dependências

Objetivo: garantir que o projeto compila com Java 21, usa Maven Wrapper e referencia corretamente o `praxis-metadata-starter`.

Saídas esperadas:

- Versões reportadas: `java -version` (21.x), `./mvnw -v`
- Trecho(s) do(s) `pom.xml` com a dependência do starter
- Build rodado com `./mvnw -B -DskipTests package` finalizando sem erros

Checklist

- Java 21 está ativo no ambiente e toolchain Maven está consistente
- Uso do Maven Wrapper (`./mvnw`) padrão do repo
- Dependência do starter presente em cada módulo que o utiliza:
  - `<groupId>io.github.codexrodrigues</groupId>`
  - `<artifactId>praxis-metadata-starter</artifactId>`
- Build com flags recomendadas para agentes: `-B -DskipTests [-T 1C]`

Verificações e evidências

- Comandos
  - `java -version`
  - `./mvnw -v`
  - `./mvnw -B -DskipTests package`
- Evidências
  - Screenshot/log do sucesso do build
  - Trechos de POM comprovando a dependência

Correções comuns

- Ajustar toolchain para Java 21 no Maven (quando presente) ou configurar JDK na máquina de build
- Incluir a dependência do starter no(s) módulo(s) corretos
- Em ambientes com rede restrita, habilitar rede para comandos Maven

Referências rápidas

- README do starter: README.md:1
- Auto‑configuração: docs/technical/AUTO-CONFIGURACAO.md:1

Prompt para agente

```
Tarefa: Auditar o build do projeto que usa o Praxis Metadata Starter.

Passos:
1) Verifique o ambiente: execute `java -version` (esperado 21.x) e `./mvnw -v`
2) Localize nos POM(s) dos módulos que usam o starter a dependência `io.github.codexrodrigues:praxis-metadata-starter`
3) Rode `./mvnw -B -DskipTests package` e colete o resultado

Entregue:
- Saída de `java -version` e `./mvnw -v`
- Trechos dos POM(s) com a dependência
- Resultado do build (sucesso ou falhas) e, se falhar, causas e correções propostas

Observações:
- Use o Maven Wrapper (`./mvnw`). Se a rede for restrita, solicite rede para baixar dependências.
```

