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

No baseline atual:

- `/schemas/filtered` e a superficie estrutural canonica consumida pelos runtimes
- `/schemas/catalog` e discovery documental; nao substitui o contrato estrutural

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
  <version>8.0.0-rc.174</version>
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
import org.praxisplatform.uischema.annotation.OptionLabel;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    private Long id;

    @DefaultSortColumn(priority = 1, ascending = true)
    private String name;
    private String email;
    private BigDecimal salary;
    private LocalDate hireDate;
    private Boolean active;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @OptionLabel
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

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
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

### DTOs (com semântica de domínio, UI, governança e filtros ricos)

```java
package com.example.hr.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.annotation.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(
    name = "EmployeeResponseDTO",
    description = "Visão cadastral e funcional do colaborador no domínio de RH, reunindo identificação, contato, remuneração base, data de admissão e situação operacional."
)
@ResourceIdentity(
    keyField = "id",
    titleField = "name",
    subtitleField = "email",
    metadataFields = {"hireDate"},
    displayLabelField = "name"
)
public class EmployeeResponseDTO {

    @Schema(description = "Identificador único do registro do colaborador no cadastro de RH.", example = "101")
    @UISchema(preset = UISchemaPreset.ENTERPRISE_ID, label = "ID", type = FieldDataType.NUMBER, numericFormat = NumericFormat.INTEGER, readOnly = true, formHidden = true, group = "Identificação", order = 1, icon = "tag")
    private Long id;

    @Schema(description = "Nome civil completo do colaborador utilizado em contratos, listagens operacionais e identificação funcional.", example = "Ana Beatriz Costa")
    @UISchema(preset = UISchemaPreset.ENTERPRISE_NAME, label = "Nome", group = "Identificação", order = 10, icon = "badge", quickViewVisible = true, quickViewOrder = 10, helpText = "Nome civil completo do colaborador.")
    private String name;

    @DomainGovernance(
        kind = DomainGovernanceKind.PRIVACY,
        classification = DomainClassification.CONFIDENTIAL,
        dataCategory = DomainDataCategory.PERSONAL,
        complianceTags = {"LGPD"},
        aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK, trainingUse = AiTrainingUseMode.DENY, ruleAuthoring = AiControlledUseMode.REVIEW_REQUIRED, reasoningUse = AiControlledUseMode.ALLOW),
        reason = "Canal de contato corporativo e pessoal do colaborador."
    )
    @Schema(description = "Endereço de e-mail corporativo do colaborador utilizado para comunicações oficiais e notificações.", example = "ana.costa@empresa.com.br")
    @UISchema(label = "E-mail", type = FieldDataType.EMAIL, controlType = FieldControlType.EMAIL_INPUT, group = "Identificação", order = 20, icon = "email", helpText = "Endereço de e-mail corporativo ativo.")
    private String email;

    @DomainGovernance(
        kind = DomainGovernanceKind.PRIVACY,
        classification = DomainClassification.CONFIDENTIAL,
        dataCategory = DomainDataCategory.FINANCIAL,
        complianceTags = {"LGPD", "INTERNAL_POLICY"},
        aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK, trainingUse = AiTrainingUseMode.DENY, ruleAuthoring = AiControlledUseMode.REVIEW_REQUIRED, reasoningUse = AiControlledUseMode.ALLOW),
        reason = "Remuneração contratual individual sujeita a sigilo salarial."
    )
    @Schema(description = "Remuneração mensal base vigente do colaborador em moeda corrente.", example = "12500.00")
    @UISchema(preset = UISchemaPreset.MONETARY_AMOUNT, label = "Salário Base", type = FieldDataType.NUMBER, controlType = FieldControlType.CURRENCY_INPUT, numericFormat = NumericFormat.CURRENCY, group = "Vínculo e Remuneração", order = 10, icon = "payments", helpText = "Valor bruto mensal contratado.")
    private BigDecimal salary;

    @Schema(description = "Data de início efetivo do vínculo do colaborador na organização.", example = "2024-03-15")
    @UISchema(label = "Data de Admissão", type = FieldDataType.DATE, controlType = FieldControlType.DATE_PICKER, numericFormat = NumericFormat.DATE, mask = "dd/MM/yyyy", group = "Vínculo e Remuneração", order = 20, icon = "event_available", quickViewVisible = true, quickViewOrder = 20, helpText = "Data oficial de admissão.")
    private LocalDate hireDate;

    @Schema(description = "Indicador de vínculo ativo do colaborador; registros inativos permanecem disponíveis para histórico e auditoria.", example = "true")
    @UISchema(preset = UISchemaPreset.BOOLEAN_FLAG, label = "Ativo", type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX, group = "Situação Operacional", order = 10, icon = "toggle_on", quickViewVisible = true, quickViewOrder = 30, helpText = "Indica se o colaborador está em exercício ativo.")
    private Boolean active;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public BigDecimal getSalary() { return salary; }
    public void setSalary(BigDecimal salary) { this.salary = salary; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
```

