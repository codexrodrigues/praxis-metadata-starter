package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.hateoas.Link;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica mutante do core resource-oriented.
 *
 * <p>
 * Esta classe adiciona a camada de comando sobre
 * {@link AbstractResourceQueryController}, publicando create, update e delete com envelope
 * {@link RestApiResponse}, links HATEOAS e discovery coerente com o restante do baseline
 * canonico do recurso.
 * </p>
 *
 * <p>
 * O uso recomendado e herdar desta base apenas para recursos realmente mutaveis. Para recursos
 * query-only, a variante correta continua sendo {@link AbstractReadOnlyResourceController}, sem
 * expor endpoints de escrita que retornariam {@code 405}.
 * </p>
 *
 * @see AbstractCreateUpdateResourceController
 */
public abstract class AbstractResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractCreateUpdateResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract BaseResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getResourceControllerClass() {
        return (Class<? extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir item")
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        assertItemOperationAvailable("delete", id);
        getService().deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Excluir itens em lote")
    public ResponseEntity<Void> deleteBatch(@RequestBody List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        assertCollectionOperationAvailable("delete");
        ids.forEach(id -> assertItemOperationAvailable("delete", id));
        getService().deleteAllById(ids);
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
