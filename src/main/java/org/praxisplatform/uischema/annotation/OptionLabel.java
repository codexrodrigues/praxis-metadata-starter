package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca o campo ou getter que deve fornecer o label usado nas projecoes
 * {@code OptionDTO}, como combos, selects e auto-complete.
 *
 * <p>
 * O objetivo da anotacao e explicitar a fonte canonica do texto exibido ao usuario quando um
 * recurso ou option source e projetado como opcao reduzida. Isso evita depender apenas de
 * heuristicas de nome de metodo ou de campo.
 * </p>
 *
 * <p>
 * Precedencia na resolucao do label em
 * {@code AbstractBaseQueryResourceService.computeOptionLabel()}:
 * </p>
 *
 * <ol>
 *   <li>Membro anotado com {@code @OptionLabel}, verificando getter antes de campo.</li>
 *   <li>Heuristicas como {@code getLabel()}, {@code getNomeCompleto()}, {@code getNome()}, {@code getDescricao()} e {@code getTitle()}.</li>
 *   <li>Fallback para {@code String.valueOf(id)}.</li>
 * </ol>
 *
 * <p>
 * A deteccao percorre a cadeia de heranca, entao a anotacao pode ficar em uma superclasse quando
 * isso representar a semantica canonica compartilhada.
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface OptionLabel {}
