package org.praxisplatform.uischema.determination;

/** Binding entre o response da determinacao e um campo do draft, usando JSON Pointer. */
public record ReactiveDeterminationOutputBinding(
        String responsePath,
        String fieldPath
) {
}
