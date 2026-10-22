create table outbox (
  id uuid primary key,
  event_type varchar(40) not null,
  envelope jsonb not null,
  available_at timestamptz not null default now(),
  attempts integer not null default 0,
  published_at timestamptz,
  failed_at timestamptz
);
create index outbox_pending_idx on outbox(available_at) where published_at is null and failed_at is null;
