package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica mutante do core resource-oriented.
 */
public abstract class AbstractResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractResourceQueryController<ResponseDTO, ID, FD> {

    @Override
    protected abstract BaseResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getResourceControllerClass() {
        return (Class<? extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @PostMapping
    @Operation(summary = "Criar novo registro")
    public ResponseEntity<RestApiResponse<ResponseDTO>> create(@jakarta.validation.Valid @RequestBody CreateDTO dto) {
        BaseResourceCommandService.SavedResult<ID, ResponseDTO> saved = getService().create(dto);
        ID newId = saved.id();
        ResponseDTO body = saved.body();
        Link selfLink = linkToSelf(newId);

        Links links = Links.of(
                selfLink,
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToDelete(newId),
                linkToUiSchema("/", "post", "request")
        );

        return withVersion(
                ResponseEntity.created(selfLink.toUri()),
                RestApiResponse.success(body, hateoasOrNull(links))
        );
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualizar registro existente")
    public ResponseEntity<RestApiResponse<ResponseDTO>> update(
            @PathVariable ID id,
            @jakarta.validation.Valid @RequestBody UpdateDTO dto
    ) {
        ResponseDTO updated = getService().update(id, dto);

        Links links = Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUpdate(id),
                linkToDelete(id),
                linkToUiSchema("/{id}", "put", "request")
        );

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(updated, hateoasOrNull(links)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir registro")
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        getService().deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Excluir registros em lote")
    public ResponseEntity<Void> deleteBatch(@RequestBody List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        getService().deleteAllById(ids);
        return ResponseEntity.noContent().build();
    }

    @Override
    protected List<Link> buildItemActionLinks(ID id) {
        return List.of(linkToUpdate(id), linkToDelete(id));
    }

    @Override
    protected List<Link> buildEntityActionLinks(ID id) {
        List<Link> links = new ArrayList<>();
        links.add(linkToCreate());
        links.add(linkToUpdate(id));
        links.add(linkToDelete(id));
        return links;
    }

    protected Link linkToCreate() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getResourceControllerClass()).create(null)
        ).withRel("create");
    }

    protected Link linkToUpdate(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getResourceControllerClass()).update(id, null)
        ).withRel("update");
    }

    protected Link linkToDelete(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getResourceControllerClass()).delete(id)
        ).withRel("delete");
    }
}
