# 📚 Documentação Praxis Metadata Starter

Este diretório contém a documentação completa do **Praxis Metadata Starter**, organizadas seguindo as melhores práticas de documentação de projetos.

Site da documentação (GitHub Pages):
- https://codexrodrigues.github.io/praxis-metadata-starter/

Exemplos no Javadoc (GitHub Pages):
- Modelando DTO com @UISchema: https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/doc-files/exemplos-modelando-dto.html#modelando-dto-com-uischema-heading
- Expondo controller com @ApiResource: https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/doc-files/exemplos-expondo-controller.html#expondo-controller-com-apiresource-heading
- Consumindo o contrato (/schemas/filtered): https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/doc-files/exemplos-consumindo-contrato.html#consumindo-o-contrato-heading

## 🗂️ **Estrutura da Documentação**

```
docs/
├── README.md                 # Este arquivo - índice principal
├── architecture-overview.md  # Visão arquitetural com diagramas
├── packages-overview.md      # Mapa dos pacotes Java
├── sitemap.xml               # Navegação para motores de busca
├── overview/                 # Visão geral para novos usuários
│   └── VISAO-GERAL.md
├── guides/                   # Guias completos de implementação
│   ├── GUIA-CLAUDE-AI-CRUD-BULK.md
│   └── GUIA-CLAUDE-AI-APLICACAO-NOVA.md
├── examples/                 # Exemplos práticos e templates
│   ├── EXEMPLO-PROMPT-CRUD-BULK.md
│   ├── filter-dto.md
│   └── spring-integration.md
├── concepts/               # Conceitos fundamentais (Self-describing, UI vs Data Schema, etc.)
│   └── ui-schema.md
├── technical/               # Documentação técnica detalhada
│   ├── AUTO-CONFIGURACAO.md
│   ├── CURSOR-PAGINATION-KEYSET-BACKLOG.md
│   ├── CURSOR-PAGINATION-KEYSET-PLAN.md
│   ├── ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md
│   └── VALIDACAO-API-RESOURCE.md
└── api/                    # Documentação de API (para futuras adições)
```

---

## 📖 **Guias de Implementação** (`/guides/`)

## 📘 **Visão Geral** (`/overview/`)
- Leitura rápida (problema/abordagem/benefícios) e como começar:
  - [VISAO-GERAL.md](overview/VISAO-GERAL.md)

## 🏛️ **Arquitetura e Mapa de Pacotes**

- [Visão Arquitetural](architecture-overview.md): diagramas Mermaid, fluxo de enriquecimento x-ui e principais componentes.
- [Visão dos Pacotes](packages-overview.md): responsabilidades de cada pacote Java e quando estender contratos.
- [Sitemap](sitemap.xml): referência para indexadores (Google, IA) e navegação cruzada entre tópicos.

### 🤖 [Guia de CRUD Metadata-Driven por Entidade](guides/GUIA-CLAUDE-AI-CRUD-BULK.md)
**O guia principal para geração automatizada de recursos CRUD alinhados ao contrato real do starter**

- ✅ ancorado no código canônico do starter
- ✅ referenciado no uso real do `praxis-api-quickstart`
- ✅ compatível com o consumo do `GenericCrudService` no `praxis-ui-angular`
- ✅ corrige a regra de options para `OptionDTO{id,label}`

**Quando usar:** Para criar um recurso CRUD metadata-driven de forma padronizada e consumível pela UI Praxis.

### 🚀 [Guia para Aplicações Novas](guides/GUIA-CLAUDE-AI-APLICACAO-NOVA.md) 
**Guia para criar uma aplicação Spring Boot mínima e correta sobre o starter**

- baseline mínimo de dependências
- estrutura inicial de projeto
- primeiro recurso metadata-driven
- alinhamento com quickstart e Angular

**Quando usar:** Ao criar uma nova aplicação Spring Boot com Praxis Metadata Starter.

---

## 💡 **Exemplos Práticos** (`/examples/`)

### 📝 [Template de Prompt para Recurso CRUD Metadata-Driven](examples/EXEMPLO-PROMPT-CRUD-BULK.md)
**Formato recomendado de prompt para solicitar um recurso alinhado ao starter**

- 🎯 template padronizado com entradas mínimas
- 🔗 referência explícita ao quickstart e ao Angular
- 🛠️ regras para evitar bulk obrigatório e exemplos legados

**Quando usar:** Como referência para solicitar geração assistida de um recurso CRUD metadata-driven.

### 🧾 [Filter DTO com Metadados x-ui](examples/filter-dto.md)
**Mostra um filtro completo com `@Filterable` + `@UISchema`**

- 🔍 Mapeamento para Specifications com operadores personalizados.
- 🧭 Ordem e agrupamento pensados para formulários avançados.
- 🔗 Links diretos para endpoints `/options` e `/schemas/filtered`.

### 🔌 [Integração Spring Boot ponta a ponta](examples/spring-integration.md)
**Guia rápido para subir uma aplicação consumindo o starter**

- ⚙️ Configuração mínima com `@SpringBootApplication`.
- 🧱 Controller CRUD que reutiliza `AbstractCrudController`.
- 📄 Resposta real do endpoint `/schemas/filtered`.

