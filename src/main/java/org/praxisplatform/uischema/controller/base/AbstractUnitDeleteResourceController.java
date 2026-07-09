package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseUnitDeleteResourceService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
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
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getUnitDeleteResourceControllerClass()).delete(id)
        ).withRel("delete");
    }
}
