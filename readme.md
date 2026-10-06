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
- Tech Stack : Java 25 · Spring Boot 4 · Picocli · Caffeine · Jackson · Maven · Docker