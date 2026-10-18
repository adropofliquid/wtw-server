# wtw-server

The backend for the What Should I Wear app (KMP app in `../wtw-app`). Kotlin + Spring Boot 4.1, Java 25, Postgres.
Spec: `../WTW_SERVER_BRIEF.md`. Routes: `API.md`. Layout: `ARCHITECTURE.md`. How to change things: `CONTRIBUTING.md`.
Calls made during the build: `DECISIONS.md`.

## Local run (no keys, no spend)
The `dev` profile uses stubs for Gemini, WeatherAPI, RevenueCat, FCM and rembg, stores files on disk, and accepts a dev token.

You need JDK 25 (Gradle finds it through the toolchain) and a Postgres with a `wtw` database.

```sh
# Option A: your own Postgres (what the build was verified with)
psql -d postgres -c "CREATE ROLE wtw LOGIN PASSWORD 'wtw'" -c "CREATE DATABASE wtw OWNER wtw"
./gradlew bootRun                      # dev profile by default; Flyway applies V1 and V2 on start

# Option B: docker compose (Postgres 17 + MinIO + rembg + API). Not run during the build (no Docker on the build Mac).
docker compose up --build
```

### Local development: real background removal (rembg)
The dev default `REMBG_MODE=stub` just returns your photo as a PNG. For real cut-outs, run the Python rembg
service (`../../imgbg-remover`, its own repo) and point the server at it. This is the recommended local path.

```sh
# A. With Docker: only the rembg service from docker-compose.yml (port 8000)
docker compose up --build rembg
# B. Without Docker: from the remover's own venv (verified this way, 2026-10-11)
cd ../../imgbg-remover && venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000

# then, in this folder
REMBG_MODE=http REMBG_URL=http://localhost:8000/rembg ./gradlew bootRun
curl -H 'Authorization: Bearer dev-token' -F photo=@shirt.jpg localhost:8080/images/remove-background -o cut.png
```
No Docker and no Python? Keep `REMBG_MODE=stub` (the default).

Cut-outs come back with the longest side at **600 px**: the remover reads `MAX_SIDE` from the env and defaults to
600 (patched in `imgbg-remover/main.py`, 2026-10-17). Leave `MAX_SIDE` unset, or set it to 600. The server stores
images at `IMAGE_MAX_SIDE` (600) and never upscales, so a larger `MAX_SIDE` only costs bandwidth.

### Local development: a local model instead of the Gemini stub
The dev default `GEMINI_MODE=stub` gives canned replies. For a cheap "real feel", run a small vision model on
your machine with any OpenAI-compatible server (Ollama shown; `mlx-vlm` serve also works) and set `GEMINI_MODE=local`.
It is used for `/ai/attributes`, the suggestion routes and `/ai/describe-outfit`. **`/ai/generate-image` stays a
placeholder** in local mode: it returns your photo as a PNG (no image model is wired). Never active in prod.

```sh
brew install ollama && ollama serve            # you start and stop the model; the server never does
ollama pull qwen2.5vl:3b                       # ~3.2 GB vision model (default). Alternative: gemma3:4b (~3.4 GB)
GEMINI_MODE=local LOCAL_MODEL_NAME=qwen2.5vl:3b ./gradlew bootRun     # LOCAL_MODEL_URL defaults to http://localhost:11434
```
If nothing is listening, the AI routes give 502 `UPSTREAM_FAILED` with
`"Local model failed: nothing is listening at http://localhost:11434; start it yourself (see README)"`.
Small local models follow the JSON prompts less reliably than Gemini; the server cleans replies the same way.

Try it:
```sh
curl localhost:8080/health
curl -H 'Authorization: Bearer dev-token' localhost:8080/ledger          # as uid "dev-user"
curl -H 'X-Dev-User-Id: someone' localhost:8080/mirror/pull?since=0       # as any uid (dev only)
```

Library images locally: run the seed once (see "Commands"), with `LIBRARY_SEED_DIR` holding `catalog.json` + `cutout/`
from `../../research/catalog-gen/batch1/` and `starter-items.json` from the old server's `src/main/resources/onboarding/`.

## Profiles and modes
| | `prod` | `dev` (or any other profile) |
|---|---|---|
| Set by | Dockerfile (`SPRING_PROFILES_ACTIVE=prod`) | `./gradlew bootRun` default |
| Auth | Firebase ID token | `Bearer $AUTH_DEV_TOKEN` → `$AUTH_DEV_UID`; `X-Dev-User-Id` when `AUTH_DEV_BYPASS_ENABLED=true` (dev default) |
| Gemini, WeatherAPI, RevenueCat, FCM | real | stub (canned, no network). Gemini: `GEMINI_MODE=local` for a local model |
| Storage `STORAGE_MODE` | `s3` (default) | `local` (default) |
| Background removal `REMBG_MODE` | `http` (default) | `stub` (default) |
| Push job | on (`NOTIFICATIONS_ENABLED`) | off by default |

