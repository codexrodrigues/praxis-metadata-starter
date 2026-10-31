# Typed partial updates and presence

`PartialUpdateInput` tracks whether a typed setter was called, including with null.
An operation DTO extends it and calls `supplied("publicWireName")` in each setter.
The handler uses `wasSupplied` before applying the property. Omission preserves
state; explicit null clears only fields whose schema and domain permit it.

Use `@JsonSetter(nulls = Nulls.SET)` and `@Schema(nullable = true)` for nullable
properties. Use `Nulls.FAIL` for mandatory stored properties while permitting their
omission from the partial request. Keep bean/domain validation, authorization,
resource-version preconditions and transaction invariants on the normal write path.
Do not implement this policy by merging arbitrary JSON into managed entities.

Tracking state is ignored by Jackson and hidden from OpenAPI. The semantic reviewer
excludes properties explicitly excluded by both annotations; schema hiding alone
does not excuse missing domain documentation.

Discovery response projections that lose their final live operational proof fail
closed with HTTP 503 and `BULK_DISCOVERY_UNAVAILABLE`. No partial capability snapshot
or nested infrastructure cause is returned. Consumers refresh availability before
continuing. Deadlines and live authorization checks are unchanged.

Publication preparation failures use the existing governed OpenAPI unavailable
503 contract. A transaction deadline reached before projection is classified as
503 `TRANSACTION_TIMEOUT`. Neither outcome authorizes automatic mutation replay:
inspect durable state and current generation before an explicit retry. These
classifications do not enlarge budgets or certify availability under every load.
