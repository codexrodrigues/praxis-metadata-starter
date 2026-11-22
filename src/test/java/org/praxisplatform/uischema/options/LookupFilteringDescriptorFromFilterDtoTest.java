package org.praxisplatform.uischema.options;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookupFilteringDescriptorFromFilterDtoTest {

    enum RecordStatus implements PraxisUiOptionEnum {
        ACTIVE("Active", true, "check_circle", QuickFilterTone.SUCCESS),
        INACTIVE("Inactive", false, "block", QuickFilterTone.DANGER);

        private final String label;
        private final boolean selectable;
        private final String icon;
        private final QuickFilterTone tone;

        RecordStatus(String label, boolean selectable, String icon, QuickFilterTone tone) {
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

    enum ContractKind {
        PERMANENT("CLT", "Permanent"),
        TEMPORARY("TMP", "Temporary");

        private final String code;
        private final String label;

        ContractKind(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @JsonValue
        public String getCode() { return code; }
        public String getLabel() { return label; }
    }

    static class DepartmentResourceMarker {}

    static class EmployeeLookupFilterDTO implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.LIKE, primarySearch = true,
                relations = {"fullName", "department.name"})
        @UISchema(label = "Search", placeholder = "Search employee or department")
        private String omniboxSearch;

        @Filterable(operation = Filterable.FilterOperation.LIKE, relation = "fullName", inline = true)
        @UISchema(label = "Employee Name")
        private String fullName;

        @Filterable(operation = Filterable.FilterOperation.IN, relation = "status", inline = true)
        @UISchema(label = "Status")
        private List<RecordStatus> statusIn;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Contract Kind", controlType = FieldControlType.SELECT)
        private ContractKind contractKind;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Priority", optionItems = {
                @UiOption(value = "1", label = "High", tone = QuickFilterTone.WARNING),
                @UiOption(value = "2", label = "Low", tone = QuickFilterTone.NEUTRAL)
        })
        private Integer priority;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Remote Eligible", labelTrue = "Yes", labelFalse = "No")
        private Boolean remoteEligible;

        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "hireDate")
        @UISchema(label = "Hire Period", type = FieldDataType.DATE)
        private List<LocalDate> hireDateBetween;

        @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL, relation = "baseSalary")
        @Schema(title = "Minimum Salary")
        private BigDecimal minSalary;

        @NotNull
        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "department.id", inline = true)
        @UISchema(
                label = "Department",
                controlType = FieldControlType.ENTITY_LOOKUP,
                endpoint = "/api/departments/option-sources/department/options/filter",
                dependsOn = "companyId",
                extraProperties = @ExtensionProperty(
                        name = "optionSource.dependencyFilterMap",
                        value = "{\"companyId\":\"company.id\"}"
                )
        )
        private Long departmentId;

        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "Company")
        private Long companyId;

        @Filterable(operation = Filterable.FilterOperation.IS_NULL)
        private Boolean withoutEmail;

        private String unannotatedField;
    }

    static class InvalidDateEqualFilterDTO implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        private LocalDate createdAt;
    }

    @Test
    @SuppressWarnings("unchecked")
    void derivesAllFilterTypesOptionsDependenciesAndQuickFiltersFromFilterDto() {
        OptionSourceDescriptor departmentSource = new OptionSourceDescriptor(
                "department",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/departments",
                "departmentId",
                "id",
                "name",
                "id",
                List.of("tenantId"),
                Map.of("tenantId", "tenant.id"),
                OptionSourcePolicy.defaults(),
                EntityLookupDescriptor.directoryBuilder("workforce.departments", "code", "name")
                        .filtering(new LookupFilteringDescriptor(
                                List.of(new LookupFilterDefinition("code", "Code", "text", List.of("equals"), "equals", null, false, false)),
                                Map.of(), List.of(), null, List.of(), null, List.of()))
                        .build()
        );
        OptionSourceRegistry registry = OptionSourceRegistry.builder()
                .add(DepartmentResourceMarker.class, departmentSource)
                .build();

        LookupFilteringDescriptor filtering = LookupFilteringDescriptor
                .builderFromFilterDto(EmployeeLookupFilterDTO.class, registry)
                .sortOptions(List.of(new LookupSortOption("nameAsc", "fullName", "asc", "Name")))
                .defaultSort("nameAsc")
                .searchStrategies(List.of(
                        new LookupSearchStrategyDefinition("text", "descriptive-text", 2),
                        new LookupSearchStrategyDefinition("code", "business-code", 1, "digits")
                ))
                .defaultSearchStrategy("text")
                .build();

        assertThat(filtering.searchPlaceholder()).isEqualTo("Search employee or department");
        assertThat(filtering.quickFilterFields()).containsExactly("fullName", "statusIn", "departmentId");
        assertThat(filtering.defaultSearchStrategy()).isEqualTo("text");

        assertThat(filtering.availableFilters())
                .extracting(LookupFilterDefinition::field)
                .containsExactly(
                        "fullName",
                        "statusIn",
                        "contractKind",
                        "priority",
                        "remoteEligible",
                        "hireDateBetween",
                        "minSalary",
                        "departmentId",
                        "companyId"
                )
                .doesNotContain("omniboxSearch", "withoutEmail", "unannotatedField");

        Map<String, LookupFilterDefinition> byField = filtering.availableFilters().stream()
                .collect(java.util.stream.Collectors.toMap(LookupFilterDefinition::field, f -> f));

        LookupFilterDefinition statusDef = byField.get("statusIn");
        assertThat(statusDef.type()).isEqualTo("enum");
        assertThat(statusDef.defaultOperator()).isEqualTo("in");
        assertThat(statusDef.effectiveEntityPropertyPath()).isEqualTo("status");
        assertThat(statusDef.options()).hasSize(2);
        assertThat(statusDef.options().getFirst()).containsEntry("value", "ACTIVE").containsEntry("label", "Active");

        LookupFilterDefinition contractDef = byField.get("contractKind");
        assertThat(contractDef.type()).isEqualTo("enum");
        assertThat(contractDef.options().getFirst()).containsEntry("value", "CLT").containsEntry("label", "Permanent");

        LookupFilterDefinition priorityDef = byField.get("priority");
        assertThat(priorityDef.type()).isEqualTo("enum");
        assertThat(priorityDef.options().getFirst()).containsEntry("value", 1L).containsEntry("label", "High");

        LookupFilterDefinition remoteDef = byField.get("remoteEligible");
        assertThat(remoteDef.type()).isEqualTo("enum");
        assertThat(remoteDef.options()).containsExactly(
                Map.of("value", true, "label", "Yes"),
                Map.of("value", false, "label", "No")
        );

        LookupFilterDefinition hireDateDef = byField.get("hireDateBetween");
        assertThat(hireDateDef.type()).isEqualTo("date");
        assertThat(hireDateDef.defaultOperator()).isEqualTo("between");
        assertThat(hireDateDef.effectiveEntityPropertyPath()).isEqualTo("hireDate");

        LookupFilterDefinition salaryDef = byField.get("minSalary");
        assertThat(salaryDef.type()).isEqualTo("number");
        assertThat(salaryDef.label()).isEqualTo("Minimum Salary");
        assertThat(salaryDef.defaultOperator()).isEqualTo("gte");

        LookupFilterDefinition deptDef = byField.get("departmentId");
        assertThat(deptDef.type()).isEqualTo("reference");
        assertThat(deptDef.required()).isTrue();
        assertThat(deptDef.optionsSource()).isEqualTo("department");
        assertThat(deptDef.effectiveEntityPropertyPath()).isEqualTo("department.id");
        assertThat(deptDef.dependsOn()).containsExactly("companyId", "tenantId");
        assertThat(deptDef.dependencyFilterMap())
                .containsEntry("companyId", "company.id")
                .containsEntry("tenantId", "tenant.id");
        assertThat(deptDef.optionSource())
                .containsEntry("key", "department")
                .containsEntry("resourcePath", "/api/departments")
                .doesNotContainKey("filtering");
        assertThat(deptDef.toMetadataMap()).doesNotContainKey("targetPropertyPath");
    }

    @Test
    void failsClosedOnInvalidIncludedFieldsUnsupportedOperationAndUnknownDefaultSearchStrategy() {
        assertThatThrownBy(() -> LookupFilteringDescriptor.fromFilterDto(EmployeeLookupFilterDTO.class, "missingField"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupFilteringDescriptor.fromFilterDto(EmployeeLookupFilterDTO.class, "unannotatedField"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupFilteringDescriptor.fromFilterDto(EmployeeLookupFilterDTO.class, "omniboxSearch"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupFilteringDescriptor.fromFilterDto(EmployeeLookupFilterDTO.class, "withoutEmail"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupFilteringDescriptor.fromFilterDto(InvalidDateEqualFilterDTO.class, "createdAt"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LookupFilteringDescriptor(
                List.of(), Map.of(), List.of(), null, List.of(), null,
                List.of(new LookupSearchStrategyDefinition("text", "descriptive-text", 2)),
                "undeclaredKey"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
