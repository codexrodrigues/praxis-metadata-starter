package org.praxisplatform.uischema.bulk;

/**
 * Marker interface identifying canonical bulk resource controller base classes
 * (both legacy governed controllers and lightweight v2 bulk controllers) so
 * {@link BulkControllerOperationIds} can derive default operation IDs without
 * coupling directly to a concrete implementation class.
 */
public interface BulkResourceController {}
