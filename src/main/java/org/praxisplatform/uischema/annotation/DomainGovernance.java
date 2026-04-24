package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara governanca semantica canonica para campos publicados nos schemas do starter.
 *
 * <p>
 * Use esta anotacao quando o recurso hospedeiro precisar tornar explicito, no proprio
 * codigo-fonte, como um campo deve ser classificado para discovery semantico, consumo por IA e
 * authoring governado de regras. O starter materializa esses dados como extensao OpenAPI
 * estruturada e o catalogo de dominio os republica em {@code /schemas/domain}.
 * </p>
 *
 * <p>
 * Quando a anotacao nao estiver presente, o catalogo ainda pode aplicar heuristicas de fallback.
 * A recomendacao, porem, e preferir esta declaracao explicita nos exemplos e nos hosts que
 * queiram ficar totalmente self-describing.
 * </p>
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainGovernance {

     /**
     * Categoria principal da anotacao publicada.
     */
    DomainGovernanceKind kind();

     /**
      * Nivel de classificacao aplicado ao campo.
      */
    DomainClassification classification();

     /**
     * Categoria semantica do dado.
     */
    DomainDataCategory dataCategory();

    /**
     * Tags regulatórias ou de politica interna associadas ao campo.
     */
    String[] complianceTags() default {};

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
