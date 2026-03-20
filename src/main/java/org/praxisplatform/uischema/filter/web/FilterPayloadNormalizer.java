package org.praxisplatform.uischema.filter.web;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface FilterPayloadNormalizer {

    boolean normalizeInPlace(ObjectNode payload, Class<?> filterClass);
}
