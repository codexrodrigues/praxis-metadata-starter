package org.praxisplatform.uischema.exporting;

/**
 * Resolve o valor de uma coluna exportavel para uma linha de recurso.
 *
 * <p>O recurso continua dono das regras de acesso, joins e campos derivados; os engines
 * canonicos cuidam apenas da serializacao do formato solicitado.</p>
 */
@FunctionalInterface
public interface CollectionExportValueResolver<T> {

    Object resolve(T row, CollectionExportField field);
}
