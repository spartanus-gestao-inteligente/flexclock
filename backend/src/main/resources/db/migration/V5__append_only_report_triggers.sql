-- Flex Clock, schema version 5: append only enforcement for the report tables.
--
-- WS-B4 owns migration number 5 (decision 0058 allocates V2 to WS-B1, V3 to WS-B2, V4 to WS-B3 and V5
-- to WS-B4). V1 to V4 are frozen and belong to the other workstreams.
--
-- The report tables accept insertion only. The repository exposes no UPDATE and no DELETE, and the
-- triggers below abort any UPDATE or DELETE at the database level, with no exception, including on the
-- error detail table. This is the second half of the append only defence of decision 0049; the first
-- half is the absence of an update or delete method in the code.
--
-- A record is never rewritten: a wrong record is superseded by a new record and an error record keeps
-- its immutable detail in error_detail (decision 0056).
--
-- The startup self check (br.com.flexclock.reports.AppendOnlyGuard) refuses to run the product when one
-- of these triggers is missing (requirement RF-050).

CREATE TRIGGER trg_failure_event_no_update
BEFORE UPDATE ON failure_event
BEGIN
    SELECT RAISE(ABORT, 'failure_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_failure_event_no_delete
BEFORE DELETE ON failure_event
BEGIN
    SELECT RAISE(ABORT, 'failure_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_error_detail_no_update
BEFORE UPDATE ON error_detail
BEGIN
    SELECT RAISE(ABORT, 'error_detail is append only: update is not allowed');
END;

CREATE TRIGGER trg_error_detail_no_delete
BEFORE DELETE ON error_detail
BEGIN
    SELECT RAISE(ABORT, 'error_detail is append only: delete is not allowed');
END;

CREATE TRIGGER trg_hallucination_event_no_update
BEFORE UPDATE ON hallucination_event
BEGIN
    SELECT RAISE(ABORT, 'hallucination_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_hallucination_event_no_delete
BEFORE DELETE ON hallucination_event
BEGIN
    SELECT RAISE(ABORT, 'hallucination_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_quality_evaluation_no_update
BEFORE UPDATE ON quality_evaluation
BEGIN
    SELECT RAISE(ABORT, 'quality_evaluation is append only: update is not allowed');
END;

CREATE TRIGGER trg_quality_evaluation_no_delete
BEFORE DELETE ON quality_evaluation
BEGIN
    SELECT RAISE(ABORT, 'quality_evaluation is append only: delete is not allowed');
END;

CREATE TRIGGER trg_graph_usage_event_no_update
BEFORE UPDATE ON graph_usage_event
BEGIN
    SELECT RAISE(ABORT, 'graph_usage_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_graph_usage_event_no_delete
BEFORE DELETE ON graph_usage_event
BEGIN
    SELECT RAISE(ABORT, 'graph_usage_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_token_economy_event_no_update
BEFORE UPDATE ON token_economy_event
BEGIN
    SELECT RAISE(ABORT, 'token_economy_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_token_economy_event_no_delete
BEFORE DELETE ON token_economy_event
BEGIN
    SELECT RAISE(ABORT, 'token_economy_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_source_event_no_update
BEFORE UPDATE ON source_event
BEGIN
    SELECT RAISE(ABORT, 'source_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_source_event_no_delete
BEFORE DELETE ON source_event
BEGIN
    SELECT RAISE(ABORT, 'source_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_download_event_no_update
BEFORE UPDATE ON download_event
BEGIN
    SELECT RAISE(ABORT, 'download_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_download_event_no_delete
BEFORE DELETE ON download_event
BEGIN
    SELECT RAISE(ABORT, 'download_event is append only: delete is not allowed');
END;

CREATE TRIGGER trg_upload_event_no_update
BEFORE UPDATE ON upload_event
BEGIN
    SELECT RAISE(ABORT, 'upload_event is append only: update is not allowed');
END;

CREATE TRIGGER trg_upload_event_no_delete
BEFORE DELETE ON upload_event
BEGIN
    SELECT RAISE(ABORT, 'upload_event is append only: delete is not allowed');
END;
