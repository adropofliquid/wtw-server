# wtw-server HTTP API

This file is for the people and agents who build the app's HTTP clients (KMP app, `source/wtw-app`).
It is written from the code on `main`, not from the brief. Every shape below was checked against a running
dev server with curl (2026-10-11), unless a line says "from code".

Route numbers (1–29) are the same as in `WTW_SERVER_BRIEF.md` section 4, so both files can be read side by side.
Where the code and the brief differ, this file follows the code. The list is at the end:
[Differences from the brief](#differences-from-the-brief).

Contents:
[Basics](#basics) ·
[Profile](#profile) ·
[Mirror](#mirror) ·
[Library](#library) ·
[AI gateway](#ai-gateway) ·
[Background removal](#background-removal) ·
[Ledger](#ledger) ·
[RevenueCat webhook](#revenuecat-webhook) ·
[Push tokens](#push-tokens) ·
[Weather](#weather) ·
[Account deletion](#account-deletion) ·
[Health](#health) ·
[Dev-only routes](#dev-only-routes) ·
[Differences from the brief](#differences-from-the-brief)

---

## Basics

### Base URL

There is no fixed base URL in the server. The app picks it from its own config.

- Local dev: `http://localhost:8080` (or whatever `PORT` is set to).
- Prod: the Dokku app URL. All paths below hang directly off the base URL. There is no `/api` prefix.

### Auth

Every route needs a signed-in user, except:
`GET /health`, `POST /webhooks/revenuecat` (it has its own secret) and `/dev-files/**` (dev only).

**Prod.** Send the Firebase ID token:

```
Authorization: Bearer <Firebase ID token>
```

The server checks the token and uses its `uid` as the user. There is no revocation check.

**Dev profile only** (never in prod):

- `Authorization: Bearer dev-token` signs you in as uid `dev-user`.
  Both values can be changed with `AUTH_DEV_TOKEN` and `AUTH_DEV_UID`.
- `X-Dev-User-Id: <any uid>` signs you in as that uid. It works only when `AUTH_DEV_BYPASS_ENABLED=true`
  (on by default in the dev profile). The value must be 1–128 characters.
- If an `Authorization: Bearer ...` header is present, it wins. A bad bearer token gives 401 even if
  `X-Dev-User-Id` is also sent.

**No token, or a bad token** gives:

```
HTTP 401
{"error":"Sign-in required","code":"UNAUTHENTICATED"}
```

**First request from a new uid.** The server makes an empty "stub" profile row for that uid on any signed-in
request. You do not need to create the profile first. (Ledger and AI routes rely on this row.)

### JSON rules

- Field names are camelCase. The only exception is the weather response, which keeps Open-Meteo's snake_case names.
- Unknown fields in a request are ignored.
- In a request, you may send `null` or leave a field out. Both mean "not given".
- In a response, every field is always present. Fields with no value are sent as `null`. They are never left out.
- Times are **epoch milliseconds** (a JSON number, e.g. `1760000000000`).
- Dates are **`yyyy-MM-dd`** strings (e.g. `"2026-10-12"`).
- A body that is not valid JSON gives `400 VALIDATION` (`"error": "JSON parse error"`).
- A request with the wrong `Content-Type` (for example JSON sent to a multipart route) gives
  `400 VALIDATION`, not 415.
- Enum-like strings (context, rejection, category, platform...) are checked by the server. A bad value gives
  `400 VALIDATION` with a message that lists the allowed values.

### Error body

Every error has the same JSON body:

```json
{"error": "<plain message for logs, not for users>", "code": "<CODE>"}
```

Some codes add extra fields (see the table). The app should switch on `code`, never on `error`.

| HTTP | `code` | When | Extra fields |
|---|---|---|---|
| 400 | `VALIDATION` | Bad or missing field, bad JSON, wrong content type, bad query value | — |
| 401 | `UNAUTHENTICATED` | Token missing or bad. Also a bad webhook secret. | — |
| 402 | `INSUFFICIENT_CREDITS` | Balance is below the cost of the action | `"balance": int, "cost": int` |
| 404 | `NOT_FOUND` | Row missing, or not the caller's. Also an unknown route. | — |
| 405 | `VALIDATION` | Wrong HTTP method on a known path (`"error": "Method not allowed"`) | — |
| 409 | `SESSION_CLOSED` | Suggestion session already completed, or it has no history to continue | — |
| 409 | `ITEM_NOT_PUSHED` | Image upload for an item id the server has never seen | — |
| 409 | `IDEMPOTENCY_KEY_USED` | `Idempotency-Key` reused after its request failed or its result expired (routes 15, 16, 17) | — |
| 413 | `TOO_LARGE` | Upload over `UPLOAD_MAX_BYTES` (10 MB), or a mirror push with more than 1000 rows | — |
| 429 | `LIMIT_REACHED` | A daily cap was hit | `"limit": "attributes" \| "remove_bg" \| "generations"` |
| 500 | `INTERNAL` | Anything else | — |
| 502 | `UPSTREAM_FAILED` | Gemini, rembg or WeatherAPI failed, or gave a reply the server could not use | — |
| 504 | `UPSTREAM_TIMEOUT` | Gemini, rembg or WeatherAPI timed out | — |

Examples:

```json
{"error":"Not enough credits","code":"INSUFFICIENT_CREDITS","balance":0,"cost":2}
{"error":"Daily limit reached","code":"LIMIT_REACHED","limit":"generations"}
```

### Credits header (`X-Credits-Balance`)

Metered routes add this header to a **200** response:

```
X-Credits-Balance: 5
```

It is the user's credit balance after the call. The app can use it to refresh its meter without a
`GET /ledger`.

Routes that send it: `GET /ledger`, `POST /ai/generate-image`, `POST /ai/describe-outfit`,
`POST /outfit/suggest/start`, `/next`, `/refine`, `/accept`.

Error responses do **not** carry the header. A 402 carries the balance in its body instead.
Free routes (`/ai/attributes`, `/images/remove-background`) do not send it.

### Live numbers

Costs, weekly credits and caps are not fixed in code. They live in the `app_config` table (row `ledger`) and
can change without a deploy (applied within 5 minutes). Today's values:

| Key | Value | Used by |
|---|---|---|
| `cost_outfit_suggestion` | 1 | `/outfit/suggest/start`, once per session |
| `cost_ai_image` | 2 | `/ai/generate-image` |
| `cost_outfit_description` | 1 | `/ai/describe-outfit` |
| `free_weekly_credits` / `premium_weekly_credits` | 7 / 100 | ledger |
| `free_daily_generations` / `premium_daily_generations` | 20 / 200 | suggestion generations per local day |
| `max_attempts_per_session` | 10 | `/outfit/suggest/next` → `EXHAUSTED` |
| `attributes_daily_cap` | 200 | `/ai/attributes` |
| `remove_bg_daily_cap` | 300 | `/images/remove-background` |
| `free_item_limit` | 100 | shown in `GET /ledger`; the server does not enforce it |

The app should read costs from `GET /ledger` (`costs`), not hard-code them.

---

## Profile

Routes 1–7. These match the app's `HttpProfile` (`backend/http/HttpProfile.kt`), which already exists.

All seven routes return the same `ProfileResponse`:

```json
{
  "id": "api-doc-test",
  "displayName": "Doc",
  "vibes": ["calm"],
  "event": ["work"],
  "style": "Minimal",
  "timezone": "Europe/London"
}
```

| Field | Type |
|---|---|
| `id` | string. The uid. |
| `displayName` | string or null |
| `vibes` | string[] (never null; may be empty) |
| `event` | string[] (never null; may be empty) |
| `style` | string or null |
| `timezone` | string (IANA zone id) or null |

Request bodies use `ProfileRequest`. All fields are optional:

```json
{"displayName": "Doc", "vibes": ["calm"], "event": ["work"], "style": "Minimal", "timezone": "Europe/London"}
```

Any `id` in the body is ignored. The uid always comes from the token.
In `vibes` and `event`, each string is trimmed and blank strings are dropped.

The timezone matters: the ledger week and the daily suggestion cap both use it (UTC if unset).

### 1. POST `/user/profile`

Upsert. Only the fields you send change. Fields you leave out (or send as null) stay as they are.

- Response: 200 `ProfileResponse`.
- Errors: 400 `VALIDATION` if `timezone` is not a valid IANA zone.

### 2. GET `/user/profile`

- Response: 200 `ProfileResponse`.
- Errors: 404 `NOT_FOUND` (`"No profile yet"`) while the row is still a stub. A stub is a row where
  `displayName`, `style` and `timezone` are null and `vibes` and `event` are empty.
  The app should treat this 404 as "new user, start onboarding".

### 3. PATCH `/user/profile/name`

- Request: `{"displayName": "Doc2"}`
- Sets `displayName` to the value sent. Sending `{}` or `null` **clears** it.
- Response: 200 `ProfileResponse`.

### 4. PUT `/user/profile/vibes`

- Request: `{"vibes": ["bold"]}`
- Replaces the whole list.
- Errors: 400 `VALIDATION` (`"vibes is required"`) if `vibes` is missing or null.

### 5. PUT `/user/profile/event`

- Request: `{"event": ["date"]}`
- Replaces the whole list.
- Errors: 400 `VALIDATION` (`"event is required"`) if `event` is missing or null.

### 6. PATCH `/user/profile/style`

- Request: `{"style": "Minimal"}`
- Sets `style` to the value sent. Sending `{}` or `null` **clears** it.

### 7. PATCH `/user/profile/timezone`

- Request: `{"timezone": "Europe/London"}`
- Errors: 400 `VALIDATION` if missing (`"timezone is required"`) or not a valid IANA zone
  (`"timezone is not a valid IANA zone"`).

Routes 3–7 create the row if it does not exist.

---

## Mirror

Routes 8–10. **App client needed** for all three. The phone is the master copy of the user's data.
The server keeps a mirror of it for restore and for server jobs. Mirror routes are not metered.

The app needs `updatedAt` (ms) and `deletedAt` (ms or null) on Item, Outfit, Planned, Wear and PackingList,
a push queue, and a restore pull on first sign-in.

### Push

#### 8. POST `/mirror/push`

Batch upserts and tombstones. Every list is optional. `{}` is a valid body (it writes nothing).

Request (`Content-Type: application/json`), every field shown:

```json
{
  "items": [{
    "id": "doc-item-1",
    "createdAt": 1760000000000,
    "updatedAt": 1760000000000,
    "deletedAt": null,
    "chosen": "CUTOUT",
    "reviewed": true,
    "category": "tops",
    "attributes": {"name": "Navy tee", "category": "tops", "...": "the superset object, see below"},
    "price": 29.99,
    "currency": "GBP",
    "purchaseDate": "2026-05-01",
    "brand": "Uniqlo",
    "archived": false,
    "catalogId": null,
    "source": "camera",
    "poolConsent": false,
    "poolFilter": "passed"
  }],
  "outfits": [{
    "id": "doc-outfit-1",
    "name": "Work",
    "createdByAi": true,
    "createdAt": 1760000000000,
    "updatedAt": 1760000000000,
    "deletedAt": null,
    "occasion": "Work",
    "note": "n",
    "layout": "canvas",
    "pieces": [{"itemId": "doc-item-1", "x": 0.5, "y": 0.4, "scale": 1.0, "rotation": 0.0, "z": 1}]
  }],
  "planned": [{
    "date": "2026-10-12", "outfitId": "doc-outfit-1",
    "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null
  }],
  "wear": [{
    "date": "2026-10-11", "status": "WORN", "outfitId": "doc-outfit-1", "itemIds": ["doc-item-1"],
    "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null
  }],
  "packingLists": [{
    "id": "doc-list-1", "name": "Lisbon", "startDate": "2026-11-01", "endDate": "2026-11-05",
    "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null,
    "items": [{"itemId": "doc-item-1", "packed": true}]
  }]
}
```

**Item row fields**

| Field | Type | Rule | If left out |
|---|---|---|---|
| `id` | string | required, not blank, ≤ 64 chars | — (row rejected) |
| `updatedAt` | ms | **required** | — (row rejected) |
| `createdAt` | ms or null | | `updatedAt` on first insert; unchanged after |
| `deletedAt` | ms or null | set = tombstone | live row |
| `chosen` | `"ORIGINAL"` \| `"CUTOUT"` \| `"GENERATED"` | | `"CUTOUT"` |
| `reviewed` | bool | | `false` |
| `category` | `"tops"` \| `"bottoms"` \| `"outerwear"` \| `"shoes"` \| `"accessories"` or null | lowercase | null |
| `attributes` | object or null | stored whole, unknown keys kept | null |
| `price` | number or null | abs value < 10,000,000,000 | null |
| `currency` | string or null | exactly 3 chars; stored upper-case | null |
| `purchaseDate` | `yyyy-MM-dd` or null | | null |
| `brand` | string or null | | null |
| `archived` | bool | | `false` |
| `catalogId` | string or null | ≤ 128 chars. A Library item id. | null |
| `source` | string or null | ≤ 16 chars (e.g. `camera`, `gallery`, `catalog`) | null |
| `poolConsent` | bool | | `false` |
| `poolFilter` | string or null | ≤ 16 chars (e.g. `passed`) | null |

A live item row replaces every field. A field you leave out is reset to its default, not kept. So always send
the full row.

**Outfit row fields**

| Field | Type | Rule | If left out |
|---|---|---|---|
| `id` | string | required, ≤ 64 | rejected |
| `updatedAt` | ms | required | rejected |
| `createdAt`, `deletedAt` | ms or null | | as items |
| `name` | string | | `""` |
| `createdByAi` | bool | | `false` |
| `occasion`, `note` | string or null | | null |
| `layout` | `"slots"` \| `"canvas"` or null | | null |
| `pieces` | array | each `itemId` required, ≤ 64, and **unique within the outfit** | `[]` |
| `pieces[].x`, `.y` | float | | `0.5` |
| `pieces[].scale` | float | | `1.0` |
| `pieces[].rotation` | float | | `0.0` |
| `pieces[].z` | int | | `0` |

The server does not check that piece `itemId`s exist. `pieces` replaces the outfit's pieces completely.

**Planned row fields** (key = user + `date`; one plan per day)

| Field | Type | Rule |
|---|---|---|
| `date` | `yyyy-MM-dd` | required |
| `updatedAt` | ms | required |
| `outfitId` | string | Live row: required, and the outfit must be on the server, owned by the caller and not tombstoned (else `MISSING_OUTFIT`). Tombstone: optional, ≤ 64. |
| `createdAt`, `deletedAt` | ms or null | |

Push the outfit before (or in the same request as) the plan that uses it. Outfits are processed first within a request.

**Wear row fields** (key = user + `date`)

| Field | Type | Rule |
|---|---|---|
| `date` | `yyyy-MM-dd` | required |
| `updatedAt` | ms | required |
| `status` | `"WORN"` \| `"NOT_WORN"` | required on a live row |
| `outfitId` | string or null | ≤ 64. Not checked against outfits, on purpose. |
| `itemIds` | string[] | nulls inside are dropped; default `[]` |
| `createdAt`, `deletedAt` | ms or null | |

**Packing list row fields**

| Field | Type | Rule |
|---|---|---|
| `id` | string | required, ≤ 64 |
| `updatedAt` | ms | required |
| `name` | string | **required on a live row** |
| `startDate`, `endDate` | `yyyy-MM-dd` or null | |
| `createdAt`, `deletedAt` | ms or null | |
| `items` | array of `{itemId: string, packed: bool}` | `itemId` required, ≤ 64, unique in the list; `packed` default `false`. Replaces all items. |

**All rows:** every time value must be between 0 and 253402300799999 (year 9999), or the row is `INVALID`.

**How each row is handled**

The whole request is one database transaction. Lists are processed in this order:
items → outfits → planned → wear → packingLists. Each row gets one result:

| Case | Result |
|---|---|
| Row is `null`, or `id` missing / blank / over 64 chars (or `date` not a valid date for planned and wear) | rejected `INVALID` |
| `id` exists and belongs to another user (items, outfits, packingLists) | rejected `NOT_OWNER` |
| Row fails a rule in the tables above | rejected `INVALID` |
| Planned live row whose outfit is missing, tombstoned or not the caller's | rejected `MISSING_OUTFIT` |
| Server copy has a **newer** `updatedAt` than the row | counted in `stale`, not written |
| Otherwise (including an equal `updatedAt`) | written, counted in `upserted` |

The `NOT_OWNER` check comes before the `INVALID` check for the other fields.

**Tombstones** (`deletedAt` set):
- The row is kept with `deletedAt` set. Other fields may be left out; they are not checked and not changed.
- An item tombstone deletes the item's cut-out and AI images from storage and clears their keys.
- An outfit tombstone deletes its pieces. A packing list tombstone deletes its items.
- A later live row with the same id (and a newer `updatedAt`) brings the row back. An item's images do not
  come back; the app must upload them again.

Response 200:

```json
{
  "serverTime": 1791684339257,
  "results": {
    "items":        {"upserted": 3, "stale": 0, "rejected": [
                      {"id": "doc-item-bad", "reason": "INVALID"},
                      {"id": "doc-item-nots", "reason": "INVALID"},
                      {"id": "", "reason": "INVALID"}]},
    "outfits":      {"upserted": 1, "stale": 0, "rejected": [{"id": "doc-outfit-dup", "reason": "INVALID"}]},
    "planned":      {"upserted": 1, "stale": 0, "rejected": [
                      {"id": "2026-10-13", "reason": "MISSING_OUTFIT"},
                      {"id": "13/10/2026", "reason": "INVALID"}]},
    "wear":         {"upserted": 1, "stale": 0, "rejected": [{"id": "2026-10-10", "reason": "INVALID"}]},
    "packingLists": {"upserted": 1, "stale": 0, "rejected": [{"id": "doc-list-noname", "reason": "INVALID"}]}
  }
}
```

- All five keys are always present.
- `rejected[].id` is the row id. For planned and wear it is the `date` string as sent. For a `null` row or a
  row with no id it is `""`.
- `reason` is one of `INVALID`, `NOT_OWNER`, `MISSING_OUTFIT`.
- `serverTime` is the server clock (ms) at the start of the push.

App notes:
- `stale` rows mean the server already has a newer copy. Pull to get it.
- `MISSING_OUTFIT`: keep the plan in the queue and send it again on the next push.
- `INVALID` and `NOT_OWNER` will not succeed on retry. Drop them from the queue and log them.

Errors:
- 400 `VALIDATION` — body is not valid JSON.
- 413 `TOO_LARGE` — more than `MIRROR_MAX_ROWS_PER_PUSH` rows (default 1000) across all five lists together.
  Child rows (`pieces`, packing `items`) do not count. Split the push.

The free item limit (100) is **not** checked here. The device enforces it.

#### The `attributes` object (superset, 17 keys)

`items[].attributes` (push and pull), `GET /library` `attributes`, and `POST /ai/attributes` all use the same
object. It is the app's `ItemAttributes` (first ten keys) plus seven extra keys from the old server.

The server stores whatever the app sends, whole, and keeps unknown keys. So the object coming back on a pull is
exactly what was pushed (key order may change). The app must decode it with `ignoreUnknownKeys = true`.

| # | Key | Type | Values |
|---|---|---|---|
| 1 | `name` | string | free text, ≤ 80 chars from AI |
| 2 | `category` | string | `tops` `bottoms` `outerwear` `shoes` `accessories` |
| 3 | `description` | string | free text, ≤ 600 chars from AI |
| 4 | `formality` | string | `lounge` `casual` `elevated_casual` `smart_casual` `business` `cocktail` `formal` |
| 5 | `polish` | string | `unfinished` `casual` `considered` `polished` `sharp` |
| 6 | `fitShape` | string | `tight` `tailored` `relaxed` `boxy` `oversized` `slouchy` |
| 7 | `layeringRole` | string | `base` `mid` `outerwear` `standalone` `statement-layer` |
| 8 | `colors` | string[] | free words, ≤ 5 from AI |
| 9 | `season` | string | `summer` `winter` `all` |
| 10 | `tags` | string[] | free words, ≤ 12 from AI (e.g. `garment_type:sneakers`) |
| 11 | `silhouetteRole` | string or null | `structured` `draped` `vertical` `body-skimming` `body-revealing` `body-hiding` |
| 12 | `statementLevel` | string or null | `quiet` `mid` `statement` `loud` |
| 13 | `comfortCost` | string or null | `none` `low` `medium` `high` |
| 14 | `skinCoverageOverall` | string or null | `high` `medium` `low` |
| 15 | `colorStory` | string[] | `neutral` `muted` `saturated` `jewel` `monochrome` `high-contrast` `low-contrast` `dark` |
| 16 | `fabricHand` | string[] | `crisp` `soft` `drapey` `structured` `stretchy` `slinky` `plush` |
| 17 | `skinCoverageZones` | string[] | `chest` `arm` `leg` `back` `midriff` `shoulder` |

From `POST /ai/attributes` the first ten are never null (the server fills safe defaults), and keys 11–14 are
null when the model did not give a known value.

Warning for the app: items copied from the old server, and items pushed by older app builds, may have a partial
object (for example no `name`). The KMP `ItemAttributes` has required fields, so a strict decode of a pulled
item can fail. Decode pulled attributes leniently (defaults, or fall back to `null` attributes) and show the
category label when `name` is missing.

### Pull (restore)

#### 9. GET `/mirror/pull?since={ms}`

| Query | Type | Default | Rule |
|---|---|---|---|
| `since` | long (ms) | `0` | must be ≥ 0. Not a number → 400. |

Two modes:

- **`since=0` (restore).** Returns every **live** row (no tombstones) for items, outfits, planned, wear and
  packing lists. Returns every suggestion.
- **`since>0` (catch-up).** Returns every row whose server `synced_at` is after `since`, **including tombstones**
  (`deletedAt` set). Suggestions: every suggestion whose `updatedAt` is after `since`.

`synced_at` is the server time when the row was last written by a push or an image upload. It is not
`updatedAt`. An image upload bumps `synced_at` (so the item comes back in the next pull with fresh URLs), but
not `updatedAt`.

`serverTime` is read **before** the queries. Store it and use `serverTime - 60000` as the next `since`
(a one-minute overlap). Applying the same row twice is safe. There is no paging.

Response 200 (real output, shortened only in `reasoning`):

```json
{
  "serverTime": 1791684339815,
  "items": [{
    "id": "doc-item-1",
    "createdAt": 1760000000000,
    "updatedAt": 1760000000000,
    "deletedAt": null,
    "chosen": "CUTOUT",
    "reviewed": true,
    "category": "tops",
    "attributes": {"name": "Navy tee", "x-extra": 5, "category": "tops"},
    "price": 29.99,
    "currency": "GBP",
    "purchaseDate": "2026-05-01",
    "brand": "Uniqlo",
    "archived": false,
    "catalogId": null,
    "source": "camera",
    "poolConsent": false,
    "poolFilter": "passed",
    "cutoutUrl": "http://localhost:8095/dev-files/user/users/api-doc-test/items/doc-item-1/cutout.webp",
    "generatedUrl": "http://localhost:8095/dev-files/user/users/api-doc-test/items/doc-item-1/generated.webp"
  }],
  "outfits": [{
    "id": "doc-outfit-1",
    "name": "Work",
    "createdByAi": true,
    "createdAt": 1760000000000,
    "updatedAt": 1760000000000,
    "deletedAt": null,
    "occasion": "Work",
    "note": "n",
    "layout": "canvas",
    "pieces": [
      {"itemId": "doc-item-2", "x": 0.5, "y": 0.5, "scale": 1.0, "rotation": 0.0, "z": 0},
      {"itemId": "doc-item-1", "x": 0.5, "y": 0.4, "scale": 1.0, "rotation": 0.0, "z": 1}
    ]
  }],
  "planned": [{"date": "2026-10-12", "outfitId": "doc-outfit-1", "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null}],
  "wear": [{"date": "2026-10-11", "status": "WORN", "outfitId": "doc-outfit-1", "itemIds": ["doc-item-1"], "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null}],
  "packingLists": [{
    "id": "doc-list-1", "name": "Lisbon", "startDate": "2026-11-01", "endDate": "2026-11-05",
    "createdAt": 1760000000000, "updatedAt": 1760000000000, "deletedAt": null,
    "items": [{"itemId": "doc-item-1", "packed": true}]
  }],
  "suggestions": [{
    "id": "8bf9a0b0-4ff2-419f-af22-2143228948d1",
    "sessionId": "sess_92a6be30-6411-4c29-8e8c-96ce48f67fd7",
    "kind": "session",
    "createdAt": 1791684399018,
    "updatedAt": 1791684399054,
    "context": "DAILY",
    "date": "2026-10-12",
    "name": "Everyday errands",
    "itemIds": ["doc-item-1", "doc-item-2", "doc-item-3"],
    "reasoning": "A clean, easy pairing: ...",
    "occasion": "Everyday errands, Casual lunch, Relaxed office",
    "accepted": true,
    "rejected": false,
    "rejectionReason": null,
    "feedback": null
  }]
}
```

All six lists are always present (maybe empty).

**Item fields on pull.** Same as push, plus two URLs. On pull these are never null: `id`, `createdAt`,
`updatedAt`, `chosen`, `reviewed`, `archived`, `poolConsent`. These may be null: `deletedAt`, `category`,
`attributes`, `price`, `currency`, `purchaseDate`, `brand`, `catalogId`, `source`, `poolFilter`, `cutoutUrl`,
`generatedUrl`.

| Field | Meaning |
|---|---|
| `cutoutUrl` | Time-limited GET URL of the item's own cut-out (WebP). If the item has no own cut-out but its `catalogId` is an active Library item, this is the Library image URL instead. Null for a tombstone. |
| `generatedUrl` | Time-limited GET URL of the AI studio image (WebP), or null. Null for a tombstone. |

URLs are valid for `STORAGE_PRESIGN_TTL` (default 7 days). Download the image soon; do not store the URL as
a long-lived reference. In dev they point at `/dev-files/...` on the same server.

**Outfit fields on pull.** As push. `pieces` is never null and is sorted by `z` (lowest first).

**Planned / wear on pull.** As push. `planned.outfitId` may be null only on an old tombstone. `wear.status` may be
null on a tombstone. `wear.itemIds` is never null.

**Packing lists on pull.** As push. `items` is never null.

**Suggestions** (read-only history; the app has no table for it yet and may ignore it):

| Field | Type |
|---|---|
| `id` | string (UUID) |
| `sessionId` | string or null (`sess_...`) |
| `kind` | string (`"session"` for new ones; copied rows may differ) |
| `createdAt`, `updatedAt` | ms |
| `context` | `"DAILY"` \| `"PLANNER"` \| `"WARDROBE"` or null |
| `date` | `yyyy-MM-dd` or null (the day the outfit was for) |
| `name` | string or null |
| `itemIds` | string[] |
| `reasoning`, `occasion` | string or null |
| `accepted`, `rejected` | bool |
| `rejectionReason` | `NOT_MY_STYLE` \| `WRONG_VIBE` \| `TOO_FORMAL` \| `TOO_CASUAL` \| `JUST_BROWSING` or null |
| `feedback` | string or null |

Errors: 400 `VALIDATION` if `since` is negative or not a number.

App notes:
- Old users restored from the old server have no original photo. Save the downloaded cut-out as the item's
  image (`originalPath` = the cut-out, `chosen = CUTOUT`).
- Apply a tombstone by deleting the local row (or marking it deleted).

### Item images

#### 10. PUT `/mirror/items/{itemId}/images/{kind}`

Uploads the cut-out or the AI studio image for an item that is already pushed.

| Part | Value |
|---|---|
| `itemId` (path) | the item id |
| `kind` (path) | `cutout` or `generated` |
| Body | **raw image bytes** (not multipart) |
| `Content-Type` | `image/png`, `image/webp` or `image/jpeg` |
| Size limit | `UPLOAD_MAX_BYTES` (default 10 MB = 10485760 bytes) |

The server decodes the bytes (by content, not by header), shrinks the image so the longest side is at most
600 px (never upscales), and stores it as WebP (quality 80). A new upload overwrites the old one.

Response 200:

```json
{
  "key": "users/api-doc-test/items/doc-item-1/cutout.webp",
  "url": "http://localhost:8095/dev-files/user/users/api-doc-test/items/doc-item-1/cutout.webp"
}
```

`url` is a time-limited GET URL, like the pull URLs.

Errors:

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION` | `kind` is not `cutout` or `generated` |
| 400 | `VALIDATION` | `Content-Type` is not one of the three (e.g. `image/gif`) |
| 400 | `VALIDATION` | empty body, or bytes that are not a readable PNG, JPEG or WebP |
| 404 | `NOT_FOUND` | item belongs to another user, or is tombstoned |
| 409 | `ITEM_NOT_PUSHED` | the server has never seen this item id. Push the item row first, then retry. |
| 413 | `TOO_LARGE` | body over the size limit |

---

## Library

Routes 11–13. The Library is the shared catalog: 42 starter items plus generated (brand) items.

### 11. GET `/library`

**App client needed** (to replace `HttpCatalog.items`). Needs auth. Returns all active items: starters first,
then by name.

Response 200 — an array (real output; one starter, one generated):

```json
[
  {
    "id": "7b0e9857-646b-4a6b-bca5-4e2142c7bc2b",
    "source": "starter",
    "name": "Athletic tshirt",
    "category": "tops",
    "description": "A moisture-wicking athletic T-shirt in charcoal grey. ...",
    "vibes": ["sporty"],
    "styles": ["masculine", "feminine", "androgynous"],
    "brand": null,
    "keywords": [],
    "imageUrl": "http://localhost:8095/dev-files/library/library/7b0e9857-646b-4a6b-bca5-4e2142c7bc2b.webp",
    "attributes": null
  },
  {
    "id": "adidas-adilette-black",
    "source": "generated",
    "name": "Adidas Adilette, black",
    "category": "shoes",
    "description": "Adidas Adilette slide sandals in black.",
    "vibes": [],
    "styles": [],
    "brand": "Adidas",
    "keywords": ["slide sandals", "black", "adidas", "adilette", "..."],
    "imageUrl": "http://localhost:8095/dev-files/library/library/adidas-adilette-black.webp",
    "attributes": {
      "name": "Adidas Adilette, black", "category": "shoes", "description": "Adidas Adilette slide sandals in black.",
      "formality": "casual", "polish": "casual", "fitShape": "relaxed", "layeringRole": "standalone",
      "colors": ["black"], "season": "summer",
      "tags": ["garment_type:slide-sandals", "brand:adidas", "model:adilette", "season:summer"]
    }
  }
]
```

| Field | Type |
|---|---|
| `id` | string |
| `source` | `"starter"` \| `"generated"` |
| `name`, `description` | string |
| `category` | one of the five categories |
| `vibes`, `styles`, `keywords` | string[] |
| `brand` | string or null |
| `imageUrl` | string. Public URL of the WebP image (no auth needed to fetch it in prod). |
| `attributes` | superset attributes object, or null (null for starters) |

App note: this is the KMP `CatalogItem` plus `source`, `brand`, `keywords` and `imageUrl`. Decode with
`ignoreUnknownKeys`. The list is cached on the server for 10 minutes.

### 12. GET `/user/onboarding/starter-items`

**Compat** route, used by the existing `HttpCatalog`. Starters only, filtered by the caller's profile:

- If the profile has a `style`, only starters whose `styles` contain it.
- If the profile has `vibes`, only starters that share at least one vibe.
- No style and no vibes → all starters (42 today).

Response 200 — an array (shape from code; count checked by curl):

```json
[{"id": "<library id>", "name": "<old file name>.png", "description": "...", "type": "tops", "vibe": ["sporty"], "style": ["masculine", "feminine", "androgynous"]}]
```

`name` is the old PNG file name. `type` is the category. `vibe` and `style` are singular names here, on purpose
(old server shape).

### 13. GET `/image/{id}`

**Compat** route for `HttpCatalog.image`. Serves **Library images only**. Needs auth (send the token).

- Response 200: raw bytes, `Content-Type: image/webp`, `Cache-Control: max-age=86400, public`, `Content-Length` set.
- Errors: 404 `NOT_FOUND` if `id` is not an active Library item, or its image file is missing.
- User item images are never served here. Use the pull URLs.

---

## AI gateway

Routes 14–20. **App client needed**: `HttpAiGateway` still sends AI work to the fake.
Field names match the KMP `@Serializable` names in `backend/AiGateway.kt` and `data/Models.kt`.

Photo routes (14, 15 and 21) take a multipart form:

| Part | Value |
|---|---|
| `Content-Type` | `multipart/form-data` |
| Field name | `photo` (a file part) |
| File type | JPEG, PNG or WebP. Checked by the bytes, not the file name or part content type. |
| Size limit | 10 MB for the file **and** for the whole request. Keep photos a little under 10 MB. |

Photo errors (all three routes):

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION` | no `photo` part, or it is empty (`"photo is required"`) |
| 400 | `VALIDATION` | bytes are not JPEG, PNG or WebP (`"photo must be a jpeg, png or webp image"`) |
| 400 | `VALIDATION` | request is not multipart |
| 413 | `TOO_LARGE` | over the size limit |

Nothing from these routes is stored. Photos sent to Gemini are shrunk to 1024 px JPEG first.

#### Optional `Idempotency-Key` header (routes 15 and 16; also route 17)

Optional and backward compatible: a request without it works exactly as before.

```
Idempotency-Key: 3f1c9a2e-6b1d-4d8e-9a57-0c2f4b8e1d11
```

- Value: 1–64 characters of `A-Z a-z 0-9 - _ . :` (a UUID is ideal), else 400 `VALIDATION`.
  Make a new key per user action, and send the **same** key when you retry that action.
- The key is the charge's ledger ref, so one key is charged **at most once** per route.
- A retry while the first request is still running waits for it and gets the same response.
  A retry after it succeeded (within 1 hour) gets the same response again, with no new charge and no new call.
- A retry after the first request **failed** (it was refunded), or after the stored result is gone (1 hour, or a
  server restart), gives 409 `IDEMPOTENCY_KEY_USED`. Send a new key to try again.

**Timeouts.** The server gives Gemini up to 60 s on these routes (`AI_IMAGE_TIMEOUT_MS` for the image call,
`AI_TIMEOUT_MS` for the review). Set the app's HTTP timeout for routes 15 and 16 to **90 s**, so the app does not
give up before the server has answered. With an `Idempotency-Key`, a retry after an app-side timeout is still safe.

### 14. POST `/ai/attributes`

Reads a photo and returns the superset attributes object plus `filter`.

- Cost: free. Daily cap `attributes_daily_cap` (200) per user per **UTC** day → 429 `LIMIT_REACHED`,
  `"limit": "attributes"`. The counter is in memory, so it resets if the server restarts.
- Response 200 (real dev-stub output; prod values come from Gemini):

```json
{
  "name": "Everyday sneakers",
  "category": "shoes",
  "description": "Everyday sneakers: a simple piece with a relaxed fit. ...",
  "formality": "casual",
  "polish": "considered",
  "fitShape": "relaxed",
  "layeringRole": "standalone",
  "colors": ["black"],
  "season": "all",
  "tags": ["garment_type:sneakers"],
  "silhouetteRole": "draped",
  "statementLevel": "quiet",
  "comfortCost": "low",
  "skinCoverageOverall": "high",
  "colorStory": ["neutral", "dark"],
  "fabricHand": ["soft"],
  "skinCoverageZones": [],
  "filter": {"singleGarment": true, "personPresent": false}
}
```

Always 18 keys: the 17 attribute keys plus `filter`. The first ten are never null. When the model gives no
usable value, the server fills a default:
`name` "Top" / "Bottoms" / "Jacket" / "Shoes" / "Accessory" (by category), `category` "tops", `description` "",
`formality` "casual", `polish` "casual", `fitShape` "relaxed", `layeringRole` by category
(`base` for tops and bottoms, `outerwear`, `standalone` for shoes and accessories), `season` "all", lists `[]`.
Keys 11–14 are null when unknown. Values outside the lists above are dropped.

`filter` is never null. When unknown it is `{"singleGarment": false, "personPresent": true}` (the safe answer:
it fails the pool filter).

- Other errors: 502 `UPSTREAM_FAILED` (Gemini failed or did not reply with JSON), 504 `UPSTREAM_TIMEOUT`.
- App note: map keys 1–10 to `ItemAttributes`, keep the whole object for the mirror push, and keep `filter` as the
  item's `poolFilter`.

### 15. POST `/ai/generate-image`

Makes a clean studio image of the garment.

- Cost: `cost_ai_image` (2). Charged **before** the Gemini call. If the call fails, the charge is refunded.
- Response 200: raw PNG bytes, `Content-Type: image/png`, header `X-Credits-Balance` (balance after the charge).
  The PNG is at most 600 px on its longest side.
- Errors: photo errors above, 402 `INSUFFICIENT_CREDITS` (`cost` = 2), 502 `UPSTREAM_FAILED`,
  504 `UPSTREAM_TIMEOUT` (both refunded).
- App note: to store it, upload it with route 10 (`kind=generated`). Maps to `MeteredAction.AI_IMAGE`.

### 16. POST `/ai/describe-outfit`

A short stylist review of an outfit the user made.

Request (`application/json`):

```json
{
  "outfitId": "doc-outfit-1",
  "occasion": "Work",
  "items": [
    {"id": "doc-item-1", "category": "tops", "attributes": {"name": "Navy tee"}},
    {"id": "doc-item-2", "category": "bottoms", "attributes": null}
  ]
}
```

| Field | Type | Rule |
|---|---|---|
| `outfitId` | string or null | accepted, not used |
| `occasion` | string or null | trimmed, ≤ 200 chars used |
| `items` | `WardrobeEntry[]` | required, 1–20 entries |
| `items[].id` | string or null | not checked |
| `items[].category` | string | required, one of the five (any case) |
| `items[].attributes` | superset object or null | |

- Cost: `cost_outfit_description` (1). Charged first, refunded if the call fails.
- Response 200, header `X-Credits-Balance`:

```json
{"review": "The pieces share a calm colour story and a similar level of polish, ...", "occasion": "Casual day out, Coffee with friends, Weekend errands"}
```

Both fields are always strings. `occasion` falls back to the request's `occasion`, then to `""`.

- Errors: 400 `VALIDATION` (`"items is required"`, `"items has more than 20 entries"`,
  `"items[].category must be one of ..."`), 402 `INSUFFICIENT_CREDITS`, 502, 504.
- App note: the app needs a new `MeteredAction.OUTFIT_DESCRIPTION` in `meter/UsageMeter.kt`.

### Suggestion sessions (routes 17–20)

One outfit at a time. The app starts a session, asks for the next one or a refined one, and accepts one.

- The wardrobe comes from the request, not from the mirror. Returned `itemIds` are always ids from the sent
  wardrobe.
- Money: **one flat fee per session.** Start charges `cost_outfit_suggestion` (1) once (ledger reason
  `outfit_suggestion`, ref = the session id). If the first model turn fails, the fee is refunded
  (reason `refund_failed_call`). An `INSUFFICIENT_WARDROBE` start makes no model call and charges nothing.
  **next, refine and accept charge nothing.** (Changed 2026-10-11: the fee used to be charged at accept.)
- Daily generation cap: every generated suggestion counts. Limit is `free_daily_generations` (20) or
  `premium_daily_generations` (200), counted from midnight in the profile's timezone (UTC if unset).
  At the cap, start / next / refine give 429 `LIMIT_REACHED`, `"limit": "generations"`.
- All four routes return `X-Credits-Balance` on 200.
- A session with no activity for 24 hours is marked `ABANDONED` by a server job. It can still be continued.

`SuggestionResponse` (returned by start, next and refine):

```json
{
  "status": "SUCCESS",
  "sessionId": "sess_92a6be30-6411-4c29-8e8c-96ce48f67fd7",
  "attempt": 1,
  "suggestion": {
    "id": "6e951ea9-32e3-48df-81cc-7d22b3a13c45",
    "name": "Everyday errands",
    "itemIds": ["doc-item-1", "doc-item-2", "doc-item-3"],
    "reasoning": "A clean, easy pairing: ...",
    "occasion": "Everyday errands, Casual lunch, Relaxed office"
  },
  "failure": null
}
```

| Field | Type |
|---|---|
| `status` | `"SUCCESS"` \| `"INSUFFICIENT_WARDROBE"` \| `"LOW_CONFIDENCE"` \| `"EXHAUSTED"` |
| `sessionId` | string, `sess_<uuid>` |
| `attempt` | int |
| `suggestion` | object when `SUCCESS`, else null |
| `suggestion.id` | string (UUID). Use it in accept. |
| `suggestion.name` | string (first part of `occasion`, or `"Outfit"`) |
| `suggestion.itemIds` | string[], at least one id, ordered top → bottom → outerwear → shoes → accessories |
| `suggestion.reasoning` | string (may be `""`) |
| `suggestion.occasion` | string or null |
| `failure` | object when not `SUCCESS`, else null |
| `failure.reason` | string or null |
| `failure.missingCategories` | string[] of category names (only the five), may be empty |

This matches the KMP `SuggestionResponse`, `SuggestedOutfit` and `FailureContext` exactly.

The other statuses (real output):

```json
{"status":"INSUFFICIENT_WARDROBE","sessionId":"sess_6920...","attempt":0,"suggestion":null,
 "failure":{"reason":"Not enough items in wardrobe to create an outfit","missingCategories":["tops","bottoms"]}}

{"status":"LOW_CONFIDENCE","sessionId":"sess_fd65...","attempt":1,"suggestion":null,
 "failure":{"reason":"I need at least a top and a bottom to build an honest outfit from this wardrobe.","missingCategories":["bottoms"]}}

{"status":"EXHAUSTED","sessionId":"sess_02d2...","attempt":10,"suggestion":null,
 "failure":{"reason":"You've seen many suggestions in this session","missingCategories":[]}}
```

#### 17. POST `/outfit/suggest/start`

Request:

```json
{
  "context": "DAILY",
  "dayType": "Work",
  "comfort": "Comfortable",
  "date": "2026-10-12",
  "weather": {"date": "2026-10-12", "code": 3, "maxC": 16.1, "minC": 9.4, "precipChance": 10},
  "wardrobe": [
    {"id": "doc-item-1", "category": "tops", "attributes": {"name": "Navy tee", "category": "tops"}},
    {"id": "doc-item-2", "category": "bottoms", "attributes": {"name": "Jeans"}},
    {"id": "doc-item-3", "category": "shoes", "attributes": null}
  ],
  "style": "feminine",
  "note": "big meeting",
  "recentlyWorn": {"doc-item-1": "2026-10-10"}
}
```

| Field | Type | Rule |
|---|---|---|
| `context` | `"DAILY"` \| `"PLANNER"` \| `"WARDROBE"` | required (any case) |
| `dayType`, `comfort` | string or null | free text, trimmed |
| `date` | `yyyy-MM-dd` or null | DAILY and PLANNER default to today (profile timezone) |
| `weather` | `DayWeather` or null | `{date: string, code: int (WMO), maxC: number, minC: number, precipChance: int}`, all nullable |
| `wardrobe` | `WardrobeEntry[]` | required (may be empty), at most 2000 entries; duplicate ids are merged |
| `wardrobe[].id` | string | required, ≤ 64 chars |
| `wardrobe[].category` | string | required, one of the five (any case) |
| `wardrobe[].attributes` | superset object or null | |
| `style` | string or null | |
| `note` | string or null | ≤ 1000 chars used |
| `recentlyWorn` | object `{itemId: "yyyy-MM-dd"}` or null | |

What happens:
1. Validate. Check the daily generation cap (429). Check balance ≥ `cost_outfit_suggestion` (402).
2. If the wardrobe has no top, or no bottom (a dress, gown, jumpsuit, playsuit, romper, boilersuit or overalls
   in tops counts as both), return `INSUFFICIENT_WARDROBE` with `attempt: 0`. No model call. Shoes are optional.
3. Otherwise charge `cost_outfit_suggestion` (ref = session id), then ask the model. Return `SUCCESS`
   (attempt 1) or `LOW_CONFIDENCE` (attempt 1). If the model call fails (502/504), the charge is refunded.

Optional header `Idempotency-Key` (backward compatible: without it, every call is a new session and a new fee).
Same value rules and behaviour as routes 15 and 16 (see "Optional `Idempotency-Key` header" above): the key is
the fee's ledger ref, so one key pays at most once. A retry with the same key while the first is running, or
within 1 hour after it succeeded, gets the **same session** back (same `sessionId`, no new charge, no model
call). After the first one failed (refunded) or its result is gone (1 hour, or a server restart), 409
`IDEMPOTENCY_KEY_USED`; send a new key. A start that ended in 402, 429 or `INSUFFICIENT_WARDROBE` charged
nothing, so retrying it with the same key simply runs again. Make a new key per tap; reuse it only for a retry.
A start can take a while (up to 4 tries plus one re-ask, 60 s each), so with a key a retry after an app-side
timeout is safe.

- Response 200 `SuggestionResponse`, header `X-Credits-Balance` (balance after the session fee; for
  `INSUFFICIENT_WARDROBE`, nothing charged).
- Errors: 400 `VALIDATION` (`"context must be one of DAILY, PLANNER, WARDROBE"`, `"wardrobe is required"`,
  `"wardrobe[].id is required"`, `"wardrobe[].category must be one of ..."`, `"date must be yyyy-MM-dd"`,
  `"Idempotency-Key must be ..."`), 402 `INSUFFICIENT_CREDITS`, 409 `IDEMPOTENCY_KEY_USED`, 429 `LIMIT_REACHED`,
  502 `UPSTREAM_FAILED`, 504 `UPSTREAM_TIMEOUT`.
- App note: the KMP `WardrobeEntry.attributes` is non-null; the server also accepts null.

#### 18. POST `/outfit/suggest/next`

Rejects the current suggestion (if there is one and it is not yet accepted or rejected) and asks for another.

Request:

```json
{"sessionId": "sess_...", "rejection": "TOO_FORMAL", "feedback": "more relaxed"}
```

| Field | Type | Rule |
|---|---|---|
| `sessionId` | string | required |
| `rejection` | `NOT_MY_STYLE` \| `WRONG_VIBE` \| `TOO_FORMAL` \| `TOO_CASUAL` \| `JUST_BROWSING` or null | any case |
| `feedback` | string or null | ≤ 1000 chars used |

- If `attempt + 1` would pass `max_attempts_per_session` (10), the server records the rejection and returns
  `EXHAUSTED` with the current `attempt`. No model call.
- Otherwise returns `SUCCESS` or `LOW_CONFIDENCE` with `attempt + 1`.
- Response 200 `SuggestionResponse`, header `X-Credits-Balance`.
- Errors: 400 `VALIDATION` (`"sessionId is required"`, bad `rejection`), 404 `NOT_FOUND` (session missing or
  not the caller's), 409 `SESSION_CLOSED` (session completed, or it was an `INSUFFICIENT_WARDROBE` session with
  nothing to continue), 429 `LIMIT_REACHED` (checked before `EXHAUSTED`), 502, 504.

#### 19. POST `/outfit/suggest/refine`

Asks for a changed version. Also used for "Show me anyway" after `LOW_CONFIDENCE`: then the model is told it
must return an outfit.

Request:

```json
{
  "sessionId": "sess_...",
  "modifications": {"tooWarm": true, "tooCold": false, "tooCasual": false, "tooFormal": false, "differentTop": false, "differentBottom": true},
  "feedback": null
}
```

`modifications` may be null or partial; missing flags are `false`. `feedback` ≤ 1000 chars used.

- A refine resets the attempt count: the response has `attempt: 1`.
- Response 200 `SuggestionResponse`, header `X-Credits-Balance`.
- Errors: as next (400, 404, 409, 429, 502, 504).

#### 20. POST `/outfit/suggest/accept`

Request:

```json
{"sessionId": "sess_...", "suggestionId": "8bf9a0b0-4ff2-419f-af22-2143228948d1"}
```

Both fields required.

- Charges nothing (the session fee was taken at start). Marks the suggestion accepted and the session
  `COMPLETED`.
- Accepting the **same** suggestion again is safe: 200, nothing changes.
- Response 200, header `X-Credits-Balance`:

```json
{"charged": false, "balance": 1}
```

- `charged` is **always `false`** now. The field stays so the shape does not change; the app can ignore it.
- Errors: 400 `VALIDATION` (missing field), 404 `NOT_FOUND` (session missing or not the caller's; or
  `"Suggestion not found in this session"`), 409 `SESSION_CLOSED` (session already completed with a
  **different** suggestion).
- App note: the server does not create the outfit. The app saves it locally and pushes it with the mirror.
  `MeteredAction.OUTFIT_SUGGESTION` is now spent at start (once per session), not here.

---

## Background removal

### 21. POST `/images/remove-background`

**App client needed.** Sends the photo to rembg and returns a transparent cut-out.

- Request: multipart `photo` (see the photo rules in [AI gateway](#ai-gateway)).
- Cost: free. Daily cap `remove_bg_daily_cap` (300) per user per UTC day → 429 `LIMIT_REACHED`,
  `"limit": "remove_bg"`. In-memory counter, resets on restart.
- Response 200: raw PNG bytes, `Content-Type: image/png`. Longest side up to 600 px (rembg `MAX_SIDE`).
  No credits header.
- Errors: photo errors, 429, 502 `UPSTREAM_FAILED`, 504 `UPSTREAM_TIMEOUT`.
- Stores nothing. To keep the cut-out, upload it with route 10 (`kind=cutout`).

---

## Ledger

### 22. GET `/ledger`

**App client needed** (a server-backed `UsageMeter`). The caller's credits.

A read also writes the weekly grant if this is the first read or spend of the week, and the premium top-up if
the user became premium mid-week.

Response 200, header `X-Credits-Balance`:

```json
{
  "balance": 7,
  "weeklyAllowance": 7,
  "weeklyRemaining": 7,
  "purchasedRemaining": 0,
  "premium": false,
  "weekStart": "2026-10-05",
  "resetsAt": 1791759600000,
  "costs": {"OUTFIT_SUGGESTION": 1, "AI_IMAGE": 2, "OUTFIT_DESCRIPTION": 1},
  "freeItemLimit": 100
}
```

| Field | Type | Meaning |
|---|---|---|
| `balance` | int | `weeklyRemaining + purchasedRemaining` |
| `weeklyAllowance` | int | credits granted this week (grant + any premium top-up) |
| `weeklyRemaining` | int | weekly credits left (never below 0) |
| `purchasedRemaining` | int | pack credits left; they do not expire |
| `premium` | bool | from RevenueCat (cached up to 15 min; a webhook clears the cache) |
| `weekStart` | `yyyy-MM-dd` | Monday of this week, in the profile's timezone (UTC if unset) |
| `resetsAt` | ms | next Monday 00:00 in that timezone |
| `costs` | object | keys `OUTFIT_SUGGESTION`, `AI_IMAGE`, `OUTFIT_DESCRIPTION` → int |
| `freeItemLimit` | int | wardrobe size limit for free users (the device enforces it) |

Spending takes weekly credits first, then purchased ones.

---

## RevenueCat webhook

### 23. POST `/webhooks/revenuecat`

Called by RevenueCat, not by the app.

- Auth: no Firebase token. The `Authorization` header must equal `RC_WEBHOOK_AUTH` exactly (the whole header
  value, no `Bearer` prefix added by the server). If `RC_WEBHOOK_AUTH` is not set, every call is refused.
- Request: the RevenueCat webhook JSON, e.g.

```json
{"api_version": "1.0", "event": {"id": "doc-evt-1", "type": "TEST", "app_user_id": "api-doc-test"}}
```

The server reads `event.id` (required), `event.type`, `event.app_user_id` (else `original_app_user_id`),
`event.product_id`, `event.transaction_id`, `event.environment`, `event.cancel_reason`,
`event.transferred_from`, `event.transferred_to`. It stores the whole body.

- Response 200: `{}`

What it does:
1. Store the event. If this `event.id` was already **processed**, stop (200). If it was stored but an earlier try
   failed half-way, process it again (ledger refs make that safe).
2. Act on `event.type`:

| `event.type` | Action |
|---|---|
| `TEST` | nothing |
| `TRANSFER` | clear the entitlement cache for every id in `transferred_from` and `transferred_to` |
| user id blank, `$RCAnonymousID:...`, or no profile row | nothing (outcome `ignored`) |
| `INITIAL_PURCHASE`, `RENEWAL`, `UNCANCELLATION`, `PRODUCT_CHANGE`, `SUBSCRIPTION_EXTENDED`, `TEMPORARY_ENTITLEMENT_GRANT`, `EXPIRATION`, `BILLING_ISSUE`, `SUBSCRIPTION_PAUSED` | clear the entitlement cache |
| `NON_RENEWING_PURCHASE` with `product_id` in the `packs` config and a `transaction_id` | add the pack's credits to PURCHASED (once per `transaction_id`) |
| `CANCELLATION` with `cancel_reason = CUSTOMER_SUPPORT` on a pack | take the pack's credits back from PURCHASED, never below 0 |
| `CANCELLATION` (other) | clear the cache only |
| anything else | clear the cache, outcome `ignored` |

Errors:

| Status | Code | When |
|---|---|---|
| 401 | `UNAUTHENTICATED` | `Authorization` missing or wrong (`"Bad webhook authorization"`) |
| 400 | `VALIDATION` | no `event` object, or no `event.id` |
| 500 | `INTERNAL` | a database write failed, or processing failed. RevenueCat will retry. |

---

## Push tokens

Routes 24–26. These match the app's `HttpPush` (`backend/http/HttpPush.kt`).

### 24. POST `/user/notification` and 25. PUT `/user/notification`

Both do the same thing: upsert on `fcmToken`. If the token is held by another user (a phone that changed
account), it moves to the caller.

Request:

```json
{"userId": "ignored", "fcmToken": "doc-fcm-1", "platform": "ios"}
```

| Field | Type | Rule |
|---|---|---|
| `userId` | string or null | ignored |
| `fcmToken` | string | required, trimmed, 1–4096 chars |
| `platform` | `"ios"` \| `"android"` | required, any case; stored lower-case |

Response 200:

```json
{"id": "5940e960-5cc7-47f8-bb2b-621b4e6d0557", "fcmToken": "doc-fcm-1", "platform": "ios"}
```

`id` is the row id (UUID string). It stays the same when the same token is sent again.

Errors: 400 `VALIDATION` (`"fcmToken is required"`, `"platform must be ios or android"`).

### 26. DELETE `/user/notification`

**App client needed.** Call it on sign-out.

- Request: `{"fcmToken": "doc-fcm-1"}`. The body is optional.
- Deletes the token only if it belongs to the caller.
- Response: **204** with no body, always (even if nothing was deleted, or no body was sent).

---

## Weather

### 27. GET `/weather/forecast`

A WeatherAPI.com forecast, returned in the **same shape as Open-Meteo**, so the app's
`WeatherRepository.parse` works unchanged. App change: call this URL with the auth header instead of
`api.open-meteo.com`.

| Query | Type | Rule |
|---|---|---|
| `latitude` | number | required, -90 to 90 |
| `longitude` | number | required, -180 to 180 |
| `days` | int | optional; clamped to 1..`WEATHER_MAX_DAYS` (14). Default 16, so in practice 14. |

Response 200 (`?latitude=51.5&longitude=-0.12&days=2`, dev stub):

```json
{
  "current": {"temperature_2m": 9.4, "weather_code": 61},
  "daily": {
    "time": ["2026-10-11", "2026-10-12"],
    "weather_code": [61, 71],
    "temperature_2m_max": [12.4, 13.4],
    "temperature_2m_min": [5.4, 6.4],
    "precipitation_probability_max": [80, 80]
  }
}
```

| Field | Type |
|---|---|
| `current` | object or null |
| `current.temperature_2m` | number (°C) or null |
| `current.weather_code` | int (WMO code) or null |
| `daily.time` | string[] (`yyyy-MM-dd`) |
| `daily.weather_code` | int[] (WMO: 0, 2, 3, 45, 61, 71 or 95) |
| `daily.temperature_2m_max` / `_min` | number[] (°C) |
| `daily.precipitation_probability_max` | int[] (0–100) |

The `daily` lists are parallel: one entry per day.
Answers are cached 30 min by lat/lon rounded to 2 decimals plus days.

Errors: 400 `VALIDATION` (`"latitude and longitude are required"`, `"latitude must be between -90 and 90"`,
`"longitude must be between -180 and 180"`, or a value that is not a number), 502 `UPSTREAM_FAILED`,
504 `UPSTREAM_TIMEOUT`.

---

## Account deletion

### 28. DELETE `/account`

**App client needed.** Deletes everything the server holds for the caller:
all stored images under `users/{uid}/`, every mirror row, AI sessions and suggestions, ledger rows,
push tokens, the profile row, and RevenueCat event rows for this uid.

- Request: no body.
- Response: **204**, no body. Idempotent: 204 even if there was nothing to delete.
- The server does not delete the Firebase user or the RevenueCat customer.
- Call it **before** deleting the Firebase account, because it needs a valid token.
- If the same token is used again afterwards, the server makes a fresh empty stub profile. That is expected.

---

## Health

### 29. GET `/health`

- Auth: none.
- Response 200: `{"status":"UP"}` when the database answers. 503 `{"status":"DOWN"}` when it does not.
- `Content-Type` is `application/vnd.spring-boot.actuator.v3+json` (Spring Boot Actuator), not plain
  `application/json`. Parse it as JSON anyway.
- Not logged per request.

---

## Dev-only routes

**Not available in prod.** Do not build app clients for these.

| Route | Exists when | Auth | What it does |
|---|---|---|---|
| GET `/dev-files/{bucket}/{key...}` | `STORAGE_MODE=local` (dev default) | none | Serves files from local storage, so dev URLs work like the prod presigned and public URLs. `bucket` = `user` or `library`. 200 with the file's content type (e.g. `image/webp`), 404 `NOT_FOUND` if missing. |
| POST `/dev/jobs/push-check?at={ms}` | profile is not `prod` | signed in | Runs the push-notification job now. `at` (optional) pretends the clock reads that time. Returns `{"candidates":1,"habitSent":0,"weeklySent":0,"deadTokensRemoved":0,"failed":0}`. |
| POST `/dev/jobs/session-sweep?at={ms}` | profile is not `prod` | signed in | Marks sessions idle for 24 h as `ABANDONED`. Returns `{"abandoned":0}`. |

In dev, the image URLs in pull, upload and Library responses point at `/dev-files/...` on the dev server
(`STORAGE_LOCAL_BASE_URL`, default `http://localhost:{PORT}`). Also in dev: Gemini, rembg, WeatherAPI,
RevenueCat and FCM are stubs. Premium users can be faked with `RC_DEV_PREMIUM_UIDS`.

---

## Differences from the brief

The code is the source of truth. These are the places where it differs from, or adds to,
`WTW_SERVER_BRIEF.md` section 4.

1. **Repeat accept is allowed.** Brief: accept gives 409 `SESSION_CLOSED` if the session is COMPLETED.
   Code: accepting the **same** suggestion again gives 200 `{"charged": false}`. Only a **different**
   suggestion on a completed session gives 409.
2. **`INSUFFICIENT_WARDROBE` sessions cannot be continued.** next / refine on such a session give
   409 `SESSION_CLOSED` (`"This session has no history to continue; start a new one"`). Not in the brief.
3. **Refine resets `attempt` to 1.** Not in the brief.
4. **Flat fee per session.** Start charges `cost_outfit_suggestion` once per session (refunded if the first
   model turn fails). Accept, next and refine charge nothing and never give 402.
5. **The generation cap is checked before `EXHAUSTED`.** At the daily cap, next gives 429 even when it would
   have returned `EXHAUSTED`.
6. **Webhook de-duplication.** Brief: if `event.id` already exists, return 200 and stop. Code: stops only if the
   event was already **processed**. An event stored but not processed (an earlier try failed) is processed
   again.
7. **Webhook 400s.** A body with no `event` or no `event.id` gives 400 `VALIDATION`. Not in the brief.
8. **Webhook auth details.** The whole `Authorization` value must equal `RC_WEBHOOK_AUTH`. If the secret is
   unset, every call gets 401. The 401 code is `UNAUTHENTICATED`.
9. **`X-Credits-Balance` only on 200.** Brief: "every metered route returns" it. Code: only success responses
   carry it; a 402 carries `balance` in the body.
10. **405 and 404 for routes.** A wrong method gives 405 with code `VALIDATION`. An unknown path gives 404
    `NOT_FOUND`. Neither is in the brief's error table.
11. **Wrong content type gives 400, not 415.** E.g. `image/gif` on route 10, or JSON on a multipart route.
12. **Weather adds range checks and a real default of 14 days.** Latitude and longitude out of range give 400.
    The brief's default of 16 days is clamped to `WEATHER_MAX_DAYS` (14). `current` may be null.
13. **`/health` content type** is `application/vnd.spring-boot.actuator.v3+json`.
14. **Mirror pull rejects a negative or non-numeric `since`** with 400. Not in the brief.
15. **Mirror push details the brief does not list:** an equal `updatedAt` overwrites (only a strictly newer
    server copy is `stale`); a `null` row or a row with no id is rejected `INVALID` with `id: ""`; duplicate
    piece or packing item ids in one row are `INVALID`; a packing list with no `name` is `INVALID`;
    `MISSING_OUTFIT` also covers a tombstoned outfit or another user's outfit; a planned tombstone needs no
    outfit; `currency` is stored upper-case; time values must be 0..year 9999. A live item row resets every
    field you leave out to its default.
16. **`/ai/attributes` fills defaults.** The ten app keys are never null (defaults listed in route 14); off-list
    values are dropped; `filter` defaults to the safe "fail" answer. The brief does not say this.
17. **`/ai/generate-image` output is at most 600 px.** Not in the brief.
18. **Free caps are per UTC day and in memory.** `attributes` and `remove_bg` caps reset at UTC midnight and on
    a server restart. The generation cap uses the profile timezone and counts stored rows.
19. **Describe-outfit limits.** `items` must have 1–20 entries; `outfitId` is accepted but not used;
    `items[].id` is not checked.
20. **Suggest start limits.** `wardrobe` may have at most 2000 entries; `note` and `feedback` are cut to 1000
    chars; a dress-like top counts as top and bottom for the wardrobe check.
21. **Push token rules.** `platform` is accepted in any case and returned lower-case; `fcmToken` is at most 4096
    chars; the DELETE body is optional.
22. **PATCH name and PATCH style clear the field** when it is sent as null or left out. The brief does not say.
23. **Dev auth.** Besides `X-Dev-User-Id`, `Bearer dev-token` signs in as `dev-user` outside prod. A bearer
    header always wins over `X-Dev-User-Id`.
24. **`/dev-files/**` is public in dev.** It is listed in `SecurityConfig` but not in brief section 4.
