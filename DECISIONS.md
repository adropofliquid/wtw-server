# Decisions

Agent-facing. Calls made while building `wtw-server` (2026-10-11), so the developer can overrule them.
Part A traces the brief's **[D]** decisions to the code. Part B lists calls made during the build. Brief = `../WTW_SERVER_BRIEF.md`.

## A. Brief [D] decisions — where they live
All were accepted by the developer (ruling 9.3). Each is implemented as written unless a note says otherwise.

| Brief | [D] decision | Where |
|---|---|---|
| 3.0 | `synced_at` on mirror parents; the pull filters on it | `V1__schema.sql`; `MirrorPushService`, `MirrorPullService` |
| 3.0 | CHECKs only on server-owned enums | `V1__schema.sql` (chosen, wear status, planned, library source, session context/status, suggestion kind, ledger bucket) |
| 3.1 | `last_notified_at` on `user_profile` | `UserProfile`, `NotificationJobService` |
| 3.1 | `last_known_lat/lon` dropped | not in V1 |
| 3.2 | Pieces travel inside their outfit; packing items inside their list | `MirrorPushService` (children diffed to the sent list, see B-21) |
| 3.2 | No FK on `catalog_id`, piece/packing `item_id` | Superseded by ruling 9.1: no FKs at all |
| 3.2 | `generated_key` on items | `MirrorItem`, `MirrorImageService` (`kind=generated`) |
| 3.3 | Generated items: styles from gender | `LibrarySeedRunner` (men → masculine, women → feminine, else empty) |
| 3.6 | Push to all of a user's tokens; dead tokens deleted | `NotificationJobService`, `client/push/` |
| 3.7 | `config_map` real JSON types | `V2__seed_config.sql`, `AppConfigService` / `LedgerSettings` |
| 3.7 | Daily generation caps 20 / 200 | `app_config.ledger`, `SuggestionService` |
| 4.1 | GET profile 404 if only a stub | `UserProfileService.get` |
| 4.2 | Push does not check the 100-item limit | `MirrorPushService` (no check) |
| 4.2 | No pull paging in v1 | `MirrorPullService`. Dry-run: largest user ≈ 7.5k rows / 3.1 MB, under the 5 MB bar |
| 4.2 | Old items without `name`: app shows the category | app side; server returns `attributes` without `name` |
| 4.3 | `/library` + compat starter-items + compat `/image/{id}` | `LibraryController` |
| 4.5 | rembg `MAX_SIDE` env (600) | **Not done here**: rembg is another repo. Patch described in README "Deploy". Server works with 300px meanwhile. |
| 4.9 | Server does not delete the Firebase user or RevenueCat customer | `AccountService` |
| 5.4 | Two buckets (private user, public library) | `ObjectStorage` (`Bucket.USER`, `Bucket.LIBRARY`) |
| 5.7 | RevenueCat error = not premium, not cached, WARN | `EntitlementService` |
| 7.3 | `colors` from `color_story` | `DataCopyTransforms`. Only real colour words are kept; the real data has none, so `colors` is `[]`. Values stay under `colorStory`. |
| 7.4 | `z` = rank by `ctid` | `DataCopyOldReader` / `DataCopyTransforms` |
| 7.5 | Planned date converted to the user's timezone | **Changed**, see B-30 |

## B. Calls made during the build

