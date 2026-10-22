create table inbox (
  event_id uuid primary key,
  processed_at timestamptz not null default now()
);
create table notice_version (
  recipient_id uuid not null,
  aggregate_id uuid not null,
  category varchar(20) not null,
  version bigint not null,
  primary key(recipient_id,aggregate_id,category)
);
create table notice (
  id uuid primary key,
  recipient_id uuid not null,
  event_id uuid not null references inbox(event_id),
  title varchar(100) not null,
  body varchar(600) not null,
  link varchar(100) not null,
  created_at timestamptz not null,
  read_at timestamptz,
  email_sent_at timestamptz,
  email_available_at timestamptz not null default now(),
  email_attempts int not null default 0
);
create index notice_recipient_idx on notice(recipient_id,created_at desc);
create index notice_email_pending_idx on notice(email_available_at) where email_sent_at is null and email_attempts<12;
