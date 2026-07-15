package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Referencia versionada a uma politica de dominio aplicada por uma projection analitica.
 *
 * <p>A referencia identifica a politica e os campos que atestam sua materializacao. Ela nao
 * publica thresholds, expressoes nem concede acesso aos dados governados pela politica.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticsPolicyReference {

    String policyId();

    String policyVersion();

    String role();

    String resultField();

    String policyIdField() default "";

    String policyVersionField() default "";
}
