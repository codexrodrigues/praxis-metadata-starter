package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.action.EmptyWorkflowActionRequest;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.DuplicateDraftLegacyBackedResourceService;
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
public abstract class AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO, DraftDTO>
        extends AbstractLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract DuplicateDraftLegacyBackedResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO>> getDuplicateDraftResourceControllerClass() {
        return (Class<? extends AbstractDuplicateDraftLegacyBackedResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO>>) getClass();
    }

    @PostMapping("/{id}/duplicate-draft")
    @Operation(
            summary = "Duplicar item como rascunho",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = false,
                    description = "Sem payload de entrada; o rascunho e preparado a partir do identificador do recurso no path.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EmptyWorkflowActionRequest.class)
                    )
            )
    )
    @WorkflowAction(
            id = "duplicate-draft",
            title = "Duplicar como rascunho",
            description = "Prepara um rascunho editavel a partir do registro atual, sem gravar alteracao ate que o usuario confirme a criacao.",
            scope = ActionScope.ITEM,
            order = 90,
            successMessage = "Rascunho de duplicacao preparado",
            tags = {"duplicate-draft", "draft", "business-command"}
    )
    public ResponseEntity<RestApiResponse<DraftDTO>> duplicateDraft(@PathVariable ID id) {
        if (!getService().supportsDuplicateDraft()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "duplicate-draft is not supported by this resource.");
        }
        assertItemOperationAvailable("duplicate-draft", id);
        DraftDTO draft = getService().duplicateDraft(id);

        List<Link> linkList = new ArrayList<>();
        linkList.add(linkToSelf(id));
        if (isCollectionOperationAvailable("create")) {
            linkList.add(linkToCreate());
        }
        linkList.add(linkToUiSchema("/{id}/duplicate-draft", "post", "response"));

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(draft, hateoasOrNull(Links.of(linkList))));
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
