package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.LegacyBackedResourceService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Base canonica para recursos mutaveis cujo contrato publico e Praxis-native, mas cuja escrita
 * e executada por um adaptador legado do host.
 */
public abstract class AbstractLegacyBackedResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO>
        extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract LegacyBackedResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>> getLegacyResourceControllerClass() {
        return (Class<? extends AbstractLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO>>) getClass();
    }

    @PostMapping("/{id}/duplicate-draft")
    @Operation(summary = "Duplicar item como rascunho")
    public ResponseEntity<RestApiResponse<ResponseDTO>> duplicateDraft(@PathVariable ID id) {
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
        if (isItemOperationAvailable("duplicate-draft", newId)) {
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
                WebMvcLinkBuilder.methodOn(getLegacyResourceControllerClass()).duplicateDraft(id)
        ).withRel("duplicate-draft");
    }
}
