# Distributed Rate Limiter (Redis + Spring Boot)

A small but real rate-limiting service. Two algorithms — **sliding window log** and **token bucket** — both implemented as atomic Lua scripts inside Redis so that multiple app instances share state correctly under load.

Runs locally on the default ports: app on **8080** (and a second instance on **8081** via Docker Compose), Redis on **6379**.

---

## Problem

If you put rate limiting in application memory (a `ConcurrentHashMap`, Guava `RateLimiter`, Bucket4j local), every instance has its own counter. Once you scale past one box, a user can blow past the limit by N× simply because the load balancer fans them out. Worse, you can't even tell — each instance thinks it's enforcing the rule.

This project solves the boring-but-important version of that problem:

- One shared source of truth (Redis) for counters
- Decisions made atomically so two requests racing on the same key can't both "win" the last token
- Works the same whether you're running 1 app instance or 50
- Two algorithms with different trade-offs, picked per request

---

## Design decisions

**Lua scripts, not Java-side `MULTI/EXEC`.**
The check-and-decrement has to be atomic. Doing it from Java means a round trip per step plus optimistic-locking retries on `WATCH`. A Lua script runs inside Redis as a single atomic unit — one network call, no race, no retry loop. The two scripts (`sliding_window.lua`, `token_bucket.lua`) are the actual core of this project. The Java code is mostly plumbing.

**Sliding window log over fixed window counters.**
The naive "increment a counter that resets every 60s" lets a user fire `2 * limit` requests across a window boundary. Sliding window log keeps the timestamps of recent hits in a Redis sorted set and trims anything older than the window. Slightly more memory per key, but no boundary spike. For high-cardinality cases the memory cost is real — see "at scale" below.

**Token bucket as the second option.**
Sliding window is great for "no more than N per minute" semantics. Token bucket is better when you want to allow short bursts but cap the sustained rate — APIs, login endpoints, etc. Both are exposed; callers pick `?policy=sliding_window` or `?policy=token_bucket`.

**Per-request client id, not just IP.**
The controller honors `X-Client-Id` and only falls back to remote IP. In real deployments you want to key off the API key, user id, or tenant id — IP alone gets you NAT'd corporate networks sharing one limit.

**Two app instances in compose.**
Just to make the "shared state" claim falsifiable. Hammer `:8080` and `:8081` with the same client id and you'll see the limit hold across them.

**Fail-open on Redis errors.**
If Redis is unreachable, we let the request through rather than 500. A rate limiter that takes the whole API down when it has a bad day is worse than no rate limiter. This is a deliberate trade-off and the kind of thing I'd want to discuss with whoever owns the SLOs.

---

## Run it

```bash
# the whole thing (Redis + two app instances)
docker compose up --build

# or locally, against a Redis you already have on :6379
mvn spring-boot:run
```

Try it:

```bash
# allowed
curl -i http://localhost:8080/api/limited?policy=token_bucket

# spam until you get 429
for i in $(seq 1 150); do curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-Client-Id: alice" http://localhost:8080/api/limited; done

# prove state is shared: hit instance 2 with the same id
curl -i -H "X-Client-Id: alice" http://localhost:8081/api/limited
```

Response headers:
- `X-RateLimit-Remaining` — tokens or slots left in the window
- `X-RateLimit-Policy` — which algorithm was used
- `Retry-After-Ms` — only on `429`

---

## Benchmark

Quick local run with `hey`, app on `:8080`, Redis on `:6379`, both in Docker Compose on a single laptop (M-series, 8 cores). Each request uses a unique client id so we're measuring throughput of the limiter itself, not rejections.

```bash
./scripts/benchmark.sh
```

Representative numbers from a local run (one app instance, one Redis):

```
Summary:
  Total:        2.1 s
  Requests/sec: ~9,400
  Average:      21 ms
  p95:          38 ms
  p99:          61 ms
  Status 200:   20000
```

With two app instances behind a round-robin and the same Redis: ~14k req/s before Redis CPU became the bottleneck. Numbers will vary wildly by hardware — the point is the order of magnitude, and that the limiter itself is not where you'll fall over first.

---

## What I'd do differently at scale

- **Sharded Redis / Redis Cluster.** Single-node Redis is great until it isn't. Hash-tag the bucket keys (e.g. `rl:{tenant}:user:123`) so a tenant's keys land on one shard and Lua scripts still work — cross-slot scripts are rejected by Cluster.
- **Replace sliding-window-log with a counting variant at high QPS.** The log holds one entry per hit, which is fine at hundreds of QPS per key and painful at tens of thousands. A weighted two-bucket approximation (current + previous window, weighted by elapsed fraction) is ~95% accurate at a fraction of the memory.
- **Push the limiter to the edge.** At real scale this belongs in Envoy / NGINX / a CDN worker, with Redis as the shared store. The Spring app becomes the control plane (policy CRUD, analytics) rather than the data path.
- **Hierarchical limits.** Per-user AND per-tenant AND global, evaluated in one script. Right now policies are flat.
- **Hot-key protection.** A single abusive key can pin one Redis shard. Detect with `MONITOR`/slowlog sampling and shed at the LB before it reaches Redis.
- **Async write-behind for analytics.** Today the script returns and we forget. At scale I'd publish allow/deny events to a stream (Redis Streams or Kafka) for an analytics consumer — no extra latency on the hot path.

---

## Known limitations

- **Fail-open on Redis outage.** Conscious choice; not always the right one. Behind a payments API I'd flip this.
- **Clock source is the app server.** `System.currentTimeMillis()` is passed into the script. Skew between app nodes can cause minor unfairness at window boundaries. Moving the timestamp to `redis.call('TIME')` fixes that but couples behavior to Redis's clock.
- **No policy persistence.** Capacity/refill are config, not per-tenant rows. Adding a `policies` table (Postgres) + cache is the obvious next step.
- **Single Redis, no failover.** Compose ships one node. Use Sentinel or managed Redis for anything real.
- **No auth on the demo endpoint.** It's a demo endpoint.
- **Sliding window log memory.** Each request adds a ZSET member; tune window + limit accordingly, or switch algorithms for very high-traffic keys.
- **No tests yet.** The Lua scripts are the part most worth testing (with embedded Redis or testcontainers). On the list.
