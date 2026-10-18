# Architecture (short)

Agent-facing. The spec is `../WTW_SERVER_BRIEF.md` (sections 1–9). This file says where things live.

## What the server does
A thin backend for the KMP app (`../wtw-app`). The phone is the master of the user's closet. The server adds:
auth (Firebase token check), a credit **ledger** (+ RevenueCat webhook), an **AI gateway** (Gemini: photo attributes,
suggestions, AI item image, outfit description), **background removal** (proxy to the rembg service), a **mirror**
of the phone's rows (backup and restore), the **Library** (540 generated + 42 starter items), **push** scheduling,
**weather** proxy and **account deletion**.

## Stack
Kotlin 2.4 + Spring Boot 4.1 (Spring MVC, virtual threads), Java 25, Postgres (Flyway owns the schema,
Hibernate only validates), Caffeine caches, S3-compatible storage (R2 / S3 / MinIO), Firebase Admin, google-genai.

## Layout (by layer, never by domain)
```
src/main/kotlin/com/adropofliquid/wtw/
  controller/   HTTP routes only. Read uid with @AuthenticationPrincipal. No logic.
  service/      All logic and transactions. Throws ApiException.
  domain/       JPA entities, one per table (15).
  repository/   Spring Data JPA, derived queries.
  dto/          Request/response shapes (camelCase JSON).
  config/       Properties (WtwProperties), DataSource, Firebase, request logging.
  security/     Auth filter, token verifiers, SecurityConfig.
  client/       External systems behind interfaces, one folder each: gemini/ revenuecat/ weather/ rembg/ push/ storage/.
                The interface file holds its shared types; each implementation (prod, stub) has its own file.
src/main/resources/
  application.yml, application-dev.yml, db/migration/V1__schema.sql, V2__seed_config.sql, prompts/
```

## Profiles and modes
| Setting | prod | anything else (dev) |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (set in the Dockerfile) | `dev` (default for `./gradlew bootRun`) |
| Auth | Firebase ID token | `Bearer dev-token` → `dev-user`; `X-Dev-User-Id: <uid>` |
| Gemini, WeatherAPI, RevenueCat, FCM | real clients | stubs with canned results, no network (Gemini: `GEMINI_MODE=local` → a local model, README) |
| Storage (`STORAGE_MODE`) | `s3` | `local` (files in `.local-storage/`, read through the API like S3) |
| rembg (`REMBG_MODE`) | `http` | `stub` |

## Key rules
- No foreign keys (ruling 9.1). Ownership and cascades are done in services: load by `(id, user_id)`; deletes by `user_id`.
- Item attributes are one `jsonb` object holding the superset vocabulary (ruling 9.2), stored as sent.
- Mirror: last writer wins on the device's `updatedAt`; the pull filters on server `synced_at`.
- Ledger: append-only rows; spend locks the `user_profile` row; idempotent by `(user_id, reason, ref)`.
- Live numbers (credits, costs, caps, notification copy) live in `app_config`, cached 5 minutes.
- Never call a paid API from dev. Never log tokens, bodies, prompts, photos; log uids as an 8-char hash.

## One-off commands (same jar, no web server)
`--seed-library` (Library seed, brief 5.4) and `--data-copy=dry-run|apply` (old → new copy, brief 7).
