-- Reference data for the demo tenant: a plausible e-commerce topology plus one escalation ladder.
-- Idempotent so it can be re-applied against an existing database during development.

insert into service_node (id, version, tenant_id, service_key, display_name, tier, owner_team,
                          created_at, updated_at)
values
    (gen_random_uuid(), 0, 'acme', 'edge-gateway',    'Edge Gateway',        'TIER_1', 'platform',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'Checkout API',        'TIER_1', 'payments',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'cart-service',    'Cart Service',        'TIER_1', 'commerce',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'payment-gateway', 'Payment Gateway',     'TIER_1', 'payments',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'inventory-api',   'Inventory API',       'TIER_2', 'commerce',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'pricing-engine',  'Pricing Engine',      'TIER_2', 'commerce',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'identity-api',    'Identity API',        'TIER_1', 'platform',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'notification-api','Notification API',    'TIER_3', 'growth',    now(), now()),
    (gen_random_uuid(), 0, 'acme', 'orders-postgres', 'Orders Postgres',     'TIER_1', 'platform',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'session-redis',   'Session Redis',       'TIER_2', 'platform',  now(), now()),
    (gen_random_uuid(), 0, 'acme', 'search-api',      'Search API',          'TIER_2', 'discovery', now(), now())
on conflict (tenant_id, service_key) do nothing;

insert into service_dependency (id, version, tenant_id, source_key, target_key, kind, criticality,
                                created_at, updated_at)
values
    (gen_random_uuid(), 0, 'acme', 'edge-gateway',    'checkout-api',    'SYNC',      0.95, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'edge-gateway',    'search-api',      'SYNC',      0.60, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'edge-gateway',    'identity-api',    'SYNC',      0.95, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'cart-service',    'SYNC',      0.90, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'payment-gateway', 'SYNC',      0.95, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'inventory-api',   'SYNC',      0.80, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'orders-postgres', 'DATASTORE', 0.95, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'checkout-api',    'notification-api','ASYNC',     0.20, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'cart-service',    'pricing-engine',  'SYNC',      0.75, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'cart-service',    'session-redis',   'CACHE',     0.65, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'identity-api',    'session-redis',   'CACHE',     0.70, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'inventory-api',   'orders-postgres', 'DATASTORE', 0.85, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'payment-gateway', 'stripe',          'THIRD_PARTY', 0.90, now(), now()),
    (gen_random_uuid(), 0, 'acme', 'search-api',      'inventory-api',   'ASYNC',     0.30, now(), now())
on conflict (tenant_id, source_key, target_key) do nothing;

-- 'stripe' is referenced as a dependency target above; register it so the graph has no dangling node.
insert into service_node (id, version, tenant_id, service_key, display_name, tier, owner_team,
                          created_at, updated_at)
values (gen_random_uuid(), 0, 'acme', 'stripe', 'Stripe (external)', 'TIER_1', 'payments', now(), now())
on conflict (tenant_id, service_key) do nothing;

do $$
declare
    policy_id uuid;
begin
    if not exists (select 1 from escalation_policy where tenant_id = 'acme' and policy_key = 'default-critical') then
        policy_id := gen_random_uuid();

        insert into escalation_policy (id, version, tenant_id, policy_key, display_name,
                                       minimum_severity, created_at, updated_at)
        values (policy_id, 0, 'acme', 'default-critical', 'Default critical ladder', 'HIGH', now(), now());

        insert into escalation_step (id, version, policy_id, step_order, target_type, target,
                                     delay_seconds, created_at, updated_at)
        values
            (gen_random_uuid(), 0, policy_id, 0, 'TEAM',    'primary-oncall',   300,  now(), now()),
            (gen_random_uuid(), 0, policy_id, 1, 'TEAM',    'secondary-oncall', 600,  now(), now()),
            (gen_random_uuid(), 0, policy_id, 2, 'USER',    'engineering-manager', 900, now(), now()),
            (gen_random_uuid(), 0, policy_id, 3, 'WEBHOOK', 'https://hooks.internal/incident-bridge', 1200, now(), now());
    end if;
end $$;
