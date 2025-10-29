package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.LocateResponse;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Links;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Base Controller para recursos somente leitura (ex.: JPA Views @Immutable).
 *
 * Expõe apenas endpoints de leitura herdados e bloqueia operações de escrita
 * com HTTP 405 (Method Not Allowed).
 */
public abstract class AbstractReadOnlyController<E, D, ID, FD extends GenericFilterDTO>
        extends AbstractCrudController<E, D, ID, FD> {

    @Override
    protected boolean isReadOnlyResource() { return true; }

    @Override
    @PostMapping
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<RestApiResponse<D>> create(@jakarta.validation.Valid @RequestBody D dto) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "recurso somente leitura");
    }

    @Override
    @PutMapping("/{id}")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<RestApiResponse<D>> update(@PathVariable ID id, @jakarta.validation.Valid @RequestBody D dto) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "recurso somente leitura");
    }

    @Override
    @DeleteMapping("/{id}")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "recurso somente leitura");
    }

    @Override
    @DeleteMapping("/batch")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<Void> deleteBatch(@RequestBody List<ID> ids) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "recurso somente leitura");
    }
}

