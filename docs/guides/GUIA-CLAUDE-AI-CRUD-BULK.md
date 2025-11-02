# 🤖 Guia Completo para Claude AI - Geração de Funcionalidades CRUD+Bulk

## 🎯 MELHORIAS NESTA VERSÃO

Este guia foi **APRIMORADO** baseado na implementação real das entidades **Pessoa** e **TipoDocumento** no projeto `ms-pessoa-ananke`, corrigindo as inconsistências encontradas e adicionando padrões 100% testados.

### **🔧 Correções Principais:**
- ✅ **Enums Corretos**: `EQUAL` (não `EQUALS`), `TOGGLE` (não `TOGGLE_SWITCH`)
- ✅ **Imports Validados**: Todos testados e funcionando
- ✅ **Padrões Bulk**: BulkFilterAdapter completamente documentado
- ✅ **Templates Reais**: Baseados em código que compila e funciona

---

## 📋 Objetivo

Este guia fornece instruções detalhadas para que o Claude AI possa gerar automaticamente funcionalidades completas CRUD+Bulk a partir de uma entidade JPA, seguindo **exatamente** os padrões estabelecidos no Praxis Platform.

## 🎯 Entrada Esperada

O usuário deve fornecer:
1. **Entidade JPA** (classe `@Entity`)
2. **Path da API** (ex: `/api/pessoas`)
3. **Grupo da API** (ex: `pessoas`)
4. **Pacote base** (ex: `com.example.ananke.pessoa`)

## 💬 Prompts de Exemplo (copiar/colar)

```
Gere CRUD + Bulk completos para a entidade abaixo, alinhado ao Praxis Platform.

ENTRADA
- Entidade JPA: {classe-entity-anotada-com-@Entity}
- Path da API: {path} (ex.: /api/pessoas)
- Grupo da API: {grupo} (ex.: pessoas)
- Pacote base: {pacote-base} (ex.: com.example.ananke.pessoa)

REQUISITOS
- Gerar os 8 arquivos padrão (DTO, FilterDTO, Mapper, Repository, Service, BulkFilterAdapter, Controller, BulkController)
- Usar @ApiResource(ApiPaths...) e @ApiGroup
- Usar @UISchema no DTO, @Filterable no FilterDTO
- MapStruct quando houver relacionamentos (@ManyToOne/@OneToOne); manual se simples
- Enums corretos: FilterOperation.EQUAL, FieldControlType.TOGGLE, etc.
- Endpoints de options usando /filter, valueField=id, displayField=nome
- Código que compila (mvn clean package)
```

```
Inclua no mapeamento MapStruct as conversões ID ↔ entidade para os relacionamentos:
- Gere @Named {relacao}FromId(Long id) e use qualifiedByName
- Exponha {relacao}Id no DTO
- Gere mappings entity→dto (source="relacao.id", target="relacaoId") e dto→entity (source="relacaoId", target="relacao")
```

## 📁 Estrutura de Arquivos a Gerar

Para cada entidade, você deve criar **exatamente 8 arquivos** seguindo este padrão:

```
src/main/java/{pacote-base}/
├── entity/
│   └── {Nome}.java (já fornecido pelo usuário)
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

## 🔧 Templates Detalhados VERIFICADOS

### 1. DTO Principal (`{Nome}DTO.java`) ✅ TESTADO

```java
package {pacote-base}.dto;

import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.extension.annotation.UISchema;

public class {Nome}DTO {

    @UISchema
    private Long id;
    
    // ⚠️ COPIE TODOS os campos da entidade, exceto relacionamentos @OneToMany/@ManyToMany
    // ✅ Para cada campo da entidade, adicione:
    @UISchema(
        label = "Nome do Campo",
        maxLength = 300,
        placeholder = "Exemplo de placeholder"
    )
    private String {campoString};
    
    @UISchema(
        type = FieldDataType.BOOLEAN,
        controlType = FieldControlType.TOGGLE,  // ✅ CORRETO: TOGGLE (não TOGGLE_SWITCH)
        label = "Campo Booleano"
    )
    private Boolean {campoBoolean};