## Configuration (env vars)
Live numbers (credits, costs, caps, pack sizes, notification copy) are **not** env vars; they are rows in `app_config` (see CONTRIBUTING "Change a live number").

| Env var | Default | Used for |
|---|---|---|
| `PORT` | 8080 | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `prod` in the image | `prod` = real clients |
| `DATABASE_URL` | `postgres://wtw:wtw@localhost:5432/wtw` | Postgres (Dokku format; a `jdbc:` URL also works) |
| `DB_POOL_SIZE` | 10 | Hikari pool |
| `FIREBASE_CREDENTIALS_PATH` | — (required in prod) | Service-account JSON: token check + FCM |
| `GOOGLE_APPLICATION_CREDENTIALS` | — | Vertex AI credentials file (Google ADC) |
| `VERTEX_PROJECT_ID`, `VERTEX_LOCATION` | —, `global` | Vertex project and location. Keep `global`: the 3.x image models are not served from `us-central1` (404) |
| `GEMINI_MODEL_SUGGEST` | `gemini-2.5-flash` | Suggestions, outfit description |
| `GEMINI_MODEL_ATTRIBUTES` | `gemini-2.5-flash-lite` | Reading a photo |
| `GEMINI_MODEL_IMAGE` | `gemini-3.1-flash-lite-image` | AI item image |
| `AI_TIMEOUT_MS` | 600000 | Every Gemini call (except the image call) |
| `AI_IMAGE_TIMEOUT_MS` | 600000 | The Gemini image call (`/ai/generate-image`). The app should wait 11 min on routes 15 and 16 (API.md). |
| `GEMINI_MODE` | `stub` | Not prod only: `stub` or `local` (see "Local development: a local model"). Prod always uses Vertex. |
| `LOCAL_MODEL_URL`, `LOCAL_MODEL_NAME` | `http://localhost:11434`, `qwen2.5vl:3b` | `GEMINI_MODE=local` only: OpenAI-compatible server and model |
| `REMBG_MODE`, `REMBG_URL`, `REMBG_TIMEOUT_MS` | `http`, `http://localhost:8000/rembg`, 60000 | Background removal service. The timeout covers one call |
| `REMBG_CONCURRENCY`, `REMBG_QUEUE_WAIT_MS` | 1, 180000 | Calls sent to rembg at once; the rest wait in line this long, then 504 |
| `STORAGE_MODE` | `s3` | `s3` or `local` |
| `STORAGE_ENDPOINT`, `STORAGE_REGION` | empty (= AWS), `auto` | S3-compatible endpoint (R2: `https://<account>.r2.cloudflarestorage.com`) |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | — | Storage credentials |
| `STORAGE_BUCKET_USER`, `STORAGE_BUCKET_LIBRARY` | `wtw-user`, `wtw-library` | Private user images; public library images |
| `STORAGE_PRESIGN_TTL` | `7d` | Life of presigned image URLs (user images and Library) |
| `STORAGE_LOCAL_DIR`, `STORAGE_LOCAL_BASE_URL` | `.local-storage`, `http://localhost:$PORT` | `local` mode only |
| `IMAGE_MAX_SIDE`, `IMAGE_WEBP_QUALITY` | 600, 80 | Stored images |
| `UPLOAD_MAX_BYTES` | 10485760 | Upload limit (also the multipart limit) |
| `RC_API_KEY`, `RC_PROJECT_ID` | — | RevenueCat REST v2 |
| `RC_ENTITLEMENT_ID` | — | Entitlement id for REST v2 (old server: `entlf1fdc6965b`) |
| `RC_ENTITLEMENT_IDENTIFIER` | `Pro` | Entitlement identifier (app/webhooks) |
| `RC_WEBHOOK_AUTH` | — | Shared secret RevenueCat sends in `Authorization`. Empty = every webhook is refused (401). |
| `RC_CACHE_TTL` | `15m` | Entitlement cache |
| `RC_DEV_PREMIUM_UIDS` | — | dev only: uids the stub treats as premium |
| `WEATHER_API_KEY`, `WEATHER_MAX_DAYS`, `WEATHER_CACHE_TTL` | —, 14, `30m` | WeatherAPI.com proxy |
| `MIRROR_MAX_ROWS_PER_PUSH` | 1000 | Push size limit |
| `NOTIFICATIONS_ENABLED` | true (dev: false) | Hourly push job |
| `AUTH_DEV_BYPASS_ENABLED` | false (dev: true) | Accept `X-Dev-User-Id`. Never in prod. |
| `AUTH_DEV_TOKEN`, `AUTH_DEV_UID` | `dev-token`, `dev-user` | dev token verifier |
| `LOG_LEVEL` | INFO | Root log level |
| `LIBRARY_SEED_DIR`, `OLD_S3_BUCKET` | —, `whatshouldiwearr` | `--seed-library` only |
| `OLD_DB_URL`, `OLD_DB_USER`, `OLD_DB_PASS` | — | `--data-copy` only (read-only session) |
| `OLD_S3_ACCESS_KEY`, `OLD_S3_SECRET`, `OLD_S3_REGION` | —, —, `eu-west-2` | `--data-copy` and `--seed-library` (old images) |
| `DATA_COPY_REPORT_DIR` | working dir | Where `--data-copy` writes its report files |

