package org.praxisplatform.uischema.openapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Preaquece documentos OpenAPI de grupos publicados sem bloquear o startup nem a primeira
 * requisicao do consumidor. O cache continua sendo propriedade de {@link OpenApiDocumentService}.
 */
public final class OpenApiDocumentWarmup {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiDocumentWarmup.class);

    private final OpenApiDocumentService documentService;
    private final List<GroupedOpenApi> groups;
    private final Executor executor;
    private final boolean enabled;

    public OpenApiDocumentWarmup(
            OpenApiDocumentService documentService,
            List<GroupedOpenApi> groups,
            Executor executor,
            boolean enabled) {
        this.documentService = documentService;
        this.groups = groups == null ? List.of() : List.copyOf(groups);
        this.executor = executor;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmAfterApplicationReady() {
        if (!enabled || groups.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            long startedAt = System.nanoTime();
            int warmed = 0;
            for (String group : groups.stream()
                    .map(GroupedOpenApi::getGroup)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList()) {
                try {
                    documentService.getDocumentForGroup(group);
                    warmed++;
                } catch (RuntimeException exception) {
                    LOGGER.warn("OpenAPI prewarm skipped group '{}': {}", group, exception.getMessage());
                }
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOGGER.info("OpenAPI document prewarm completed (groups={}, elapsedMs={})", warmed, elapsedMs);
        });
    }
}
