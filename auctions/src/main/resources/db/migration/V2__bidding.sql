create table standing_offer (
  auction_id uuid not null references auction(id),
  bidder_id uuid not null,
  amount numeric(14,2) not null,
  priority bigint not null,
  primary key(auction_id,bidder_id)
);
create index offer_ranking_idx on standing_offer(auction_id,amount desc,priority);
create table bid_request (
  auction_id uuid not null references auction(id),
  bidder_id uuid not null,
  request_key uuid not null,
  kind varchar(12) not null,
  amount numeric(14,2) not null,
  receipt jsonb not null,
  primary key(auction_id,bidder_id,request_key)
);
create table public_bid (
  auction_id uuid not null references auction(id),
  version bigint not null,
  bidder_id uuid not null,
  price numeric(14,2) not null,
  placed_at timestamptz not null,
  primary key(auction_id,version)
);