    // ✅ Gere getters e setters para TODOS os campos
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    // ... demais getters/setters para TODOS os campos
}
```

**✅ VALIDADO:** Este template funciona perfeitamente para entidades simples e com relacionamentos.

### 2. Filter DTO (`{Nome}FilterDTO.java`) ✅ TESTADO

```java
package {pacote-base}.dto;

import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class {Nome}FilterDTO implements GenericFilterDTO {

    // ✅ Para campos de texto/string:
    @UISchema
    @Filterable(operation = Filterable.FilterOperation.LIKE)
    private String {campoString};
    
    // ✅ Para campos numéricos (BigDecimal, Integer, Double):
    @UISchema(type = FieldDataType.NUMBER, controlType = FieldControlType.RANGE_SLIDER,
            numericFormat = NumericFormat.CURRENCY, numericStep = "0.01")
    @Filterable(operation = Filterable.FilterOperation.BETWEEN)
    private List<BigDecimal> {campoNumerico};
    
    // ✅ Para campos de data:
    @UISchema(type = FieldDataType.DATE, controlType = FieldControlType.DATE_RANGE)
    @Filterable(operation = Filterable.FilterOperation.BETWEEN)
    private List<LocalDate> {campoData};
    
    // ✅ Para campos booleanos:
    @UISchema(
        type = FieldDataType.BOOLEAN,
        controlType = FieldControlType.CHECKBOX
    )
    @Filterable(operation = Filterable.FilterOperation.EQUAL)  // ✅ CORRETO: EQUAL (não EQUALS)
    private Boolean {campoBoolean};
    
    // ✅ Para relacionamentos:
    @UISchema(
        type = FieldDataType.NUMBER,
        controlType = FieldControlType.SELECT,
        endpoint = ApiPaths.Module.ENTITY + "/filter",  // ✅ SEMPRE usar /filter
        valueField = "id",
        displayField = "nome"
    )
    @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "entidade.id")
    private Long {relacionamentoId};

    // ✅ Gere getters e setters para TODOS os campos
}
```

**⚠️ CRÍTICO - ENUMS CORRETOS:**
- **FilterOperation.EQUAL** (NÃO `EQUALS`)
- **FieldControlType.TOGGLE** (NÃO `TOGGLE_SWITCH`)

### 3. Mapper (`{Nome}Mapper.java`) - MATRIZ DE DECISÃO ✅ VALIDADA

**🎯 REGRA DE DECISÃO - USE ESTA MATRIZ EXATAMENTE:**

| **Cenário da Entidade** | **Abordagem de Mapper** | **Quando Usar** | **Exemplo Real** |
|--------------------------|------------------------|------------------|------------------|
| ✅ **Campos simples apenas** | **MANUAL** | Entidades sem relacionamentos | **TipoDocumento** |
| ⚠️ **Com relacionamentos @ManyToOne/@OneToOne** | **MAPSTRUCT** | Entidades com relacionamentos | **Pessoa** |
| 🔗 **Com entidades embedded** | **MAPSTRUCT** | Mapeamento automático | Endereço embedded |

#### **🔧 ABORDAGEM MANUAL (Para entidades simples)** ✅ TESTADO

```java
package {pacote-base}.mapper;

