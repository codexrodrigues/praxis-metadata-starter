package org.praxisplatform.uischema.annotation;

/**
 * Canonical legal treatment purposes for semantic surfaces ({@link UiSurface}) and governed
 * workflow commands ({@link WorkflowAction}).
 *
 * <p>
 * Binds an operational read projection or business command to an explicit treatment finality
 * (such as LGPD Art. 7 / Art. 11, statutory public-administration obligations, official health
 * expertise, or active public transparency under LAI), enabling deterministic policy engines,
 * audit trails, and semantic catalogs to verify purpose compatibility before exposing or
 * mutating governed data.
 * </p>
 */
public enum LegalTreatmentPurpose {

    /**
     * No explicit purpose declared on the annotation; inherits resource-level or default
     * operational governance.
     */
    UNSPECIFIED(""),

    /**
     * Human resources registration, functional career management, and workforce administration
     * (LGPD Art. 7, II / V / Art. 23).
     */
    HUMAN_RESOURCES_ADMINISTRATION("human_resources_administration"),

    /**
     * Payroll calculation, tax withholding, eSocial/fiscal reporting, and financial settlement
     * (LGPD Art. 7, II).
     */
    PAYROLL_AND_TAX_COMPLIANCE("payroll_and_tax_compliance"),

    /**
     * Official medical/health expertise, occupational health assessment, and sick-leave
     * homologation involving sensitive health data (LGPD Art. 11, II, 'a' / 'b' / 'f').
     */
    OFFICIAL_HEALTH_EXPERTISE("official_health_expertise"),

    /**
     * Social security, pension, family-salary, income-tax dependent deduction, and statutory
     * welfare benefits (LGPD Art. 7, II / Art. 11, II, 'a').
     */
    SOCIAL_SECURITY_AND_BENEFITS("social_security_and_benefits"),

    /**
     * Active public transparency and open-government reporting subject to LAI (Lei 12.527/2011)
     * and LGPD Art. 23 minimization/masking rules.
     */
    ACTIVE_PUBLIC_TRANSPARENCY_LAI("active_public_transparency_lai"),

    /**
     * Internal/external audit, court of accounts inspection, and administrative compliance
     * verification (LGPD Art. 7, II / III).
     */
    EXTERNAL_AUDIT_CONTROL("external_audit_control"),

    /**
     * Self-service consultation or update performed by the data subject or authorized
     * representative (LGPD Art. 7, I / V; Art. 18).
     */
    EMPLOYEE_SELF_SERVICE("employee_self_service"),

    /**
     * Execution of a specific statutory or regulatory legal obligation not covered by a narrower
     * category (LGPD Art. 7, II; Art. 11, II, 'a').
     */
    STATUTORY_LEGAL_OBLIGATION("statutory_legal_obligation"),

    /**
     * Treatment authorized by explicit, informed and unambiguous consent of the data subject
     * (LGPD Art. 7, I; Art. 11, I; GDPR Art. 6(1)(a)).
     */
    CONSENT("consent"),

    /**
     * Treatment necessary for the execution of a contract or preliminary procedures related to a
     * contract to which the data subject is a party (LGPD Art. 7, V; GDPR Art. 6(1)(b)).
     */
    CONTRACT_EXECUTION("contract_execution"),

    /**
     * Treatment necessary to fulfill legitimate interests of the controller or third party,
     * preserving fundamental rights and freedoms (LGPD Art. 7, IX; GDPR Art. 6(1)(f)).
     */
    LEGITIMATE_INTEREST("legitimate_interest"),

    /**
     * Protection of life or physical safety of the data subject or a third party
     * (LGPD Art. 7, VII; Art. 11, II, 'e'; GDPR Art. 6(1)(d)).
     */
    VITAL_INTEREST("vital_interest"),

    /**
     * Credit protection and financial solvency assessment in accordance with applicable law
     * (LGPD Art. 7, X).
     */
    CREDIT_PROTECTION("credit_protection"),

    /**
     * Regular exercise of rights in judicial, administrative or arbitral proceedings
     * (LGPD Art. 7, VI; Art. 11, II, 'd').
     */
    JUDICIAL_DEFENSE("judicial_defense"),

    /**
     * Fraud prevention and security assurance of the data subject in identification and
     * authentication processes (LGPD Art. 11, II, 'g').
     */
    FRAUD_PREVENTION("fraud_prevention"),

    /**
     * Regulatory compliance and statutory supervision obligations before sectoral authorities
     * (LGPD Art. 7, II; GDPR Art. 6(1)(c)).
     */
    REGULATORY_COMPLIANCE("regulatory_compliance");

    private final String wireValue;

    LegalTreatmentPurpose(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the canonical snake_case wire representation published in semantic catalogs and
     * OpenAPI/domain bindings, or an empty string for {@link #UNSPECIFIED}.
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Indicates whether an explicit legal treatment purpose was declared.
     */
    public boolean isSpecified() {
        return !wireValue.isEmpty();
    }
}
