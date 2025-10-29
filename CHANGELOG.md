# Changelog — praxis-metadata-starter

All notable changes to this module will be documented in this file.

## [1.0.0-beta.1] - YYYY-MM-DD

### Added
- New annotation `@OptionLabel` to declare the label source for OptionDTO on entity field or getter (supports inheritance).
- Default `OptionMapper` fallback in `BaseCrudService`: if `getOptionMapper()` is not overridden, entities are projected to `OptionDTO` using `extractId()` and `computeOptionLabel()`.

### Compatibility
- No breaking changes. Existing services and custom mappers continue to work unchanged.

