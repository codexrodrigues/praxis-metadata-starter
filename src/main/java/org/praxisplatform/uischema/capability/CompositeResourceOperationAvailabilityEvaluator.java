package org.praxisplatform.uischema.capability;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ResourceOperationAvailabilityProvider} composicional que avalia uma lista ordenada de
 * {@link ResourceOperationAvailabilityRule}s com semantica AND (deny-wins) e agregacao de metadata.
 */
public class CompositeResourceOperationAvailabilityEvaluator implements ResourceOperationAvailabilityProvider {

    private final List<ResourceOperationAvailabilityRule> rules;

    public CompositeResourceOperationAvailabilityEvaluator(List<ResourceOperationAvailabilityRule> rules) {
        if (rules == null || rules.isEmpty()) {
            this.rules = List.of();
        } else {
            List<ResourceOperationAvailabilityRule> ordered = new ArrayList<>();
            for (ResourceOperationAvailabilityRule rule : rules) {
                if (rule != null) {
                    ordered.add(rule);
                }
            }
            AnnotationAwareOrderComparator.sort(ordered);
            this.rules = List.copyOf(ordered);
        }
    }

    public List<ResourceOperationAvailabilityRule> getRules() {
        return rules;
    }

    @Override
    public AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context) {
        if (rules.isEmpty()) {
            return AvailabilityDecision.allowAll();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (ResourceOperationAvailabilityRule rule : rules) {
            AvailabilityDecision decision = rule.evaluate(context);
            if (decision == null) {
                continue;
            }
            if (decision.metadata() != null && !decision.metadata().isEmpty()) {
                metadata.putAll(decision.metadata());
            }
            if (!decision.allowed()) {
                String reason = decision.reason() != null && !decision.reason().isBlank()
                        ? decision.reason()
                        : "operation-unavailable";
                return AvailabilityDecision.deny(reason, metadata);
            }
        }
        return AvailabilityDecision.allow(metadata);
    }
}
