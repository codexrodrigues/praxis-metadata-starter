package org.praxisplatform.uischema.dto;

import java.util.Map;

/**
 * Lightweight projection for select options used in formulários corporativos,
 * onde apenas o identificador e o rótulo são necessários para popular
 * componentes UI com baixo tráfego de dados.
 *
 * <p><strong>Uso em DTOs (@UISchema)</strong>: referencie {@code /options/filter} no
 * {@code endpoint} e mapeie {@code valueField/displayField} para {@code id/label}:</p>
 * <pre>{@code
 * @UISchema(
 *   controlType = FieldControlType.SELECT,
 *   endpoint = ApiPaths.Catalog.CATEGORIAS + "/options/filter",
 *   valueField = "id",
 *   displayField = "label"
 * )
 * private Long categoriaId;
 * }
 * </pre>
 * <p>Para reidratação de valores salvos, utilize {@code GET /options/by-ids?ids=...}.</p>
 *
 * @param id    identifier value
 * @param label human friendly label
 * @param extra optional additional attributes
 * @param <ID>  type of the identifier
 */
public record OptionDTO<ID>(ID id, String label, Map<String, Object> extra) {}
