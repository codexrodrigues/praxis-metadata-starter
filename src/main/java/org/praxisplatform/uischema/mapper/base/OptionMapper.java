package org.praxisplatform.uischema.mapper.base;

import org.praxisplatform.uischema.dto.OptionDTO;

/**
 * Maps entities to lightweight {@link OptionDTO} projections used by
 * componentes de seleção. A implementação típica extrai apenas os campos
 * necessários para preencher combos em aplicações corporativas.
 *
 * @param <E>  entity type
 * @param <ID> identifier type
 */
public interface OptionMapper<E, ID> {
    OptionDTO<ID> toOption(E entity);
}
