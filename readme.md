# CacheFlow

A caching proxy server built with **Java + Spring Boot**, run as a CLI tool via **Picocli**. It sits in front of an origin server, forwards incoming HTTP requests to it, and caches `GET` responses so repeated requests can be served without re-hitting the origin.

This project is a solution to the [roadmap.sh Caching Server challenge](https://roadmap.sh/projects/caching-server).

## How it works

```
Client ──► CacheFlow ──► Origin server
              │
          Caffeine cache (in memory)
              │
          data/cache.json (on disk)
```

- All incoming requests are forwarded to the configured origin server.
- `GET` responses are cached in memory (Caffeine), keyed by method + full origin URL (path + query string).
- Every response carries an `X-Cache: HIT` or `X-Cache: MISS` header so you can tell whether it came from the cache or the origin.
- The cache is also persisted to disk (`data/cache.json`), so it survives restarts.

## CLI Options

| Flag             | Default                  | Description                                      |
|------------------|---------------------------|---------------------------------------------------|
| `--origin`       | *(required)*               | Origin server URL to forward requests to           |
| `--port`         | `8080`                     | Port to run the proxy on                           |
| `--ttl`          | `15`                       | Cache entry time-to-live, in minutes               |
| `--max-entries`  | `100`                      | Maximum number of entries the cache can hold       |
| `--clear-cache`  | `false`                    | Delete the persisted cache file (`data/cache.json`) and exit |


## Implementation Phases

This project was built incrementally, in line with the roadmap.sh challenge structure.

| Phase                 | Description                                                                                                   |
|-----------------------|---------------------------------------------------------------------------------------------------------------|
| Phase 0<br/>CLI flags |  Picocli-based CLI to parse `--port`, `--origin`, `--ttl`, `--max-entries`, `--clear-cache`                    |
| Phase 1<br/>Proxy     | forwards all incoming requests to the configured origin and relays back the response as-is                   |
| Phase 2<br/>Caching   | (in-memory) caches `GET` responses in a Caffeine cache with TTL and max-size eviction                  |
| Phase 3<br/>Caching   | (persistent) snapshots the cache to `data/cache.json` on disk and restores it on startup                    |
| Phase 4<br/>Docker   | multi-stage Dockerfile (Maven build stage → JRE-only runtime stage) plus a Compose file                    |
| Phase 5<br/>testing   | k6 load tests against a mock origin (`origin.py`),  measuring throughput, p95 latency and hit rate |
## Run with Docker

```
docker compose up --build
```

The proxy is then available at `http://localhost:3000`, forwarding to the origin set in `docker-compose.yml`. Responses carry an `X-Cache: HIT` or `MISS` header.

The container is configured with **environment variables**, not CLI flags. The image's entrypoint turns them into flags:

| Env var       | Flag             | Default                         |
|---------------|------------------|---------------------------------|
| `ORIGIN`      | `--origin`       | *(required, container exits without it)* |
| `PORT`        | `--port`         | `8080`                          |
| `TTL`         | `--ttl`          | `15` (minutes)                  |
| `MAX_ENTRIES` | `--max-entries`  | `100`                           |

To proxy a different server or change a setting, edit the `environment:` block in `docker-compose.yml`. Empty values fall back to the defaults. Without Compose:

```
docker build -t cacheflow .
docker run -e ORIGIN=https://dummyjson.com -e TTL=30 -p 3000:8080 cacheflow
```

Anything after the image name is passed to the app as extra flags (e.g. `docker run -e ORIGIN=... cacheflow --clear-cache`). Don't repeat `--origin`/`--port`/`--ttl`/`--max-entries` that way, since the entrypoint already sets them; use the env vars instead.

The cache is persisted to `data/cache.json` inside a named Docker volume, so it survives container restarts. To wipe it: `docker compose down -v`.

**How the image is built:** a two-stage Dockerfile. Stage 1 (Maven + JDK) compiles the jar; stage 2 (JRE only) contains just the jar, which keeps the final image small.



## Load Testing

CacheFlow was load-tested with [Grafana k6](https://k6.io) against a mock origin with a fixed 150 ms response delay, to compare direct-to-origin latency with latency through the proxy.

### Method

- **Origin:** `origin.py`, a small Python server that answers `GET /item/<id>` after a 150 ms delay and counts the requests that actually reach it.
- **Workload:** k6 (`loadtest.js`) with upto 50 concurrent users for 30 s, each requesting a random key from a space of 100 keys.
- **Cache config:** TTL 30 s, max 200 entries.
- **Procedure:** the baseline run goes straight to the origin. For each proxy run, CacheFlow is restarted first (cold cache) and warmed up with a few requests outside the test key range.
- **Hit rate:** `1 - (origin requests / total requests)`, measured from the origin's own counter.


### Results

| Scenario | Users | Throughput (req/s) | p95 latency | Hit rate |
|:--|--:|--:|--:|--:|
| Direct to origin | 50 | ~328 | ~153 ms | n/a |
| Through CacheFlow | 5 | ~4,740 | ~1.1 ms | 99.8% |
| Through CacheFlow | 25 | ~9,450 | ~4.8 ms | 99.7% |
| Through CacheFlow | 50 | ~10,940 | ~9.5 ms | 99.8% |


### Caveats

- The key space (100 keys) fits entirely in the cache, which is why the hit rate is close to 100%. A larger key space will lower it.
- Throughput levels off near 10K req/s because k6, CacheFlow and the origin share one machine, so this is not a hard limit of CacheFlow.
- Figures are averaged across 8–10 runs and may vary by machine.

### Reproduce

```powershell
# 1. Start the mock origin (port 9000, 150 ms delay)
python origin.py

# 2. Start CacheFlow on :8080 pointing at http://127.0.0.1:9000 

# 3. Run the full sequence 
powershell -ExecutionPolicy Bypass -File .\run_tests.ps1 -OriginUrl http://127.0.0.1:9000 -ProxyUrl http://127.0.0.1:8080
```

Results are written to a `results_<timestamp>/` folder (CSV summary plus the full k6 logs).


## Deploy on Render

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/Aditya-Khemka/CacheFlow)

The button reads [`render.yaml`](render.yaml) and creates a free Docker web service. Before the first deploy, Render asks for:

| Field         | Required | Description                                        |
|---------------|----------|----------------------------------------------------|
| `ORIGIN`      | yes      | Origin server URL to forward requests to, e.g. `https://dummyjson.com` |
| `TTL`         | no       | Cache entry time-to-live in minutes (empty = `15`) |
| `MAX_ENTRIES` | no       | Maximum number of cached entries (empty = `100`)   |

The port is not a field: Render injects `PORT` and the entrypoint passes it to `--port`.

**Free tier notes**

- The service sleeps when idle, so the first request after a pause is slow while it wakes up.
- There is no persistent disk, so `data/cache.json` is lost on every restart or redeploy. Caching works normally while the service is up, but the persistence doesn't survive redeploys.

> **Security warning:** the cache key ignores request headers such as `Authorization`. Don't point a public deployment at an authenticated API, because one user's cached response could be served to another.

**Verify it works:** request the same URL twice and check the `X-Cache` header:

```
curl -s -o /dev/null -D - https://<your-service>.onrender.com/products/1 | grep -i x-cache   # X-Cache: MISS
curl -s -o /dev/null -D - https://<your-service>.onrender.com/products/1 | grep -i x-cache   # X-Cache: HIT
```





## Notes

- Only `GET` responses are cached; all other methods are always forwarded live.
- The cache key does not currently account for request headers (e.g. `Authorization`), so two different clients requesting the same GET URL will share a cache entry.
- `data/cache.json` is written relative to the current working directory the app is run from (the project root when run locally); the `data/` folder is created on startup if it does not already exist.
- Tech Stack : Java 25 · Spring Boot 4 (Spring Web, embedded Tomcat, `RestTemplate`) · Picocli (CLI flags) · Caffeine (in-memory cache) · Jackson · Maven · Docker (multi-stage build, Compose) · Render (deployment) · Grafana k6, Python and PowerShell (load testing)