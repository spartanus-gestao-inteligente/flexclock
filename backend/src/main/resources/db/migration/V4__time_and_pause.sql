-- Flex Clock, schema version 4: work session and agent pause support.
--
-- V1 is frozen and owns the fourteen contract tables. This migration adds no column and changes no
-- declared type: it adds the indexes the time and pause paths query, plus two partial unique indexes
-- that make the "no ghost pause" and the "one open work session per procedure" rules impossible to
-- break from a second writer. The application enforces both rules first and fails with a stable error;
-- the database guard is defense in depth, the same posture used by the append only triggers in WS-B4.
--
-- The partial indexes are supported by SQLite and are ignored by a row whose procedure is null, so a
-- work session without a procedure stays valid (requirement RF-030).
--
-- Migration allocation is frozen by decision 0058: V2 WS-B1, V3 WS-B2, V4 WS-B3, V5 WS-B4.
--
-- The work_session statements use IF NOT EXISTS on purpose: the sessions workstream also rebuilds the
-- table and may already carry the same rule under the same index name, and an integration must never
-- fail at startup because two migrations agree on the same guard. The definition below stays the
-- authoritative one for a database that only has V1 and V4.

-- Query path of the current clock state: the open session of a procedure, and the latest open session.
CREATE INDEX IF NOT EXISTS ix_work_session_procedure ON work_session (procedure_id, started_at);

-- Query path of the time report and of the midnight split: the sessions of a procedure in order.
CREATE INDEX IF NOT EXISTS ix_work_session_started_at ON work_session (started_at);

-- One open work session per procedure. A procedure may run one clock at a time, and a second start
-- must be refused with WORK_SESSION_ALREADY_RUNNING instead of creating a duplicate row.
CREATE UNIQUE INDEX IF NOT EXISTS ux_work_session_open_procedure ON work_session (procedure_id)
    WHERE ended_at IS NULL AND procedure_id IS NOT NULL;

-- Query path of the pause history of a procedure and of the paused time of a work session.
CREATE INDEX IF NOT EXISTS ix_agent_pause_procedure ON agent_pause (procedure_id, pause_start);

-- Query path of the current pause of an OpenCode session.
CREATE INDEX IF NOT EXISTS ix_agent_pause_session ON agent_pause (session_id, status);

-- Query path of the 60 minute expiry job.
CREATE INDEX IF NOT EXISTS ix_agent_pause_expiry ON agent_pause (status, pause_start);

-- One open pause per procedure. paused and expired are both unresolved, so a procedure can never hold
-- two at the same time; the application refuses the transition and the index refuses the race.
CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_pause_open_procedure ON agent_pause (procedure_id)
    WHERE status IN ('paused', 'expired');
