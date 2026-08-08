package org.praxisplatform.uischema.openapi;

import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiDocumentWarmupTest {

    @Test
    void doesNotLoadGroupsWhenTheHostDidNotOptIn() {
        OpenApiDocumentService documentService = mock(OpenApiDocumentService.class);
        OpenApiDocumentWarmup warmup = new OpenApiDocumentWarmup(
                documentService,
                List.of(group("human-resources")),
                Runnable::run,
                false);

        warmup.warmAfterApplicationReady();

        verify(documentService, never()).getDocumentForGroup("human-resources");
    }

    @Test
    void warmsEveryDistinctPublishedGroup() {
        OpenApiDocumentService documentService = mock(OpenApiDocumentService.class);
        OpenApiDocumentWarmup warmup = new OpenApiDocumentWarmup(
                documentService,
                List.of(group("human-resources"), group("operations"), group("human-resources")),
                Runnable::run,
                true);

        warmup.warmAfterApplicationReady();

        verify(documentService).getDocumentForGroup("human-resources");
        verify(documentService).getDocumentForGroup("operations");
    }

    @Test
    void continuesWhenOneGroupCannotBeWarmed() {
        OpenApiDocumentService documentService = mock(OpenApiDocumentService.class);
        when(documentService.getDocumentForGroup("operations")).thenThrow(new IllegalStateException("unavailable"));
        OpenApiDocumentWarmup warmup = new OpenApiDocumentWarmup(
                documentService,
                List.of(group("operations"), group("human-resources")),
                Runnable::run,
                true);

        warmup.warmAfterApplicationReady();

        verify(documentService).getDocumentForGroup("operations");
        verify(documentService).getDocumentForGroup("human-resources");
    }

    private GroupedOpenApi group(String name) {
        return GroupedOpenApi.builder().group(name).pathsToMatch("/**").build();
    }
}
