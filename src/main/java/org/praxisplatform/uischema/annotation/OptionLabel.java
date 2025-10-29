package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca o campo ou getter que deve fornecer o rótulo (label) utilizado nas
 * projeções {@code OptionDTO} (combos/auto-complete).
 *
 * <p>Uso básico:
 * <pre>{@code
 * // Em campo
 * @OptionLabel
 * private String nome;
 *
 * // Ou no getter
 * @OptionLabel
 * public String getNomeCompleto() { return nomeCompleto; }
 * }
 * </pre>
 *
 * <p>Precedência na resolução do label (em {@code BaseCrudService.computeOptionLabel()}):</p>
 * <ol>
 *   <li>Membro anotado com {@code @OptionLabel} (getter é verificado antes de campo)</li>
 *   <li>Heurísticas: {@code getLabel()}, {@code getNomeCompleto()}, {@code getNome()}, {@code getDescricao()}, {@code getTitle()}</li>
 *   <li>Fallback: {@code String.valueOf(id)}</li>
 * </ol>
 *
 * <p>Herança:</p>
 * <p>A detecção percorre a cadeia de classes; a anotação pode estar em uma superclasse.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface OptionLabel {}
