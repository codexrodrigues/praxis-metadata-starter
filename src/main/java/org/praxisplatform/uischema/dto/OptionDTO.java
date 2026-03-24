package org.praxisplatform.uischema.dto;

import java.util.Map;

/**
 * Projecao leve canonica para opcoes de selecao.
 *
 * <p>
 * {@code OptionDTO} e o payload padrao retornado pelos endpoints {@code /options/filter},
 * {@code /options/by-ids} e pelas option-sources derivadas. Ele existe para reduzir trafego,
 * evitar exposicao desnecessaria do DTO completo e padronizar o contrato consumido por selects,
 * multiselects e combos dependentes.
 * </p>
 *
 * <p><strong>Uso em DTOs (@UISchema)</strong>: referencie {@code /options/filter} no
 * {@code endpoint} e mapeie {@code valueField/displayField} para {@code id/label}.</p>
 *
 * @param id valor identificador
 * @param label rotulo legivel ao usuario
 * @param extra atributos adicionais opcionais
 * @param <ID> tipo do identificador
 */
public record OptionDTO<ID>(ID id, String label, Map<String, Object> extra) {}
