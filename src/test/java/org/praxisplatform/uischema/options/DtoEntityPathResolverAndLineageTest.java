package org.praxisplatform.uischema.options;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.DomainLineageTransformation;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DtoEntityPathResolverAndLineageTest {

    enum AssignmentStatus implements PraxisUiOptionEnum {
        ACTIVE("Ativo", true, "check_circle", QuickFilterTone.SUCCESS),
        CLOSED("Encerrado", false, "block", QuickFilterTone.DANGER);

        private final String label;
        private final boolean selectable;
        private final String icon;
        private final QuickFilterTone tone;

        AssignmentStatus(String label, boolean selectable, String icon, QuickFilterTone tone) {
            this.label = label;
            this.selectable = selectable;
            this.icon = icon;
            this.tone = tone;
        }

        @Override public String value() { return name(); }
        @Override public String label() { return label; }
        @Override public boolean selectableInLookup() { return selectable; }
        @Override public String icon() { return icon; }
        @Override public QuickFilterTone tone() { return tone; }
    }

    @Embeddable
    static class EmbeddedAddress {
        String city;
    }

    @Embeddable
    static class CompositeKey implements Serializable {
        @Column(name = "company_id")
        Long companyId;
        Long seq;
    }

    @Entity
    static class CompanyEntity {
        @Id
        Long id;
        String legalName;
    }

    @Entity
    static class DepartmentEntity {
        @Id
        Long id;
        String code;
        String name;

        @ManyToOne
        @JoinColumn(name = "parent_id")
        DepartmentEntity parent;
    }

    @Entity
    static class EmployeeEntity {
        @Id
        Long id;
        String fullName;
    }

    @Entity
    static class AssignmentEntity {
        @EmbeddedId
        CompositeKey id;

        String internalCode;
        String title;
        AssignmentStatus status;

        @Embedded
        EmbeddedAddress address;

        @Column(name = "backup_dept_id")
        Long backupDeptId;

        @ManyToOne
        @JoinColumn(name = "employee_id")
        EmployeeEntity employee;

        @ManyToOne
        @JoinColumn(name = "department_id")
        DepartmentEntity primaryDepartment;

        @ManyToOne
        @JoinColumn(name = "assigned_department_id")
        DepartmentEntity assignedDepartment;

        @ManyToOne
        @JoinColumn(name = "company_id", insertable = false, updatable = false)
        CompanyEntity company;

        @ManyToOne
        @JoinColumns({
                @JoinColumn(name = "shared_tenant_id", referencedColumnName = "id"),
                @JoinColumn(name = "primary_unit_id", referencedColumnName = "id")
        })
        DepartmentEntity sharedPrimaryUnit;

        @ManyToOne
        @JoinColumns({
                @JoinColumn(name = "shared_tenant_id", referencedColumnName = "id"),
                @JoinColumn(name = "secondary_unit_id", referencedColumnName = "id")
        })
        DepartmentEntity sharedSecondaryUnit;

        @OneToMany
        List<DepartmentEntity> history;

        @Transient
        String computedSummary;
    }

    static class ContactDto {
        @UISchema(label = "Corporate Email", icon = "mail")
        private String email;
    }

    @ResourceIdentity(
            keyField = "id",
            titleField = "title",
            subtitleField = "businessCode",
            metadataFields = {"employeeName", "departmentName", "assignedDepartmentName", "cityLabel"}
    )
    static class ValidAssignmentDto {
        private CompositeKey id;

        @UISchema(label = "Code", icon = "badge", preset = UISchemaPreset.ENTERPRISE_CODE)
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"internalCode"}, transformation = DomainLineageTransformation.ALIAS)
        private String businessCode;

        @UISchema(label = "Title", icon = "work")
        @ResourceReference(resourceKey = "workforce.assignments", valueField = "id", surfaceId = "detail")
        private String title;

        private Long employeeId;

        @UISchema(label = "Employee", icon = "person")
        @ResourceReference(resourceKey = "workforce.employees", valueField = "employeeId", surfaceId = "detail")
        @DomainLineage(sourceClass = Void.class, sourceResourceKey = "workforce.employees", sourcePaths = {"fullName"})
        private String employeeName;

        private Long departmentId;

        @UISchema(label = "Primary Department", icon = "apartment")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "departmentId", surfaceId = "detail")
        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"}, transformation = DomainLineageTransformation.ALIAS)
        private String departmentName;

        private Long assignedDepartmentId;

        @UISchema(label = "Assigned Department", icon = "swap_horiz")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "assignedDepartmentId", surfaceId = "detail")
        @DomainLineage(sourceClass = Void.class, sourceResourceKey = "workforce.departments", sourcePaths = {"name"})
        private String assignedDepartmentName;

        @UISchema(label = "City", icon = "location_on")
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"address.city"}, transformation = DomainLineageTransformation.ALIAS)
        private String cityLabel;

        @ResourceState
        @UISchema(label = "Status", icon = "verified")
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"status"}, transformation = DomainLineageTransformation.ALIAS)
        private AssignmentStatus operationalStatus;

        private ContactDto contact;
        private List<String> tags;
    }

    @ResourceIdentity(keyField = "id", titleField = "name")
    static class SelfReferentialDepartmentDto {
        private Long id;

        @UISchema(label = "Name", icon = "apartment")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "id", surfaceId = "detail")
        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        private String name;

        private Long parentId;

        @UISchema(label = "Parent Department", icon = "account_tree")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "parentId", surfaceId = "detail")
        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        private String parentName;
    }

    static class CompositeFkDto {
        @DomainLineage(sourceClass = CompanyEntity.class, sourcePaths = {"legalName"})
        @ResourceReference(resourceKey = "workforce.companies", valueField = "id.companyId", surfaceId = "detail")
        private String companyName;
    }

    static class NegativeCasesDto {
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"title"}, transformation = DomainLineageTransformation.DERIVED)
        private String derivedField;

        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"title"}, transformation = DomainLineageTransformation.AGGREGATED)
        private String aggregatedField;

        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"internalCode", "title"})
        private String multiSourceField;

        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        private String ambiguousDepartmentName;

        private Long backupDeptId;

        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        @ResourceReference(resourceKey = "workforce.departments", valueField = "backupDeptId", surfaceId = "detail")
        private String scalarFkOnlyDeptName;

        private Long sharedTenantId;

        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        @ResourceReference(resourceKey = "workforce.departments", valueField = "sharedTenantId", surfaceId = "detail")
        private String sharedColumnAmbiguousDeptName;

        private String computedSummary;

        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"history.name"})
        private String pluralCollectionPath;

        @DomainLineage(sourceClass = Void.class, sourceResourceKey = "workforce.departments", sourcePaths = {"name"})
        private String foreignKeyWithoutValueField;
    }

    @Test
    void supportsDottedSubDtoNavigationAndExplicitEntityOverload() {
        LookupDisplayFieldDescriptor flatSubDto = LookupDisplayFieldDescriptor.fromDtoField(ValidAssignmentDto.class, "contact.email");
        assertThat(flatSubDto.key()).isEqualTo("contact.email");
        assertThat(flatSubDto.propertyPath()).isEqualTo("contact.email");
        assertThat(flatSubDto.label()).isEqualTo("Corporate Email");
        assertThat(flatSubDto.icon()).isEqualTo("mail");

        LookupDisplayFieldDescriptor mappedSubDto = LookupDisplayFieldDescriptor.fromDtoField(
                ValidAssignmentDto.class, "contact.email", "contactInfo.emailAddress");
        assertThat(mappedSubDto.key()).isEqualTo("contact.email");
        assertThat(mappedSubDto.propertyPath()).isEqualTo("contactInfo.emailAddress");
        assertThat(mappedSubDto.label()).isEqualTo("Corporate Email");

        Map<String, String> orderedMappings = new java.util.LinkedHashMap<>();
        orderedMappings.put("businessCode", "internalCode");
        orderedMappings.put("contact.email", "contactInfo.emailAddress");
        List<LookupDisplayFieldDescriptor> fields = LookupDisplayDescriptor.fieldsFromDto(
                ValidAssignmentDto.class,
                orderedMappings
        );
        assertThat(fields).extracting(LookupDisplayFieldDescriptor::key)
                .containsExactly("businessCode", "contact.email");
        assertThat(fields).extracting(LookupDisplayFieldDescriptor::propertyPath)
                .containsExactly("internalCode", "contactInfo.emailAddress");

        assertThatThrownBy(() -> LookupDisplayFieldDescriptor.fromDtoField(ValidAssignmentDto.class, "contact."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupDisplayFieldDescriptor.fromDtoField(ValidAssignmentDto.class, "contact..email"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupDisplayFieldDescriptor.fromDtoField(ValidAssignmentDto.class, "tags.value"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupDisplayFieldDescriptor.fromDtoField(ValidAssignmentDto.class, "contact.missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directoryBuilderFromDtoWithEntityClassResolvesLineageAndPreservesDtoWireKeys() {
        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.assignments", ValidAssignmentDto.class, AssignmentEntity.class)
                .build();

        assertThat(lookup.codePropertyPath()).isEqualTo("internalCode");
        assertThat(lookup.descriptionPropertyPaths())
                .containsExactly("title", "employee.fullName", "primaryDepartment.name", "assignedDepartment.name", "address.city");
        assertThat(lookup.statusPropertyPath()).isEqualTo("status");
        assertThat(lookup.selectionPolicy().statusPropertyPath()).isEqualTo("status");
        assertThat(lookup.searchPropertyPaths())
                .containsExactly("internalCode", "title", "employee.fullName", "primaryDepartment.name", "assignedDepartment.name", "address.city");
        assertThat(lookup.display().primaryPropertyPath()).isEqualTo("title");
        assertThat(lookup.display().fields())
                .extracting(LookupDisplayFieldDescriptor::key)
                .containsExactly("businessCode", "title", "employeeName", "departmentName", "assignedDepartmentName", "cityLabel", "operationalStatus");
        assertThat(lookup.display().fields())
                .extracting(LookupDisplayFieldDescriptor::propertyPath)
                .containsExactly("internalCode", "title", "employee.fullName", "primaryDepartment.name", "assignedDepartment.name", "address.city", "status");
        assertThat(lookup.filtering().sortOptions())
                .extracting(LookupSortOption::field)
                .containsExactly("internalCode", "title");
    }

    @Test
    void resolvesSelfReferentialAndCompositeIdFkPaths() {
        EntityLookupDescriptor selfRef = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.departments", SelfReferentialDepartmentDto.class, DepartmentEntity.class)
                .build();
        assertThat(selfRef.descriptionPropertyPaths()).containsExactly("name");

        String parentEntityPath = DtoEntityPathResolver.resolveEntityPropertyPath(
                SelfReferentialDepartmentDto.class, DepartmentEntity.class, "parentName", "workforce.departments", "id");
        assertThat(parentEntityPath).isEqualTo("parent.name");

        String companyEntityPath = DtoEntityPathResolver.resolveEntityPropertyPath(
                CompositeFkDto.class, AssignmentEntity.class, "companyName", "workforce.assignments", "id");
        assertThat(companyEntityPath).isEqualTo("company.legalName");
    }

    @Test
    void failsClosedOnDerivedMultiSourceAmbiguousMissingFkAndTransientPaths() {
        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "derivedField", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DERIVED");

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "aggregatedField", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AGGREGATED");

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "multiSourceField", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourcePath");

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "ambiguousDepartmentName", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryDepartment")
                .hasMessageContaining("assignedDepartment");

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "scalarFkOnlyDeptName", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "sharedColumnAmbiguousDeptName", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharedPrimaryUnit")
                .hasMessageContaining("sharedSecondaryUnit");

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "computedSummary", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "pluralCollectionPath", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DtoEntityPathResolver.resolveEntityPropertyPath(
                NegativeCasesDto.class, AssignmentEntity.class, "foreignKeyWithoutValueField", "workforce.assignments", "id"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
