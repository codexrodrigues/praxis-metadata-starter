package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseUnitDeleteResourceService;
import org.praxisplatform.uischema.rest.response.RestApiErrorResponse;
import org.springframework.hateoas.Link;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica mutante para recursos com create, update e delete unitario, sem delete em lote.
 *
 * <p>
 * Esta classe cobre recursos corporativos cujo contrato publico aprova {@code DELETE /{id}}, mas
 * nao aprova {@code DELETE /batch}. Como o endpoint de lote nao e publicado, OpenAPI, catalogos,
 * capabilities e links derivados permanecem coerentes sem filtros locais no host ou no frontend.
 * </p>
 *
 * @see AbstractCreateUpdateResourceController
 * @see AbstractResourceController
 */
public abstract class AbstractUnitDeleteResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractCreateUpdateResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract BaseUnitDeleteResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractUnitDeleteResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getUnitDeleteResourceControllerClass() {
        return (Class<? extends AbstractUnitDeleteResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @DeleteMapping("/{id:^(?!batch$).+}")
    @Operation(summary = "Excluir item")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Resource deleted"),
            @ApiResponse(responseCode = "403", description = "Delete operation is not available", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Resource was not found", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Delete conflicts with dependent data", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "412", description = "Resource version precondition failed", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Sanitized unexpected failure", content = @Content(schema = @Schema(implementation = RestApiErrorResponse.class)))
    })
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        assertItemOperationAvailable("delete", id);
        getService().deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    protected List<Link> buildItemActionLinks(ID id) {
        List<Link> links = new ArrayList<>(super.buildItemActionLinks(id));
        if (isItemOperationAvailable("delete", id)) {
            links.add(linkToDelete(id));
        }
        return links;
    }

    @Override
    protected List<Link> buildEntityActionLinks(ID id) {
        List<Link> links = new ArrayList<>(super.buildEntityActionLinks(id));
        if (isItemOperationAvailable("delete", id)) {
            links.add(linkToDelete(id));
        }
        return links;
    }

    @Override
    protected List<Link> buildWriteResponseActionLinks(ID id) {
        List<Link> links = new ArrayList<>(super.buildWriteResponseActionLinks(id));
        if (isItemOperationAvailable("delete", id)) {
            links.add(linkToDelete(id));
        }
        return links;
    }

    protected Link linkToDelete(ID id) {
        return Link.of(resourcePath(id), "delete");
    }
}
