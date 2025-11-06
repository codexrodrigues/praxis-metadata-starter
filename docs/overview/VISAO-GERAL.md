# Visão Geral — Praxis Metadata Starter

O Praxis Metadata Starter permite publicar contratos ricos (OpenAPI + x‑ui) a partir de anotações Java e auto‑configuração Spring, para que a UI se configure em tempo de execução com o mínimo de boilerplate.

## Problema que resolvemos
- Telas rígidas e duplicação de lógica entre back e front.
- Muito código cerimonial para listar/filtrar/paginar/ordenar.
- Evolução custosa do contrato entre times e sistemas.

## Abordagem
- Self‑describing APIs: o backend publica o contrato com extensões de UI (x‑ui).
- Schema‑driven UI: tabelas/formulários nascem do schema (sem codegen frágil).
- Configuration‑driven: preferências e variações vivem em configuração, não forks.
- Rules as Specifications: variações condicionais/visibilidade como regras portáveis.

## Como funciona (fluxo)
1) Você anota DTOs/entidades com `@UISchema` e expõe controllers com `@ApiResource`.
2) O starter enriquece o OpenAPI com `x‑ui` e expõe `/schemas/filtered`.
3) A UI consome esse endpoint, revalida com ETag e rende componentes dinâmicos.

Trecho de uso (exemplo simplificado):
```java
@UISchema(label = "Nome") @NotBlank @Size(max = 120)
private String nome;

@ApiResource("/api/human-resources/pessoas")
public class PessoaController extends AbstractCrudController<...> {}
```

```bash
# Schema para grid (response)
curl -i "http://localhost:8080/schemas/filtered?path=/api/human-resources/pessoas/all&operation=get&schemaType=response"
```

## Benefícios práticos
- Menos código acoplado; mais velocidade e consistência.
- Contratos versionáveis (ETag/If‑None‑Match) e evolução segura.
- Integração suave com Spring, SpringDoc e HATEOAS opcional.

## Passos rápidos
1) Dependência no pom.xml do serviço backend (após publicar RC/final):
```xml
<dependency>
  <groupId>io.github.codexrodrigues</groupId>
  <artifactId>praxis-metadata-starter</artifactId>
  <version>1.0.0-rc.6</version>
  </dependency>
```
2) Anote DTOs com `@UISchema` e exponha `@ApiResource` nos controllers.
3) No front, consuma `/schemas/filtered` e configure `resourcePath` no componente.

## Evolução de contrato
- Cache condicional: `ETag`/`If‑None‑Match` evita downloads desnecessários.
- Mudanças non‑breaking incrementam hash; mudanças breaking usam versão lógica/documentação.

## Conceitos relacionados (leitura rápida)
- Self‑describing APIs: ../concepts/self-describing-apis.md
- UI Schema vs Data Schema: ../concepts/ui-schema-vs-data-schema.md
- Schema‑driven UI: ../concepts/schema-driven-ui.md
- Configuration‑driven development: ../concepts/configuration-driven-development.md
- Rules & Specifications: ../concepts/rules-engines-and-specifications.md

## Exemplo completo
- Repositório de exemplo (Quickstart): https://github.com/codexrodrigues/praxis-api-quickstart

## Guias e técnica
- Guia CRUD+Bulk: ../guides/GUIA-CLAUDE-AI-CRUD-BULK.md
- Estratégia de grupos OpenAPI: ../technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md
- Auto‑configuração: ../technical/AUTO-CONFIGURACAO.md
- Validação @ApiResource: ../technical/VALIDACAO-API-RESOURCE.md