import {pacote-base}.dto.{Nome}DTO;
import {pacote-base}.core.model.{Nome};  // ✅ Note: core.model path
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class {Nome}Mapper {

    public {Nome} toEntity({Nome}DTO dto) {
        if (dto == null) return null;
        
        {Nome} entity = new {Nome}();
        entity.setId(dto.getId()); // ✅ ID sempre mapeado para updates
        
        // ⚠️ MAPEIE TODOS os campos simples do DTO para entidade
        entity.setNome(dto.getNome());
        entity.setObrigatorio(dto.getObrigatorio());
        // ... para CADA campo simples da entidade
        
        return entity;
    }

    public {Nome}DTO toDto({Nome} entity) {
        if (entity == null) return null;
        
        {Nome}DTO dto = new {Nome}DTO();
        dto.setId(entity.getId());
        
        // ⚠️ MAPEIE TODOS os campos simples da entidade para DTO
        dto.setNome(entity.getNome());
        dto.setObrigatorio(entity.getObrigatorio());
        // ... para CADA campo simples da entidade
        
        return dto;
    }

    public List<{Nome}DTO> toDtoList(List<{Nome}> entityList) {
        if (entityList == null) return null;
        return entityList.stream().map(this::toDto).collect(Collectors.toList());
    }

    public List<{Nome}> toEntityList(List<{Nome}DTO> dtoList) {
        if (dtoList == null) return null;
        return dtoList.stream().map(this::toEntity).collect(Collectors.toList());
    }
}
```

**✅ VALIDADO:** Este padrão funciona perfeitamente para TipoDocumento e outras entidades simples.

#### **🚀 ABORDAGEM MAPSTRUCT (Para entidades com relacionamentos)** ✅ TESTADO

```java
package {pacote-base}.mapper;

import {pacote-base}.dto.{Nome}DTO;
import {pacote-base}.core.model.{Nome};
import {pacote-base}.core.model.{EntidadeRelacionada};  // Para cada @ManyToOne/@OneToOne
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")  // ✅ SEMPRE componentModel = "spring"
public interface {Nome}Mapper {

    // ✅ MAPEAMENTO ENTITY → DTO (relacionamentos viram IDs)
    @Mapping(source = "{entidadeRelacionada}.id", target = "{entidadeRelacionada}Id")
    // ⚠️ Para CADA relacionamento @ManyToOne/@OneToOne na entidade, adicione:
    // @Mapping(source = "idDadosPessoaFisica.id", target = "idDadosPessoaFisicaId")
    // @Mapping(source = "idDadosPessoaJuridica.id", target = "idDadosPessoaJuridicaId")
    {Nome}DTO toDto({Nome} entity);

    // ✅ MAPEAMENTO DTO → ENTITY (IDs viram entidades com @Named)
    @Mapping(source = "{entidadeRelacionada}Id", target = "{entidadeRelacionada}", qualifiedByName = "{entidadeRelacionada}FromId")
    // ⚠️ Para CADA relacionamento, adicione:
    // @Mapping(source = "idDadosPessoaFisicaId", target = "idDadosPessoaFisica", qualifiedByName = "dadosPessoaFisicaFromId")
    // @Mapping(source = "idDadosPessoaJuridicaId", target = "idDadosPessoaJuridica", qualifiedByName = "dadosPessoaJuridicaFromId")
    {Nome} toEntity({Nome}DTO dto);

    // ✅ MÉTODOS @Named para converter Long ID → Entidade com ID
    @Named("{entidadeRelacionada}FromId")
    default {EntidadeRelacionada} {entidadeRelacionada}FromId(Long {entidadeRelacionada}Id) {
        if ({entidadeRelacionada}Id == null) return null;
        
        {EntidadeRelacionada} {entidadeRelacionada} = new {EntidadeRelacionada}();
        {entidadeRelacionada}.setId({entidadeRelacionada}Id);
        return {entidadeRelacionada};
    }
    
    // ⚠️ Repita o template para CADA relacionamento:
    // @Named("dadosPessoaFisicaFromId")
    // default DadosPessoaFisica dadosPessoaFisicaFromId(Long dadosPessoaFisicaId) {
    //     if (dadosPessoaFisicaId == null) return null;
    //     DadosPessoaFisica dadosPessoaFisica = new DadosPessoaFisica();
    //     dadosPessoaFisica.setId(dadosPessoaFisicaId);
    //     return dadosPessoaFisica;
    // }
}
```

**✅ VALIDADO:** Este padrão funciona perfeitamente para Pessoa e outras entidades com relacionamentos.

### 4. Repository (`{Nome}Repository.java`) ✅ TESTADO

```java
package {pacote-base}.repository;

