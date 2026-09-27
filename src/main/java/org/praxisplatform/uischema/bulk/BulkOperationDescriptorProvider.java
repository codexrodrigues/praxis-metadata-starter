package org.praxisplatform.uischema.bulk;

/**
 * Host-owned, stable description of the policy and persistence binding for one declared bulk
 * confirmation operation. A provider supplies no route and cannot publish readiness by itself.
 *
 * <p>The revision must cover evaluation, authorization/admission, and domain mutation semantics.
 * It is distinct from an evaluator-only revision. The infrastructure is the runtime transaction
 * binding; the control-plane transaction is configured separately by Metadata.</p>
 *
 * <p>{@code deploymentId} supplied by the infrastructure must be a stable logical deployment
 * identity shared by its replicas, never a pod, process, or node identifier. Provider IDs and
 * revisions are public digest inputs: never encode credentials, tokens, subject IDs, or secrets in
 * them. The descriptor contract is declarative; production READY remains gated on real host MVC
 * handlers and the host's concrete provider wiring.</p>
 */
public interface BulkOperationDescriptorProvider {

    /** Canonical confirmation operationId already present in the validated MVC declaration. */
    String confirmationOperationId();

    /** Stable governed identity for the implementation of this operation. */
    String providerId();

    /** Revision covering evaluation, admission and mutation behavior. */
    String providerRevision();

    /** Exact identity codec used by the request reader and domain adapter. */
    BulkIdentityCodec<?, ?> identityCodec();

    /**
     * JSON Pointer to the explicit-selection item identity in the canonical evaluation request
     * schema. Composition compares this node to {@link BulkIdentityCodec#wireSchema()} and fails
     * closed on any mismatch.
     */
    String identitySchemaPointer();

    /** Server-owned limits and supported modes, included in the operation's effective contract. */
    BulkOperationalProfile profile();

    /** Runtime transaction binding for the operation. */
    BulkExecutionInfrastructure executionInfrastructure();
}
