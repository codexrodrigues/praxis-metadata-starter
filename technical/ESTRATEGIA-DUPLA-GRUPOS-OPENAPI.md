# 🎯 Estratégia Dupla de Grupos OpenAPI - Documentação Técnica

## 📋 Visão Geral

Esta documentação descreve a **Estratégia Dupla de Grupos OpenAPI** implementada no Praxis Metadata Starter, que otimiza drasticamente a performance da documentação OpenAPI através da criação automática de grupos ultra-específicos e contextuais.

## 🚀 Problema Resolvido

**Antes:** Documento OpenAPI monolítico de ~500KB+ para qualquer consulta
**Depois:** Grupos específicos de 3-5KB para CRUDs individuais + grupos contextuais de 50-100KB

## 🎯 Estratégia Dupla

### 1. 📊 Grupos Individuais Ultra-Específicos

**Escopo:** Apenas controllers que estendem `AbstractCrudController`
**Performance:** ~3-5KB por documento (99% de redução)
**Uso:** Consultas específicas como `/schemas/filtered?path=/api/human-resources/eventos-folha/all`

#### Características:
- ✅ Criados automaticamente para cada controller CRUD
- ✅ Nome baseado no path completo do controller
- ✅ Performance extremamente otimizada
- ✅ Ideais para operações específicas

#### Exemplo:
```java
@ApiResource("/api/human-resources/funcionarios")
@ApiGroup("human-resources") 
public class FuncionarioController extends AbstractCrudController<...> {
    // Cria grupo individual: "api-human-resources-funcionarios"
}
```

### 2. 🏷️ Grupos Agregados por Contexto

**Escopo:** QUALQUER controller com anotação `@ApiGroup`
**Performance:** ~50-100KB por documento (90% de redução)
**Uso:** Visualização de contextos completos no Swagger UI

#### Características:
- ✅ Qualquer controller pode participar via `@ApiGroup`
- ✅ Agrupa controllers por contexto de negócio
- ✅ Calcula padrões inteligentes que englobam múltiplos paths
- ✅ Flexibilidade total para organização

#### Exemplo:
```java
// Controllers CRUD
@ApiGroup("human-resources")
public class FuncionarioController extends AbstractCrudController<...> { }

@ApiGroup("human-resources") 
public class CargoController extends AbstractCrudController<...> { }

// Controllers Bulk
@ApiGroup("human-resources-bulk")
public class FuncionarioBulkController extends AbstractBulkController<...> { }

// Qualquer Controller
@ApiGroup("relatorios")
@RestController
public class CustomReportController { }
```

## 📊 Resultado Típico

Para uma aplicação com 8 controllers CRUD + 8 controllers Bulk:

```
📋 Grupos Individuais (8):
├── api-human-resources-funcionarios     (3KB - ultra-rápido)
├── api-human-resources-cargos          (3KB - ultra-rápido) 
├── api-human-resources-departamentos   (3KB - ultra-rápido)
├── api-human-resources-dependentes     (3KB - ultra-rápido)
├── api-human-resources-enderecos       (3KB - ultra-rápido)
├── api-human-resources-eventos-folha   (3KB - ultra-rápido)
├── api-human-resources-ferias-afastamentos (3KB - ultra-rápido)
└── api-human-resources-folha-pagamentos (3KB - ultra-rápido)

🏷️ Grupos Agregados (2):
├── human-resources        (50KB - 8 controllers CRUD)
└── human-resources-bulk   (30KB - 8 controllers Bulk)

📈 Total: 10 grupos vs documento completo (500KB+)
```

## 🔧 Implementação Técnica

### DynamicSwaggerConfig

Classe principal que implementa a estratégia dupla:

1. **1ª Passada:** Escaneia controllers `AbstractCrudController` para grupos individuais
2. **2ª Passada:** Escaneia TODOS os controllers para grupos agregados via `@ApiGroup`
3. **Registro:** Cria beans `GroupedOpenApi` no Spring Bean Factory
4. **Otimização:** Evita duplicação e calcula padrões agregados inteligentes

### ApiDocsController

Resolve automaticamente qual grupo usar baseado no path da requisição:

