# CacheFlow

A caching proxy server built with **Java + Spring Boot**, run as a CLI tool via **Picocli**. It sits in front of an origin server, forwards incoming HTTP requests to it, and caches `GET` responses so repeated requests can be served without re-hitting the origin.

This project is a solution to the [roadmap.sh Caching Server challenge](https://roadmap.sh/projects/caching-server).

## How it works

- All incoming requests are forwarded to the configured origin server.
- `GET` responses are cached in memory (Caffeine), keyed by method + full origin URL (path + query string).
- Every response carries an `X-Cache: HIT` or `X-Cache: MISS` header so you can tell whether it came from the cache or the origin.
- The cache is also persisted to disk (`cache.json`), so it survives restarts.

## CLI Options

| Flag             | Default                  | Description                                      |
|------------------|---------------------------|---------------------------------------------------|
| `--origin`       | *(required)*               | Origin server URL to forward requests to           |
| `--port`         | `8080`                     | Port to run the proxy on                           |
| `--ttl`          | `15`                       | Cache entry time-to-live, in minutes               |
| `--max-entries`  | `100`                      | Maximum number of entries the cache can hold       |
| `--clear-cache`  | `false`                    | Delete the persisted cache file (`cache.json`) and exit |


## Implementation Phases

This project was built incrementally, in line with the roadmap.sh challenge structure.

| Phase                 | Description                                                                                                   |
|-----------------------|---------------------------------------------------------------------------------------------------------------|
| Phase 0<br/>CLI flags |  Picocli-based CLI to parse `--port`, `--origin`, `--ttl`, `--max-entries`, `--clear-cache` into shared config |
| Phase 1<br/>Proxy     | forwards all incoming requests to the configured origin and relays back the response as-is                   |
| Phase 2<br/>Caching   | (in-memory) caches `GET` responses in a Caffeine cache with TTL and max-size eviction                  |
| Phase 3<br/>Caching   | (persistent) snapshots the cache to `cache.json` on disk and restores it on startup                    |
## Notes

- Only `GET` responses are cached; all other methods are always forwarded live.
- The cache key does not currently account for request headers (e.g. `Authorization`), so two different clients requesting the same GET URL will share a cache entry.
- `cache.json` is written to the current working directory the app is run from.