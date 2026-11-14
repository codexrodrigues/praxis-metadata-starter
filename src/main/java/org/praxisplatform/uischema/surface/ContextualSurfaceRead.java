package org.praxisplatform.uischema.surface;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Authorized values and destination availability from the same item read. */
@Schema(
        name = "ContextualSurfaceRead",
        description = "Resposta enxuta de leitura contextual de uma surface de item, reunindo apenas os "
                + "valores resumidos permitidos pela politica do host, a revisao estrutural corrente e "
                + "as surfaces de destino autorizadas para o registro."
)
public record ContextualSurfaceRead(
        @Schema(description = "Identificador canonico da surface de item consultada.")
        String surfaceId,
        @Schema(description = "Revisao da publicacao OpenAPI sob a qual a leitura contextual foi admitida.")
        String schemaPublication,
        @Schema(description = "Mapa enxuto e sanitizado com os campos selecionados pelo contrato de Quick View / Visao 360.")
        Map<String, Object> data,
        @Schema(description = "Catalogo contextual filtrado das surfaces de destino permitidas para o item e usuario atuais.")
        SurfaceCatalogResponse destinations
) {}