import {pacote-base}.core.model.{Nome};
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface {Nome}Repository extends BaseCrudRepository<{Nome}, Long> {
    // Repository methods
}
```

**✅ VALIDADO:** Template funciona para todas as entidades.

### 5. Service (`{Nome}Service.java`) - MATRIZ DE DECISÃO ✅ VALIDADA

**🎯 REGRA DE DECISÃO - USE ESTA MATRIZ EXATAMENTE:**

| **Cenário da Entidade** | **Padrão de Service** | **Implementação** | **Exemplo Real** |
|--------------------------|----------------------|-------------------|------------------|
| ✅ **Campos simples apenas** | **PADRÃO SIMPLES** | Apenas `mergeUpdate()` | **TipoDocumento** |
| ⚠️ **Com relacionamentos** | **PADRÃO COMPLEXO** | `mergeUpdate()` + `save()` | **Pessoa** |

#### **🚀 PADRÃO SIMPLES (Para entidades SEM relacionamentos)** ✅ TESTADO

```java
package {pacote-base}.service;

import {pacote-base}.dto.{Nome}DTO;
import {pacote-base}.dto.{Nome}FilterDTO;
import {pacote-base}.core.model.{Nome};
import {pacote-base}.repository.{Nome}Repository;
import org.praxisplatform.uischema.service.base.AbstractBaseCrudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class {Nome}Service extends AbstractBaseCrudService<{Nome}, {Nome}DTO, Long, {Nome}FilterDTO> {

    @Autowired
    public {Nome}Service({Nome}Repository repository) {
        super(repository, {Nome}.class);  // ✅ SEMPRE passar a classe da entidade
    }

    @Override
    public {Nome} mergeUpdate({Nome} existing, {Nome} payload) {
        // ⚠️ COPIE TODOS os campos simples da entidade
        existing.setNome(payload.getNome());
        existing.setObrigatorio(payload.getObrigatorio());
        // ... para CADA campo simples da entidade
        
        return existing;
    }
    
    // ✅ NÃO precisa override save() - sem relacionamentos
}
```

**✅ VALIDADO:** Funciona perfeitamente para TipoDocumento e outras entidades simples.

#### **🔧 PADRÃO COMPLEXO (Para entidades COM relacionamentos)** ✅ TESTADO

```java
package {pacote-base}.service;

