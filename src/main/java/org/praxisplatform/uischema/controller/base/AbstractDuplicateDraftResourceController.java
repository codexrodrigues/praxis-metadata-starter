package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.capability.ResourceStateSnapshotScope;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;
import org.praxisplatform.uischema.action.EmptyWorkflowActionRequest;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.DuplicateDraftResourceService;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in base for resources that publish the optional, non-persistent duplicate-draft command.
 *
 * <p>
 * Keeping this endpoint out of {@link AbstractResourceController} prevents OpenAPI,
 * capabilities and _links from advertising duplicate-draft for resources whose command port does
 * not actually implement it.
 * </p>
 * <p>The service may use JPA or delegate to an external adapter. The draft type is independent
 * of the persisted response type and must be compatible with the resource's create payload.</p>
 */
public abstract class AbstractDuplicateDraftResourceController<ResponseDTO, ID, FD extends GenericFilterDTO, CreateDTO, UpdateDTO, DraftDTO>
        extends AbstractResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO> {

    @Override
    protected abstract DuplicateDraftResourceService<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO> getService();

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractDuplicateDraftResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO>> getDuplicateDraftResourceControllerClass() {
        return (Class<? extends AbstractDuplicateDraftResourceController<ResponseDTO, ID, FD, CreateDTO, UpdateDTO, DraftDTO>>) getClass();
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
        try (var ignored = ResourceStateSnapshotScope.open()) {
            if (!getService().supportsDuplicateDraft()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "duplicate-draft is not supported by this resource.");
            }
            if (!isDuplicateDraftAvailable(id)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "duplicate-draft-unavailable");
            }
            DraftDTO draft = getService().duplicateDraft(id);

            List<Link> linkList = new ArrayList<>();
            linkList.add(linkToSelf(id));
            if (isDuplicateDraftCreateAvailable()) {
                linkList.add(linkToCreate());
            }
            linkList.add(linkToUiSchema("/{id}/duplicate-draft", "post", "request"));
            linkList.add(linkToUiSchema("/{id}/duplicate-draft", "post", "response"));

            return withVersion(ResponseEntity.ok(), RestApiResponse.success(draft, hateoasOrNull(Links.of(linkList))));
        }
    }

    @Override
    protected List<Link> buildItemActionLinks(ID id) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<Link> links = new ArrayList<>(super.buildItemActionLinks(id));
            if (getService().supportsDuplicateDraft() && isDuplicateDraftAvailable(id)) {
                links.add(linkToDuplicateDraft(id));
            }
            return links;
        }
    }

    @Override
    protected List<Link> buildEntityActionLinks(ID id) {
        try (var ignored = ResourceStateSnapshotScope.open()) {
            List<Link> links = new ArrayList<>(super.buildEntityActionLinks(id));
            if (getService().supportsDuplicateDraft() && isDuplicateDraftAvailable(id)) {
                links.add(linkToDuplicateDraft(id));
            }
            return links;
        }
    }

    // Duplication requires affirmative decisions. Generic CRUD helpers retain their existing
    // optional-discovery semantics; their permissive null fallback is not suitable here.
    private boolean isDuplicateDraftAvailable(ID id) {
        var capabilities = getCapabilityService();
        String resourceKey = getResourceKeyOrNull();
        if (id == null || capabilities == null || !StringUtils.hasText(resourceKey)
                || !StringUtils.hasText(getBasePath())) return false;
        try {
            return isAllowed(capabilities.collectionOperationAvailability(resourceKey, getBasePath(), "create"))
                    && isAllowed(capabilities.itemOperationAvailability(resourceKey, getBasePath(), "view", id))
                    && isAllowed(capabilities.itemOperationAvailability(resourceKey, getBasePath(), "duplicate-draft", id));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean isDuplicateDraftCreateAvailable() {
        var capabilities = getCapabilityService();
        String resourceKey = getResourceKeyOrNull();
        if (capabilities == null || !StringUtils.hasText(resourceKey)) return false;
        try {
            return isAllowed(capabilities.collectionOperationAvailability(resourceKey, getBasePath(), "create"));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isAllowed(AvailabilityDecision decision) {
        return decision != null && decision.allowed();
    }

    protected Link linkToDuplicateDraft(ID id) {
        return Link.of(resourcePath(id, "duplicate-draft"), "duplicate-draft");
    }
}
