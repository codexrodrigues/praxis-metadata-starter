# Guia 01 - IA Backend - Aplicacao Nova com Core Canonico Atual

## Objetivo

Este guia existe para uma LLM conseguir gerar, com alta chance de sucesso, uma
nova aplicacao Spring Boot baseada no baseline atual do
`praxis-metadata-starter`.

A aplicacao nova deve nascer sobre:

- `AbstractResourceController`
- `AbstractReadOnlyResourceController`
- `AbstractBaseResourceService`
- `AbstractReadOnlyResourceService`
- `ResourceMapper`
- `@ApiResource(value = ..., resourceKey = ...)`

E deve publicar, no minimo:

- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

## O que uma LLM precisa receber como entrada

Se voce quer que a IA gere um servico novo com sucesso, entregue pelo menos:

1. pacote base da aplicacao
2. nome do modulo
3. nome da entidade
4. tipo do ID
5. `resourcePath`
6. `resourceKey`
7. grupo OpenAPI
8. campos minimos da entidade
9. se o recurso e mutavel ou read-only

Exemplo de entrada boa:

```text
Crie um servico novo Spring Boot com praxis-metadata-starter.

Entrada canonica:
- Base package: com.example.hr
- Modulo: employees
- Entidade: Employee
- ID: Long
- Resource path: /api/human-resources/employees
- Resource key: human-resources.employees
- Api group: human-resources
- Recurso mutavel: sim
- Campos:
  - id: Long
  - name: String
  - email: String
  - active: Boolean
```

Sem essa entrada minima, a LLM tende a errar:

- `resourceKey`
- nome de DTOs
- assinatura do service
- shape do mapper
- repositorio
- caminho de filtro

## Resultado esperado

Uma aplicacao nova deve sair com:

- `pom.xml` coerente
- classe principal Spring Boot
- `ApiPaths.java`
- um modulo inicial completo
- DTOs separados de `response`, `create`, `update` e `filter`
- controller e service compilaveis
- runtime metadata-driven ativo

## Dependencias minimas

Exemplo base:

```xml
<dependency>
  <groupId>io.github.codexrodrigues</groupId>
  <artifactId>praxis-metadata-starter</artifactId>
  <version>5.0.0-rc.2</version>
</dependency>

<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Observacoes:

- o host ainda precisa declarar banco, driver, Flyway e plugins de build quando aplicavel
- o starter nao substitui o setup do host Spring Boot

## Estrutura recomendada

```text
src/main/java/{base-package}/
|-- {App}Application.java
|-- constants/
|   `-- ApiPaths.java
`-- {modulo}/
    |-- controller/
    |-- dto/
    |   |-- {Resource}ResponseDTO.java
    |   |-- Create{Resource}DTO.java
    |   |-- Update{Resource}DTO.java
    |   `-- filter/
    |       `-- {Resource}FilterDTO.java
    |-- entity/
    |-- mapper/
    |   `-- {Resource}Mapper.java
    |-- repository/
    |   `-- {Resource}Repository.java
    `-- service/
        `-- {Resource}Service.java
```

## Ordem correta para a LLM gerar os arquivos

Se a IA gerar o controller primeiro, costuma faltar detalhe do resto. A ordem
mais segura e:

1. `ApiPaths.java`
2. entidade
3. repository
4. DTOs
5. mapper
6. service
7. controller
8. opcionalmente `@UiSurface`, `@ResourceIntent` e `@WorkflowAction`

## Como pensar `resourcePath` vs `resourceKey`

- `resourcePath`: URL operacional real do recurso
- `resourceKey`: identidade semantica estavel do recurso

Exemplo:

- `resourcePath = /api/human-resources/employees`
- `resourceKey = human-resources.employees`

Na plataforma Praxis, `resourceKey` alimenta:

- `GET /schemas/surfaces?resource={resourceKey}`
- `GET /schemas/actions?resource={resourceKey}`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Regra pratica:

- se a URL mudar, tente preservar o mesmo `resourceKey`
- so mude o `resourceKey` quando a semantica canonica do recurso mudar

## Baseline minimo compilavel

### `ApiPaths.java`

```java
package com.example.hr.constants;

public final class ApiPaths {

    private ApiPaths() {
    }

    public static final class HumanResources {
        public static final String EMPLOYEES = "/api/human-resources/employees";

