package org.praxisplatform.uischema.bulk;

/** Operation mode executed by an asynchronous namespace-scoped bulk publication job. */
public enum BulkPublicationJobIntent {
    PUBLISH_ALL,
    RECONCILE_ALL,
    CONVERGE_ALL
}
