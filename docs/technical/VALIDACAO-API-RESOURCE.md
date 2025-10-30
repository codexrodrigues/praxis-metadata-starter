# 🔍 Validação de Uso de @ApiResource

## 📋 Visão Geral

A partir desta versão, o Praxis Metadata Starter inclui validação automática para garantir que controllers que estendem `AbstractCrudController` usem a anotação `@ApiResource` em vez de `@RestController` + `@RequestMapping`.

## 🎯 Objetivo

Garantir consistência arquitetural e aproveitar os benefícios da resolução automática de grupos OpenAPI, incluindo:

- ✅ **Documentação OpenAPI específica por controller** (~14KB vs 500KB+)
- ✅ **Resolução automática de grupos** sem configuração manual
- ✅ **Melhor organização da documentação** por domínio
- ✅ **Conformidade com padrões do framework** Praxis

## 🚀 Como Funciona

### Validação Automática

A validação é executada automaticamente durante o startup da aplicação através do `DynamicSwaggerConfig.validateApiResourceUsage()`.

### Comportamentos Disponíveis

Configure via `application.properties`:

```properties
# Padrão - emite warnings informativos
praxis.openapi.validation.api-resource-required=WARN

# Falha o startup se encontrar não conformidade
praxis.openapi.validation.api-resource-required=FAIL

# Desabilita completamente a validação
praxis.openapi.validation.api-resource-required=IGNORE
```

## 🔄 Como Migrar

### ❌ Padrão Antigo (será alertado)
```java
@RestController
@RequestMapping("/api/human-resources/funcionarios")
public class FuncionarioController extends AbstractCrudController<...> {
    // implementação
}
```

### ❌ Controller sem Anotações (será alertado também)
```java
public class FuncionarioController extends AbstractCrudController<...> {
    // ⚠️ CONFIGURAÇÃO OBRIGATÓRIA: Controllers que estendem AbstractCrudController
    // DEVEM usar @RequestMapping ou @ApiResource para definir o base path
}
```

### ✅ Padrão Recomendado
```java
@ApiResource("/api/human-resources/funcionarios")
@ApiGroup("human-resources")
@Tag(name = "HR - Funcionários", description = "Operações CRUD para funcionários")
public class FuncionarioController extends AbstractCrudController<...> {
    // implementação
}
```

## 🚫 Migração Obrigatória

**Todos** os controllers que estendem `AbstractCrudController` devem usar `@ApiResource`. Não há anotação de exceção ou contorno - a migração é obrigatória para:

- ✅ **Aproveitamento total** dos benefícios da resolução automática de grupos
- ✅ **Consistência arquitetural** em todo o codebase  
- ✅ **Documentação OpenAPI otimizada** e organizada
- ✅ **Facilitar manutenção** futura do código

## 📊 Exemplo de Log de Validação

```
🔍 Iniciando validação de uso de @ApiResource em controllers AbstractCrud...
📊 Relatório de Conformidade @ApiResource:
   ✅ Conformes: 8/9 controllers
   ⚠️ Precisam migração: 1/9 controllers  

🚨 Controllers que precisam migrar para @ApiResource: TestNonCompliantController. 
Recomenda-se substituir @RestController + @RequestMapping por @ApiResource(ApiPaths.CONSTANT) 
para aproveitar os benefícios da resolução automática de grupos OpenAPI.

💡 Para desabilitar esta validação: praxis.openapi.validation.api-resource-required=IGNORE
💡 Para falhar o startup: praxis.openapi.validation.api-resource-required=FAIL
```

## 🛠️ Configuração Avançada

### Para Falhar o Startup em Ambiente de Produção

```properties
# application-prod.properties
praxis.openapi.validation.api-resource-required=FAIL
```

Isso garante que nenhum controller não conforme seja deployado em produção.

### Para Desenvolvimento Local Flexível

```properties
# application-dev.properties  
praxis.openapi.validation.api-resource-required=WARN
```

Permite desenvolvimento sem interrupção, mas com alertas educativos.

## 🔗 Ver Também

- [ApiResource.java](../../src/main/java/org/praxisplatform/uischema/annotation/ApiResource.java) - Anotação principal
- [DynamicSwaggerConfig.java](../../src/main/java/org/praxisplatform/uischema/configuration/DynamicSwaggerConfig.java) - Implementação da validação
- [ApiDocsController.java](../../src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java) - Resolução automática de grupos
- [AbstractCrudController.java](../../src/main/java/org/praxisplatform/uischema/controller/base/AbstractCrudController.java) - Controller base com validação

## ❓ Perguntas Frequentes

**P: Posso desabilitar a validação permanentemente?**
R: Sim, configure `praxis.openapi.validation.api-resource-required=IGNORE`, mas não é recomendado para projetos novos.

**P: O que acontece se eu usar FAIL e tiver controllers não conformes?**
R: A aplicação não iniciará e mostrará uma exceção detalhada indicando quais controllers precisam ser migrados.

**P: O que acontece se meu controller não tiver @RequestMapping nem @ApiResource?**
R: O AbstractCrudController emitirá um warning claro e definirá um path temporário problemático (`/CONFIGURACAO-PENDENTE/NomeController`) para evidenciar a necessidade de configuração adequada. Não há mais fallback automático baseado no nome da classe.

**P: Por que não há fallback automático baseado no nome da classe?**
R: Para ser explícito sobre a necessidade de configuração adequada em vez de tentar adivinhar um base path. É melhor alertar claramente o desenvolvedor sobre a configuração obrigatória do que criar paths automaticamente.

**P: Existe alguma forma de contornar a validação para controllers específicos?**
R: Não. A validação é obrigatória para todos os controllers que estendem AbstractCrudController. A única opção é desabilitar completamente a validação com `IGNORE`, mas isso não é recomendado para projetos novos.