        private HumanResources() {
        }
    }
}
```

### Entidade

```java
package com.example.hr.employee.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    private Long id;

    private String name;
    private String email;
    private Boolean active;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
```

### Repository

```java
package com.example.hr.employee.repository;

import com.example.hr.employee.entity.Employee;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;

public interface EmployeeRepository extends BaseCrudRepository<Employee, Long> {
}
```

### DTOs

```java
package com.example.hr.employee.dto;

import org.praxisplatform.uischema.extension.annotation.UISchema;

public class EmployeeResponseDTO {

    @UISchema(label = "ID", hidden = true)
    private Long id;

    @UISchema(label = "Nome")
    private String name;

    @UISchema(label = "E-mail")
    private String email;

    @UISchema(label = "Ativo")
    private Boolean active;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
```

```java
package com.example.hr.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.praxisplatform.uischema.extension.annotation.UISchema;

public class CreateEmployeeDTO {

    @NotBlank
    @UISchema(label = "Nome")
    private String name;

    @NotBlank
    @Email
    @UISchema(label = "E-mail")
    private String email;

    @UISchema(label = "Ativo")
    private Boolean active;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
```

```java
package com.example.hr.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.praxisplatform.uischema.extension.annotation.UISchema;

public class UpdateEmployeeDTO {

    @NotBlank
    @UISchema(label = "Nome")
    private String name;

    @NotBlank
    @Email
    @UISchema(label = "E-mail")
    private String email;

    @UISchema(label = "Ativo")
    private Boolean active;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
```

```java
package com.example.hr.employee.dto.filter;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

public class EmployeeFilterDTO implements GenericFilterDTO {

    private String name;
    private Boolean active;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
```

### Resource mapper

```java
package com.example.hr.employee.mapper;

import com.example.hr.employee.dto.CreateEmployeeDTO;
import com.example.hr.employee.dto.EmployeeResponseDTO;
import com.example.hr.employee.dto.UpdateEmployeeDTO;
import com.example.hr.employee.entity.Employee;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper implements ResourceMapper<
        Employee,
        EmployeeResponseDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO,
        Long> {

    @Override
    public EmployeeResponseDTO toResponse(Employee entity) {
        EmployeeResponseDTO dto = new EmployeeResponseDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setEmail(entity.getEmail());
        dto.setActive(entity.getActive());
        return dto;
    }

    @Override
    public Employee newEntity(CreateEmployeeDTO dto) {
        Employee entity = new Employee();
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        entity.setActive(dto.getActive());
        return entity;
    }

    @Override
    public void applyUpdate(Employee entity, UpdateEmployeeDTO dto) {
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        entity.setActive(dto.getActive());
    }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }
}
```

### Service canonico

```java
package com.example.hr.employee.service;

import com.example.hr.employee.dto.CreateEmployeeDTO;
import com.example.hr.employee.dto.EmployeeResponseDTO;
import com.example.hr.employee.dto.UpdateEmployeeDTO;
import com.example.hr.employee.dto.filter.EmployeeFilterDTO;
import com.example.hr.employee.entity.Employee;
import com.example.hr.employee.mapper.EmployeeMapper;
import com.example.hr.employee.repository.EmployeeRepository;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private final EmployeeMapper mapper;

    public EmployeeService(EmployeeRepository repository, EmployeeMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
    }
}
```

### Controller canonico

```java
package com.example.hr.employee.controller;

import com.example.hr.constants.ApiPaths;
import com.example.hr.employee.dto.CreateEmployeeDTO;
import com.example.hr.employee.dto.EmployeeResponseDTO;
import com.example.hr.employee.dto.UpdateEmployeeDTO;
import com.example.hr.employee.dto.filter.EmployeeFilterDTO;
import com.example.hr.employee.service.EmployeeService;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;

@ApiResource(value = ApiPaths.HumanResources.EMPLOYEES, resourceKey = "human-resources.employees")
@ApiGroup("human-resources")
public class EmployeeController extends AbstractResourceController<
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @Override
    protected EmployeeService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(EmployeeResponseDTO dto) {
        return dto.getId();
    }
}
```

## Variante read-only

Se o recurso for apenas leitura, troque:

- controller: `AbstractReadOnlyResourceController`
- service: `AbstractReadOnlyResourceService`

E nao gere:

- `CreateDTO`
- `UpdateDTO`
- endpoints de `POST`, `PUT`, `DELETE`

Service minimo:

```java
@Service
public class PayrollViewService extends AbstractReadOnlyResourceService<
        PayrollView,
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewMapper mapper;

