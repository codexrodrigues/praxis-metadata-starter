package org.praxisplatform.uischema.action;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request canonico para workflow actions que nao precisam de payload alem do contexto do path.
 */
@Schema(
        name = "EmptyWorkflowActionRequest",
        description = "Comando sem campos de entrada; a action usa apenas o contexto canonico do path, como o identificador do recurso."
)
public class EmptyWorkflowActionRequest {
}