## Commands (same jar, no web server, exit when done)
```sh
java -jar app.jar --seed-library                 # Library seed (brief 5.4). Idempotent. Also sets Library vibe tags (VibeTagger); re-run after changing the tagger.
java -jar app.jar --data-copy=dry-run|apply ...  # Old → new data copy (brief 7). See "Data copy".
# locally: ./gradlew bootRun -Pargs="--seed-library"
```
Seed: `LIBRARY_SEED_DIR` must hold `starter-items.json`, the 42 starter PNGs (root or `onboarding-clothing-items/`),
`catalog.json` and `cutout/`. The starter PNGs are only in the old private S3 bucket. Fetch them first:
`aws s3 cp s3://whatshouldiwearr/onboarding-clothing-items/ $LIBRARY_SEED_DIR/onboarding-clothing-items/ --recursive --region eu-west-2`
(or set `OLD_S3_ACCESS_KEY`/`OLD_S3_SECRET` and the seed fetches them). Without them the 42 starter rows are seeded but have no image. Re-run any time; unchanged rows and existing images are skipped.

## Data copy (brief 7)
```
java -jar app.jar --data-copy=dry-run|apply [--only=profiles,items,outfits,pieces,planned,tokens,sessions,suggestions]
  [--users=uid1,uid2] [--skip-images] [--image-concurrency=16]
```
- Reads the old DB through `OLD_DB_*` in a read-only session; old images through `OLD_S3_*`. No S3 creds, or `--skip-images`, = "images: not checked".
- Writes the new DB (one transaction per user, last writer wins on `updated_at`) and the new user bucket (skips existing keys). Apply refuses to run if old and new DB are the same.
- Dry-run writes nothing to either database. Report files: `data-copy-report-{ts}.json`, `data-copy-users-{ts}.csv`, `data-copy-missing-images.csv` in `DATA_COPY_REPORT_DIR`. Exit 0 = OK, 1 = errors or failed checks.
- Verified: dry-run against the local copy of production (reconciles; largest pull ≈ 7.5k rows / 3.1 MB); apply twice on throwaway fake databases (second run wrote 0 rows). **Not verified: the image copy against real S3.**

Rehearsal on Dokku:
1. `dokku postgres:create wtw-db-rehearsal`, link it to `wtw-server`, deploy (Flyway builds V1/V2), run `--seed-library`.
2. `dokku config:set wtw-server OLD_DB_URL=… OLD_DB_USER=… OLD_DB_PASS=… OLD_S3_ACCESS_KEY=… OLD_S3_SECRET=… DATA_COPY_REPORT_DIR=/app/credentials/reports`
3. `dokku run wtw-server java -jar /app/app.jar --data-copy=dry-run` and read the report.
4. `dokku run wtw-server java -jar /app/app.jar --data-copy=apply`, then run apply again: rows written must be 0, no images copied.
5. Brief 7.9 checks. Automated (in the report): global counts, per-user counts, images or missing list, largest pull, ledger empty, re-run. Manual: HEAD 200 random cutout keys; restore spot check (20 random users + 5 largest closets, pull vs old `/wardrobe`, `/outfit`, `/planner`); compare 10 canvases old vs KMP.

## Deploy (Dokku)
Three units on one host. Dokku builds from the `Dockerfile` (no Procfile needed; the image `CMD` runs the jar).
`app.json` holds the `/health` startup check (port 8080).

