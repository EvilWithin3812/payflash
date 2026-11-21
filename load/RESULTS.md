# Flash-sale load test results

Script: [`flash-sale.js`](flash-sale.js) (k6). Every request is a real `POST /api/v1/sales/{id}/reserve` with a
valid JWT, from 1,000 distinct buyers retrying like real users (most get `409 SOLD_OUT` or `409 ALREADY_RESERVED`).

## Setup

- One Windows 11 machine (16 logical cores, Docker Desktop with an 8 GB VM). The **load generator, the app,
  PostgreSQL, Redis and Kafka all ran on that same machine**, inside one Docker network, so they competed for
  the same CPUs. Real numbers on separate hosts would be higher.
- `docker compose up -d --build` with `RATE_LIMIT_ENABLED=false` (all k6 traffic comes from one IP) and `REQUIRE_VERIFIED_EMAIL=false` (k6 buyers cannot click email links).
- 300 virtual users, 30 s, 500 units. One warm-up run first (JIT), then the measured run.

## Result (2026-11-20, measured run)

| Metric | Value |
|---|---|
| Reserve requests | 143,711 in 30 s ≈ **4,790 req/s** |
| Latency | median 58 ms · p95 **103 ms** · p99 144 ms · max 474 ms |
| Units granted | **500 of 500, zero oversell** (`units_granted <= UNITS` threshold) |
| HTTP errors / unexpected statuses | 0 / 0 |
| Rows in `sale_orders` for the sale | 500, all distinct `unit_no` |

## A bottleneck the load test found

The first runs gave much lower throughput, with the app using about 7 cores. A Java Flight Recorder profile
(`-XX:StartFlightRecording`) showed ~480 contended lock waits (11.5 s blocked in total) inside Tomcat's
class loader, all coming from `JwtService`. It was building a new jjwt `JwtParser` on **every request**, and each
build looks up its JSON deserializer by reflection under the class-loader lock. Building the parser once (it is
immutable and thread-safe) removed the contention. The JWT check runs on every authenticated request, so this
fix speeds up the whole API, not just the flash sale.

## Reproduce

```bash
RATE_LIMIT_ENABLED=false REQUIRE_VERIFIED_EMAIL=false docker compose up -d --build
docker run --rm -i --network payflash_default -e BASE_URL=http://app:8090 \
  -e UNITS=500 -e BUYERS=1000 -e VUS=300 -e DURATION=30s grafana/k6:1.4.2 run - < load/flash-sale.js
docker compose up -d app   # turn rate limiting back on afterwards
```