    public PayrollViewService(PayrollViewRepository repository, PayrollViewMapper mapper) {
        super(repository, PayrollView.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<PayrollView, PayrollViewResponseDTO, ?, ?, Long> getResourceMapper() {
        return mapper;
    }
}
```

Controller minimo:

```java
@ApiResource(value = ApiPaths.HumanResources.PAYROLL_VIEW, resourceKey = "human-resources.payroll-view")
@ApiGroup("human-resources")
public class PayrollViewController extends AbstractReadOnlyResourceController<
        PayrollViewResponseDTO,
        Long,
        PayrollViewFilterDTO> {

    private final PayrollViewService service;

    public PayrollViewController(PayrollViewService service) {
        this.service = service;
    }

    @Override
    protected PayrollViewService getService() {
        return service;
    }

    @Override
    protected Long getResponseId(PayrollViewResponseDTO dto) {
        return dto.getId();
    }
}
```

## Discovery que a aplicacao nova deve publicar

No baseline atual, um host novo deve expor pelo menos:

- `/schemas/filtered`
- `/schemas/catalog`
- `/schemas/surfaces`
- `/schemas/actions`
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`

Regras importantes:

- `/schemas/surfaces?resource=...` publica `list` e `detail` para qualquer controller canonico
- `create` e `edit` aparecem apenas em recursos mutaveis
- `/schemas/actions?resource=...` so existe quando houver `@WorkflowAction`
- sem workflow explicito, o esperado em `/schemas/actions?resource=...` e `404`
- `capabilities` agrega o que existir sem virar uma segunda fonte de verdade do schema

## Erros mais comuns de LLM ao criar uma aplicacao nova

- gerar `@RestController` junto com `@ApiResource`
- usar DTO unico para tudo
- esquecer `implements GenericFilterDTO` no filtro
- esquecer `extends BaseCrudRepository<Entidade, ID>` no repository
- gerar `ResourceMapper` sem `extractId`
- gerar `Service` sem sobrescrever `getResourceMapper()`
- gerar `Controller` sem sobrescrever `getService()` e `getResponseId()`
- tratar `resourceKey` como copia cega do path
- gerar workflow generico sem `@WorkflowAction`

## Prompt recomendado para IA

```text
Voce esta criando uma nova aplicacao Spring Boot sobre o baseline atual do praxis-metadata-starter.

Gere uma aplicacao compilavel e completa.

Use obrigatoriamente:
- @ApiResource(value=..., resourceKey=...)
- @ApiGroup
- AbstractResourceController ou AbstractReadOnlyResourceController
- AbstractBaseResourceService ou AbstractReadOnlyResourceService
- BaseCrudRepository
- ResourceMapper
- DTOs separados: ResponseDTO, CreateDTO, UpdateDTO, FilterDTO

Para recurso mutavel, gere nesta ordem:
1. ApiPaths
2. entidade
3. repository
4. DTOs
5. mapper
6. service
7. controller

Regras obrigatorias:
- nao use @RestController quando houver @ApiResource
- nao gere classes do core legado removido
- FilterDTO deve implementar GenericFilterDTO
- Repository deve estender BaseCrudRepository<Entidade, ID>
- Service deve sobrescrever getResourceMapper()
- Controller deve sobrescrever getService() e getResponseId()
- ResourceMapper deve implementar toResponse, newEntity, applyUpdate e extractId

Entrega esperada:
- arquivos completos
- imports corretos
- codigo compilavel
- um primeiro recurso metadata-driven funcional
```

## Checklist minimo

Antes de concluir a aplicacao nova:

- `mvn test`
- `GET /v3/api-docs/{grupo}`
- `GET /schemas/filtered`
- `GET /schemas/catalog`
- `GET /schemas/surfaces?resource={resourceKey}`
- `GET /schemas/actions?resource={resourceKey}` se houver workflow
- `GET /{resource}/capabilities`
- `GET /{resource}/{id}/capabilities`
- `@Valid` funcionando com erro `400`
- controller, service, mapper e repository compilando sem stubs

## Referencias

- `docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
- `docs/guides/GUIA-03-MIGRACAO-CONSUMIDOR-PILOTO.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/architecture-overview.md`
