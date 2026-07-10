package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara como um registro de recurso deve ser identificado em superficies metadata-driven.
 *
 * <p>A configuracao e estrutural: informa a chave visual, o titulo humano e os metadados
 * secundarios sem authorar HTML, CSS ou templates no backend.</p>
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceIdentity {

    /** Campo que representa a chave visual e estavel do registro. */
    String keyField() default "";

    /** Campo que representa o titulo humano principal do registro. */
    String titleField() default "";

    /** Campos secundarios exibidos em ordem apos a chave e o titulo. */
    String[] metadataFields() default {};

    /** Campo textual de fallback para superficies que ainda nao materializam identidade estruturada. */
    String displayLabelField() default "";
}