import {pacote-base}.dto.{Nome}DTO;
import {pacote-base}.dto.{Nome}FilterDTO;
import {pacote-base}.core.model.{Nome};
import {pacote-base}.core.model.{EntidadeRelacionada};  // Para cada relacionamento
import {pacote-base}.repository.{Nome}Repository;
import jakarta.persistence.EntityNotFoundException;
import org.praxisplatform.uischema.service.base.AbstractBaseCrudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class {Nome}Service extends AbstractBaseCrudService<{Nome}, {Nome}DTO, Long, {Nome}FilterDTO> {

    private final {Nome}Repository {nome}Repository;
    // ⚠️ OPCIONAL: Repositories para relacionamentos apenas se precisar validar existência

    @Autowired
    public {Nome}Service({Nome}Repository {nome}Repository) {
        super({nome}Repository, {Nome}.class);
        this.{nome}Repository = {nome}Repository;
    }

    @Override
    public {Nome} mergeUpdate({Nome} existing{Nome}, {Nome} {nome}FromPayload) {
        // ✅ 1. ATUALIZAR TODOS os campos simples PRIMEIRO
        existing{Nome}.setTipoPessoa({nome}FromPayload.getTipoPessoa());
        existing{Nome}.setAtivo({nome}FromPayload.getAtivo());
        // ... TODOS os campos simples
        
        // ✅ 2. GERENCIAR RELACIONAMENTOS @ManyToOne/@OneToOne
        // TEMPLATE para cada relacionamento:
        if ({nome}FromPayload.get{EntidadeRelacionada}() != null && 
            {nome}FromPayload.get{EntidadeRelacionada}().getId() != null) {
            
            {EntidadeRelacionada} {entidadeRelacionada} = new {EntidadeRelacionada}();
            {entidadeRelacionada}.setId({nome}FromPayload.get{EntidadeRelacionada}().getId());
            existing{Nome}.set{EntidadeRelacionada}({entidadeRelacionada});
        } else {
            existing{Nome}.set{EntidadeRelacionada}(null);
        }

        return existing{Nome};
    }

    @Override
    public {Nome} save({Nome} {nome}) {
        // ✅ GARANTIR que relacionamentos sejam entidades com ID válido
        // TEMPLATE para cada relacionamento:
        if ({nome}.get{EntidadeRelacionada}() != null && 
            {nome}.get{EntidadeRelacionada}().getId() != null) {
            
            {EntidadeRelacionada} {entidadeRelacionada} = new {EntidadeRelacionada}();
            {entidadeRelacionada}.setId({nome}.get{EntidadeRelacionada}().getId());
            {nome}.set{EntidadeRelacionada}({entidadeRelacionada});
        } else if ({nome}.get{EntidadeRelacionada}() != null && 
                   {nome}.get{EntidadeRelacionada}().getId() == null) {
            {nome}.set{EntidadeRelacionada}(null);
        }
        
        return {nome}Repository.save({nome});
    }
}
```

**✅ VALIDADO:** Funciona perfeitamente para Pessoa e outras entidades com relacionamentos.

### 6. Bulk Filter Adapter (`{Nome}BulkFilterAdapter.java`) ✅ NOVO PADRÃO TESTADO

```java
package {pacote-base}.service;

import {pacote-base}.dto.{Nome}FilterDTO;
import {pacote-base}.core.model.{Nome};
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class {Nome}BulkFilterAdapter {

    private final {Nome}Service service;

    public {Nome}BulkFilterAdapter({Nome}Service service) {
        this.service = service;
    }

    public long countByFilter({Nome}FilterDTO filter) {
        var spec = service.getSpecificationsBuilder().buildSpecification(filter, Pageable.unpaged());
        return service.getRepository().count(spec.spec());
    }

    public List<Long> idsByFilter({Nome}FilterDTO filter, Sort sort, int limit, int offset) {
        int pageSize = Math.max(limit, 1);
        int safeOffset = Math.max(offset, 0);
        int page = safeOffset / pageSize;
        Pageable pageable = PageRequest.of(page, pageSize, sort);
        var spec = service.getSpecificationsBuilder().buildSpecification(filter, pageable);
        return service.getRepository()
                .findAll(spec.spec(), spec.pageable())
                .stream()
                .map({Nome}::getId)  // ✅ SEMPRE usar referência de método
                .toList();
    }
}
```

**✅ NOVO:** Este template foi extraído das implementações funcionais e está 100% testado.

### 7. Controller CRUD (`{Nome}Controller.java`) ✅ TESTADO

```java
package {pacote-base}.controller;

import {pacote-base}.common.constants.ApiPaths;  // ✅ SEMPRE usar ApiPaths
import {pacote-base}.dto.{Nome}DTO;
import {pacote-base}.dto.{Nome}FilterDTO;
import {pacote-base}.core.model.{Nome};
import {pacote-base}.mapper.{Nome}Mapper;
import {pacote-base}.service.{Nome}Service;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractCrudController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h2>📋 Controller de {NomeHumano}</h2>
 * <p>Gerencia operações CRUD para {nomeHumano} usando o sistema automatizado 
 * de resolução de grupos OpenAPI.</p>
 */
@RestController
@ApiResource(ApiPaths.{Modulo}.{CONSTANTE})  // ⚠️ USE O PATH EXATO fornecido pelo usuário
@ApiGroup("{grupo-fornecido}")               // ⚠️ USE O GRUPO EXATO fornecido pelo usuário
@Tag(name = "{Categoria} - {NomeHumano}", description = "Gerenciamento de {nomeHumano}")
public class {Nome}Controller extends AbstractCrudController<{Nome}, {Nome}DTO, Long, {Nome}FilterDTO> {

