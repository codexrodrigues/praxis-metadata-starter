package org.praxisplatform.consumer;

import org.praxisplatform.uischema.bulk.BulkOperationControlIdentity;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Test-only servlet route that exercises fresh OpenAPI composition in a real request context. */
@RestController
@RequestMapping("/_test/bulk-lifecycle")
public class ArtifactLifecycleTestController {

    private static final BulkOperationControlIdentity OPERATION = new BulkOperationControlIdentity(
            "artifact-consumer-test", ArtifactBulkController.CONFIRMATION);

    private final ObjectProvider<BulkOperationLifecycle> lifecycle;

    public ArtifactLifecycleTestController(ObjectProvider<BulkOperationLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/publish-and-verify")
    public Map<String, Object> publishAndVerify() {
        BulkOperationLifecycle service = lifecycle.getObject();
        var ready = service.publish(OPERATION, 0);
        var verified = service.requireReady(OPERATION);
        return Map.of("generation", ready.generation(), "verified", verified.equals(ready),
                "descriptorFingerprint", verified.descriptorFingerprint(), "structuralRevision", verified.structuralRevision());
    }
}
