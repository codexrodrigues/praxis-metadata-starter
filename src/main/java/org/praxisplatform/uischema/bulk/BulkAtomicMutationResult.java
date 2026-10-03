package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ordered, protected outcomes and host-declared stable references to transactional effects. */
@JsonIgnoreType
public final class BulkAtomicMutationResult {
    private final List<Item> items;

    public BulkAtomicMutationResult(List<Item> items) {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty() || items.size() > 50)
            throw new IllegalArgumentException("Atomic mutation requires 1 to 50 outcomes");
        this.items = List.copyOf(items);
        var references = new HashSet<String>();
        for (int ordinal = 0; ordinal < this.items.size(); ordinal++) {
            Item item = this.items.get(ordinal);
            if (item.ordinal() != ordinal) throw new IllegalArgumentException("Atomic outcomes must be ordinal ordered");
            for (String reference : item.effectReferences())
                if (!references.add(reference)) throw new IllegalArgumentException("Duplicate atomic effect reference");
        }
    }

    public List<Item> items() { return items; }
    @Override public String toString() { return "BulkAtomicMutationResult[protected]"; }

    public record Item(int ordinal, BulkUnitOutcome outcome, List<String> effectReferences) {
        public Item {
            if (ordinal < 0) throw new IllegalArgumentException("Negative atomic ordinal");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(effectReferences, "effectReferences");
            if (effectReferences.size() > 8) throw new IllegalArgumentException("Too many effect references");
            effectReferences = List.copyOf(effectReferences);
            if (outcome == BulkUnitOutcome.UNCHANGED && !effectReferences.isEmpty())
                throw new IllegalArgumentException("Unchanged atomic item cannot declare effect references");
            for (String reference : effectReferences)
                if (!BulkTargetDigest.validEffectReference(reference))
                    throw new IllegalArgumentException("Invalid atomic effect reference");
        }
    }
}
