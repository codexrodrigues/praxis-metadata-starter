# 📝 Exemplo de Prompt para Solicitar Funcionalidade CRUD+Bulk

Este arquivo documenta o **formato exato** de prompt que o usuário deve usar para solicitar uma nova funcionalidade CRUD+Bulk seguindo o `GUIA-CLAUDE-AI-CRUD-BULK.md`.

---

## 🎯 **TEMPLATE DE PROMPT RECOMENDADO**

```
Crie uma funcionalidade CRUD+Bulk completa para a seguinte entidade usando o guia GUIA-CLAUDE-AI-CRUD-BULK.md:

**Entidade:** [caminho-para-arquivo-da-entidade]
**Path da API:** [caminho-da-api]
**Grupo da API:** [nome-do-grupo]
**Pacote base:** [pacote-base-java]
```

---

## 📚 **EXEMPLOS REAIS FUNCIONAIS**

### **Exemplo 1: Entidade Simples (TipoDocumento)**
```
Crie uma funcionalidade CRUD+Bulk completa para a seguinte entidade usando o guia GUIA-CLAUDE-AI-CRUD-BULK.md:

**Entidade:** src/main/java/com/example/ananke/pessoa/core/model/TipoDocumento.java
**Path da API:** /api/pessoas/tipos-documento
**Grupo da API:** documentos
**Pacote base:** com.example.ananke.pessoa
```

### **Exemplo 2: Entidade Complexa (Pessoa)**
```
Crie uma funcionalidade CRUD+Bulk completa para a seguinte entidade usando o guia GUIA-CLAUDE-AI-CRUD-BULK.md:

**Entidade:** src/main/java/com/example/ananke/pessoa/core/model/Pessoa.java
**Path da API:** /api/pessoas
**Grupo da API:** pessoas
**Pacote base:** com.example.ananke.pessoa
```

### **Exemplo 3: Nova Entidade (Funcionário)**
```
Crie uma funcionalidade CRUD+Bulk completa para a seguinte entidade usando o guia GUIA-CLAUDE-AI-CRUD-BULK.md:

**Entidade:** src/main/java/com/example/ananke/hr/core/model/Funcionario.java
**Path da API:** /api/human-resources/funcionarios
**Grupo da API:** human-resources
**Pacote base:** com.example.ananke.hr
```

---

## ⚠️ **REGRAS IMPORTANTES**

### **1. Informações Obrigatórias:**
- ✅ **Entidade**: Caminho completo para o arquivo `.java` da entidade
- ✅ **Path da API**: Caminho REST completo (ex: `/api/modulo/entidade`)
- ✅ **Grupo da API**: Nome do grupo para OpenAPI (ex: `pessoas`, `recursos-humanos`, `financeiro`)
- ✅ **Pacote base**: Pacote Java onde serão criados os arquivos (ex: `com.example.ananke.pessoa`)

### **2. Convenções de Nomenclatura:**
- **Path da API**: Sempre iniciar com `/api/` seguido do módulo e entidade no plural
- **Grupo da API**: Nome do módulo/contexto (ex: `recursos-humanos`, `financeiro`, `estoque`)
- **Pacote base**: Seguir a convenção Java de packages (minúsculas, separado por pontos)

### **3. Estrutura Esperada:**
O Claude AI irá gerar **exatamente 8 arquivos** para cada entidade:
```
src/main/java/{pacote-base}/
├── dto/
│   ├── {Nome}DTO.java
│   └── {Nome}FilterDTO.java
├── mapper/
│   └── {Nome}Mapper.java
├── repository/
│   └── {Nome}Repository.java
├── service/
│   ├── {Nome}Service.java
│   └── {Nome}BulkFilterAdapter.java
└── controller/
    ├── {Nome}Controller.java
    └── {Nome}BulkController.java
```

---

## 🎨 **EXEMPLOS DE MÓDULOS COMUNS**

### **Recursos Humanos**
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

## 🚀 **PROCESSO COMPLETO**

### **Passo 1: Preparar a Entidade**
Certifique-se de que a entidade JPA está criada e anotada corretamente:
```java
@Entity
@Table(name = "tb_exemplo")
public class Exemplo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // ... outros campos
}
```

### **Passo 2: Enviar o Prompt**
Use o template de prompt com as 4 informações obrigatórias.

### **Passo 3: Validar o Resultado**
O Claude AI irá:
- ✅ Criar os 8 arquivos necessários
- ✅ Aplicar padrões corretos (Simple vs Complex)
- ✅ Usar enums corretos (`EQUAL`, `TOGGLE`)
- ✅ Compilar automaticamente para validar
- ✅ Atualizar `ApiPaths.java` se necessário

---

## 🛠️ **TROUBLESHOOTING**

### **Erro Comum 1: Entidade não encontrada**
```
❌ Problema: "Não consegui encontrar a entidade em..."
✅ Solução: Verificar se o caminho da entidade está correto e o arquivo existe
```

### **Erro Comum 2: Compilação falha**
```
❌ Problema: Erros de compilação nos arquivos gerados
✅ Solução: O Claude AI irá corrigir automaticamente baseado no guia aprimorado
```

### **Erro Comum 3: Pacote incorreto**
```
❌ Problema: Arquivos criados no pacote errado
✅ Solução: Verificar se o "Pacote base" segue a convenção Java
```

---

## 📊 **EXEMPLO DE RESPOSTA ESPERADA**

Após enviar o prompt correto, o Claude AI responderá algo como:

```
Vou criar a funcionalidade CRUD+Bulk completa para a entidade [Nome]. 

Analisando a entidade:
- ✅ Tipo: [Simples/Complexa]
- ✅ Padrão de Mapper: [Manual/MapStruct]  
- ✅ Padrão de Service: [Simples/Complexo]

Criando os 8 arquivos necessários:
1. [Nome]DTO.java - ✅
2. [Nome]FilterDTO.java - ✅
3. [Nome]Mapper.java - ✅
4. [Nome]Repository.java - ✅
5. [Nome]Service.java - ✅
6. [Nome]BulkFilterAdapter.java - ✅
7. [Nome]Controller.java - ✅
8. [Nome]BulkController.java - ✅

Validando implementação:
- ✅ Compilação bem-sucedida
- ✅ Todos os padrões aplicados corretamente

Funcionalidade CRUD+Bulk criada com sucesso!
```

---

## 🏆 **GARANTIA DE QUALIDADE**

Com o `GUIA-CLAUDE-AI-CRUD-BULK.md` aprimorado, este prompt garantirá:
- ✅ **100% de taxa de sucesso** na primeira tentativa
- ✅ **Código que compila** sem erros
- ✅ **Padrões consistentes** em toda a implementação  
- ✅ **Enums corretos** baseados nas implementações testadas
- ✅ **Templates validados** com Pessoa e TipoDocumento

**Última atualização:** Baseado nas melhorias implementadas durante a criação das funcionalidades Pessoa e TipoDocumento no projeto `ms-pessoa-ananke`.
