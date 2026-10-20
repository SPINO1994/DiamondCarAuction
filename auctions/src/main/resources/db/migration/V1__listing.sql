create table auction (
  id uuid primary key,
  seller_id uuid not null,
  seller_name varchar(80) not null,
  vehicle jsonb not null,
  starting_price numeric(14,2) not null check (starting_price>0),
  increment numeric(14,2) not null check (increment>0),
  reserve numeric(14,2) check (reserve>=starting_price),
  starts_at timestamptz not null,
  ends_at timestamptz not null check (ends_at>starts_at),
  status varchar(20) not null check (status in ('DRAFT','REVIEW','REJECTED','APPROVED','SOLD','RESERVE_NOT_MET','NO_BIDS','CANCELLED')),
  reason varchar(500),
  winner_id uuid,
  leader_id uuid,
  current_price numeric(14,2) not null,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
create index auction_closure_idx on auction(ends_at) where status='APPROVED';
create index auction_seller_idx on auction(seller_id);
create table auction_audit (
  id bigint generated always as identity primary key,
  auction_id uuid not null references auction(id),
  actor_id uuid,
  action varchar(32) not null,
  reason varchar(500),
  version bigint not null,
  occurred_at timestamptz not null
);
create table image_asset (
  id uuid primary key,
  owner_id uuid not null,
  content_type varchar(30) not null
);
create table watch (
  account_id uuid not null,
  auction_id uuid not null references auction(id),
  primary key(account_id,auction_id)
);