```java
package com.example.hr.employee.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties({"id"})
@Schema(name = "CreateEmployeeDTO", description = "Contrato de entrada para admissão e cadastro inicial de um novo colaborador no domínio de RH.")
public class CreateEmployeeDTO {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Nome civil completo do colaborador para registro contratual.", example = "Ana Beatriz Costa")
    @UISchema(preset = UISchemaPreset.ENTERPRISE_NAME, label = "Nome", required = true, maxLength = 200, group = "Identificação", order = 10, icon = "badge", helpText = "Informe o nome civil completo.")
    private String name;

    @NotBlank
    @Email
    @Size(max = 200)
    @Schema(description = "Endereço de e-mail corporativo atribuído ao colaborador no processo de admissão.", example = "ana.costa@empresa.com.br")
    @UISchema(label = "E-mail", type = FieldDataType.EMAIL, controlType = FieldControlType.EMAIL_INPUT, required = true, maxLength = 200, group = "Identificação", order = 20, icon = "email", helpText = "Informe o e-mail corporativo.")
    private String email;

    @NotNull
    @DecimalMin("0.00")
    @Schema(description = "Salário mensal bruto inicial definido na contratação.", example = "12500.00")
    @UISchema(preset = UISchemaPreset.MONETARY_AMOUNT, label = "Salário Base", type = FieldDataType.NUMBER, controlType = FieldControlType.CURRENCY_INPUT, numericFormat = NumericFormat.CURRENCY, required = true, min = "0.00", group = "Vínculo e Remuneração", order = 10, icon = "payments", helpText = "Informe a remuneração base mensal.")
    private BigDecimal salary;

    @NotNull
    @Schema(description = "Data oficial de início do vínculo contratual do colaborador.", example = "2024-03-15")
    @UISchema(label = "Data de Admissão", type = FieldDataType.DATE, controlType = FieldControlType.DATE_PICKER, numericFormat = NumericFormat.DATE, mask = "dd/MM/yyyy", required = true, group = "Vínculo e Remuneração", order = 20, icon = "event_available", helpText = "Selecione a data de admissão.")
    private LocalDate hireDate;

    @Schema(description = "Define se o registro já nasce ativo para operações imediatas.", example = "true")
    @UISchema(preset = UISchemaPreset.BOOLEAN_FLAG, label = "Ativo", type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX, group = "Situação Operacional", order = 10, icon = "toggle_on", helpText = "Marque para ativar o colaborador imediatamente.")
    private Boolean active = Boolean.TRUE;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public BigDecimal getSalary() { return salary; }
    public void setSalary(BigDecimal salary) { this.salary = salary; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
```

