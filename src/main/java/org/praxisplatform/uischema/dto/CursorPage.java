package org.praxisplatform.uischema.dto;

import java.util.List;

/**
 * Resultado canonico de paginacao por cursor/keyset.
 *
 * <p>
 * Esse contrato e usado pelos endpoints {@code /filter/cursor} para navegacao estavel em listas
 * longas ou sujeitas a mutacoes. Em vez de depender de offset numerico, a navegacao ocorre por
 * cursores opacos em {@code next} e {@code prev}.
 * </p>
 *
 * @param content conteudo da pagina atual
 * @param next cursor para a proxima pagina ou {@code null}
 * @param prev cursor para a pagina anterior ou {@code null}
 * @param size tamanho solicitado da pagina
 * @param <T> tipo do conteudo
 */
public record CursorPage<T>(List<T> content, String next, String prev, int size) {}

