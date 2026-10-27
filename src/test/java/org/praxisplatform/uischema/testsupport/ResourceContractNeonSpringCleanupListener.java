package org.praxisplatform.uischema.testsupport;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/** afterTestClass runs in reverse order: 2999 follows DirtiesContext's context close at 3000. */
public final class ResourceContractNeonSpringCleanupListener implements TestExecutionListener, Ordered {
    @Override public int getOrder() { return 2999; }
    @Override public void afterTestClass(TestContext context) throws Exception {
        ResourceContractNeonFixture.closeForClass(context.getTestClass());
    }
}
