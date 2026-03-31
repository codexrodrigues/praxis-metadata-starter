package org.praxisplatform.uischema.e2e.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.math.BigDecimal;
import java.time.LocalDate;

enum EmployeeStatus {
    ACTIVE,
    INACTIVE,
    LEAVE
}

@Entity
@Table(name = "e2e_departments")
class DepartmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DefaultSortColumn(priority = 1)
    private String nome;

    private String code;

    DepartmentEntity() {
    }

    DepartmentEntity(String nome, String code) {
        this.nome = nome;
        this.code = code;
    }

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

@Entity
@Table(name = "e2e_employees")
class EmployeeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DefaultSortColumn(priority = 1)
    private String nome;

    private String matricula;

    private String payrollProfileLabel;

    private String payrollProfile;

    private String faixaPctDesconto;

    @Enumerated(EnumType.STRING)
    private EmployeeStatus status;

    private BigDecimal salario;

    private LocalDate admissionDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private DepartmentEntity department;

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

    public String getPayrollProfileLabel() {
        return payrollProfileLabel;
    }

    public void setPayrollProfileLabel(String payrollProfileLabel) {
        this.payrollProfileLabel = payrollProfileLabel;
    }

    public String getPayrollProfile() {
        return payrollProfile;
    }

    public void setPayrollProfile(String payrollProfile) {
        this.payrollProfile = payrollProfile;
    }

    public String getFaixaPctDesconto() {
        return faixaPctDesconto;
    }

    public void setFaixaPctDesconto(String faixaPctDesconto) {
        this.faixaPctDesconto = faixaPctDesconto;
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

    public DepartmentEntity getDepartment() {
        return department;
    }

    public void setDepartment(DepartmentEntity department) {
        this.department = department;
    }
}

@Entity
@Table(name = "e2e_payroll_view")
class PayrollViewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DefaultSortColumn(priority = 1)
    private String employeeNome;

    private String departmentNome;

    private BigDecimal netAmount;

    private String payrollStatus;

    PayrollViewEntity() {
    }

    PayrollViewEntity(String employeeNome, String departmentNome, BigDecimal netAmount, String payrollStatus) {
        this.employeeNome = employeeNome;
        this.departmentNome = departmentNome;
        this.netAmount = netAmount;
        this.payrollStatus = payrollStatus;
    }

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

interface DepartmentRepository extends BaseCrudRepository<DepartmentEntity, Long>, JpaSpecificationExecutor<DepartmentEntity> {
}

interface EmployeeRepository extends BaseCrudRepository<EmployeeEntity, Long>, JpaSpecificationExecutor<EmployeeEntity> {
}

interface PayrollViewRepository extends BaseCrudRepository<PayrollViewEntity, Long>, JpaSpecificationExecutor<PayrollViewEntity> {
}
