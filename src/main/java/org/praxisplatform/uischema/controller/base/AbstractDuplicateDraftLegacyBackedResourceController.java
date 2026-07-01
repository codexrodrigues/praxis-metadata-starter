package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in legacy-backed base for resources that publish the optional duplicate-draft command.
 *
 * <p>
 * Keeping this endpoint out of {@link AbstractLegacyBackedResourceController} prevents OpenAPI,
 * capabilities and _links from advertising duplicate-draft for resources whose command port does
 * not actually implement it.
 * </p>
 */
public abstract class AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getDuplicateDraftResourceControllerClass() {
        return (Class<? extends AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @PostMapping("/{id}/duplicate-draft")
    @Operation(summary = "Duplicar item como rascunho")
    public ResponseEntity<RestApiResponse<ResponseDTO>> duplicateDraft(@PathVariable ID id) {
        if (!getService().supportsDuplicateDraft()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "duplicate-draft is not supported by this resource.");
        }
        assertItemOperationAvailable("duplicate-draft", id);
        BaseResourceCommandService.SavedResult<ID, ResponseDTO> saved = getService().duplicateDraft(id);
        ID newId = saved.id();
        ResponseDTO body = saved.body();
        Link selfLink = linkToSelf(newId);

        List<Link> linkList = new ArrayList<>();
        linkList.add(selfLink);
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        if (isItemOperationAvailable("edit", newId)) {
            linkList.add(linkToUpdate(newId));
        }
        if (isItemOperationAvailable("delete", newId)) {
            linkList.add(linkToDelete(newId));
        }
        if (getService().supportsDuplicateDraft() && isItemOperationAvailable("duplicate-draft", newId)) {
            linkList.add(linkToDuplicateDraft(newId));
        }
        linkList.addAll(buildItemDiscoveryLinks(newId));

        return withVersion(
                ResponseEntity.created(selfLink.toUri()),
                RestApiResponse.success(body, hateoasOrNull(Links.of(linkList)))
        );
    }

    @Override
    protected List<Link> buildItemActionLinks(ID id) {
        List<Link> links = new ArrayList<>(super.buildItemActionLinks(id));
        if (getService().supportsDuplicateDraft() && isItemOperationAvailable("duplicate-draft", id)) {
            links.add(linkToDuplicateDraft(id));
        }
        return links;
    }

    @Override
    protected List<Link> buildEntityActionLinks(ID id) {
        List<Link> links = new ArrayList<>(super.buildEntityActionLinks(id));
        if (getService().supportsDuplicateDraft() && isItemOperationAvailable("duplicate-draft", id)) {
            links.add(linkToDuplicateDraft(id));
        }
        return links;
    }

    protected Link linkToDuplicateDraft(ID id) {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getDuplicateDraftResourceControllerClass()).duplicateDraft(id)
        ).withRel("duplicate-draft");
    }
}
