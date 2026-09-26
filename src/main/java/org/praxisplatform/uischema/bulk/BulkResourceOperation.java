package org.praxisplatform.uischema.bulk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a real MVC handler with its structural role in the shared bulk lifecycle. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BulkResourceOperation {
    Role value();

    enum Role {
        PROPOSAL("GET"),
        PROPOSAL_RESULTS("GET"),
        EXECUTION("GET"),
        EXECUTION_RESULTS("GET"),
        CANCEL("POST");

        private final String httpMethod;

        Role(String httpMethod) {
            this.httpMethod = httpMethod;
        }

        public String httpMethod() {
            return httpMethod;
        }
    }
}
