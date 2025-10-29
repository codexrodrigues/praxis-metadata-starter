package org.praxisplatform.uischema.dto;

/**
 * Representa a posição de um registro em uma lista paginada.
 *
 * @param position índice absoluto (zero-based) do registro
 * @param page     página correspondente considerando o tamanho informado
 */
public record LocateResponse(long position, long page) {}
