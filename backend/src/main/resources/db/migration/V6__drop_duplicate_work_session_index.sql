-- Flex Clock, schema version 6: one work session index on (procedure_id, started_at), not two.
--
-- V3 created idx_work_session_procedure_started on (procedure_id, started_at). V4 created
-- ix_work_session_procedure with the same columns under another name. SQLite keeps both, so every
-- inserted work session maintained two identical indexes and the query planner had two equal choices.
--
-- V4 is frozen: editing it would change its checksum and validate-on-migrate would abort on every
-- installed database (decision 0058). The correction is therefore a new migration.
--
-- What survives after this migration:
--   idx_work_session_procedure_started  (procedure_id, started_at), created by V3
--   ux_work_session_open_procedure      partial unique on (procedure_id) WHERE ended_at IS NULL, V3
--   ix_work_session_started_at          (started_at), created by V4, kept because it answers a
--                                       different query (the latest sessions in time order)
--
-- DROP INDEX IF EXISTS keeps the migration safe on any database that never carried the duplicate,
-- and the unique partial index is not touched: there is only one of it, because V4 created it with
-- IF NOT EXISTS under the same name V3 used.

DROP INDEX IF EXISTS ix_work_session_procedure;