```java
package com.example.hr.employee.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties({"id"})
@Schema(name = "UpdateEmployeeDTO", description = "Contrato de atualização cadastral e contratual de um colaborador existente, preservando a identidade da rota.")
public class UpdateEmployeeDTO {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Nome civil completo atualizado do colaborador.", example = "Ana Beatriz Costa")
    @UISchema(preset = UISchemaPreset.ENTERPRISE_NAME, label = "Nome", required = true, maxLength = 200, group = "Identificação", order = 10, icon = "badge", helpText = "Atualize o nome civil completo se necessário.")
    private String name;

    @NotBlank
    @Email
    @Size(max = 200)
    @Schema(description = "Endereço de e-mail corporativo atualizado do colaborador.", example = "ana.costa@empresa.com.br")
    @UISchema(label = "E-mail", type = FieldDataType.EMAIL, controlType = FieldControlType.EMAIL_INPUT, required = true, maxLength = 200, group = "Identificação", order = 20, icon = "email", helpText = "Atualize o e-mail corporativo.")
    private String email;

    @NotNull
    @DecimalMin("0.00")
    @Schema(description = "Salário mensal base vigente após revisão ou enquadramento.", example = "13200.00")
    @UISchema(preset = UISchemaPreset.MONETARY_AMOUNT, label = "Salário Base", type = FieldDataType.NUMBER, controlType = FieldControlType.CURRENCY_INPUT, numericFormat = NumericFormat.CURRENCY, required = true, min = "0.00", group = "Vínculo e Remuneração", order = 10, icon = "payments", helpText = "Informe o salário base vigente.")
    private BigDecimal salary;

    @NotNull
    @Schema(description = "Data oficial de admissão registrada no vínculo.", example = "2024-03-15")
    @UISchema(label = "Data de Admissão", type = FieldDataType.DATE, controlType = FieldControlType.DATE_PICKER, numericFormat = NumericFormat.DATE, mask = "dd/MM/yyyy", required = true, group = "Vínculo e Remuneração", order = 20, icon = "event_available", helpText = "Data de início do vínculo.")
    private LocalDate hireDate;

    @Schema(description = "Indicador de vínculo ativo do colaborador no cadastro.", example = "true")
    @UISchema(preset = UISchemaPreset.BOOLEAN_FLAG, label = "Ativo", type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX, group = "Situação Operacional", order = 10, icon = "toggle_on", helpText = "Indica se o vínculo permanece ativo.")
    private Boolean active;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public BigDecimal getSalary() { return salary; }
    public void setSalary(BigDecimal salary) { this.salary = salary; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
```

```java
package com.example.hr.employee.dto.filter;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "EmployeeFilterDTO", description = "Critérios de busca avançada de colaboradores por identificação, situação cadastral, faixa salarial e janelas de admissão.")
public class EmployeeFilterDTO implements GenericFilterDTO {

    @Schema(description = "Trecho do nome civil do colaborador para busca textual parcial.")
    @UISchema(label = "Nome", controlType = FieldControlType.INPUT, order = 10, icon = "badge", helpText = "Digite parte do nome do colaborador.")
    @Filterable(operation = Filterable.FilterOperation.LIKE)
    private String name;

    @Schema(description = "Situação operacional do vínculo para restringir a listagem a ativos ou inativos.")
    @UISchema(label = "Ativo", type = FieldDataType.BOOLEAN, controlType = FieldControlType.CHECKBOX, order = 20, icon = "toggle_on", helpText = "Filtrar apenas colaboradores ativos ou inativos.")
    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private Boolean active;

    @Schema(description = "Faixa de remuneração base mensal (mínimo e máximo) para recortes orçamentários.")
    @UISchema(label = "Faixa Salarial", type = FieldDataType.NUMBER, controlType = FieldControlType.PRICE_RANGE, numericFormat = NumericFormat.CURRENCY, numericStep = "0.01", order = 30, icon = "payments", helpText = "Informe o salário mínimo e máximo.")
    @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "salary")
    private List<BigDecimal> salaryBetween;

    @Schema(description = "Intervalo de datas de admissão para análise de coortes de contratação.")
    @UISchema(label = "Período de Admissão", type = FieldDataType.DATE, controlType = FieldControlType.DATE_RANGE, order = 40, icon = "event", helpText = "Selecione a data inicial e final de admissão.")
    @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "hireDate")
    private List<LocalDate> hireDateRange;

    @Schema(description = "Quantidade de dias recentes para localizar colaboradores admitidos na última janela móvel.")
    @UISchema(label = "Admitidos nos últimos (dias)", type = FieldDataType.NUMBER, controlType = FieldControlType.INPUT, order = 50, icon = "history", helpText = "Ex.: 30 para admitidos nos últimos 30 dias.")
    @Filterable(operation = Filterable.FilterOperation.IN_LAST_DAYS, relation = "hireDate")
    private Integer hireDateLastDays;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public List<BigDecimal> getSalaryBetween() { return salaryBetween; }
    public void setSalaryBetween(List<BigDecimal> salaryBetween) { this.salaryBetween = salaryBetween; }
    public List<LocalDate> getHireDateRange() { return hireDateRange; }
    public void setHireDateRange(List<LocalDate> hireDateRange) { this.hireDateRange = hireDateRange; }
    public Integer getHireDateLastDays() { return hireDateLastDays; }
    public void setHireDateLastDays(Integer hireDateLastDays) { this.hireDateLastDays = hireDateLastDays; }
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
        dto.setSalary(entity.getSalary());
        dto.setHireDate(entity.getHireDate());
        dto.setActive(entity.getActive());
        return dto;
    }

    @Override
    public Employee newEntity(CreateEmployeeDTO dto) {
        Employee entity = new Employee();
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        entity.setSalary(dto.getSalary());
        entity.setHireDate(dto.getHireDate());
        entity.setActive(dto.getActive() != null ? dto.getActive() : Boolean.TRUE);
        return entity;
    }

    @Override
    public void applyUpdate(Employee entity, UpdateEmployeeDTO dto) {
        // Nunca sobrescreva entity.setId(...) nem chaves técnicas pertencentes à rota!
        entity.setName(dto.getName());
        entity.setEmail(dto.getEmail());
        entity.setSalary(dto.getSalary());
        entity.setHireDate(dto.getHireDate());
        entity.setActive(dto.getActive());
    }

    @Override
    public Long extractId(Employee entity) {
        return entity.getId();
    }
}
```

