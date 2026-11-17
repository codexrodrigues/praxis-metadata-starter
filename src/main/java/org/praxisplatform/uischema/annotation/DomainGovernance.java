package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara governança semântica canônica para campos publicados nos schemas do starter.
 *
 * <p>
 * Use esta anotação quando o recurso hospedeiro precisar tornar explícito, no próprio
 * código-fonte, como um campo deve ser classificado para discovery semântico, consumo por IA e
 * authoring governado de regras. O starter materializa esses dados como extensão OpenAPI
 * estruturada e o catálogo de domínio os republica em {@code /schemas/domain}.
 * </p>
 *
 * <p>
 * Quando a anotação não estiver presente, o catálogo ainda pode aplicar heurísticas de fallback.
 * A recomendação, porém, é preferir esta declaração explícita nos exemplos e nos hosts que
 * queiram ficar totalmente self-describing, combinando-a com {@link LegalTreatmentPurpose} em
 * superfícies e comandos.
 * </p>
 *
 * <pre>{@code
 * @DomainGovernance(
 *     preset = DomainGovernancePreset.LGPD_PERSONAL_ID,
 *     exportable = false,
 *     reason = "Documento fiscal pessoal protegido por sigilo cadastral."
 * )
 * private String taxId;
 * }</pre>
 *
 * @see AiUsagePolicy
 * @see DomainGovernancePreset
 * @see LegalTreatmentPurpose
 */
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainGovernance {

    /**
     * Preset canonico de governanca (LGPD, dados sensiveis Art. 11, financeiro confidencial, credencial).
     * Quando diferente de {@link DomainGovernancePreset#NONE}, fornece os valores base de {@link #kind()},
     * {@link #classification()}, {@link #dataCategory()}, {@link #complianceTags()}, {@link #exportable()}
     * e {@link #aiUsage()}, reduzindo a densidade visual de anotacoes nos DTOs.
     */
    DomainGovernancePreset preset() default DomainGovernancePreset.NONE;

     /**
     * Categoria principal da anotacao publicada.
     */
    DomainGovernanceKind kind() default DomainGovernanceKind.PRIVACY;

     /**
      * Nivel de classificacao aplicado ao campo.
      */
    DomainClassification classification() default DomainClassification.INTERNAL;

     /**
     * Categoria semantica do dado.
     */
    DomainDataCategory dataCategory() default DomainDataCategory.OPERATIONAL;

    /**
     * Tags regulatórias ou de politica interna associadas ao campo.
     */
    String[] complianceTags() default {};

    /** Whether the field may leave the resource through a governed collection export. */
    boolean exportable() default true;

    /**
     * Politicas de uso por IA associadas ao campo.
     */
    AiUsagePolicy aiUsage() default @AiUsagePolicy;

    /**
     * Razao humana curta usada para auditoria e explicabilidade.
     */
    String reason() default "";

    /**
     * Confianca associada a esta classificacao declarativa.
     */
    double confidence() default 1.0d;
}
