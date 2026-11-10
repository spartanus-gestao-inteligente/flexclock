-- Flex Clock, schema version 3: sessions, anchors and procedure (workstream WS-B2).
--
-- V1 is the frozen baseline and V2 belongs to another workstream, so this file carries only what the
-- sessions, anchors and procedure lifecycle needs:
--
-- 1. The nullable foreign key from work_session to procedure, so a work session can never point to a
--    procedure that does not exist while a work session without a procedure stays valid (requirement
--    RF-030; decision 0042).
-- 2. The indexes used by the anchor maintenance, by the history filter and by the clock lookup
--    (requirements RF-016, RF-019, RF-031 and RNF-002).
-- 3. A partial unique index that makes "at most one open work session per procedure" a database rule,
--    because each window has its own clock and the product never runs two clocks on the same
--    procedure (decision 0041; requirement RF-020).
-- 4. Two guards that make the procedure end instant and its final status write once (requirements
--    RF-029 and RN-016).
--
-- SQLite cannot add a constraint to an existing table, so work_session is rebuilt: the new table is
-- created with the foreign key, the rows are copied, the old table is dropped and the new one takes
-- its name. The declared column types are identical to V1, so Hibernate validation still matches. A
-- row whose procedure reference dangles is copied with a null procedure, because a work session
-- without a procedure is a valid row and losing a time record would be worse than losing a link that
-- was already broken.

CREATE TABLE work_session_v3 (
    id integer,
    procedure_id bigint,
    mode varchar(32) NOT NULL CHECK (mode IN ('stopwatch', 'project_stopwatch')),
    target_seconds integer,
    started_at timestamp NOT NULL,
    ended_at timestamp,
    total_ms bigint NOT NULL,
    paused_ms bigint NOT NULL,
    active_ms bigint NOT NULL,
    timezone varchar(64) NOT NULL,
    override_author varchar(128),
    override_at timestamp,
    PRIMARY KEY (id),
    CONSTRAINT fk_work_session_procedure FOREIGN KEY (procedure_id) REFERENCES procedure (id) ON DELETE RESTRICT
);

INSERT INTO work_session_v3 (id, procedure_id, mode, target_seconds, started_at, ended_at, total_ms, paused_ms,
    active_ms, timezone, override_author, override_at)
SELECT id,
    CASE
        WHEN procedure_id IS NULL THEN NULL
        WHEN EXISTS (SELECT 1 FROM procedure p WHERE p.id = work_session.procedure_id) THEN procedure_id
        ELSE NULL
    END,
    mode, target_seconds, started_at, ended_at, total_ms, paused_ms, active_ms, timezone, override_author,
    override_at
FROM work_session;

DROP TABLE work_session;

ALTER TABLE work_session_v3 RENAME TO work_session;

-- Heartbeat lookup of the orphan cleanup: the cleanup deletes by the last heartbeat instant.
CREATE INDEX idx_agent_anchor_updated_at ON agent_anchor (updated_at);

-- History filter of the procedure list and of the dashboard.
CREATE INDEX idx_procedure_status ON procedure (status);

-- Clock lookup and history of one procedure.
CREATE INDEX idx_work_session_procedure_started ON work_session (procedure_id, started_at);

-- At most one open work session per procedure. A closed session leaves the index free, and the
-- standalone clock, whose procedure is null, is not covered here because SQLite treats nulls as
-- distinct in a unique index; the service enforces the single standalone clock.
CREATE UNIQUE INDEX ux_work_session_open_procedure ON work_session (procedure_id)
    WHERE procedure_id IS NOT NULL AND ended_at IS NULL;

-- The end instant of a procedure is written once and never rewritten (requirement RF-029).
CREATE TRIGGER trg_procedure_ended_at_write_once
BEFORE UPDATE OF ended_at ON procedure
FOR EACH ROW
WHEN OLD.ended_at IS NOT NULL AND (NEW.ended_at IS NULL OR NEW.ended_at IS NOT OLD.ended_at)
BEGIN
    SELECT RAISE(ABORT, 'procedure.ended_at is written once and cannot be rewritten');
END;

-- A procedure that reached completed or aborted is final: it never returns to active and never moves
-- to the other final status (requirements RF-029 and RN-016).
CREATE TRIGGER trg_procedure_status_final
BEFORE UPDATE OF status ON procedure
FOR EACH ROW
WHEN OLD.status IN ('completed', 'aborted') AND NEW.status IS NOT OLD.status
BEGIN
    SELECT RAISE(ABORT, 'procedure.status is final after completed or aborted');
END;