### Service canonico (com Analytics / `StatsFieldRegistry` e `getDatasetVersion()` ativos)

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
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class EmployeeService extends AbstractBaseResourceService<
        Employee,
        EmployeeResponseDTO,
        Long,
        EmployeeFilterDTO,
        CreateEmployeeDTO,
        UpdateEmployeeDTO> {

    private static final StatsFieldRegistry STATS_FIELDS = StatsFieldRegistry.builder()
            .categoricalGroupByBucket("active", "active")
            .temporalTimeSeriesField("hireDate", "hireDate")
            .numericHistogramMeasureField("salary", "salary")
            .build();

    private final EmployeeMapper mapper;

    public EmployeeService(EmployeeRepository repository, EmployeeMapper mapper) {
        super(repository, Employee.class);
        this.mapper = mapper;
    }

    @Override
    protected ResourceMapper<Employee, EmployeeResponseDTO, CreateEmployeeDTO, UpdateEmployeeDTO, Long> getResourceMapper() {
        return mapper;
    }

    @Override
    public Optional<String> getDatasetVersion() {
        return Optional.of("Employee:" + getRepository().count());
    }

    @Override
    public StatsFieldRegistry getStatsFieldRegistry() {
        return STATS_FIELDS;
    }

    @Override
    public StatsSupportMode getGroupByStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getTimeSeriesStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getDistributionStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }

    @Override
    public StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.AUTO;
    }
}
```

### Controller canonico (com `@ResourceIdentity` e `@QuickFilter`)

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
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.controller.base.AbstractResourceController;

@ApiResource(
    value = ApiPaths.HumanResources.EMPLOYEES,
    resourceKey = "human-resources.employees",
    title = "Colaboradores",
    description = "Gestão cadastral, remuneração base e situação operacional de colaboradores.",
    icon = "groups",
    visualTone = "human-resources",
    identity = @ResourceIdentity(
        keyField = "id",
        titleField = "name",
        subtitleField = "email",
        metadataFields = {"hireDate"},
        displayLabelField = "name"
    ),
    quickFilters = {
        @QuickFilter(id = "active", label = "Ativos", filter = "active=true", icon = "check_circle"),
        @QuickFilter(id = "inactive", label = "Inativos", filter = "active=false", icon = "remove_circle_outline")
    }
)
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

- `/schemas/filtered` continua sendo a fonte de verdade estrutural para request e response
- `/schemas/catalog` ajuda discovery e documentacao, mas nao substitui o schema canonico
- `/schemas/surfaces?resource=...` publica `list` e `detail` para qualquer controller canonico
- `create` e `edit` aparecem apenas em recursos mutaveis
- `/schemas/actions?resource=...` so existe quando houver `@WorkflowAction`
- sem workflow explicito, o esperado em `/schemas/actions?resource=...` e `404`
- `capabilities` agrega o que existir sem virar uma segunda fonte de verdade do schema

## Erros mais comuns de LLM ao criar uma aplicacao nova

- gerar `@RestController` junto com `@ApiResource`
- inverter a ordem dos parametros genericos entre `ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>` (5 tipos, `ID` em 5º), `AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` (6 tipos, `ID` em 3º) e `AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO>` (5 tipos, sem `E`, `ID` em 2º)
- usar DTO unico para tudo ou gerar DTOs sem `@Schema(description = ...)`, grupos, icones, `@UISchema(preset = ...)` e `@DomainGovernance`
- esquecer `implements GenericFilterDTO` ou deixar o `FilterDTO` sem `@Filterable` e `@UISchema`
- esquecer `@OptionLabel` na entidade (degradando `/options/filter` e `/options/by-ids`)
- esquecer `extends BaseCrudRepository<Entidade, ID>` no repository
- gerar `ResourceMapper` sem `extractId`, inverter `applyUpdate(E entity, UpdateDTO dto)` ou sobrescrever `entity.setId(...)` no update
- gerar `Service` sem sobrescrever `getResourceMapper()`, `getDatasetVersion()`, `getStatsFieldRegistry()` e `StatsSupportMode.AUTO`
- gerar `Controller` sem sobrescrever `getService()` e `getResponseId()`
- tratar `resourceKey` como copia cega do path
- gerar workflow generico sem `@WorkflowAction`

## Prompt recomendado para IA

```text
Voce esta criando uma nova aplicacao Spring Boot sobre o baseline atual do praxis-metadata-starter.

