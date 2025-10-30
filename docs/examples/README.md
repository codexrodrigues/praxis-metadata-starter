# 💡 Exemplos Práticos

Esta seção contém exemplos práticos, templates e casos de uso reais para facilitar a implementação com Praxis Metadata Starter.

## 📋 **Exemplos Disponíveis**

### 📝 [Template de Prompt CRUD+Bulk](EXEMPLO-PROMPT-CRUD-BULK.md)
**Formato exato para solicitar novas funcionalidades automatizadas**

- 🎯 **Template padronizado** com 4 informações obrigatórias
- 🎨 **Exemplos por módulo** (Recursos Humanos, Financeiro, Estoque, Vendas)
- 🛠️ **Troubleshooting** para erros comuns
- 🏆 **100% de garantia** de sucesso na primeira tentativa

**Como usar:**
```
Crie uma funcionalidade CRUD+Bulk completa para a seguinte entidade usando o guia GUIA-CLAUDE-AI-CRUD-BULK.md:

**Entidade:** [caminho-para-arquivo-da-entidade]
**Path da API:** [caminho-da-api]
**Grupo da API:** [nome-do-grupo]
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
4. **🎯 Execute** usando o guia CRUD+Bulk correspondente

---

## ⚠️ **Convenções Importantes**

- **Path da API:** Sempre `/api/{módulo}/{entidade}` no plural
- **Grupo da API:** Nome do módulo/contexto (ex: `human-resources`, `financeiro`)
- **Pacote base:** Convenção Java (`com.empresa.módulo`)

---

## 📚 **Referências Relacionadas**

- 📖 [Guia CRUD+Bulk Completo](../guides/GUIA-CLAUDE-AI-CRUD-BULK.md)
- 🔧 [Documentação Técnica](../technical/)
- 🏠 [Índice Principal](../README.md)

---

## 🔎 Exemplos de Filtros (novos)

- DTO demonstrando todas as operações: [DTO-FILTROS-EXEMPLOS.md](DTO-FILTROS-EXEMPLOS.md)
- Cheat Sheet de payloads `POST /{resource}/filter`: [FILTER-CHEATSHEET.md](FILTER-CHEATSHEET.md)

---

**🎯 Dica:** Use estes exemplos como referência rápida - eles foram testados e garantem 100% de sucesso!
