# 🔧 Documentação Técnica

Esta seção contém documentação técnica detalhada sobre os recursos avançados e internos do Praxis Metadata Starter.

## 📋 **Documentação Técnica Disponível**

### ⚙️ [Auto-configuração](AUTO-CONFIGURACAO.md)
**Sistema de configuração automática do Praxis Metadata Starter**

- Configuração automática de componentes Spring
- Propriedades de configuração disponíveis  
- Personalização e extensões
- Bean registration automático

**Ideal para:** Entender como o sistema se configura automaticamente e como personalizar.

### 🏷️ [Estratégia Dupla de Grupos OpenAPI](ESTRATEGIA-DUPLA-GRUPOS-OPENAPI.md)
**Sistema avançado de resolução automática de grupos OpenAPI**

- ✅ **@ApiResource + @ApiGroup** - Sistema duplo de anotações
- ✅ **Resolução automática** via OpenApiGroupResolver  
- ✅ **97% menos dados** - Documentos específicos vs completos
- ✅ **Cache inteligente** com algoritmo "best match"
- ✅ **Zero configuração** - Grupos criados automaticamente

**Ideal para:** Entender o sistema revolucionário de documentação OpenAPI automática.

### ✅ [Validação @ApiResource](VALIDACAO-API-RESOURCE.md)
**Sistema de validação obrigatória de anotações**

- Validação automática em tempo de compilação
- Detecção de controllers sem @ApiResource
- Sistema de testes automatizados
- Integração com CI/CD

**Ideal para:** Garantir que todos os controllers sigam os padrões estabelecidos.

---

## 🎯 **Para Quem é Esta Seção**

### **Arquitetos de Software**
- Entender as decisões de design do framework
- Avaliar impacto de performance e escalabilidade
- Planejar integrações e extensões

### **Desenvolvedores Sênior** 
- Implementar customizações avançadas
- Resolver problemas técnicos complexos  
- Contribuir com melhorias no framework

### **DevOps/SRE**
- Configurar ambientes de produção
- Implementar monitoramento e observabilidade
- Otimizar performance e cache

---

## 🔍 **Conceitos Técnicos Principais**

### **Sistema de Resolução Automática**
- **DynamicSwaggerConfig:** Escaneia controllers no startup
- **ApiDocsController:** Resolve grupos baseado no path da requisição
- **OpenApiGroupResolver:** Algoritmo "best match" para detecção
- **AbstractCrudController:** Auto-detecção de base path

### **Cache Inteligente**
- Documentos específicos por grupo (97% menor que completo)
- Cache baseado em path pattern matching
- Invalidação automática em mudanças de schema

### **Validação Automática**
- Detecção de controllers não anotados
- Validação em tempo de compilação
- Testes automatizados de conformidade

---

## 🚀 **Performance e Escalabilidade**

### **Métricas de Performance**
- **Redução de payload:** ~500KB → ~14KB (97% menor)
- **Cache hit rate:** >95% em ambientes típicos
- **Startup time:** Impacto mínimo (<100ms adicional)

### **Escalabilidade**
- Suporte a milhares de controllers simultâneos
- Cache distribuído para ambientes multi-instância
- Lazy loading de documentação por demanda

---

## 🛠️ **Troubleshooting Avançado**

### **Problemas Comuns**
- Controllers não detectados → Verificar herança de AbstractCrudController
- Grupos não criados → Validar anotações @ApiResource
- Cache não funcionando → Verificar configuração de Spring Boot

### **Debug e Monitoramento**
- Logs específicos para resolução de grupos
- Métricas de cache via Micrometer
- Health checks automáticos

---

## 📚 **Referências Relacionadas**

- 📖 [Guias de Implementação](../guides/)
- 💡 [Exemplos Práticos](../examples/)
- 🏠 [Índice Principal](../README.md)
- 🧭 [Heurística de ControlType](../concepts/CONTROLTYPE-HEURISTICA.md)
 - 🧩 [Roadmap de Filtros (Lote 2 e 3)](FILTROS-ROADMAP.md)
 - 🔎 Javadoc: [Visão geral](../apidocs/index.html), [Pacotes](../apidocs/allpackages-index.html)

---

**⚡ Nota:** Esta seção é para usuários avançados. Para uso básico, consulte os [Guias](../guides/) e [Exemplos](../examples/).
