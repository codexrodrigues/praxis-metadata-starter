# C15 historical publication-identity negative producer

Private test fixture copied from the proven C11 public rc149 consumer. Boot3.5.15,
Springdoc baseline, Flyway11.17.0, public149 POM/JAR/tag/SQL remain coherent. This
fixture installs no public GAV and must run in a fresh child repository with only
that published JAR owning SDK classes/resources.

The selected native-writer test creates a fully initialized V15 database, a real
published READY operation and an evaluated retained proposal. A test-only parameter
`historical.suspend.before.close=true` then calls the historical public lifecycle
API, proving SUSPENDED generation2 and zero READY before child context shutdown.
It never deletes publication rows: the parent performs that explicit corruption
only after independently observing the committed existing row.

This is a native15 producer, not a public14 producer, migration correction,
corporate authorization proof, public recipe or adoption guidance. Original C11
and C13/C14 fixtures/evidence are unchanged. Parent/child XML, actual process exits,
JAR CodeSource, physical classpath exclusion and cleanup are separate gates.
