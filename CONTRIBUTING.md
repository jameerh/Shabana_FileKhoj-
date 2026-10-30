# Contributing

- Read README and architecture docs before changing modules.
- Never include customer document contents in logs, tests, or issue attachments.
- Add tests for normalization, ranking, state transitions and index consistency.
- Add instrumentation/performance tests for database and UI behavior.
- Use Room migrations for schema changes.
- Keep indexing idempotent and cancellation-safe.
- Never commit local.properties, signing material, generated outputs or user documents.
- Document new permissions, network calls, telemetry and retention behavior.
