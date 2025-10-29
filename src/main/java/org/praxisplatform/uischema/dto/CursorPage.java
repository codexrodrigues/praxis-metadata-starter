package org.praxisplatform.uischema.dto;

import java.util.List;

/**
 * Result set for cursor/keyset pagination.
 *
 * @param content page content
 * @param next    cursor pointing to the next page or {@code null}
 * @param prev    cursor pointing to the previous page or {@code null}
 * @param size    requested page size
 * @param <T>     content type
 */
public record CursorPage<T>(List<T> content, String next, String prev, int size) {}

