create function keep_auction_history() returns trigger language plpgsql as $$
begin
  raise exception 'Auction history is append-only';
end;
$$;
create trigger auction_audit_append_only before update or delete on auction_audit
  for each row execute function keep_auction_history();
create trigger public_bid_append_only before update or delete on public_bid
  for each row execute function keep_auction_history();
create trigger bid_request_append_only before update or delete on bid_request
  for each row execute function keep_auction_history();
