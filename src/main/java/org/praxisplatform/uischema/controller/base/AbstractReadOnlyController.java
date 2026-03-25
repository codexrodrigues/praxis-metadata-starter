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
 * <h2>📄 Controller Base — Somente Leitura (Views JPA / {@code @Immutable})</h2>
 *
 * <p>
 * Para recursos materializados a partir de views do banco ou entidades {@code @Immutable},
 * este controller oferece uma superfície de leitura completa — e bloqueia escrita com {@code 405}.
 * Sem duplicar endpoints; você herda e ganha tudo de graça.
 * </p>
 *
 * <h3>✨ O que vem pronto</h3>
 * <div class="ep-grid">
 *   <div class="ep"><span class="badge method get">GET</span> <code>/{id}</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/all</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/filter</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/filter/cursor</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/locate</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/options/filter</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/options/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/option-sources/{sourceKey}/options/filter</code></div>
 *   <div class="ep"><span class="badge method get">GET</span> <code>/option-sources/{sourceKey}/options/by-ids</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/group-by</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/timeseries</code></div>
 *   <div class="ep"><span class="badge method post">POST</span> <code>/stats/distribution</code></div>
 *   <div class="ep"><span class="badge method misc">GET</span> <code>/schemas</code> → <code>/schemas/filtered</code></div>
 * </div>
 * <p><strong>Escrita segura:</strong> {@code POST /}, {@code PUT /{id}}, {@code DELETE /{id}}, {@code DELETE /batch} → {@code 405 Method Not Allowed}</p>
 * <p><strong>Filtros (26 operações)</strong> + paginação tradicional e por cursor; <strong>options id/label</strong>, <strong>option-sources</strong>, <strong>stats</strong> e schema x-ui.</p>
 *
 * <h3>Quando escolher esta base</h3>
 * <ul>
 *   <li>Views materializadas, visoes SQL ou entidades anotadas com {@code @Immutable}.</li>
 *   <li>Recursos analiticos ou de consulta em que a escrita nao deve ser exposta pela API.</li>
 *   <li>Superficies em que a UI ainda precisa de filtros, options e schema metadata-driven.</li>
 * </ul>
 *
 * <p>
 * Esta classe preserva a mesma superficie de leitura do CRUD base e apenas sobrescreve as
 * operacoes mutantes para responder com {@code 405 Method Not Allowed}. Assim, a API continua
 * coerente para consumidores de schema, filtros e componentes dinâmicos, sem sugerir capacidades
 * de escrita inexistentes.
 * </p>
 */
public abstract class AbstractReadOnlyController<E, D, ID, FD extends GenericFilterDTO>
        extends AbstractCrudController<E, D, ID, FD> {

    @Override
    protected boolean isReadOnlyResource() { return true; }

    /**
     * Bloqueia criacao de registros em recursos somente leitura.
     *
     * <p>
     * A operacao e mantida no controller apenas para preservar a simetria contratual com a base
     * CRUD. Na pratica, qualquer tentativa de {@code POST /} falha com {@code 405}, deixando claro
     * para clientes HTTP e para integradores que o recurso nao aceita escrita.
     * </p>
     *
     * @param dto payload recebido; ignorado porque a operacao e proibida
     * @return nunca retorna normalmente
     * @throws org.springframework.web.server.ResponseStatusException sempre com {@code 405 Method Not Allowed}
     */
    @Override
    @PostMapping
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<RestApiResponse<D>> create(@jakarta.validation.Valid @RequestBody D dto) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Read-only resource.");
    }

    /**
     * Bloqueia atualizacao de registros em recursos somente leitura.
     *
     * @param id identificador do recurso alvo
     * @param dto payload recebido; ignorado porque a operacao e proibida
     * @return nunca retorna normalmente
     * @throws org.springframework.web.server.ResponseStatusException sempre com {@code 405 Method Not Allowed}
     */
    @Override
    @PutMapping("/{id}")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<RestApiResponse<D>> update(@PathVariable ID id, @jakarta.validation.Valid @RequestBody D dto) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Read-only resource.");
    }

    /**
     * Bloqueia exclusao individual de registros em recursos somente leitura.
     *
     * @param id identificador do recurso alvo
     * @return nunca retorna normalmente
     * @throws org.springframework.web.server.ResponseStatusException sempre com {@code 405 Method Not Allowed}
     */
    @Override
    @DeleteMapping("/{id}")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<Void> delete(@PathVariable ID id) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Read-only resource.");
    }

    /**
     * Bloqueia exclusao em lote de registros em recursos somente leitura.
     *
     * @param ids identificadores recebidos; ignorados porque a operacao e proibida
     * @return nunca retorna normalmente
     * @throws org.springframework.web.server.ResponseStatusException sempre com {@code 405 Method Not Allowed}
     */
    @Override
    @DeleteMapping("/batch")
    @Operation(summary = "Recurso somente leitura", hidden = true)
    public ResponseEntity<Void> deleteBatch(@RequestBody List<ID> ids) {
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Read-only resource.");
    }
}
