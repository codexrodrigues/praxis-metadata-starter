package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Politica canonica de uso por IA associada a um campo anotado com {@link DomainGovernance}.
 *
 * <p>
 * Os valores desta anotacao materializam tokens publicados pelo catalogo semantico em
 * {@code /schemas/domain}. Ela existe para tornar o contrato de governanca declarativo no proprio
 * codigo-fonte do recurso hospedeiro, sem depender apenas de heuristicas do catalogo.
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AiUsagePolicy {

     /**
      * Visibilidade permitida para respostas, explicacoes ou renderizacoes com IA.
      */
    AiUsageMode visibility() default AiUsageMode.ALLOW;

     /**
      * Politica de uso para treino de modelos.
      */
    AiUsageMode trainingUse() default AiUsageMode.DENY;

     /**
      * Politica para authoring de regras assistido por IA.
      */
    AiUsageMode ruleAuthoring() default AiUsageMode.REVIEW_REQUIRED;

     /**
      * Politica de uso em cadeias de raciocinio ou derivacoes semanticas.
      */
    AiUsageMode reasoningUse() default AiUsageMode.ALLOW;
}
