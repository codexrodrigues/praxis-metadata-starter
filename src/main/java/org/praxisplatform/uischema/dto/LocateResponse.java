package org.praxisplatform.uischema.dto;

/**
 * Resposta canonica do endpoint de localizacao de registros.
 *
 * <p>
 * Esse contrato informa em qual indice absoluto um registro aparece dentro de um conjunto filtrado
 * e em qual pagina ele cairia para um determinado tamanho de pagina. Ele e especialmente util para
 * grids que precisam navegar diretamente ate o item alvo.
 * </p>
 *
 * @param position indice absoluto zero-based do registro
 * @param page pagina correspondente considerando o tamanho usado na consulta
 */
public record LocateResponse(long position, long page) {}
