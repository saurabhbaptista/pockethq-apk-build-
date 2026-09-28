-- OFA private control-plane schema v0.1 (public schema only; contains NO user data or secrets)
-- Intended for a PRIVATE Supabase project after explicit organization confirmation.
create extension if not exists pgcrypto;

create table if not exists public.ofa_memberships (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('ceo','operator','viewer')),
  created_at timestamptz not null default now()
);

create table if not exists public.ofa_agents (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9][a-z0-9_-]{1,63}$'),
  name text not null,
  department text not null,
  role text not null,
  status text not null default 'idle'
    check (status in ('idle','working','waiting','paused','disabled','error')),
  authority_level text not null default 'autonomous'
    check (authority_level in ('observe','autonomous','preauthorized','protected')),
  capabilities jsonb not null default '[]'::jsonb,
  configuration jsonb not null default '{}'::jsonb,
  last_heartbeat_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.ofa_channels (
  id uuid primary key default gen_random_uuid(),
  key text not null unique,
  name text not null,
  channel_type text not null
    check (channel_type in ('ceo_chief','chief_managers','workers','audit','system')),
  visibility text not null default 'private'
    check (visibility in ('private','public-safe')),
  created_at timestamptz not null default now()
);

create table if not exists public.ofa_tasks (
  id uuid primary key default gen_random_uuid(),
  parent_task_id uuid references public.ofa_tasks(id) on delete set null,
  idempotency_key text unique,
  objective text not null,
  department text not null,
  status text not null default 'queued'
    check (status in ('queued','leased','working','waiting','review','completed','failed','cancelled')),
  priority smallint not null default 50 check (priority between 0 and 100),
  risk_tier text not null default 'low' check (risk_tier in ('low','medium','high','protected')),
  authority_required text not null default 'autonomous'
    check (authority_required in ('observe','autonomous','preauthorized','protected')),
  created_by_agent_id uuid references public.ofa_agents(id) on delete set null,
  assigned_agent_id uuid references public.ofa_agents(id) on delete set null,
  next_run_at timestamptz not null default now(),
  lease_owner text,
  lease_expires_at timestamptz,
  attempt_count integer not null default 0 check (attempt_count >= 0),
  max_attempts integer not null default 3 check (max_attempts between 1 and 20),
  resource_budget jsonb not null default '{}'::jsonb,
  input jsonb not null default '{}'::jsonb,
  result jsonb,
  result_summary text,
  error_summary text,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

create index if not exists ofa_tasks_ready_idx
  on public.ofa_tasks(status, next_run_at, priority desc)
  where status in ('queued','waiting','failed');

create index if not exists ofa_tasks_agent_idx
  on public.ofa_tasks(assigned_agent_id, status);

create table if not exists public.ofa_messages (
  id bigint generated always as identity primary key,
  channel_id uuid not null references public.ofa_channels(id) on delete cascade,
  task_id uuid references public.ofa_tasks(id) on delete set null,
  sender_kind text not null check (sender_kind in ('human','agent','system','auditor')),
  sender_ref text not null,
  message_type text not null default 'message'
    check (message_type in ('message','decision','delegation','finding','question','answer','status','warning')),
  body text not null check (char_length(body) between 1 and 12000),
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists ofa_messages_channel_idx
  on public.ofa_messages(channel_id, created_at desc);

create table if not exists public.ofa_events (
  id bigint generated always as identity primary key,
  task_id uuid references public.ofa_tasks(id) on delete set null,
  agent_id uuid references public.ofa_agents(id) on delete set null,
  event_type text not null,
  summary text not null check (char_length(summary) between 1 and 2000),
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists ofa_events_created_idx on public.ofa_events(created_at desc);
create index if not exists ofa_events_task_idx on public.ofa_events(task_id, created_at);

create table if not exists public.ofa_approvals (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null references public.ofa_tasks(id) on delete cascade,
  requested_by_agent_id uuid references public.ofa_agents(id) on delete set null,
  action_type text not null,
  action_summary text not null,
  status text not null default 'pending'
    check (status in ('pending','approved','rejected','expired','cancelled')),
  expires_at timestamptz,
  decided_by uuid references auth.users(id) on delete set null,
  decided_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.ofa_policies (
  key text primary key,
  value jsonb not null,
  description text not null,
  updated_at timestamptz not null default now()
);

insert into public.ofa_channels(key,name,channel_type,visibility) values
 ('ceo-chief','CEO ↔ Chief','ceo_chief','private'),
 ('chief-managers','Chief ↔ Managers','chief_managers','private'),
 ('workers','Worker Floor','workers','private'),
 ('audit','Independent Auditor','audit','private'),
 ('system','System Events','system','private')
on conflict (key) do nothing;

insert into public.ofa_policies(key,value,description) values
 ('default_authority','{"level":"autonomous"}','Default task authority when no stricter policy applies.'),
 ('protected_actions','{"actions":["spend_money","sign_agreement","change_live_trading","change_security_permissions","expose_sensitive_data"]}','Actions that always require explicit protected approval.'),
 ('agent_creation','{"allowed":true,"max_active":12,"default_authority":"autonomous"}','Chief may create bounded agent roles within the active-agent ceiling.'),
 ('self_modification','{"allowed":true,"requires_tests":true,"requires_audit":true,"rollback_required":true}','OFA may change its own software only with tests, independent audit and rollback evidence.')
on conflict (key) do nothing;

alter table public.ofa_memberships enable row level security;
alter table public.ofa_agents enable row level security;
alter table public.ofa_channels enable row level security;
alter table public.ofa_tasks enable row level security;
alter table public.ofa_messages enable row level security;
alter table public.ofa_events enable row level security;
alter table public.ofa_approvals enable row level security;
alter table public.ofa_policies enable row level security;

-- Authenticated OFA members may read the private control plane.
do $$ begin
  create policy ofa_members_read_membership on public.ofa_memberships
    for select using (user_id = auth.uid());
exception when duplicate_object then null; end $$;

do $$ begin
  create policy ofa_members_read_agents on public.ofa_agents
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_channels on public.ofa_channels
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_tasks on public.ofa_tasks
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_messages on public.ofa_messages
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_events on public.ofa_events
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_approvals on public.ofa_approvals
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;
do $$ begin
  create policy ofa_members_read_policies on public.ofa_policies
    for select using (exists(select 1 from public.ofa_memberships m where m.user_id=auth.uid()));
exception when duplicate_object then null; end $$;

-- The mobile client is deliberately read-mostly at this stage.
-- Writes by autonomous workers should come through server-side functions/service credentials,
-- never a service-role key embedded in the APK.
