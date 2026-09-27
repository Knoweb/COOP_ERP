- **K-03a Command idempotency** — idempotency now runs inside the
  `@CommandHandler` transaction: the database key is claimed before the handler,
  the command result is recorded last, and concurrent requests on different
  backend instances serialize on PostgreSQL. `kernel.idempotency_key` is
  day-partitioned; retention is partition-drop based and the application role
  has no DELETE or TRUNCATE grant.
