# 📖 Guias de Implementação

Esta seção contém guias completos e detalhados para implementar funcionalidades usando o Praxis Metadata Starter.

## 📋 **Guias Disponíveis**

### 🤖 [Guia CRUD+Bulk Automatizado](GUIA-CLAUDE-AI-CRUD-BULK.md)
**Guia principal para geração automatizada de funcionalidades CRUD+Bulk**

- ✅ Templates 100% testados com implementações reais
- ✅ Enums corretos baseados em código funcional  
- ✅ Matrizes de decisão para Mappers e Services
- ✅ BulkFilterAdapter completamente documentado
- ✅ 4 Anexos UISchema para referência completa

**Ideal para:** Criar funcionalidades CRUD+Bulk de forma padronizada e automatizada.

### 🚀 [Guia para Aplicações Novas](GUIA-CLAUDE-AI-APLICACAO-NOVA.md)
**Setup completo para novas aplicações Spring Boot**

- Configuração inicial do projeto
- Estrutura de módulos e pacotes
- Integração com Praxis Platform
- Boas práticas organizacionais

**Ideal para:** Inicializar novos projetos com Praxis Metadata Starter.

---

### 🧭 [CRUD com @ApiResource e @ApiGroup](CRUD-COM-APIRESOURCE.md)
Exponha recursos REST e organize a documentação em grupos OpenAPI.

### 🔎 [Filtros e Paginação](FILTROS-E-PAGINACAO.md)
Implemente filtros com `@Filterable` + Specifications e paginação consistente.

### 🔢 [Ordenação Padrão](ORDEM-PADRAO.md)
Defina `@DefaultSortColumn` e tenha ordenação determinística por padrão.

### ✅ [Options (id/label)](OPTIONS-ENDPOINT.md)
Exponha endpoints de opções id/label usando `@OptionLabel` e `OptionMapper`.

### ❗ [Erros e Envelope de Respostas](ERROS-E-RESPOSTAS.md)
Padronize respostas de erro e sucesso para melhor DX/UX.

---

## 🎯 **Como Usar os Guias**

1. **Para primeira implementação:** Comece com a [Visão Geral](../overview/VISAO-GERAL.md) e depois o [Guia CRUD+Bulk](GUIA-CLAUDE-AI-CRUD-BULK.md)
2. **Para novo projeto:** Use o [Guia de Aplicação Nova](GUIA-CLAUDE-AI-APLICACAO-NOVA.md)
3. **Para exemplos práticos:** Consulte os [Examples](../examples/)
4. **Para detalhes técnicos:** Veja a [Documentação Técnica](../technical/)

---

## 🏆 **Garantia de Qualidade**

Todos os guias nesta seção foram **validados através de implementações reais**:

- ✅ **Entidade Pessoa** (complexa) - 8 arquivos gerados e funcionando
- ✅ **Entidade TipoDocumento** (simples) - 8 arquivos gerados e funcionando  
- ✅ **100% de compilação** sem erros
- ✅ **Templates testados** em ambiente real

---

**📌 Nota:** Para uma visão geral de toda a documentação, volte ao [índice principal](../README.md).

---

## 🔎 Referências de API

- Javadoc (publicado no GitHub Pages): [Visão geral](../apidocs/index.html)
- API por pacote: [allpackages-index](../apidocs/allpackages-index.html)

## ✅ Pré‑requisitos
- Java 21
- Spring Boot 3.2+
- SpringDoc OpenAPI (starter já incluso como dependência)
- Maven (para build e publicação)

## 🚀 Exemplo completo (Quickstart)
- Repositório de exemplo: https://github.com/codexrodrigues/praxis-api-quickstart
