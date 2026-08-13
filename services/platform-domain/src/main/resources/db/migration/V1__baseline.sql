-- Baseline schema for the incident bounded context.
-- Owned jointly by correlation-service (write path) and incident-service (command + read path);
-- both deploy the same migrations and the same module, so the schema can never drift between them.

create sequence if not exists incident_key_seq start with 1000 increment by 1;

create table service_node (
    id                  uuid primary key,
    version             bigint      not null default 0,
    tenant_id           text        not null,
    service_key         text        not null,
    display_name        text        not null,
    tier                text        not null default 'TIER_3',
    owner_team          text,
    repository_url      text,
    runbook_url         text,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    constraint uq_service_node_key unique (tenant_id, service_key),
    constraint ck_service_tier check (tier in ('TIER_1', 'TIER_2', 'TIER_3'))
);

create table service_dependency (
    id                  uuid primary key,
    version             bigint      not null default 0,
    tenant_id           text        not null,
    source_key          text        not null,
    target_key          text        not null,
    kind                text        not null default 'SYNC',
    criticality         double precision not null default 0.5,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    constraint uq_dependency_edge unique (tenant_id, source_key, target_key),
    constraint ck_no_self_edge check (source_key <> target_key),
    constraint ck_criticality_range check (criticality >= 0 and criticality <= 1)
);

-- Traversal always starts from a known service key, in both directions.
create index ix_dependency_source on service_dependency (tenant_id, source_key);
create index ix_dependency_target on service_dependency (tenant_id, target_key);

create table incident (
    id                      uuid primary key,
    version                 bigint      not null default 0,
    tenant_id               text        not null,
    incident_key            text        not null,
    title                   text        not null,
    summary                 text,
    status                  text        not null default 'OPEN',
    severity                text        not null,
    primary_service_key     text        not null,
    affected_service_keys   text        not null default '',
    detected_at             timestamptz not null,
    acknowledged_at         timestamptz,
    acknowledged_by         text,
    mitigated_at            timestamptz,
    resolved_at             timestamptz,
    resolved_by             text,
    escalation_level        integer     not null default 0,
    escalation_policy_key   text,
    signal_count            integer     not null default 0,
    duplicate_of            text,
    created_at              timestamptz not null,
    updated_at              timestamptz not null,
    constraint uq_incident_key unique (tenant_id, incident_key),
    constraint ck_incident_status check (status in ('OPEN', 'ACKNOWLEDGED', 'MITIGATED', 'RESOLVED')),
    constraint ck_incident_severity check (severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    constraint ck_resolution_after_detection check (resolved_at is null or resolved_at >= detected_at)
);

-- The incident list is always "newest first, filtered by status", so lead with the filter columns.
create index ix_incident_feed on incident (tenant_id, status, detected_at desc);
create index ix_incident_service on incident (tenant_id, primary_service_key, detected_at desc);
-- Partial index: the escalation sweep only ever looks at unacknowledged open incidents.
create index ix_incident_unacked on incident (detected_at)
    where status = 'OPEN' and acknowledged_at is null;

create table incident_signal (
    id                  uuid primary key,
    version             bigint      not null default 0,
    incident_id         uuid        not null references incident (id) on delete cascade,
    fingerprint         text        not null,
    signal_type         text        not null,
    service_key         text        not null,
    severity            text        not null,
    summary             text        not null,
    correlation_score   double precision not null default 0,
    occurrences         integer     not null default 1,
    first_seen_at       timestamptz not null,
    last_seen_at        timestamptz not null,
    labels              jsonb       not null default '{}'::jsonb,
    payload             jsonb       not null default '{}'::jsonb,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    constraint uq_signal_per_incident unique (incident_id, fingerprint)
);

create index ix_signal_incident on incident_signal (incident_id, first_seen_at);
create index ix_signal_fingerprint on incident_signal (fingerprint, last_seen_at desc);

create table timeline_entry (
    id              uuid primary key,
    version         bigint      not null default 0,
    incident_id     uuid        not null references incident (id) on delete cascade,
    kind            text        not null,
    message         text        not null,
    actor           text        not null,
    occurred_at     timestamptz not null,
    metadata        jsonb       not null default '{}'::jsonb,
    created_at      timestamptz not null,
    updated_at      timestamptz not null
);

create index ix_timeline_incident on timeline_entry (incident_id, occurred_at);

create table deployment (
    id              uuid primary key,
    version         bigint      not null default 0,
    tenant_id       text        not null,
    service_key     text        not null,
    version_label   text        not null,
    commit_sha      text,
    environment     text        not null,
    status          text        not null,
    deployed_by     text,
    changelog_url   text,
    occurred_at     timestamptz not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null
);

create index ix_deployment_lookup on deployment (tenant_id, service_key, occurred_at desc);

create table incident_deployment (
    id              uuid primary key,
    version         bigint      not null default 0,
    incident_id     uuid        not null references incident (id) on delete cascade,
    deployment_id   uuid        not null references deployment (id) on delete cascade,
    suspicion_score double precision not null,
    rationale       text        not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint uq_incident_deployment unique (incident_id, deployment_id)
);

create table escalation_policy (
    id                  uuid primary key,
    version             bigint      not null default 0,
    tenant_id           text        not null,
    policy_key          text        not null,
    display_name        text        not null,
    minimum_severity    text        not null default 'HIGH',
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    constraint uq_policy_key unique (tenant_id, policy_key)
);

create table escalation_step (
    id              uuid primary key,
    version         bigint      not null default 0,
    policy_id       uuid        not null references escalation_policy (id) on delete cascade,
    step_order      integer     not null,
    target_type     text        not null,
    target          text        not null,
    delay_seconds   bigint      not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    constraint uq_step_order unique (policy_id, step_order)
);

create table incident_insight (
    id              uuid primary key,
    version         bigint      not null default 0,
    incident_id     uuid        not null references incident (id) on delete cascade,
    tenant_id       text        not null,
    kind            text        not null,
    model           text        not null,
    context_hash    text        not null,
    headline        text        not null,
    body            text        not null,
    hypotheses      jsonb       not null default '[]'::jsonb,
    confidence      double precision not null default 0,
    input_tokens    integer     not null default 0,
    output_tokens   integer     not null default 0,
    created_at      timestamptz not null,
    updated_at      timestamptz not null
);

create index ix_insight_incident on incident_insight (incident_id, kind, created_at desc);
