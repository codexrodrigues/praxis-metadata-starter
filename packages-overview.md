# Praxis Metadata Starter package overview

This guide maps the Java packages to the architectural responsibilities of the
starter. It is intended for developers, documentation tools and AI assistants
that need to navigate the source without mistaking a derived endpoint for the
canonical contract.

## Read the packages by responsibility

| Package | Responsibility | Use it when |
| --- | --- | --- |
| `annotation` | Declares resource identity, semantic governance, UI surfaces and explicit workflow actions. | Authoring the public meaning of a resource or field. |
| `extension` | Converts Java annotations and Bean Validation into OpenAPI `x-ui`. | Extending OpenAPI enrichment or field-level metadata resolution. |
| `schema` and `openapi` | Resolve a canonical operation and extract the structural request or response schema. | Working on `/schemas/filtered`, schema references or OpenAPI groups. |
| `controller.docs` | Publishes the structural schema and documentary or semantic catalogs. | Exposing discovery HTTP endpoints; it must not redefine schema semantics. |
| `controller.base` and `service.base` | Provide the resource-oriented HTTP and service baseline. | Creating mutable or read-only resources. |
| `surface`, `action`, `capability` | Publish UX surfaces, explicit business commands and contextual availability snapshots. | Modelling experiences beyond generic CRUD. |
| `filter`, `options`, `stats`, `exporting` | Provide focused operational capabilities derived from the resource contract. | Adding filtering, lookup options, analytics or collection export. |
| `configuration` | Registers the starter with Spring Boot and Springdoc. | Changing auto-configuration or bootstrap behaviour. |
| `rest`, `http`, `concurrency` | Standardize HTTP envelopes, links and conditional resource versions. | Working with responses, HATEOAS, ETag or preconditions. |
| `domain`, `authoring`, `validation` | Support semantic domain discovery and validate authoring quality. | Publishing governed metadata or validating declarative rules. |
| `id`, `mapper`, `repository`, `util` | Support identity, DTO mapping, persistence integration and small shared operations. | Implementing infrastructure without creating a second public contract. |

## Canonical flow

`@UISchema` and Bean Validation are interpreted by `extension`, which enriches
the OpenAPI document with `x-ui`. `openapi` and `schema` resolve that document
for an operation; `controller.docs.ApiDocsController` then publishes the
filtered structural result. `surface`, `action` and `capability` add semantic
discovery around the same resource, rather than alternative schema sources.

See [the architecture overview](architecture-overview.md),
[the UI Schema concept](concepts/ui-schema.md), and the public
[Javadoc](https://codexrodrigues.github.io/praxis-metadata-starter/apidocs/).
