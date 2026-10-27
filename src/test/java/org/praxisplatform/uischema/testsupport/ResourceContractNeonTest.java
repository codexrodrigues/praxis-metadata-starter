package org.praxisplatform.uischema.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;

/** Preserve Spring after-class listeners, close its cached context, then remove only assigned OIDs. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ResourceContractNeonSpringCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface ResourceContractNeonTest { }
