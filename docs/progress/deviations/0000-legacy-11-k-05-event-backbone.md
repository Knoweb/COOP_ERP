- **K-05 Event backbone** â€” domain events are written to the partitioned
  `kernel.event_outbox` in the caller transaction, relayed in per-source order
  through a separate `coop_relay` data source, and published as persistent
  RabbitMQ messages behind `BrokerAdapter`. `@EventConsumer` handlers use
  `kernel.event_inbox` for exactly-once application; three failures go to
  `domain.dlq` with an ALERT audit record, and archived events can be replayed
  to one consumer without changing the producer.
