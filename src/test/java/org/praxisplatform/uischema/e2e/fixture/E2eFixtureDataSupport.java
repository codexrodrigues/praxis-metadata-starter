package org.praxisplatform.uischema.e2e.fixture;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class E2eFixtureDataSupport {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final PayrollViewRepository payrollViewRepository;

    public E2eFixtureDataSupport(
            DepartmentRepository departmentRepository,
            EmployeeRepository employeeRepository,
            PayrollViewRepository payrollViewRepository
    ) {
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.payrollViewRepository = payrollViewRepository;
    }

    @Transactional
    public SeedState resetDefaultData() {
        payrollViewRepository.deleteAll();
        employeeRepository.deleteAll();
        departmentRepository.deleteAll();

        DepartmentEntity hr = departmentRepository.save(new DepartmentEntity("Human Resources", "HR"));
        DepartmentEntity ops = departmentRepository.save(new DepartmentEntity("Operations", "OPS"));

        Map<String, Long> employeeIdsByName = new LinkedHashMap<>();
        employeeIdsByName.put("Alice", createEmployee("Alice", "HR-001", "Executive", "EXEC", "0-10%", EmployeeStatus.ACTIVE, new BigDecimal("7200.00"), LocalDate.of(2023, 1, 10), hr).getId());
        employeeIdsByName.put("Bob", createEmployee("Bob", "OPS-001", "Operational", "OPS", "0-10%", EmployeeStatus.ACTIVE, new BigDecimal("6800.00"), LocalDate.of(2023, 2, 15), ops).getId());
        employeeIdsByName.put("Carol", createEmployee("Carol", "HR-002", "Executive", "EXEC", "20%+", EmployeeStatus.INACTIVE, new BigDecimal("5300.00"), LocalDate.of(2022, 11, 5), hr).getId());
        employeeIdsByName.put("Diana", createEmployee("Diana", "OPS-002", "Lead", "LEAD", "10-20%", EmployeeStatus.ACTIVE, new BigDecimal("9100.00"), LocalDate.of(2021, 7, 20), ops).getId());
        employeeIdsByName.put("Eve", createEmployee("Eve", "HR-003", "Specialist", "SPEC", "10-20%", EmployeeStatus.ACTIVE, new BigDecimal("6050.00"), LocalDate.of(2024, 3, 3), hr).getId());
        employeeIdsByName.put("Frank", createEmployee("Frank", "OPS-003", "Operational", "OPS", "20%+", EmployeeStatus.LEAVE, new BigDecimal("4950.00"), LocalDate.of(2020, 9, 14), ops).getId());

        Map<String, Long> payrollIdsByEmployee = new LinkedHashMap<>();
        payrollIdsByEmployee.put("Alice", payrollViewRepository.save(new PayrollViewEntity("Alice", "Human Resources", new BigDecimal("6300.00"), "CLOSED")).getId());
        payrollIdsByEmployee.put("Bob", payrollViewRepository.save(new PayrollViewEntity("Bob", "Operations", new BigDecimal("5900.00"), "CLOSED")).getId());
        payrollIdsByEmployee.put("Carol", payrollViewRepository.save(new PayrollViewEntity("Carol", "Human Resources", new BigDecimal("4700.00"), "OPEN")).getId());
        payrollIdsByEmployee.put("Diana", payrollViewRepository.save(new PayrollViewEntity("Diana", "Operations", new BigDecimal("8010.00"), "CLOSED")).getId());

        return new SeedState(
                hr.getId(),
                ops.getId(),
                Map.copyOf(employeeIdsByName),
                Map.copyOf(payrollIdsByEmployee)
        );
    }

    public long employeeCount() {
        return employeeRepository.count();
    }

    public long payrollRowCount() {
        return payrollViewRepository.count();
    }

    private EmployeeEntity createEmployee(
            String nome,
            String matricula,
            String payrollProfileLabel,
            String payrollProfile,
            String faixaPctDesconto,
            EmployeeStatus status,
            BigDecimal salario,
            LocalDate admissionDate,
            DepartmentEntity department
    ) {
        EmployeeEntity entity = new EmployeeEntity();
        entity.setNome(nome);
        entity.setMatricula(matricula);
        entity.setPayrollProfileLabel(payrollProfileLabel);
        entity.setPayrollProfile(payrollProfile);
        entity.setFaixaPctDesconto(faixaPctDesconto);
        entity.setStatus(status);
        entity.setSalario(salario);
        entity.setAdmissionDate(admissionDate);
        entity.setDepartment(department);
        return employeeRepository.save(entity);
    }

    public record SeedState(
            Long humanResourcesDepartmentId,
            Long operationsDepartmentId,
            Map<String, Long> employeeIdsByName,
            Map<String, Long> payrollIdsByEmployee
    ) {
        public List<Long> employeeIdsInSeedOrder() {
            return List.copyOf(employeeIdsByName.values());
        }

        public List<Long> payrollIdsInSeedOrder() {
            return List.copyOf(payrollIdsByEmployee.values());
        }
    }
}
