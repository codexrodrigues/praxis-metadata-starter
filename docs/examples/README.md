# 💡 Exemplos Práticos

Esta seção contém exemplos práticos, templates e casos de uso reais para facilitar a implementação com Praxis Metadata Starter.

## 📋 **Exemplos Disponíveis**

### 📝 [Template de Prompt para Recurso CRUD Metadata-Driven](EXEMPLO-PROMPT-CRUD-BULK.md)
**Formato recomendado para solicitar um recurso alinhado ao contrato real do starter**

- 🎯 template padronizado com entradas mínimas
- 🔗 referência ao quickstart e ao Angular
- 🛠️ regras para evitar desvios como bulk obrigatório

**Como usar:**
```
Crie um recurso CRUD metadata-driven alinhado ao praxis-metadata-starter.

**Entidade:** [caminho-para-arquivo-da-entidade]
**Resource path:** [caminho-da-api]
**Api group:** [nome-do-grupo]
**Pacote base:** [pacote-base-java]
```

---

## 🎨 **Exemplos por Contexto**

### **Human Resources**
```
**Path da API:** /api/human-resources/funcionarios
**Grupo da API:** human-resources
**Pacote base:** com.example.empresa.rh
```

### **Financeiro**
```
**Path da API:** /api/financeiro/contas-receber
**Grupo da API:** financeiro
**Pacote base:** com.example.empresa.financeiro
```

### **Estoque**
```
**Path da API:** /api/estoque/produtos
**Grupo da API:** estoque
**Pacote base:** com.example.empresa.estoque
```

### **Vendas**
```
**Path da API:** /api/vendas/pedidos
**Grupo da API:** vendas
**Pacote base:** com.example.empresa.vendas
```

---

## 🚀 **Como Usar os Exemplos**

1. **📝 Copie o template** do exemplo mais próximo ao seu caso
2. **🔧 Adapte as informações** para sua entidade específica
3. **✅ Valide o formato** seguindo as convenções documentadas
4. **🎯 Execute** usando o guia metadata-driven correspondente

---

## ⚠️ **Convenções Importantes**

- **Path da API:** Sempre `/api/{módulo}/{entidade}` no plural
- **Grupo da API:** Nome do módulo/contexto (ex: `human-resources`, `financeiro`)
- **Pacote base:** Convenção Java (`com.empresa.módulo`)

---

## 📚 **Referências Relacionadas**

- 📖 [Guia de CRUD Metadata-Driven](../guides/GUIA-CLAUDE-AI-CRUD-BULK.md)
- 🔧 [Documentação Técnica](../technical/)
- 🏠 [Índice Principal](../README.md)

---

## 🔎 Exemplos de Filtros (novos)

- DTO demonstrando todas as operações: [DTO-FILTROS-EXEMPLOS.md](DTO-FILTROS-EXEMPLOS.md)
- Cheat Sheet de payloads `POST /{resource}/filter`: [FILTER-CHEATSHEET.md](FILTER-CHEATSHEET.md)

---

**🎯 Dica:** Use estes exemplos como referência rápida para manter aderência ao starter, ao quickstart e ao consumo Angular.
