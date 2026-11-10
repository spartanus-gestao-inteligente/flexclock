-- Flex Clock, schema version 1: baseline of the fourteen contract tables.
--
-- Provenance: this file reproduces the schema that Hibernate created for the Phase 0 entities, with
-- the same table names, column names, declared types, nullability and unique columns. The CHECK
-- constraints carry the canonical wire tokens of the closed enumerators, in the order frozen by the
-- architecture contract, section 4.3, so a row can never hold a value the backend cannot read back.
--
-- Every instant is stored as UTC. The database engine is SQLite with write ahead logging, foreign
-- keys and a single writer connection; those pragmas belong to the connection, not to this script
-- (architecture contract, sections 4.1 and 4.5; decisions 0017, 0049; requirements RF-005,
-- RNF-010, RNF-016).

-- Configuration with a closed key, its type, value, range and owner. Mutable with audit.
CREATE TABLE settings (
    id integer,
    key varchar(128) NOT NULL UNIQUE,
    value_type varchar(32) NOT NULL CHECK (value_type IN ('string', 'integer', 'enum', 'url')),
    value varchar(2000) NOT NULL,
    min_value varchar(255),
    max_value varchar(255),
    owner varchar(32) NOT NULL CHECK (owner IN ('user', 'agent')),
    updated_by varchar(128) NOT NULL,
    updated_at timestamp NOT NULL,
    PRIMARY KEY (id)
);

-- Anchor of an OpenCode session: one row per session, heartbeat and staleness. Mutable.
CREATE TABLE agent_anchor (
    id integer,
    session_id varchar(128) NOT NULL UNIQUE,
    directory varchar(1000) NOT NULL,
    title varchar(500) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('active', 'stale')),
    anchored_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    PRIMARY KEY (id)
);

-- Procedure: one to one with an OpenCode session, root of the anchor, the pauses and the reports.
CREATE TABLE procedure (
    id integer,
    session_id varchar(128) NOT NULL UNIQUE,
    title varchar(500) NOT NULL,
    directory varchar(1000) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('active', 'completed', 'aborted')),
    started_at timestamp NOT NULL,
    ended_at timestamp,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    PRIMARY KEY (id)
);

-- Pause record of a procedure. Mutable only by transition between the closed pause statuses.
CREATE TABLE agent_pause (
    id integer,
    procedure_id bigint NOT NULL,
    session_id varchar(128) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('paused', 'resumed', 'expired', 'aborted')),
    pause_start timestamp NOT NULL,
    resume_at timestamp,
    duration_ms bigint,
    reason varchar(32) CHECK (reason IN ('user_cancel', 'session_gone', 'pause_failed')),
    PRIMARY KEY (id)
);

-- Work session of the clock: mode, target, instants in UTC and the derived durations.
CREATE TABLE work_session (
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
    PRIMARY KEY (id)
);

-- Agent failure. Append only.
CREATE TABLE failure_event (
    id integer,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    failure_type varchar(32) NOT NULL CHECK (failure_type IN ('hallucination', 'wrong_data', 'tool_error', 'timeout', 'db_write_failure', 'build_failure', 'test_failure', 'deploy_failure', 'logic_error', 'scope_error', 'permission_error', 'network_error', 'unknown')),
    severity varchar(16) NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Error detail of a failure record. Append only.
CREATE TABLE error_detail (
    id integer,
    failure_event_id bigint NOT NULL,
    code varchar(64) NOT NULL,
    message varchar(2000) NOT NULL,
    field_or_resource varchar(255),
    failure_type varchar(32) NOT NULL CHECK (failure_type IN ('hallucination', 'wrong_data', 'tool_error', 'timeout', 'db_write_failure', 'build_failure', 'test_failure', 'deploy_failure', 'logic_error', 'scope_error', 'permission_error', 'network_error', 'unknown')),
    severity varchar(16) NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    agent_id varchar(128) NOT NULL,
    occurred_at timestamp NOT NULL,
    PRIMARY KEY (id)
);

-- Confirmed invented information attributed to an agent. Append only.
CREATE TABLE hallucination_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    response_ref varchar(500) NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Quality evaluation of a session: score 0 to 10 and the five levels 0 to 4. Append only.
CREATE TABLE quality_evaluation (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    score integer NOT NULL CHECK (score >= 0 AND score <= 10),
    adherence_level integer NOT NULL CHECK (adherence_level >= 0 AND adherence_level <= 4),
    evidence_level integer NOT NULL CHECK (evidence_level >= 0 AND evidence_level <= 4),
    process_level integer NOT NULL CHECK (process_level >= 0 AND process_level <= 4),
    artifact_level integer NOT NULL CHECK (artifact_level >= 0 AND artifact_level <= 4),
    honesty_level integer NOT NULL CHECK (honesty_level >= 0 AND honesty_level <= 4),
    rationale varchar(4000) NOT NULL,
    authority varchar(16) NOT NULL CHECK (authority IN ('SELF', 'OWNER')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Usage of the knowledge graph by an agent. Append only.
CREATE TABLE graph_usage_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    query_count integer NOT NULL,
    distinct_nodes integer NOT NULL,
    consulted_before_work boolean NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Token economy of an agent session, with an explicit not measurable case. Append only.
CREATE TABLE token_economy_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    baseline_input_tokens bigint,
    baseline_output_tokens bigint,
    savings_tokens bigint,
    measurable boolean NOT NULL,
    not_measurable_reason varchar(500),
    model_id varchar(128) NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Consulted source: domain, corpus or graph, with the citation flag. Append only.
CREATE TABLE source_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    source varchar(1000) NOT NULL,
    origin varchar(32) NOT NULL CHECK (origin IN ('domain', 'corpus', 'graph')),
    task_context varchar(1000) NOT NULL,
    purpose varchar(1000) NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('success', 'failure')),
    cited boolean,
    redacted boolean NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Download performed by an agent. Append only.
CREATE TABLE download_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    source varchar(1000) NOT NULL,
    item varchar(1000) NOT NULL,
    item_type varchar(128) NOT NULL,
    size_bytes bigint NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('success', 'failure')),
    destination varchar(1000) NOT NULL,
    redacted boolean NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

-- Upload performed by an agent. Append only.
CREATE TABLE upload_event (
    id integer,
    occurred_at timestamp NOT NULL,
    agent_id varchar(128) NOT NULL,
    procedure_id bigint,
    session_id varchar(128),
    destination varchar(1000) NOT NULL,
    item varchar(1000) NOT NULL,
    item_type varchar(128) NOT NULL,
    size_bytes bigint NOT NULL,
    result varchar(16) NOT NULL CHECK (result IN ('success', 'failure')),
    sent boolean NOT NULL,
    redacted boolean NOT NULL,
    record_type varchar(32) NOT NULL CHECK (record_type IN ('failure', 'hallucination', 'quality', 'graph_usage', 'token_economy', 'source', 'download', 'upload', 'errors')),
    write_source varchar(32) NOT NULL CHECK (write_source IN ('rest', 'mcp', 'event_service')),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);
