# Native pre-V14 producer (C13)

Private test-only consumer pinned to the immutable public Metadata `8.0.0-rc.146`.
It uses native migration V1–V13, declared owner/runtime/control roles, public lifecycle publication and transactional `insertEvaluated`. It never inserts proposal, control, allocation or bootstrap markers through SQL.

The parent owns PostgreSQL and launches this project in a separate JVM with a initially empty Central-only Maven repository. The child records artifact provenance and commits a real proposal with evaluation, ordinal manifest, preview and integrity companions. Current candidate classes are prohibited in its classpath.

Only `ArtifactConsumerHttpTest#writesNativePre14Proposal` is a producer proof. Route responses describe test MVC dispatch, not actual domain mutation or corporate authorization. Successful historical production does not imply successful current upgrade.

Source reused from the independently accepted rc.149 cutover fixture on main `d85ccffdd6c17220e088f5dbb87b8a8948a20213`; V14/V15 function grants are removed because these APIs do not exist in rc.146. No production or existing cutover fixture is modified.

Historical dependency baseline: Boot parent `3.2.5`, matching the immutable rc.146 published POM/tag; native Springdoc `2.6.0` and Flyway `11.17.0` are preserved. Reusing the rc.149 consumer parent `3.5.15` produced a retained startup failure before proposal creation; no SDK or converter override is used.