```sh
# API
dokku apps:create wtw-server
dokku postgres:create wtw-db
dokku postgres:link wtw-db wtw-server                 # sets DATABASE_URL
dokku ports:set wtw-server http:80:8080 https:443:8080
dokku domains:set wtw-server api.whatshouldiwear.app
# nginx gives up after 60 s by default. Gemini calls may take up to 10 min and route 21 may wait in line 4 min.
dokku nginx:set wtw-server proxy-read-timeout 660s
dokku nginx:set wtw-server client-max-body-size 20m
dokku proxy:build-config wtw-server
dokku storage:ensure-directory wtw
dokku storage:mount wtw-server /var/lib/dokku/data/storage/wtw:/app/credentials   # firebase.json, vertex.json
dokku config:set wtw-server FIREBASE_CREDENTIALS_PATH=/app/credentials/firebase.json \
  GOOGLE_APPLICATION_CREDENTIALS=/app/credentials/vertex.json VERTEX_PROJECT_ID=… VERTEX_LOCATION=global \
  STORAGE_ENDPOINT=… STORAGE_ACCESS_KEY=… STORAGE_SECRET_KEY=… STORAGE_BUCKET_USER=… STORAGE_BUCKET_LIBRARY=… \
  RC_API_KEY=… RC_PROJECT_ID=… RC_ENTITLEMENT_ID=… RC_WEBHOOK_AUTH=… \
  WEATHER_API_KEY=… WEATHER_MAX_DAYS=… REMBG_URL=http://wtw-rembg.web:8000/rembg

# Remover (its own repo: whattowear/imgbg-remover)
dokku apps:create wtw-rembg
dokku domains:disable wtw-rembg
dokku config:set wtw-rembg MAX_SIDE=600            # optional: 600 is already the default in main.py

# Private network between the two
dokku network:create wtw-net
dokku network:set wtw-server attach-post-deploy wtw-net
dokku network:set wtw-rembg attach-post-deploy wtw-net

# Backups
dokku postgres:backup-auth wtw-db …; dokku postgres:backup-schedule wtw-db "0 3 * * *" <bucket>

git remote add dokku dokku@<host>:wtw-server && git push dokku main
```
The old app `wtw` and its database stay up, untouched, until the copy is verified.

**rembg output size:** `imgbg-remover/main.py` reads `MAX_SIDE` (default **600**), the longest side of the returned
cut-out in px. 600 matches the server's `IMAGE_MAX_SIDE`. Deploy the remover from its own repo
(`git@github.com:adropofliquid/imgbg-remover.git`, commit with the `MAX_SIDE` change or later). It needs about 2.5 GB
of RAM while it works (BiRefNet-lite) and handles one photo at a time (~9 s on an M-series Mac).

## Health
`GET /health` → `{"status":"UP"}` after a DB ping; 503 `{"status":"DOWN"}` otherwise. Public. The Docker image has a `HEALTHCHECK` too.

## Remove after cutover (data copy)
The old → new data copy runs once. After the cutover is verified, delete it with this list. Each step is one edit.

Files to delete:
- [ ] `src/main/kotlin/com/adropofliquid/wtw/service/DataCopyService.kt`
- [ ] `src/main/kotlin/com/adropofliquid/wtw/service/DataCopyOldReader.kt`
- [ ] `src/main/kotlin/com/adropofliquid/wtw/service/DataCopyTransforms.kt`
- [ ] `src/main/kotlin/com/adropofliquid/wtw/service/DataCopyImages.kt`
- [ ] `src/main/kotlin/com/adropofliquid/wtw/config/OldDataSourceConfig.kt`
- [ ] `src/main/kotlin/com/adropofliquid/wtw/dto/DataCopyDtos.kt`

Config and hooks to edit:
- [ ] `WtwApplication.kt`: drop `"--data-copy"` from `ONE_OFF_COMMANDS`.
- [ ] `security/SecurityConfig.kt`: drop `--data-copy` from the "web only" comment.
- [ ] `config/WtwProperties.kt` `Old` and `application.yml` `wtw.old`: remove `db-url`, `db-user`, `db-pass`.
      **Keep** `s3-access-key`, `s3-secret`, `s3-region`: `--seed-library` uses them for the old starter images.
- [ ] `docker-compose.yml`: remove the `OLD_DB_URL` line (and its comment) from `api`.
- [ ] README: remove the "Data copy (brief 7)" section, the `--data-copy` line in "Commands", the env rows
      `OLD_DB_URL, OLD_DB_USER, OLD_DB_PASS` and `DATA_COPY_REPORT_DIR`, and this section. Keep `OLD_S3_*`.
- [ ] `ARCHITECTURE.md` "One-off commands" and `CONTRIBUTING.md` "No raw SQL" exception: remove the data-copy mention.
- [ ] Dokku: `dokku config:unset wtw-server OLD_DB_URL OLD_DB_USER OLD_DB_PASS DATA_COPY_REPORT_DIR`.
- [ ] Then `./gradlew build -x test` and boot once.