1. **OpenApiGroupResolver:** Usa algoritmo "best match" com grupos registrados
2. **Cache Inteligente:** Documentos pequenos são cacheados eficientemente
3. **Fallbacks:** Estratégias de fallback garantem sempre uma resposta

### OpenApiGroupResolver

Implementa algoritmo "best match" que prioriza padrões mais específicos:

1. **Normalização:** Remove wildcards para comparação de especificidade
2. **Best Match:** Retorna grupo com padrão mais específico (mais longo)
3. **Performance:** O(n*m) onde n=grupos e m=padrões (tipicamente ~20 operações)

## 📈 Benefícios

### Performance Extrema
- **Grupos Individuais:** 99% menor que documento completo
- **Grupos Agregados:** 90% menor que documento completo
- **Cache:** Documentos pequenos = cache mais eficiente

### Flexibilidade Total
- **CRUDs:** Grupos individuais automáticos para performance máxima
- **Contextos:** Qualquer controller pode participar via `@ApiGroup`
- **Organização:** Agrupa por contexto de negócio

### Semântica Clara
- **Individuais:** Para operações específicas ultra-rápidas
- **Agregados:** Para visualização de contextos completos
- **Separação:** Cada tipo tem seu propósito bem definido

### Zero Configuração
- **Detecção Automática:** `@ApiResource` e `@RequestMapping` detectados automaticamente
- **Registro Automático:** Grupos criados no startup da aplicação
- **Integração:** Funciona perfeitamente com Swagger UI

## 🎯 Swagger UI Dropdown

O dropdown do Swagger UI mostrará todos os grupos disponíveis:

```
📋 Grupos Individuais (ultra-rápidos):
☑️ api-human-resources-cargos
☑️ api-human-resources-departamentos  
☑️ api-human-resources-dependentes
☑️ api-human-resources-enderecos
☑️ api-human-resources-eventos-folha      ⚡ (3KB - ultra-específico)
☑️ api-human-resources-ferias-afastamentos
☑️ api-human-resources-folha-pagamentos
☑️ api-human-resources-funcionarios

🏷️ Grupos Agregados (contextos):
☑️ human-resources                       (50KB - 8 controllers CRUD)
☑️ human-resources-bulk                  (30KB - 8 controllers Bulk)
```

## 🔄 Fluxo de Funcionamento

### 1. Startup da Aplicação
```
DynamicSwaggerConfig.createDynamicGroups() executado via @PostConstruct
│
├─ 1ª Passada: Escanear AbstractCrudController
│  ├─ Encontrar 8 controllers CRUD
│  ├─ Extrair paths via @ApiResource
│  └─ Criar 8 grupos individuais
│
├─ 2ª Passada: Escanear @ApiGroup em TODOS controllers  
│  ├─ Encontrar 16 controllers com @ApiGroup
│  ├─ Agrupar por nome do @ApiGroup
│  └─ Criar 2 grupos agregados
│
└─ Resultado: 10 grupos registrados no Spring Bean Factory
```

### 2. Requisição de Schema
```
GET /schemas/filtered?path=/api/human-resources/eventos-folha/all
│
├─ ApiDocsController.resolveGroupFromPath()
│  ├─ OpenApiGroupResolver.resolveGroup() 
│  └─ Resolve: "api-human-resources-eventos-folha"
│
├─ ApiDocsController.getDocumentForGroup()
│  ├─ Cache hit/miss check
│  ├─ Fetch: /v3/api-docs/api-human-resources-eventos-folha
│  └─ Cache documento (3KB)
│
└─ Filtrar schema específico + metadados x-ui
```

## ⚙️ Configuração

### Obrigatória
```java
@ComponentScan(basePackages = {"org.praxisplatform.uischema.configuration"})
```

### Opcional
```properties
# Validação de @ApiResource em controllers AbstractCrud
praxis.openapi.validation.api-resource-required=WARN  # WARN|FAIL|IGNORE
```

## 🎉 Conclusão

A Estratégia Dupla de Grupos OpenAPI oferece:
- **Performance extremamente otimizada** (99% de redução para CRUDs)
- **Flexibilidade total** para organização por contextos
- **Zero configuração** com detecção automática
- **Integração perfeita** com Swagger UI

Esta implementação transforma uma arquitetura de documentação monolítica em um sistema ultra-otimizado que escala perfeitamente com o crescimento da aplicação.
