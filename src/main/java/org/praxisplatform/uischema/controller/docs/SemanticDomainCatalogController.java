package org.praxisplatform.uischema.controller.docs;

import org.praxisplatform.uischema.domain.DomainCatalogResponse;
import org.praxisplatform.uischema.domain.SemanticDomainCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Exporta o vocabulario semantico inicial do dominio em formato AI-operable.
 *
 * <p>
 * Esta superficie nao substitui {@code /schemas/catalog}, {@code /schemas/actions},
 * {@code /schemas/surfaces} ou {@code /schemas/filtered}. Ela agrega significado de dominio
 * derivado dessas fontes canonicas para consumo por runtime, RAG e LLMs.
 * </p>
 */
@RestController
@RequestMapping("/schemas/domain")
public class SemanticDomainCatalogController {

    private final SemanticDomainCatalogService semanticDomainCatalogService;

    public SemanticDomainCatalogController(SemanticDomainCatalogService semanticDomainCatalogService) {
        this.semanticDomainCatalogService = semanticDomainCatalogService;
    }

    /**
     * Retorna o catalogo semantico por recurso ou grupo documental.
     */
    @GetMapping
    public ResponseEntity<DomainCatalogResponse> getDomain(
            @RequestParam(name = "resource", required = false) String resourceKey,
            @RequestParam(name = "resourceKey", required = false) String resourceKeyAlias,
            @RequestParam(name = "group", required = false) String group
    ) {
        if (StringUtils.hasText(resourceKey)
                && StringUtils.hasText(resourceKeyAlias)
                && !resourceKey.equals(resourceKeyAlias)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "'resource' and 'resourceKey' must match when both are provided."
            );
        }
        String resolvedResourceKey = StringUtils.hasText(resourceKey) ? resourceKey : resourceKeyAlias;
        boolean hasResource = StringUtils.hasText(resolvedResourceKey);
        boolean hasGroup = StringUtils.hasText(group);
        if (hasResource == hasGroup) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Exactly one of 'resource', 'resourceKey' or 'group' must be provided."
            );
        }

        DomainCatalogResponse response = hasResource
                ? semanticDomainCatalogService.findByResourceKey(resolvedResourceKey)
                : semanticDomainCatalogService.findByGroup(group);
        return ResponseEntity.ok(response);
    }
}