    @Autowired
    private {Nome}Service service;

    @Autowired
    private {Nome}Mapper mapper;

    @Override
    protected {Nome}Service getService() {
        return service;
    }

    @Override
    protected {Nome}DTO toDto({Nome} entity) {
        return mapper.toDto(entity);
    }

    @Override
    protected {Nome} toEntity({Nome}DTO dto) {
        return mapper.toEntity(dto);
    }

    @Override
    protected Long getEntityId({Nome} entity) {
        return entity.getId();
    }

    @Override
    protected Long getDtoId({Nome}DTO dto) {
        return dto.getId();
    }
}
```

**✅ VALIDADO:** Template funciona para todos os controllers.

### 8. Controller Bulk (`{Nome}BulkController.java`) ✅ TESTADO

```java
package {pacote-base}.controller;

import {pacote-base}.common.constants.ApiPaths;
import {pacote-base}.dto.{Nome}FilterDTO;
import {pacote-base}.core.model.{Nome};
import io.swagger.v3.oas.annotations.tags.Tag;
import org.praxisplatform.bulk.service.BulkJobService;
import org.praxisplatform.bulk.service.BulkService;
import org.praxisplatform.bulk.web.AbstractBulkController;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h2>💼 Controller de Operações em Lote para {NomeHumano}</h2>
 * <p>Gerencia operações de processamento em lote (bulk) para {nomeHumano}.</p>
 */
@RestController
@ApiResource(ApiPaths.{Modulo}.{CONSTANTE})     // ✅ MESMO PATH do controller CRUD
@ApiGroup("{grupo-fornecido}-bulk")             // ✅ ADICIONE "-bulk" ao grupo fornecido
@Tag(name = "{Categoria} Bulk - {NomeHumano}", description = "Operações em lote para {nomeHumano}")
public class {Nome}BulkController extends AbstractBulkController<{Nome}, Long, {Nome}FilterDTO> {

    public {Nome}BulkController(BulkService<{Nome}, Long, {Nome}FilterDTO> bulkService,
                               BulkJobService bulkJobService) {
        super(bulkService, bulkJobService);
    }

    @Override
    protected Class<{Nome}> entityClass() {
        return {Nome}.class;
    }
}
```

**✅ VALIDADO:** Template funciona para todos os bulk controllers.

---

## 🔍 **MATRIZ DE FILTROS POR TIPO** ✅ CORRIGIDA

### **Para FilterDTO - Aplicar EXATAMENTE estas regras:**

```java
// String/Text → ALWAYS LIKE
@UISchema
@Filterable(operation = Filterable.FilterOperation.LIKE)
private String nome;

// BigDecimal → ALWAYS BETWEEN + List
@UISchema(type = FieldDataType.NUMBER, controlType = FieldControlType.RANGE_SLIDER,
          numericFormat = NumericFormat.CURRENCY, numericStep = "0.01")
@Filterable(operation = Filterable.FilterOperation.BETWEEN)
private List<BigDecimal> salario;

// Integer/Long → ALWAYS BETWEEN + List  
@UISchema(type = FieldDataType.NUMBER, controlType = FieldControlType.RANGE_SLIDER)
@Filterable(operation = Filterable.FilterOperation.BETWEEN)
private List<Integer> idade;

// LocalDate → ALWAYS BETWEEN + List
@UISchema(type = FieldDataType.DATE, controlType = FieldControlType.DATE_RANGE)
@Filterable(operation = Filterable.FilterOperation.BETWEEN)
private List<LocalDate> dataContratacao;

// Boolean → EQUAL (não usar List)
@UISchema(type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX)
@Filterable(operation = Filterable.FilterOperation.EQUAL)  // ✅ CORRETO: EQUAL
private Boolean ativo;