Gere uma aplicacao compilavel, completa e rica em metadados de negocio na primeira tentativa.

Use obrigatoriamente:
- @ApiResource(value=..., resourceKey=..., title=..., description=..., icon=..., identity=@ResourceIdentity(...), quickFilters={@QuickFilter(...)})
- @ApiGroup
- Entidade com @OptionLabel e @DefaultSortColumn
- BaseCrudRepository<E, ID>
- ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>
- AbstractBaseResourceService<E, ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> ou AbstractReadOnlyResourceService<E, ResponseDTO, ID, FilterDTO>
- AbstractResourceController<ResponseDTO, ID, FilterDTO, CreateDTO, UpdateDTO> ou AbstractReadOnlyResourceController<ResponseDTO, ID, FilterDTO>
- DTOs separados: ResponseDTO, CreateDTO, UpdateDTO, FilterDTO (com @Schema de dominio em PT-BR, @UISchema com grupos/icones/presets, @DomainGovernance e @Filterable rico)

Para recurso mutavel, gere nesta ordem:
1. ApiPaths
2. entidade (com @OptionLabel e @DefaultSortColumn)
3. repository (extends BaseCrudRepository<E, ID>)
4. DTOs (ResponseDTO, CreateDTO, UpdateDTO, FilterDTO)
5. mapper (implements ResourceMapper<E, ResponseDTO, CreateDTO, UpdateDTO, ID>)
6. service (com getResourceMapper(), getDatasetVersion(), getStatsFieldRegistry() e StatsSupportMode.AUTO)
7. controller (com getService() e getResponseId())

Regras obrigatorias:
- nao use @RestController quando houver @ApiResource
- nao gere classes fora do baseline resource-oriented atual
- FilterDTO deve implementar GenericFilterDTO e usar @Filterable (LIKE, EQUAL, IN, BETWEEN, IN_LAST_DAYS) + @UISchema
- Repository deve estender BaseCrudRepository<Entidade, ID>
- Service deve sobrescrever getResourceMapper(), getDatasetVersion(), getStatsFieldRegistry() e modos StatsSupportMode.AUTO
- Controller deve sobrescrever getService() e getResponseId()
- ResourceMapper deve implementar toResponse, newEntity, applyUpdate(E entity, UpdateDTO dto) (sem sobrescrever id) e extractId

Entrega esperada:
- arquivos completos
- imports corretos (org.praxisplatform.uischema.*)
- codigo compilavel na primeira tentativa
- um primeiro recurso metadata-driven com CRUD, filtros ricos, options, stats e capabilities ativos
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
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/architecture-overview.md`
