package org.praxisplatform.uischema.e2e.fixture;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;

class DepartmentSummaryDTO {

    private Long id;
    private String nome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

class EmployeeResponseDTO {

    private Long id;
    private String nome;
    private String matricula;
    private String status;
    private BigDecimal salario;
    private LocalDate admissionDate;
    private Long departmentId;
    private String departmentNome;
    private DepartmentSummaryDTO department;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getMatricula() {
        return matricula;
    }

    public void setMatricula(String matricula) {
        this.matricula = matricula;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getSalario() {
        return salario;
    }

    public void setSalario(BigDecimal salario) {
        this.salario = salario;
    }

    public LocalDate getAdmissionDate() {
        return admissionDate;
    }

    public void setAdmissionDate(LocalDate admissionDate) {
        this.admissionDate = admissionDate;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public String getDepartmentNome() {
        return departmentNome;
    }

    public void setDepartmentNome(String departmentNome) {
        this.departmentNome = departmentNome;
    }

    public DepartmentSummaryDTO getDepartment() {
        return department;
    }

    public void setDepartment(DepartmentSummaryDTO department) {
        this.department = department;
    }
}

class CreateEmployeeDTO {

    @NotBlank
    private String nome;

    @NotBlank
    private String matricula;

    @NotNull
    private EmployeeStatus status;

    @NotNull
    private BigDecimal salario;

    @NotNull
    private LocalDate admissionDate;

    @NotNull
    private Long departmentId;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getMatricula() {
        return matricula;
    }

    public void setMatricula(String matricula) {
        this.matricula = matricula;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatus status) {
        this.status = status;
    }

    public BigDecimal getSalario() {
        return salario;
    }

    public void setSalario(BigDecimal salario) {
        this.salario = salario;
    }

    public LocalDate getAdmissionDate() {
        return admissionDate;
    }

    public void setAdmissionDate(LocalDate admissionDate) {
        this.admissionDate = admissionDate;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }
}

class UpdateEmployeeDTO extends CreateEmployeeDTO {
}

class UpdateEmployeeProfileDTO {

    @NotBlank
    private String nome;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

class ApproveEmployeeDTO {

    @NotBlank
    private String comentario;

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }
}

class BulkApproveEmployeesDTO {

    @NotEmpty
    private java.util.List<Long> employeeIds;

    @NotBlank
    private String comentario;

    public java.util.List<Long> getEmployeeIds() {
        return employeeIds;
    }

    public void setEmployeeIds(java.util.List<Long> employeeIds) {
        this.employeeIds = employeeIds;
    }

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }
}

class BulkApproveEmployeesResultDTO {

    private int approvedCount;
    private java.util.List<Long> approvedEmployeeIds;

    public int getApprovedCount() {
        return approvedCount;
    }

    public void setApprovedCount(int approvedCount) {
        this.approvedCount = approvedCount;
    }

    public java.util.List<Long> getApprovedEmployeeIds() {
        return approvedEmployeeIds;
    }

    public void setApprovedEmployeeIds(java.util.List<Long> approvedEmployeeIds) {
        this.approvedEmployeeIds = approvedEmployeeIds;
    }
}

class EmployeeFilterDTO implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.LIKE)
    private String nome;

    @Filterable(operation = Filterable.FilterOperation.EQUAL)
    private String payrollProfile;

    @Filterable(relation = "department.id")
    private Long departmentId;

    @Filterable
    private EmployeeStatus status;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getPayrollProfile() {
        return payrollProfile;
    }

    public void setPayrollProfile(String payrollProfile) {
        this.payrollProfile = payrollProfile;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatus status) {
        this.status = status;
    }
}

class DepartmentResponseDTO {

    private Long id;
    private String nome;
    private String code;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

class CreateDepartmentDTO {

    @NotBlank
    private String nome;

    @NotBlank
    private String code;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

class UpdateDepartmentDTO extends CreateDepartmentDTO {
}

class DepartmentFilterDTO implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.LIKE)
    private String nome;

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}

class PayrollViewResponseDTO {

    private Long id;
    private String employeeNome;
    private String departmentNome;
    private BigDecimal netAmount;
    private String payrollStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmployeeNome() {
        return employeeNome;
    }

    public void setEmployeeNome(String employeeNome) {
        this.employeeNome = employeeNome;
    }

    public String getDepartmentNome() {
        return departmentNome;
    }

    public void setDepartmentNome(String departmentNome) {
        this.departmentNome = departmentNome;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public String getPayrollStatus() {
        return payrollStatus;
    }

    public void setPayrollStatus(String payrollStatus) {
        this.payrollStatus = payrollStatus;
    }
}

class PayrollViewFilterDTO implements GenericFilterDTO {

    @Filterable(operation = Filterable.FilterOperation.LIKE)
    private String employeeNome;

    public String getEmployeeNome() {
        return employeeNome;
    }

    public void setEmployeeNome(String employeeNome) {
        this.employeeNome = employeeNome;
    }
}

