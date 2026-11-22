package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityLookupOptionMapperTest {

    static class EmployeePojo {
        private final Long id;
        private final String code;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private final String fullName;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        private final String taxId;

        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL)
        private final String apiToken;

        private final String status;
        private final String departmentName;
        private final String internalSecretNote;

        EmployeePojo(Long id, String code, String fullName, String taxId, String apiToken,
                     String status, String departmentName, String internalSecretNote) {
            this.id = id;
            this.code = code;
            this.fullName = fullName;
            this.taxId = taxId;
            this.apiToken = apiToken;
            this.status = status;
            this.departmentName = departmentName;
            this.internalSecretNote = internalSecretNote;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getFullName() { return fullName; }
        public String getTaxId() { return taxId; }
        public String getApiToken() { return apiToken; }
        public String getStatus() { return status; }
        public String getDepartmentName() { return departmentName; }
        public String getInternalSecretNote() { return internalSecretNote; }
    }

    record EmployeeRecord(
            Long id,
            String code,
            @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA) String fullName,
            @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID) String taxId,
            String status
    ) {}

    @Test
    @SuppressWarnings("unchecked")
    void mapsClosedExtraWithDisabledReasonTemplateAndDeclarativeLgpdMasking() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "employees",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/employees",
                "employeeId",
                "id",
                "fullName",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "workforce.employees",
                        "taxId",
                        List.of("departmentName", "taxId", "apiToken"),
                        "status",
                        null,
                        null,
                        List.of("code", "fullName"),
                        Map.of(),
                        new LookupSelectionPolicy(
                                null,
                                "status",
                                List.of("ACTIVE"),
                                List.of("BLOCKED"),
                                true,
                                "Status {status} blocks record {id}",
                                null
                        ),
                        LookupCapabilities.standardDirectory(),
                        new LookupDetailDescriptor("/api/employees/{id}", "/employees/{id}", "drawer"),
                        new LookupDisplayDescriptor(
                                "directory", "form", "comfortable", "inline", "list", "fullName",
                                List.of(
                                        new LookupDisplayFieldDescriptor("code", "code", "Code", "badge", "text", "neutral", null),
                                        new LookupDisplayFieldDescriptor("documentNumber", "taxId", "Document", "id_card", "text", "neutral", null),
                                        new LookupDisplayFieldDescriptor("apiToken", "apiToken", "Token", "lock", "text", "neutral", null)
                                ),
                                List.of("departmentName"),
                                List.of("status", "taxId", "apiToken"),
                                null, false, true, true, true, true, true, Map.of(), 3
                        ),
                        null
                )
        );

        EmployeePojo blockedEmployee = new EmployeePojo(
                42L, "EMP-042", "Ana Silva", "12345678900", "secret-token-xyz",
                "BLOCKED", "Engineering", "NEVER_LEAK_THIS"
        );

        OptionDTO<Object> masked = EntityLookupOptionMapper.toOption(descriptor, blockedEmployee);
        assertThat(masked.id()).isEqualTo(42L);
        assertThat(masked.label()).isEqualTo("Ana Silva");
        assertThat(masked.extra())
                .containsEntry("code", "***")
                .containsEntry("description", "Engineering - ***")
                .containsEntry("status", "BLOCKED")
                .containsEntry("selectable", false)
                .containsEntry("disabledReason", "Status BLOCKED blocks record 42")
                .containsEntry("detailHref", "/api/employees/42")
                .containsEntry("detailRoute", "/employees/42")
                .containsEntry("resourcePath", "/api/employees")
                .containsEntry("entityKey", "workforce.employees")
                .doesNotContainKeys("internalSecretNote", "provider", "sql", "class", "apiToken");

        List<String> badges = (List<String>) masked.extra().get("badges");
        assertThat(badges).containsExactly("BLOCKED", "***");

        List<Map<String, Object>> richFields = (List<Map<String, Object>>) masked.extra().get("richFields");
        assertThat(richFields).hasSize(2);
        assertThat(richFields.get(0)).containsEntry("key", "code").containsEntry("value", "EMP-042");
        assertThat(richFields.get(1)).containsEntry("key", "documentNumber").containsEntry("value", "***");
    }

    @Test
    void returnsNullExtraForLightLookupAndMasksNonExportableLabel() {
        OptionSourceDescriptor lightDescriptor = new OptionSourceDescriptor(
                "taxIds",
                OptionSourceType.LIGHT_LOOKUP,
                "/api/employees",
                "employeeId",
                "taxId",
                "taxId",
                "id",
                List.of(),
                OptionSourcePolicy.defaults()
        );
        EmployeeRecord record = new EmployeeRecord(7L, "E-7", "Bruno", "98765432100", "ACTIVE");

        OptionDTO<Object> lightOption = EntityLookupOptionMapper.toOption(lightDescriptor, record);
        assertThat(lightOption.id()).isEqualTo(7L);
        assertThat(lightOption.label()).isEqualTo("***");
        assertThat(lightOption.extra()).isNull();

        OptionDTO<Object> unmaskedWhenNever = EntityLookupOptionMapper.toOption(
                lightDescriptor, record, null, EmployeeRecord.class, EntityLookupOptionMapper.GovernanceMaskingMode.NEVER);
        assertThat(unmaskedWhenNever.label()).isEqualTo("98765432100");
        assertThat(unmaskedWhenNever.extra()).isNull();
    }

    @Test
    void readsMapsAndRejectsForbiddenSegments() {
        Map<String, Object> row = Map.of("id", 9L, "nested", Map.of("name", "Finance"));
        assertThat(EntityLookupOptionMapper.readPropertyPath(row, "nested.name")).isEqualTo("Finance");
        assertThat(EntityLookupOptionMapper.readPropertyPath(row, "nested.missing")).isNull();
        assertThat(EntityLookupOptionMapper.readPropertyPath(row, "class")).isNull();
        assertThat(EntityLookupOptionMapper.readPropertyPath(row, "classLoader")).isNull();
    }

    static class GovernanceEdgeCasesPojo {
        private final Long id;

        @DomainGovernance(classification = DomainClassification.RESTRICTED)
        private final String restrictedDefaultAllow;

        @DomainGovernance(
                classification = DomainClassification.RESTRICTED,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK)
        )
        private final String restrictedExplicitMask;

        @DomainGovernance(preset = DomainGovernancePreset.CONFIDENTIAL_OPERATIONAL)
        private final String tacticalNote;

        @DomainGovernance(dataCategory = DomainDataCategory.SENSITIVE_PERSONAL, exportable = true)
        private final String sensitiveExportable;

        @DomainGovernance(
                dataCategory = DomainDataCategory.OPERATIONAL,
                exportable = true,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK)
        )
        private final String operationalExplicitMask;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_CONTACT)
        private final String personalEmail;

        GovernanceEdgeCasesPojo(
                Long id,
                String restrictedDefaultAllow,
                String restrictedExplicitMask,
                String tacticalNote,
                String sensitiveExportable,
                String operationalExplicitMask,
                String personalEmail
        ) {
            this.id = id;
            this.restrictedDefaultAllow = restrictedDefaultAllow;
            this.restrictedExplicitMask = restrictedExplicitMask;
            this.tacticalNote = tacticalNote;
            this.sensitiveExportable = sensitiveExportable;
            this.operationalExplicitMask = operationalExplicitMask;
            this.personalEmail = personalEmail;
        }

        public Long getId() { return id; }
        public String getRestrictedDefaultAllow() { return restrictedDefaultAllow; }
        public String getRestrictedExplicitMask() { return restrictedExplicitMask; }
        public String getTacticalNote() { return tacticalNote; }
        public String getSensitiveExportable() { return sensitiveExportable; }
        public String getOperationalExplicitMask() { return operationalExplicitMask; }
        public String getPersonalEmail() { return personalEmail; }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesRestrictedDefaultAllowAndMasksSummarizeSensitiveAndNonPersonalMaskLabels() {
        GovernanceEdgeCasesPojo sample = new GovernanceEdgeCasesPojo(
                77L,
                "RAW-RESTRICTED-DEFAULT",
                "RAW-RESTRICTED-MASK",
                "RAW-TACTICAL-NOTE",
                "RAW-SENSITIVE-EXPORTABLE",
                "RAW-OP-MASK",
                "ana@example.com"
        );

        // Defect 1: RESTRICTED with default aiUsage=ALLOW and exportable=true must resolve to DENY (label=id, extra omitted)
        OptionSourceDescriptor restrictedLabelDesc = new OptionSourceDescriptor(
                "restrictedDefault",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/items",
                "itemId",
                "id",
                "restrictedDefaultAllow",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "items",
                        "restrictedDefaultAllow",
                        List.of("restrictedDefaultAllow", "restrictedExplicitMask"),
                        null, null, null,
                        List.of("id"),
                        Map.of(),
                        null,
                        LookupCapabilities.standardDirectory(),
                        null,
                        new LookupDisplayDescriptor(
                                "directory", "form", "comfortable", "inline", "list", "restrictedDefaultAllow",
                                List.of(
                                        new LookupDisplayFieldDescriptor("kRes", "restrictedDefaultAllow", "Res", "lock", "text", "neutral", null),
                                        new LookupDisplayFieldDescriptor("kResMask", "restrictedExplicitMask", "ResMask", "lock", "text", "neutral", null)
                                ),
                                List.of(), List.of("restrictedDefaultAllow"), null, false, true, true, false, false, true, Map.of(), 0
                        ),
                        null
                )
        );
        OptionDTO<Object> restrictedOpt = EntityLookupOptionMapper.toOption(restrictedLabelDesc, sample);
        assertThat(restrictedOpt.label()).isEqualTo("77");
        assertThat(restrictedOpt.extra())
                .doesNotContainKeys("code", "badges")
                .containsEntry("description", "***");
        List<Map<String, Object>> restrictedFields = (List<Map<String, Object>>) restrictedOpt.extra().get("richFields");
        assertThat(restrictedFields).hasSize(1);
        assertThat(restrictedFields.get(0)).containsEntry("key", "kResMask").containsEntry("value", "***");

        // RESTRICTED with explicit MASK masks label to "***" (never ALLOW)
        OptionSourceDescriptor restrictedMaskLabel = lightDescriptorForLabel("restrictedExplicitMask");
        assertThat(EntityLookupOptionMapper.toOption(restrictedMaskLabel, sample).label()).isEqualTo("***");

        // Defect 2: SUMMARIZE_ONLY, SENSITIVE_PERSONAL (even exportable=true), and non-personal MASK must mask OptionDTO.label() to "***"
        assertThat(EntityLookupOptionMapper.toOption(lightDescriptorForLabel("tacticalNote"), sample).label()).isEqualTo("***");
        assertThat(EntityLookupOptionMapper.toOption(lightDescriptorForLabel("sensitiveExportable"), sample).label()).isEqualTo("***");
        assertThat(EntityLookupOptionMapper.toOption(lightDescriptorForLabel("operationalExplicitMask"), sample).label()).isEqualTo("***");

        // Non-sensitive personal directory display label (LGPD_PERSONAL_CONTACT, personal + exportable=true + mask + !restricted) stays readable
        assertThat(EntityLookupOptionMapper.toOption(lightDescriptorForLabel("personalEmail"), sample).label())
                .isEqualTo("ana@example.com");
    }

    static class UnannotatedPojo {
        private final Long id;
        private final String code;
        private final String fullName;
        private final String taxId;
        private final String apiSecret;
        private final String restrictedCode;

        UnannotatedPojo(Long id, String code, String fullName, String taxId, String apiSecret, String restrictedCode) {
            this.id = id;
            this.code = code;
            this.fullName = fullName;
            this.taxId = taxId;
            this.apiSecret = apiSecret;
            this.restrictedCode = restrictedCode;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getFullName() { return fullName; }
        public String getTaxId() { return taxId; }
        public String getApiSecret() { return apiSecret; }
        public String getRestrictedCode() { return restrictedCode; }
    }

    @ResourceIdentity(
            keyField = "id",
            titleField = "fullName",
            subtitleField = "code",
            metadataFields = {"taxId", "apiSecret", "restrictedCode"}
    )
    static class GovernedDirectoryDto {
        private Long id;
        @UISchema(label = "Code", preset = UISchemaPreset.ENTERPRISE_CODE)
        private String code;
        @UISchema(label = "Name")
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private String fullName;
        @UISchema(label = "Tax ID")
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        private String taxId;
        @UISchema(label = "API Secret")
        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL)
        private String apiSecret;
        @UISchema(label = "Restricted")
        @DomainGovernance(classification = DomainClassification.RESTRICTED)
        private String restrictedCode;
    }

    @Test
    @SuppressWarnings("unchecked")
    void retainsSourceDtoClassInternallyWithoutSerializingAndEnforcesDtoGovernanceOverUnannotatedTarget() {
        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.unannotated", GovernedDirectoryDto.class, UnannotatedPojo.class)
                .build();

        assertThat(lookup.sourceDtoClass()).isEqualTo(GovernedDirectoryDto.class);
        assertThat(lookup.toMetadataMap()).doesNotContainKey("sourceDtoClass");

        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "unannotatedEmployees",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/unannotated-employees",
                "employeeId",
                "id",
                "fullName",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );
        assertThat(descriptor.toMetadataMap()).doesNotContainKey("sourceDtoClass");

        UnannotatedPojo pojo = new UnannotatedPojo(
                10L, "EMP-010", "Carla Dias", "11122233344", "secret-val", "RES-999"
        );

        OptionDTO<Object> projected = EntityLookupOptionMapper.toOption(descriptor, pojo);
        assertThat(projected.id()).isEqualTo(10L);
        assertThat(projected.label()).isEqualTo("Carla Dias");
        assertThat(projected.extra())
                .containsEntry("code", "EMP-010")
                .containsEntry("description", "*** - ***");
        List<Map<String, Object>> richFields = (List<Map<String, Object>>) projected.extra().get("richFields");
        assertThat(richFields)
                .extracting(f -> (String) f.get("key"))
                .containsExactly("code", "fullName", "taxId");
        assertThat(richFields.get(1)).containsEntry("value", "***");
        assertThat(richFields.get(2)).containsEntry("value", "***");
    }

    static class MixedGovernanceEntityPojo {
        private final Long id;
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private final String note;
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        private final String taxCode;

        MixedGovernanceEntityPojo(Long id, String note, String taxCode) {
            this.id = id;
            this.note = note;
            this.taxCode = taxCode;
        }

        public Long getId() { return id; }
        public String getNote() { return note; }
        public String getTaxCode() { return taxCode; }
    }

    @ResourceIdentity(keyField = "id", titleField = "note", subtitleField = "taxCode", metadataFields = {"note", "taxCode"})
    static class MixedGovernanceDto {
        private Long id;
        @DomainGovernance(classification = DomainClassification.RESTRICTED)
        private String note;
        @UISchema(label = "Tax Code", preset = UISchemaPreset.ENTERPRISE_CODE)
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private String taxCode;
    }

    @Test
    @SuppressWarnings("unchecked")
    void takesMostRestrictiveTierBetweenEntityAndSourceDto() {
        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.mixed", MixedGovernanceDto.class, MixedGovernanceEntityPojo.class)
                .build();
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "mixedEmployees",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/mixed-employees",
                "employeeId",
                "id",
                "taxCode",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );
        MixedGovernanceEntityPojo entity = new MixedGovernanceEntityPojo(88L, "Secret Note", "99988877766");
        OptionDTO<Object> option = EntityLookupOptionMapper.toOption(descriptor, entity);

        // Entity has LGPD_PERSONAL_ID (MASK_NON_EXPORTABLE) on taxCode while DTO has LGPD_PERSONAL_DATA (MASK_EXPORTABLE):
        // most restrictive wins -> label masked to "***", extra.code masked to "***"
        assertThat(option.label()).isEqualTo("***");
        assertThat(option.extra()).containsEntry("code", "***");

        // DTO has RESTRICTED (DENY) on note while Entity has LGPD_PERSONAL_DATA (MASK_EXPORTABLE):
        // most restrictive wins -> note is omitted from richFields and description
        assertThat(option.extra()).doesNotContainKey("description");
        List<Map<String, Object>> richFields = (List<Map<String, Object>>) option.extra().get("richFields");
        assertThat(richFields).extracting(f -> (String) f.get("key")).containsExactly("taxCode");
        assertThat(richFields.get(0)).containsEntry("value", "***");
    }

    private static OptionSourceDescriptor lightDescriptorForLabel(String labelPath) {
        return new OptionSourceDescriptor(
                "probe_" + labelPath,
                OptionSourceType.LIGHT_LOOKUP,
                "/api/items",
                "itemId",
                labelPath,
                labelPath,
                "id",
                List.of(),
                OptionSourcePolicy.defaults()
        );
    }
}
