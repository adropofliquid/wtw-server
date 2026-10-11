# Contributing (patterns and recipes)

Agent-facing. Read `ARCHITECTURE.md` first. Write plainly in code comments and docs.

## Ground rules
- Folders by layer: `controller/ service/ domain/ repository/ dto/ config/ security/ client/`. No per-domain packages.
  The one split: `client/` has one sub-package per external system (`gemini/ revenuecat/ weather/ rembg/ push/ storage/`).
- No tests (developer ruling). Verify by running the app (dev profile) and calling the route with `curl`.
- No raw SQL in app code. Use derived query methods. `@Query` JPQL only when a derived query cannot say it; add a comment why.
  The one exception is the data copy, which reads the OLD database with plain SELECTs (it has no entities).
- Flyway owns the schema. Never edit an applied migration; add `V{n}__what.sql`. `ddl-auto=validate` fails the boot if an entity and the table disagree.
- No foreign keys (ruling 9.1). Index every link column. Enforce ownership and cascades in the service.
- No secrets in the repo. Every setting comes from an env var through `WtwProperties`.
- Never call a paid API in dev. Every external client has a stub.
- Logs: never tokens, request bodies, prompts, model replies, photos or emails. A uid only as `RequestLoggingFilter.uidHash(uid)`.
- After a change: update `API.md` (routes), `DECISIONS.md` (a call you made), README (a new env var).

## Recipe: add a route
1. DTOs in `dto/` (camelCase fields, nullable where the client may leave them out).
2. Logic in a `@Service`; throw `ApiException` (`validation`, `notFound`, `conflict`, ...). Load user rows by `(id, userId)`: another user's row is a 404.
3. Controller in `controller/`: take `@AuthenticationPrincipal uid: String`, call the service, return the DTO. Copy `ProfileController`.
4. Public route (no token)? Add it to `permitAll` in `security/SecurityConfig.kt` and check its own secret, like the RevenueCat webhook.
5. Run `./gradlew bootRun`, call it with `curl -H 'Authorization: Bearer dev-token' ...`, then document it in `API.md`.

## Recipe: add a table
1. Add `src/main/resources/db/migration/V{next}__{what}.sql`: `user_id varchar(128) NOT NULL` + an index, timestamps `timestamptz`, `created_at`/`updated_at`, CHECKs only for small server-owned enums. No `REFERENCES`.
2. Entity in `domain/` (copy one: `var` fields with defaults; `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")` for JSON; `@JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]")` for `List<String>`; `Instant` for timestamptz; `LocalDate` for date; `@IdClass` for a composite key).
3. Repository in `repository/` with derived queries.
4. If the table holds user data: delete it in `AccountService` (account deletion) and decide whether the data copy or the mirror pull needs it.
5. Boot once: `validate` proves the entity matches.

## Recipe: add an external client
1. `client/foo/FooClient.kt`: a small interface with the calls you need. Each implementation goes in its own file in the same folder.
2. Real implementation `@Component @Profile("prod")` reading config from `WtwProperties` (add the field there and the env var in `application.yml`). Use `RestClient` with `JdkClientHttpRequestFactory` and explicit timeouts. Map timeouts to `ApiException.upstreamTimeout`, other failures to `upstreamFailed`. Log failures at WARN with upstream, status, ms.
3. Stub (`client/foo/StubFooClient.kt`) `@Component @Profile("!prod")` with canned, valid results and no network.
4. If someone may want the real client in dev, use a mode property instead of a profile (see `STORAGE_MODE`, `REMBG_MODE` with `@ConditionalOnProperty`).
5. Add the env vars to README "Configuration".

## Recipe: add a metered action (costs credits)
1. Add its cost to the `ledger` row in `app_config` (new migration `UPDATE app_config ...`) and a getter in `LedgerSettings` (service/AppConfigService.kt). Add a reason constant in `Reason` (service/LedgerService.kt).
2. In the service: `val charge = ledger.spend(uid, Reason.X, cost)` — its own transaction, commits at once, 402 if short.
3. Call the upstream OUTSIDE any transaction. On failure: `ledger.refund(uid, charge)` and rethrow (502/504).
4. Return the result with `okWithCredits(state.balance, body)` (controller/Credits.kt) so the app gets `X-Credits-Balance`.
5. Add the cost key to `LedgerResponse.costs` and tell the app side (a new `MeteredAction` in `Gates`).
Use a stable `ref` when a repeat must not charge twice: suggestion start uses the session id; the AI image and description routes use the optional `Idempotency-Key` (`IdempotencyService`). Never call the ledger from inside your own transaction (it looks up RevenueCat first, outside its transaction).

## Recipe: change a live number
Edit `app_config` in SQL (`UPDATE app_config SET config_map = jsonb_set(config_map, '{free_weekly_credits}', '10') WHERE name = 'ledger';`). It applies within 5 minutes. No deploy.
