package org.praxisplatform.uischema.bulk;

/** Business intent family, independent of sync/async transport and atomicity. */
public enum BulkMode { DOMAIN_COMMAND, UNIFORM_UPDATE, PER_ITEM_UPDATE }