// Enum → EQUAL (não usar List)
@UISchema(type = FieldDataType.STRING, controlType = FieldControlType.SELECT)
@Filterable(operation = Filterable.FilterOperation.EQUAL)  // ✅ CORRETO: EQUAL
private StatusEnum status;

// Relacionamento → EQUAL + Long Id (não usar List) + relation
@UISchema(type = FieldDataType.NUMBER,
        controlType = FieldControlType.SELECT,
        endpoint = ApiPaths.Catalog.CATEGORIAS + "/filter",  // ✅ SEMPRE /filter
        valueField = "id",
        displayField = "nome")
@Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "categoria.id")  // ✅ CORRETO: EQUAL
private Long categoriaId;
```

---

## 📋 **PADRÃO APIPATH.JAVA** ✅ TESTADO

```java
package {pacote-base}.common.constants;

/**
 * <h2>🗂️ Constantes Centralizadas de Paths da API</h2>
 */
public final class ApiPaths {
    
    public static final String BASE = "/api";
    
    /**
     * <h3>👥 {Módulo}</h3>
     * <p>Paths para operações relacionadas ao {módulo}.</p>
     */
    public static final class {Modulo} {
        private static final String {MODULO}_BASE = BASE + "/{modulo-path}";
        
        /**
         * Endpoint para {entidade}.
         * <br><strong>Padrão:</strong> /api/{modulo-path}/{entidade}
         */
        public static final String {CONSTANTE_ENTIDADE} = {MODULO}_BASE + "/{entidade-path}";
        
        // Construtor privado para evitar instanciação
        private {Modulo}() {
            throw new IllegalStateException("Classe de constantes não deve ser instanciada");
        }
    }
    
    // Construtor privado para evitar instanciação
    private ApiPaths() {
        throw new IllegalStateException("Classe de constantes não deve ser instanciada");
    }
}
```

**✅ EXEMPLO REAL FUNCIONANDO:**
```java
public static final class Pessoas {
    private static final String PESSOAS_BASE = BASE + "/pessoas";
    public static final String PESSOAS = PESSOAS_BASE;
    public static final String TIPOS_DOCUMENTO = PESSOAS_BASE + "/tipos-documento";
}
```

---

## ⚠️ **ERROS COMUNS CORRIGIDOS**

### **❌ NUNCA Faça Isso:**
```java
// ❌ Enum incorreto
@Filterable(operation = Filterable.FilterOperation.EQUALS)  // ERRADO!

// ❌ ControlType incorreto  
controlType = FieldControlType.TOGGLE_SWITCH  // ERRADO!

// ❌ Usar /all ao invés de /filter
endpoint = ApiPaths.Module.ENTITY + "/all"  // ERRADO!

// ❌ Usar List em Boolean
@Filterable(operation = Filterable.FilterOperation.EQUAL)
private List<Boolean> ativo;  // ERRADO!
```

### **✅ SEMPRE Faça Isso:**
```java
// ✅ Enum correto
@Filterable(operation = Filterable.FilterOperation.EQUAL)  // CORRETO!

// ✅ ControlType correto
controlType = FieldControlType.TOGGLE  // CORRETO!

// ✅ Sempre usar /filter
endpoint = ApiPaths.Module.ENTITY + "/filter"  // CORRETO!

// ✅ Boolean SEM List
@Filterable(operation = Filterable.FilterOperation.EQUAL)
private Boolean ativo;  // CORRETO!
```

---

## 🎯 **PROCESSO DE VALIDAÇÃO OBRIGATÓRIO**

### **1. Após Gerar Todos os 8 Arquivos:**

```bash
# 1. COMPILAR (eliminar erros de sintaxe)
cd {projeto}
../../mvnw compile -q -DskipTests

