create table account (
  id uuid primary key,
  email varchar(254) unique not null,
  password_hash varchar(100) not null,
  name varchar(80) not null,
  role varchar(10) not null check (role in ('BUYER','SELLER','ADMIN')),
  joined_at timestamptz not null default now()
);
create table refresh_session (
  digest char(64) primary key,
  account_id uuid not null references account(id),
  family uuid not null,
  expires_at timestamptz not null,
  used boolean not null default false
);
create index refresh_family_idx on refresh_session(family);
