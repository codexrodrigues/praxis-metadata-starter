package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Escopo explicito de formulario ao qual uma {@link ReactiveDetermination} se aplica.
 *
 * <p>Use quando a determinacao nao seguir o atalho CRUD padrao
 * ({@code createOperationId} + {@code updateOperationId}), por exemplo em formularios somente de
 * criacao, edicao parcial ({@code @ResourceIntent}) ou fluxos especializados.</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeterminationScope {

    /**
     * {@code operationId} OpenAPI cujo schema de requisicao recebera o binding em {@code /schemas/filtered}.
     */
    String schemaOperationId();

    /**
     * Modo do formulario associado a operacao ({@link ReactiveDeterminationFormMode#CREATE} ou {@link ReactiveDeterminationFormMode#EDIT}).
     */
    ReactiveDeterminationFormMode formMode();
}
