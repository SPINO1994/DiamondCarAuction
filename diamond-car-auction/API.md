# API and events

All public paths are relative to the application origin. JSON timestamps are ISO 8601 and written in UTC. Monetary input accepts a decimal string or JSON number with up to two fraction digits. One currency applies across the deployment. All identifiers are UUIDs.

| Method and path | Access and input |
| --- | --- |
| POST /api/auth/register | `email, password, name, role` (BUYER or SELLER) |
| POST /api/auth/login | `email, password` |
| POST /api/auth/refresh, /api/auth/logout | Refresh cookie, exact configured Origin |
| GET, PUT /api/profile | Bearer token, PUT takes `name` |
| GET /api/auctions | `q, make, bodyStyle, maxPrice, status, sort, page, size`. Status is live, upcoming or sold. Sort is ending, recent or price. |
| GET /api/auctions/{id} | Public listing |
| GET /api/auctions/{id}/state | Public listing plus up to 50 recent public bid changes |
| POST /api/auctions/{id}/bids, /api/auctions/{id}/max-bid | Buyer, `amount`, UUID `Idempotency-Key` header |
| GET /api/buyer/bids, /api/buyer/watchlist | Buyer |
| PUT, DELETE /api/buyer/watchlist/{id} | Buyer |
| GET, POST /api/seller/auctions | Seller. POST takes listing input below. |
| PUT /api/seller/auctions/{id} | Owner, listing input |
| POST /api/seller/auctions/{id}/submit | Owner |
| POST /api/seller/auctions/{id}/cancel | Owner, `reason`. Available before the first accepted bid. |
| POST /api/seller/images | Seller, multipart `file` |
| GET /api/images/{id} | Public for approved listings. Owner or admin for private listings. |
| GET /api/admin/auctions | Administrator |
| POST /api/admin/auctions/{id}/review | Administrator, `decision` APPROVE or REJECT, `reason` required for rejection |
| POST /api/admin/auctions/{id}/cancel | Administrator, `reason` |
| POST /api/admin/demo/quick-auction | Development only, administrator, `seconds` between 15 and 180 |
| GET /api/orders, /api/orders/{id} | Winning buyer |
| POST /api/orders/{id}/simulate-payment, /api/orders/{id}/cancel | Winning buyer |
| GET /api/notifications | Recipient, newest 100 |
| PUT /api/notifications/{id} | Recipient, boolean `read` |

Auth responses contain `accessToken` and `profile`. Browser tokens live in memory. The refresh cookie has a separate path and rotates on use. Logout revokes its token family. Previously issued access tokens expire after five minutes. Auth POSTs require the exact configured Origin.

Listing input has `vehicle, startingPrice, increment, reserve, startsAt, endsAt`. Vehicle takes `make, model, trim, year, mileage, mileageUnit, transmission, fuel, bodyStyle, exterior, interior, location, description, condition, serviceHistory, images`. The first image is the cover. Image uploads return `url`. Bid receipts contain `version, price, endsAt, leading, extended`. Conflicting bids or transitions return HTTP 409.

Connect to `/live/auctions/{id}` for public snapshots with the same shape as the state endpoint. Clients refetch after reconnect or a version gap. Redis publishes an auction ID after commit. Each instance reads fresh state from PostgreSQL and reconciles active subscriptions periodically.

## Events

The durable topic exchange is `diamond.events`. The routing key equals the event type. The envelope fields are `eventId, eventType, schemaVersion, aggregateId, aggregateVersion, timestamp, correlationId, data`. Current schema version is 1. Aggregate IDs refer to auctions for auction events and orders for order events.

| Type | Data | Destination |
| --- | --- | --- |
| BidPlaced | `price` for the public change | diamond.market |
| BidderOutbid | `recipientId, price` | diamond.notifications |
| AuctionExtended | `endsAt` | diamond.market |
| AuctionEnded | `outcome, title, amount, currency, winnerId?` | diamond.orders, diamond.notifications |
| AuctionCancelled | `reason, recipients[]` | diamond.notifications |
| OrderCreated | `recipientId, auctionId, status` | diamond.notifications |
| OrderStatusChanged | `recipientId, auctionId, status` | diamond.notifications |

Event IDs are deduplicated transactionally by each consumer. Orders also have a unique auction ID. Notification updates apply per-recipient, aggregate and category version checks. The market feed is bounded to 1,000 messages and a one-day TTL. Domain events and outbox rows commit together. Publishing uses confirms and returned-message detection. Crash recovery can repeat deliveries. RabbitMQ acknowledges messages after the listener transaction returns. After four listener attempts, failed messages land in `diamond.orders.dlq` or `diamond.notifications.dlq`.

## Replay after fixing the cause

For an exhausted publisher row, find the event ID in the auction or order database. Reset that specific row and let the dispatcher retry it:

```sql
update outbox
set attempts = 0, failed_at = null, available_at = now()
where id = '<event UUID>' and failed_at is not null and published_at is null;
```

For a dead-letter queue, inspect without discarding messages. Once the consumer or payload problem is resolved, use RabbitMQ's [dynamic shovel](https://www.rabbitmq.com/docs/shovel-dynamic), with the source set to the specific DLQ, destination set to its original queue through the default exchange, and acknowledgment mode set to `on-confirm`. Replay in a maintenance window, watch the destination and DLQ counts, then remove the shovel. Preserve the original event IDs for idempotency. Review any permanently failed email delivery separately before resetting its attempts.
