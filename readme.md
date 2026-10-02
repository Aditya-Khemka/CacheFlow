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

## Run with Docker

```
docker compose up --build
```

The proxy is then available at `http://localhost:3000`, forwarding to the origin set in `docker-compose.yml`. Responses carry an `X-Cache: HIT` or `MISS` header.

To proxy a different server or add flags, edit the `--origin` value in the `command:` line of `docker-compose.yml`.

The cache is persisted to `data/cache.json` inside a named Docker volume, so it survives container restarts. To wipe it: `docker compose down -v`.

**How the image is built:** a two-stage Dockerfile. Stage 1 (Maven + JDK) compiles the jar; stage 2 (JRE only) contains just the jar, which keeps the final image small.



## Notes

- Only `GET` responses are cached; all other methods are always forwarded live.
- The cache key does not currently account for request headers (e.g. `Authorization`), so two different clients requesting the same GET URL will share a cache entry.
- `data/cache.json` is written relative to the current working directory the app is run from (the project root when run locally); the `data/` folder is created on startup if it does not already exist.
- Tech Stack : Java 25 · Spring Boot 4 · Picocli · Caffeine · Jackson · Maven · Docker