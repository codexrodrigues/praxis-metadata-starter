package org.praxisplatform.uischema.dto;

import java.util.Map;

/**
 * Lightweight projection for select options used in formulários corporativos,
 * onde apenas o identificador e o rótulo são necessários para popular
 * componentes UI com baixo tráfego de dados.
 *
 * @param id    identifier value
 * @param label human friendly label
 * @param extra optional additional attributes
 * @param <ID>  type of the identifier
 */
public record OptionDTO<ID>(ID id, String label, Map<String, Object> extra) {}