### Foundation (lead)
- **B-1 Versions** (checked on Maven Central 2026-10-11): Spring Boot 4.1.1, Kotlin 2.4.21, Gradle 9.6.1, google-genai 1.76.0, firebase-admin 9.11.0, AWS SDK v2 2.55.14; Flyway, Caffeine, Hibernate 7, Postgres driver from Boot's BOM. Images `eclipse-temurin:25-jdk` / `25-jre`, `postgres:17`.
- **B-2 Gemini through the plain `google-genai` SDK (Vertex mode)**, not Spring AI. Fewer moving parts, no auto-config to switch off in dev, and the stub is a plain interface.
- **B-3 Brief made consistent with section 9** before the build: sections 3, 4, 5 and 7 edited (no FKs; one `attributes jsonb` on `mirror_item` and `library_item`; superset vocabulary; deletion and copy wording; `wtw-app` paths).
- **B-4 Item `attributes` travels nested** in push and pull (the Room `Item.attributes` object), stored whole, unknown keys kept. The brief's earlier flattened shape is gone.
- **B-5 `currency` is `varchar(3)`**, not `char(3)`, so Hibernate's `validate` maps it to `String` without a `bpchar` mismatch.
- **B-6 Profiles:** `prod` = real clients; anything else = stubs. The Docker image sets `prod`; `./gradlew bootRun` defaults to `dev`. A forgotten profile therefore never spends money.
- **B-7 Storage and rembg chosen by `STORAGE_MODE` / `REMBG_MODE`**, not by profile, so docker compose can use MinIO and the real rembg while Gemini etc. stay stubbed.
- **B-8 Dev auth:** `Bearer $AUTH_DEV_TOKEN` → `$AUTH_DEV_UID`; `X-Dev-User-Id` accepted only when `AUTH_DEV_BYPASS_ENABLED=true` (dev default true, prod default false). Both log a WARN at start.
- **B-9 Stub profile row** made by the auth filter, with an in-memory "known uid" cache (evicted on account deletion).
- **B-10 WebP via `com.github.usefulness:webp-imageio` 0.11.0.** The usual `org.sejda` 0.1.6 ships only x86_64 natives (fails on Apple silicon and arm64 servers). JVM flag `--enable-native-access=ALL-UNNAMED` is set in the Dockerfile and `bootRun`.
- **B-11 Ledger split spend:** a spend that takes from both buckets writes the PURCHASED part with ref `{ref}#purchased`, because the unique key is `(user_id, reason, ref)`.
- **B-12 Daily caps for free actions** (attributes, background removal) are in-memory counters per uid per UTC day. They reset on restart. The generation cap counts `ai_suggestion` rows instead (durable).
- **B-13 Metered pattern:** `spend` commits in its own transaction, the Gemini call runs outside any transaction, `refund` on failure. No DB transaction is held open across an AI call.
- **B-14 `/health`** is Actuator's health endpoint at `/health` (DB ping included, details hidden).
- **B-15 Dokku:** no Procfile (Dokku runs the Dockerfile `CMD`). `EXPOSE 8080`. `app.json` healthcheck sets `port: 8080` (Dokku's default check port is 5000).
- **B-16 One-off commands** (`--seed-library`, `--data-copy`) start without a web server; `SecurityConfig` and scheduling are web-only, so jobs never fire inside a `dokku run` container.
- **B-17 Data-copy report files** go to `DATA_COPY_REPORT_DIR` (a `dokku run` container's working dir is thrown away).
- **B-18 Local verification used Homebrew Postgres 16** (no Docker on the build Mac): a `wtw` role and database were created there. `docker compose` is written but was not run.

### Mirror, push, deletion
- **B-21 Children are diffed** (update / delete / insert) to the sent list, not delete-then-insert: Hibernate flushes inserts before deletes, so re-inserting the same key would clash. Same visible result as "replace all".
- **B-22 Bad fields reject only that row** (`INVALID`): time range, string lengths, price size, `layout` not slots/canvas, duplicate pieces, ids over 64 chars. One bad row never fails the whole push.
- **B-23 Item tombstone** deletes its images after the commit. A tombstone for an unknown id is stored as a tombstone row.
- **B-24 Image upload:** unknown id → 409 `ITEM_NOT_PUSHED`; someone else's or a tombstoned item → 404. Body read with a size cap (never loaded whole if too big). Wrong `Content-Type` → 400.
- **B-25 Pull:** `serverTime` is taken before the queries (safe overlap). Library ids for `cutoutUrl` checked in one query.
- **B-26 Push job:** candidates from two queries (tokens, then their profiles), WORN rows batched; `last_notified_at` set only if one send succeeded. The session sweep ignores `NOTIFICATIONS_ENABLED`.
- **B-27 Dev-only routes** (not in prod): `POST /dev/jobs/push-check?at={ms}`, `POST /dev/jobs/session-sweep?at={ms}`; the stub push sender treats tokens starting with `dead-` as dead.

### AI gateway
- **B-31 Photos go to Gemini at ≤1024px JPEG on white**; generated images come back as 600px PNG.
- **B-32 `/ai/attributes` always returns the app's ten fields non-null** (so the app's decoder cannot fail), plus the seven extra keys. A missing `filter` defaults to the cautious answer (fails the pool check).
- **B-33 Missing categories** from the model are mapped to the five category names (anything else would break the app's `Category` enum).
- **B-34 A complete outfit = top + bottom** (a dress-like top counts for both); shoes optional — as on the old server.
- **B-35 Refine resets the attempt count to 1** (old behaviour) and, after a refusal, adds the "show me anyway" instruction (the app's button calls refine).
- **B-36 Gemini retries:** up to 4 tries on a blank reply; timeouts are not retried. Unparseable reply or no valid ids → 502, nothing stored.
- **B-37 Stylist notes** (from `ai_suggestion` history + the superset attributes in the request) cached 15 min per user, cleared on accept.
- **B-38 describe-outfit** accepts `outfitId` but does not use it. Calling `next` on an `INSUFFICIENT_WARDROBE` session returns 409.

### Library, weather, ledger, webhook
- **B-41 Empty `RC_WEBHOOK_AUTH` refuses every webhook** (401). Compare is constant-time on the exact header value.
- **B-42 Webhook retries:** a repeated event id is skipped only if its first run finished; a half-processed event returns 500 and runs again on retry. Ledger refs prevent double credits.
- **B-43 TRANSFER events** have no `app_user_id`; the stored id is `transferred_to[0]`. Every id in both lists is evicted.
- **B-44 Weather `days`** is clamped to 1..`WEATHER_MAX_DAYS` (not rejected); the cache key and the upstream query use lat/lon rounded to 2 decimals. Precipitation is an integer (the app parses an int).
- **B-45 `/library`** cached 10 min; starters first, then by name. Starter-items filter: each filter applies only when that profile field is set (old server behaviour); a stub profile gets all 42.
- **B-46 Library seed:** starter images missing locally → row seeded anyway, missing list + `aws s3 cp` command printed; fetched from old S3 only when `OLD_S3_*` creds are set. Old bucket name overridable with `OLD_S3_BUCKET`.

### Data copy
- **B-30 Planned dates (brief 7.5 changed):** rows at exactly midnight keep their date; only the 126 rows with a time are converted from UTC to the user's zone. Converting midnight UTC would have moved ~122 plans to the previous day for users west of UTC.
- **B-51 The old DB is read through its own small class**, not a second Spring `DataSource` (that would replace Boot's default `JdbcTemplate` and add the old DB to `/health`). Every old connection is read-only three ways. Plain SELECTs here are the one raw-SQL exception.
- **B-52 Stub profiles already made by the auth filter** are filled from the old profile even though their timestamp is newer (they hold no user data). Pieces of an outfit a phone already pushed newer are left alone.
- **B-53 Equal `updated_at` = unchanged** (that is what makes a re-run write 0 rows). A transform fix will not reach rows already copied unless the target is reset first.
- **B-54 Apply refuses to run** when `OLD_DB_URL` and `DATABASE_URL` point at the same database.
- **B-55 `position` "NULL" rows** are actually empty arrays (`{}`) in the real data; treated as NULL (centre, scale 0.714).

## C. Cleanup pass after the review (2026-10-11, `REVIEW_2026-10-11.md`)
- **C-5 Client package split:** `client/` now has one folder per external system (`gemini/ revenuecat/ weather/ rembg/ push/ storage/`). The interface file keeps its shared types; each implementation sits in its own file. Pure moves.
- **C-1 Ledger never calls RevenueCat inside its transaction (S-1):** `LedgerService` resolves premium first (cache, then RevenueCat), then runs the DB work in a `TransactionTemplate` (REQUIRED, same as `@Transactional` before). Callers must not call the ledger from inside their own transaction.
- **C-2 Unknown pack product is left unprocessed (S-2):** outcome `unmatched_product`, `processed_at` null, WARN with the product id. A later delivery (RevenueCat retry or dashboard resend) runs it again; after adding the product to `ledger.packs`, wait for the 5-minute config cache, then resend. Unknown uid / anonymous id stay `ignored` (not in scope).
- **C-3 `Idempotency-Key` header on routes 15 and 16 (S-3):** header (not a body field), because route 15 is multipart. Ledger ref `idem:{key}`; same key while running → waits; after success → same result for 1 h (in memory, 64 MB cap); after a failure or a lost result → 409 `IDEMPOTENCY_KEY_USED` (never a free re-run, never a double charge). Image call timeout `AI_IMAGE_TIMEOUT_MS` set per request (now 10 min, see C-12; app timeout 11 min).
- **C-4 `responseSchema` on `/ai/attributes` only:** built from the same lists as `normalize()` (keys 11–14 nullable). `normalize()` stays as the safety net. Not checked against real Gemini (zero spend); compile and stub only.
- **C-6 Upstream error text:** `"Gemini failed: attributes reply is not JSON"` (new `upstreamFailed(upstream, detail)`). Same code and status.
- **C-7 Local rembg:** compose already had the `rembg` service; README now makes `REMBG_MODE=http` + `REMBG_URL=http://localhost:8000/rembg` the local path (Docker, or the remover's own venv). `MAX_SIDE` patch left as a developer step in README; `imgbg-remover/` untouched.
- **C-8 Data copy stays in the jar:** README "Remove after cutover" lists the files and config to delete. `OLD_S3_*` stays (the Library seed uses it).
- **C-9 Suggestions: one flat fee per session.** `cost_outfit_suggestion` charged once at start (ref = session id), refunded if the first model turn fails; `INSUFFICIENT_WARDROBE` charges nothing; next, refine and accept charge nothing; accept's `charged` is always false (field kept, shape unchanged). Why: in the old app people rarely accepted, and someone may see a suggestion and build it themselves; one fee per session is simple and generous. Sessions started before this deploy and accepted after it are free.
- **C-10 `GEMINI_MODE=local` (dev only):** `LocalModelGeminiClient` calls an OpenAI-compatible server (`LOCAL_MODEL_URL`, default Ollama on 11434; `LOCAL_MODEL_NAME`, default `qwen2.5vl:3b`) with JSON mode; no schema sent. Image generation is a placeholder (the photo as PNG). The server never starts the model; nothing listening → 502 with a clear message. `@Profile("!prod")`, so never active in prod.
- **C-11 `Idempotency-Key` on suggestion start (review B B-1):** optional header on route 17, same `IdempotencyService` rules as routes 15 and 16. With a key the fee's ledger ref is `idem:{key}` (else the session id, as before); a repeat returns the first session from memory (1 h), or 409 `IDEMPOTENCY_KEY_USED` if that start failed or its result is gone.
- **C-12 Gemini timeouts 10 minutes:** `AI_TIMEOUT_MS` and `AI_IMAGE_TIMEOUT_MS` default to 600000 (Gemini can be slow; the SDK historically had no bound). No DB connection is held during a call. App timeout for routes 15, 16 and suggestion start: 11 minutes (`API.md`).
- **C-13 Library images presigned, not public:** both buckets private; `ObjectStorage.presignedUrl(bucket, key)` replaces `publicUrl`; `STORAGE_LIBRARY_PUBLIC_URL` removed. On S3, public links let anyone hotlink at our egress cost. Clients must not store the URLs (7-day TTL).
- **C-14 Gemini models:** image `gemini-3.1-flash-lite-image` (2.5 Flash Image shut down 2026-10-02). Text stays on 2.5 Flash / 2.5 Flash-Lite: project `whatshouldiwear-428000` is "active", so Vertex keeps serving them until 2027-03-31 / 2027-01-28. Before moving text to Gemini 3, circulate thought signatures in `VertexGeminiClient.chat` (multi-turn suggestions).

## D. Fixes after the first usability test (2026-10-18)
- **D-1 `VERTEX_LOCATION=global`:** the 3.x image models return 404 on `us-central1` and work on `global`; the 2.5 text models work on both. Studio clean-up failed 7 of 7 before this. A deployed app that already set `us-central1` must be changed too.
- **D-2 rembg line:** rembg works through one photo at a time (~9 s). The server sends `REMBG_CONCURRENCY` (1) calls at once; the rest wait in arrival order up to `REMBG_QUEUE_WAIT_MS` (3 min), then 504. A burst of 12 went from 12 × 504 to 12 × 200. The app should still send bulk imports one by one. Known gap: a request whose app has given up still takes its turn.
- **D-3 Layered looks:** a slot in the model's JSON may be a list, so two outerwear pieces (blazer under overcoat) both reach `itemIds`. Item aliases (`I9`) are scrubbed from user-facing text.
- **D-4 Feedback (`POST /feedback`):** account deletion deletes the user's feedback rows (not anonymised).
- **D-5 Style words:** vibes, occasions/day types and comfort follow the app's new words (`service/Vocab.kt`). Old words are mapped on input, on GET /profile, in the data copy, and in stored rows (V4). Library vibes come from `VibeTagger` at seed time (every item gets at least one); starters get their old hand-picked tags, mapped, plus the tagger's; `glow up` → Colourful only where the tagger agrees (most were black evening pieces). The mapped old tags keep a few odd ones (`fresh fit` → Smart casual on the oversized hoodie and graphic tee). Route 12 never returns an empty list. After deploying V4, re-run the Library seed to tag the catalog.
- **D-6 rembg model u2net (owner, 2026-10-18):** BiRefNet-lite was OOM-killed on the first photo at 3.5 GB on the 2 vCPU Linux test box (likely the cause of the May revert). u2net peaks ~1.5 GB and takes 1.3–2 s per photo there. The remover shrinks to `MAX_SIDE` (600) and never upscales. The dev Mac's running rembg still uses BiRefNet-lite until it is restarted from `main`.

## E. After deployment: evaluations and experiments (noted, not started)
- **E-1 Suggestions ignore trip and week context** (usability run 2, 2026-10-18; `whattowear/design/usability/run2/REPORT.md` friction #8). On trip days the model picked pieces not packed in the trip's case (Daniel, run-2 log 10:26:31 and 10:29:35), and it repeated pieces already planned for other days that week (4 times). Cause: the start request carries the whole wardrobe and nothing about the trip or the week. Possible fix, app and server together: the app sends the trip or date context; on a trip day, candidates are limited to the packed items; on any day, the week's planned items go to the model as "avoid repeating". Evaluate before building.
- **D-7 Images through the API, no presigning (owner, 2026-10-18; replaces C-13):** every image link is a stable API link with `?v=<version>`: Library `/image/{id}` (public cache, served from memory), a user's own `/mirror/items/{itemId}/images/{kind}` (owner only, private cache). Year-long `immutable` caching plus ETag/304. Versions: Library = `updated_at`; own images = SHA-256 prefix of the WebP, stored on upload (V5; `0` for copied images). `/library` has a weak ETag (Tomcat skips gzip on strong ones) and JSON is gzipped (405 KB → 43 KB). Signing took 150–500 ms per `/library` on the test box and changed every link on every fetch, so phones could not cache. `PUBLIC_BASE_URL` builds the links. Removed: presigning, `STORAGE_PRESIGN_TTL`, `STORAGE_LOCAL_BASE_URL`, `/dev-files`. Not done: CDN (needs a public route), thumbnail sizes.
- **D-8 Original photos backed up (owner, 2026-10-18):** new image kind `original` on the item image route, stored in the user bucket under `user-raw/{uid}/items/{itemId}/original.{ext}` (V6: `original_key`, `original_version`). Kept as sent up to 2048 px (no second compression); larger ones shrunk to 2048 px WebP q85. `originalUrl` on pull. Removed by tombstones and account deletion; a re-upload with a new extension deletes the old object.
