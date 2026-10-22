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
create table purchase (
  id uuid primary key,
  auction_id uuid not null unique,
  buyer_id uuid not null,
  title varchar(200) not null,
  amount numeric(14,2) not null,
  currency char(3) not null,
  status varchar(12) not null check (status in ('PENDING','PAID','CANCELLED')),
  version bigint not null default 1,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
create index purchase_buyer_idx on purchase(buyer_id,created_at desc);
create table inbox (
  event_id uuid primary key,
  processed_at timestamptz not null default now()
);
