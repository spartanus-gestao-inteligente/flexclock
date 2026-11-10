-- Flex Clock, schema version 2: seed rows of the closed settings catalogue.
--
-- V1 is frozen (decision 0058). This migration only inserts data into the settings table that V1
-- created, so no column, type, constraint or table changes and no existing row is touched.
--
-- The catalogue itself is closed in code (br.com.flexclock.platform.SettingKey). This file seeds the
-- same keys with the same values, types, ranges and allowed writers, because a fresh database must be
-- usable before the installer runs: the backend reads the effective language, timezone, region clock
-- source and OpenCode endpoint from this table (decision 0026, requirement RF-008 and RF-007).
--
-- Every row is written by the installer at first run and may be changed later under the ownership
-- rule of the owner column: a key owned by the user is exclusive to the user and is never writable by
-- the agent (requirement RF-162 and rule RN-051).
--
-- updated_at is stored as the UTC instant in epoch milliseconds, the representation the SQLite JDBC
-- driver and the Hibernate timestamp mapping agree on. strftime evaluates the instant when this
-- migration is applied; the Flyway checksum covers the statement, not the instant.
--
-- Seeded keys, in catalogue order:
--   language               enum     en                          owner agent  (0019)
--   timezone               string   Etc/UTC                     owner agent  (0019)
--   orchestration.model    enum     central_coordinator         owner agent  (0026)
--   report.default.period  enum     day                         owner agent  (0019)
--   time.default-mode      enum     stopwatch                   owner agent  (0020, 0057)
--   time.default.target-seconds  integer  3600, range 1 to 86400 owner agent (0005, 0020)
--   opencode.endpoint      url      http://127.0.0.1:4096       owner user   (0034, 0043, 0046, 0150)
--   region.clock-source    enum     system                      owner agent  (0019)

INSERT INTO settings (key, value_type, value, min_value, max_value, owner, updated_by, updated_at) VALUES
    ('language', 'enum', 'en', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('timezone', 'string', 'Etc/UTC', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('orchestration.model', 'enum', 'central_coordinator', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('report.default.period', 'enum', 'day', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('time.default-mode', 'enum', 'stopwatch', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('time.default.target-seconds', 'integer', '3600', '1', '86400', 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('opencode.endpoint', 'url', 'http://127.0.0.1:4096', NULL, NULL, 'user', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('region.clock-source', 'enum', 'system', NULL, NULL, 'agent', 'installer', CAST(strftime('%s', 'now') AS INTEGER) * 1000);