---

## 🔧 **Documentação Técnica** (`/technical/`)

### ⚙️ [Auto-configuração](technical/AUTO-CONFIGURACAO.md)
**Documentação técnica sobre o sistema de auto-configuração**

- Configuração automática de componentes
- Propriedades disponíveis
- Personalização e extensões

### 🏷️ [Estratégia Dupla de Grupos OpenAPI](technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md)
**Sistema avançado de resolução de grupos OpenAPI**

- ✅ **@ApiResource + @ApiGroup** - Sistema duplo de anotações
- ✅ **Resolução automática** via OpenApiGroupResolver
- ✅ **Documentação técnica completa** com exemplos
- ✅ **Testes de validação** incluídos

### ✅ [Validação @ApiResource](technical/VALIDACAO-API-RESOURCE.md)
**Sistema de validação obrigatória de anotações**

- Validação automática de @ApiResource
- Detecção de controllers sem anotação
- Sistema de testes automatizados

### 🔁 [Plano de Cursor Pagination / Keyset](technical/CURSOR-PAGINATION-KEYSET-PLAN.md)
**Desenho de plataforma para suportar `/filter/cursor` com keyset pagination real**

- Uso de `Window<T>` e `ScrollPosition` do Spring Data Commons
- Fragmentos de repositório para infraestrutura compartilhada
- Sort estável com tie-break por ID
- Capability explícita por recurso
- Rollout seguro do v1 sem generalização excessiva

### 🧱 [Backlog Executável de Cursor Pagination / Keyset](technical/CURSOR-PAGINATION-KEYSET-BACKLOG.md)
**Backlog do v1 de keyset pagination, arquivo por arquivo**

- tipos internos
- codec de cursor
- fragmento de repositório
- integração no service base
- controller base
- auto-configuração
- testes e validação consumidora

---

## 🧭 **Como Navegar pela Documentação**

### **Para Desenvolvedores - Primeira Implementação:**
1. 📖 Leia o [Guia de CRUD Metadata-Driven](guides/GUIA-CLAUDE-AI-CRUD-BULK.md) completo
2. 📝 Use o [Template de Prompt](examples/EXEMPLO-PROMPT-CRUD-BULK.md) como referência
3. 🔧 Consulte a documentação técnica conforme necessário

### **Para Mantenedores - Aprofundamento Técnico:**
1. 🔧 Estude a [Estratégia de Grupos OpenAPI](technical/ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md)
2. ⚙️ Entenda a [Auto-configuração](technical/AUTO-CONFIGURACAO.md) 
3. ✅ Configure a [Validação @ApiResource](technical/VALIDACAO-API-RESOURCE.md)
4. 🔁 Revise o [Plano de Cursor Pagination / Keyset](technical/CURSOR-PAGINATION-KEYSET-PLAN.md) antes de evoluir `/filter/cursor`
5. 🧱 Execute o [Backlog de Cursor Pagination / Keyset](technical/CURSOR-PAGINATION-KEYSET-BACKLOG.md) para implementar o v1

### **Integração de Schema (Backend ↔ Frontend)**
- 🔒 Planos de hash/ETag, identidade de campos, diffs e persistência de versões/overrides:
  - [SCHEMA-HASH-PLAN.md](SCHEMA-HASH-PLAN.md)
  - [SCHEMA-INTEGRATION-PLAN.md](SCHEMA-INTEGRATION-PLAN.md)
- 🔁 Controle explícito de ETag/If-None-Match no front e trilha para persistência no servidor:
  - [FRONTEND-SCHEMA-CACHE-PLAN.md](FRONTEND-SCHEMA-CACHE-PLAN.md)
  - [RESUMO-EXECUTIVO.md](RESUMO-EXECUTIVO.md)

### **Para Usuários - Uso Rápido:**
1. 📝 Vá direto ao [Template de Prompt](examples/EXEMPLO-PROMPT-CRUD-BULK.md)
2. 💡 Siga os exemplos práticos por módulo
3. 🚀 Solicite suas funcionalidades seguindo o formato

---

## 🏆 Critério de Qualidade

Esta documentação deve permanecer aderente a:

- código canônico do `praxis-metadata-starter`
- uso real e publicado do `praxis-api-quickstart`
- consumo final em `praxis-ui-angular`

---

## 📈 **Histórico de Melhorias**

### **v2.1 (Atual) - Equiparado ao código fonte**
- ✅ guias reancorados no starter canônico
- ✅ exemplos alinhados ao quickstart real
- ✅ consumo Angular refletido na documentação
- ✅ claims não canônicas de bulk removidas do baseline

### **v1.0 - Versão Inicial**
- Templates básicos
- Documentação teórica
- Alguns enums incorretos (já corrigidos)

---

## 🤝 **Como Contribuir**

1. **Encontrou um erro?** Reporte através de issues
2. **Quer melhorar?** Teste os templates e documente melhorias
3. **Nova funcionalidade?** Siga os padrões estabelecidos na documentação

---

**💡 Dica:** Marque esta página nos seus favoritos - é seu ponto de partida para qualquer tarefa relacionada ao Praxis Metadata Starter!