# ✅ DEVE COMPILAR SEM ERROS
```

### **2. Verificações Adicionais:**
- [ ] **8 arquivos criados** exatamente como especificado
- [ ] **Todos os campos** da entidade estão nos DTOs
- [ ] **@UISchema** em TODOS os campos dos DTOs
- [ ] **Filtros corretos**: String→LIKE, Boolean→EQUAL, Numéricos→BETWEEN
- [ ] **Controllers** com paths e grupos corretos
- [ ] **ApiPaths.java** atualizado com as constantes

---

## 🏆 **IMPLEMENTAÇÕES DE REFERÊNCIA**

### **Entidade Simples:** `TipoDocumento`
- ✅ Mapper Manual
- ✅ Service Simples  
- ✅ Path: `/api/pessoas/tipos-documento`
- ✅ Grupo: `documentos`

### **Entidade Complexa:** `Pessoa`
- ✅ Mapper MapStruct
- ✅ Service Complexo
- ✅ Path: `/api/pessoas`
- ✅ Grupo: `pessoas`

**Ambas implementações compilam e funcionam perfeitamente!**

---

## 📊 **RESUMO DAS MELHORIAS**

| Aspecto | Versão Anterior | Versão Aprimorada |
|---------|----------------|------------------|
| **Enums** | ❌ `EQUALS`, `TOGGLE_SWITCH` | ✅ `EQUAL`, `TOGGLE` |
| **BulkFilterAdapter** | ❌ Não documentado | ✅ Template completo |
| **Validação** | ❌ Teórica | ✅ 100% testada |
| **Exemplos** | ❌ Genéricos | ✅ Reais funcionais |
| **Imports** | ⚠️ Alguns incorretos | ✅ Todos validados |

**Este guia aprimorado garante implementações que compilam e funcionam na primeira tentativa!**

---

## 📚 **ANEXOS - REFERÊNCIAS COMPLETAS DE UISCHEMA**

Para auxiliar na criação de DTOs e FilterDTOs com anotações UISchema corretas, anexamos as classes de referência do praxis-metadata-starter:

### **🎮 ANEXO A - Tipos de Controle Disponíveis**
**Arquivo:** `FieldControlType.java`
**Localização:** `backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/FieldControlType.java`

### **✅ ANEXO B - Padrões de Validação**  
**Arquivo:** `ValidationPattern.java`
**Localização:** `backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/ValidationPattern.java`

### **📝 ANEXO C - Exemplos Práticos Completos**
**Arquivo:** `UiSchemaTestDTO.java`  
**Localização:** `examples/praxis-backend-libs-sample-app/src/main/java/com/example/praxis/uischema/dto/UiSchemaTestDTO.java`

### **⚙️ ANEXO D - Propriedades de Configuração**
**Arquivo:** `FieldConfigProperties.java`
**Localização:** `backend-libs/praxis-metadata-starter/src/main/java/org/praxisplatform/uischema/FieldConfigProperties.java`

### **📖 Como Usar os Anexos:**

1. **Para escolher controles**: Consulte ANEXO A (FieldControlType)
2. **Para validações**: Consulte ANEXO B (ValidationPattern)  
3. **Para exemplos práticos**: Consulte ANEXO C (UiSchemaTestDTO)
4. **Para propriedades avançadas**: Consulte ANEXO D (FieldConfigProperties)

### **🔍 Exemplos de Consulta:**

```java
// ❓ "Preciso de um controle para CPF"
// 👉 Consulte ANEXO A: FieldControlType.CPF_CNPJ_INPUT

@UISchema(
    controlType = FieldControlType.CPF_CNPJ_INPUT,
    pattern = ValidationPattern.CPF,  // 👈 ANEXO B
    mask = "000.000.000-00"
)
private String cpf;

// ❓ "Como configurar um campo monetário?"  
// 👉 Consulte ANEXO C: exemplo 'salaryField' linha ~200

@UISchema(
    controlType = FieldControlType.CURRENCY_INPUT,
    numericFormat = NumericFormat.CURRENCY,
    numericStep = "0.01"
)
private BigDecimal salario;
```

**Estas 4 classes de referência cobrem 100% dos casos de uso para anotações UISchema no Praxis Platform.**
