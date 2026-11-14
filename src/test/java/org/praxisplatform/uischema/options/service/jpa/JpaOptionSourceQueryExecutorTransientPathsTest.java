package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupCapabilities;
import org.praxisplatform.uischema.options.LookupDetailDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayFieldDescriptor;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.LookupSortOption;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JpaOptionSourceQueryExecutorTransientPathsTest {

    @Entity(name = "TransientPathDepartment")
    static class DepartmentEntity {
        @Id
        Long id;
        String name;

        DepartmentEntity() {
        }

        DepartmentEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity(name = "TransientPathEmployee")
    static class EmployeeEntity {
        @Id
        Long id;
        String matricula;
        String nome;
        String situacao;

        @ManyToOne
        @JoinColumn(name = "department_id")
        DepartmentEntity department;

        @Transient
        String cargoCalculadoDto;

        EmployeeEntity() {
        }

        EmployeeEntity(Long id, String matricula, String nome, String situacao, DepartmentEntity department) {
            this.id = id;
            this.matricula = matricula;
            this.nome = nome;
            this.situacao = situacao;
            this.department = department;
        }
    }

    static StandardServiceRegistry registry;
    static EntityManagerFactory factory;
    final JpaOptionSourceQueryExecutor executor = new JpaOptionSourceQueryExecutor();

    @BeforeAll
    static void start() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:option-source-transient-paths;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        factory = new MetadataSources(registry)
                .addAnnotatedClass(DepartmentEntity.class)
                .addAnnotatedClass(EmployeeEntity.class)
                .buildMetadata()
                .buildSessionFactory();

        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var eng = new DepartmentEntity(10L, "Engenharia");
            var rh = new DepartmentEntity(20L, "Recursos Humanos");
            em.persist(eng);
            em.persist(rh);

            em.persist(new EmployeeEntity(1L, "MAT-001", "Ana Beatriz", "ATIVO", eng));
            em.persist(new EmployeeEntity(2L, "MAT-002", "Bruno Costa", "AFASTADO", rh));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresNonJpaAndTransientDtoPathsDuringFilterAndByIdsExecution() {
        LookupSelectionPolicy selectionPolicy = new LookupSelectionPolicy(
                "selecionavelTransiente",
                null,
                List.of(),
                List.of("INATIVO"),
                true,
                null,
                null
        );
        LookupDisplayDescriptor display = new LookupDisplayDescriptor(
                "directory",
                "form",
                "comfortable",
                "inline",
                "list",
                "nome",
                List.of(
                        new LookupDisplayFieldDescriptor("instituidorNome", "instituidorNome", "Instituidor", "person", null, null, null),
                        new LookupDisplayFieldDescriptor("matricula", "matricula", "Matrícula", "badge", null, null, null),
                        new LookupDisplayFieldDescriptor("departmentName", "department.name", "Lotação", "domain", null, null, null),
                        new LookupDisplayFieldDescriptor("cargoCalculadoDto", "cargoCalculadoDto", "Cargo Calculado", "work", null, null, null)
                ),
                List.of("vinculoMatricula", "department.name"),
                List.of("badgeTransiente", "situacao"),
                null,
                false,
                true,
                true,
                true,
                true,
                true,
                1
        );
        LookupFilteringDescriptor filtering = new LookupFilteringDescriptor(
                List.of(),
                Map.of(),
                List.of(
                        new LookupSortOption("transientSort", "instituidorNome", "asc", null),
                        new LookupSortOption("matricula", "matricula", "asc", null)
                ),
                "transientSort",
                List.of(),
                null
        );
        EntityLookupDescriptor lookup = new EntityLookupDescriptor(
                "employees",
                "matricula",
                List.of("vinculoMatricula", "department.name", "cargoCalculadoDto"),
                "situacao",
                "bloqueadoTransiente",
                "motivoBloqueioTransiente",
                List.of("servidorNomeFantasma", "department.name", "cargoCalculadoDto", "matricula"),
                Map.of(),
                selectionPolicy,
                LookupCapabilities.defaults(),
                new LookupDetailDescriptor(null, null, null),
                display,
                filtering
        );
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "employees-lookup",
                OptionSourceType.RESOURCE_ENTITY,
                "/employees",
                "employeeId",
                "id",
                "nome",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> page = executor.filterOptions(
                    em,
                    EmployeeEntity.class,
                    null,
                    null,
                    descriptor,
                    "Engenharia",
                    List.of(),
                    null,
                    PageRequest.of(0, 10),
                    null
            );

            assertThat(page.getContent()).hasSize(1);
            OptionDTO<Object> ana = page.getContent().get(0);
            assertThat(ana.id()).isEqualTo(1L);
            assertThat(ana.label()).isEqualTo("Ana Beatriz");
            assertThat(ana.extra()).containsEntry("code", "MAT-001");
            assertThat(ana.extra()).containsEntry("description", "Engenharia");
            assertThat(ana.extra()).containsEntry("status", "ATIVO");
            assertThat(ana.extra()).containsEntry("selectable", true);
            assertThat((List<String>) ana.extra().get("badges")).containsExactly("ATIVO");
            List<Map<String, Object>> richFields = (List<Map<String, Object>>) ana.extra().get("richFields");
            assertThat(richFields)
                    .extracting(field -> (String) field.get("key"))
                    .containsExactly("matricula", "departmentName");

            List<OptionDTO<Object>> byIds = executor.byIdsOptions(em, EmployeeEntity.class, descriptor, List.of(2L, 1L));
            assertThat(byIds).extracting(OptionDTO::id).containsExactly(2L, 1L);
            assertThat(byIds).extracting(OptionDTO::label).containsExactly("Bruno Costa", "Ana Beatriz");
            assertThat(byIds.get(0).extra()).containsEntry("description", "Recursos Humanos");
        }
    }

    @Test
    void fallsBackToLabelPathWhenAllSearchPathsAndOptionalCodeStatusAreNonJpa() {
        EntityLookupDescriptor lookup = new EntityLookupDescriptor(
                "employees-transient-only",
                "codigoTransiente",
                List.of("descricaoTransiente", "department.inexistente"),
                "statusTransiente",
                null,
                null,
                List.of("servidorNomeFantasma", "vinculoMatricula"),
                Map.of(),
                null,
                LookupCapabilities.defaults(),
                null,
                null,
                null
        );
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "employees-transient-fallback",
                OptionSourceType.RESOURCE_ENTITY,
                "/employees",
                "employeeId",
                "id",
                "nome",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> page = executor.filterOptions(
                    em,
                    EmployeeEntity.class,
                    null,
                    null,
                    descriptor,
                    "Bruno",
                    List.of(),
                    null,
                    PageRequest.of(0, 10),
                    null
            );

            assertThat(page.getContent()).hasSize(1);
            OptionDTO<Object> bruno = page.getContent().get(0);
            assertThat(bruno.id()).isEqualTo(2L);
            assertThat(bruno.label()).isEqualTo("Bruno Costa");
            assertThat(bruno.extra()).doesNotContainKey("code");
            assertThat(bruno.extra()).doesNotContainKey("description");
            assertThat(bruno.extra()).doesNotContainKey("status");
        }
    }
}
