package org.praxisplatform.uischema.filter.specification;

import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Objeto para retornar tanto a {@link Specification} quanto o {@link Pageable} após processamento
 * pelo {@link GenericSpecificationsBuilder}
 *
 * @param spec
 * @param pageable
 * @param <E>      Tipo da entidade alvo da consulta.
 * @see GenericSpecificationsBuilder#buildSpecification(GenericFilterDTO, Pageable)
 */
public record GenericSpecification<E>(Specification<E> spec, Pageable pageable) {
}
