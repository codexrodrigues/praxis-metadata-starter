package org.praxisplatform.uischema.determination;

import java.util.Collection;

/**
 * SPI para hosts registrarem bindings estruturais de determinacoes reativas.
 *
 * <p>O registry captura as definicoes uma unica vez durante o bootstrap. Providers devem ser
 * deterministas e independentes de request, principal e tenant. A operacao referenciada continua
 * sendo responsavel por autorizacao, facts, avaliacao e validacao final.</p>
 */
@FunctionalInterface
public interface ReactiveDeterminationDefinitionProvider {

    Collection<ReactiveDeterminationDefinition> definitions();
}
