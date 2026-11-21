-- W3C traceparent of the request that wrote the event. The publisher runs later on a scheduler thread;
-- restoring this context lets one trace span HTTP request -> outbox -> Kafka -> consumers in Zipkin.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55);
