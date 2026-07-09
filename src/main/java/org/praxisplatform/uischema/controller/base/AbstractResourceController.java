package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Base canonica mutante do core resource-oriented.
 *
 * <p>
 * Esta classe adiciona a camada de comando sobre
 * {@link AbstractUnitDeleteResourceController}, publicando tambem delete em lote com envelope,
 * links HATEOAS e discovery coerente com o restante do baseline canonico do recurso.
 * </p>
 *
 * <p>
 * O uso recomendado e herdar desta base apenas para recursos realmente mutaveis. Para recursos
 * query-only, a variante correta continua sendo {@link AbstractReadOnlyResourceController}. Para
 * recursos com delete unitario aprovado mas sem contrato de lote, use
 * {@link AbstractUnitDeleteResourceController}.
 * </p>
 *
 * @see AbstractCreateUpdateResourceController
 */
public abstract class AbstractResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractUnitDeleteResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract BaseResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

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
}
