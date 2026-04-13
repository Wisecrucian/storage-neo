# Schema Simulation (`storage_schema_sim.py`)

Documents the synthetic schema used in load-generation scripts.
Source: `experiments/scripts/storage_schema_sim.py`.

> **NOTE: ASSUMPTIONS** — the real prod schema is NDA-protected.
> Attribute names/types/counts are modeled from the task specification.
> Exact prod attribute names may differ.

---

## Synthetic Schema Structure

### StorageAttribute

```
StorageAttribute(name, attr_type, enum_values?)
  name        → logical name in UPPER_SNAKE_CASE
  attr_type   → AttrType: LONG | BOOLEAN | STRING | ENUM_STRING
  enum_values → fixed value set (only for ENUM_STRING)
  ch_key      → ClickHouse Map key = name.lower() (e.g. USER_DEVICE_TYPE → user_device_type)
```

`validate(value)` mirrors `attribute.getType()` check in `StorageRecord.setAttribute`.
`to_string(value)` serializes to `Map(String, String)` (booleans → `"1"` / `"0"`).

### Attribute Catalog (50 attributes)

| Group           | Count | Examples                                              |
|-----------------|------:|-------------------------------------------------------|
| Required        | 3     | TYPE, TIMESTAMP (Long ms), USER_ID                   |
| Session/identity| 9     | USER_DEVICE_TYPE, TERMINAL_TYPE, ORG_HASH, GENDER, USER_ACTIVE_STATUS, LANG |
| Message         | 11    | CHAT_ID, MESSAGE_ID, HAS_ATTACHMENTS, REACTION_TYPE  |
| Moderation      | 5     | MODERATION_SCORE, MODERATION_CATEGORY, IS_AUTOMATED  |
| Purchase        | 6     | AMOUNT_CENTS, CURRENCY, SUBSCRIPTION_TIER            |
| Profile         | 5     | EMAIL_VERIFIED, COUNTRY_CODE, TIMEZONE               |
| Channel         | 3     | MEMBER_COUNT, IS_PUBLIC, CHANNEL_TYPE                |
| File            | 3     | FILE_SIZE_KB, FILE_TYPE, MIME_TYPE                   |
| Call            | 3     | CALL_DURATION_SEC, CALL_TYPE, PARTICIPANT_COUNT       |
| Search/misc     | 2     | SOURCE (`web`/`ios`/`android`), QUERY_LENGTH         |

---

## UserActivityType Mappings (30 types)

Each `UserActivityType` has 2–15 extra attributes beyond the required three.

### UserActivityType → RecordType (Java API field)

The Java API only accepts 5 `RecordType` values.  Mapping:

| UserActivityType group  | → RecordType |
|-------------------------|-------------|
| REGISTRATION, LOGIN, LOGOUT, PROFILE_*, CHANNEL_CREATED, CHANNEL_DELETED, STATUS_CHANGED, AVATAR_CHANGED | SYSTEM |
| MESSAGE_*, REACTION_*, CHANNEL_JOINED/LEFT, FILE_*, CALL_* | MESSAGE |
| MODERATION_* | MODERATION |
| PURCHASE_* | PURCHASE |
| SEARCH_PERFORMED, NOTIFICATION_RECEIVED | OTHER |

The `type` attribute is also stored in `attrs['type']` so the original
`UserActivityType` name is always recoverable from ClickHouse queries.

### Key per-type attribute sets

| UserActivityType    | Extra attrs (examples)                                            |
|---------------------|-------------------------------------------------------------------|
| REGISTRATION        | USER_DEVICE_TYPE, TERMINAL_TYPE, ORG_HASH, GENDER, USER_ACTIVE_STATUS, EMAIL_VERIFIED, … |
| MESSAGE_SENT        | CHAT_ID, MESSAGE_ID, HAS_ATTACHMENTS, IS_FORWARDED, LANG, SOURCE |
| MODERATION_FLAG     | MODERATION_SCORE, MODERATION_CATEGORY, REPORT_COUNT, IS_AUTOMATED |
| PURCHASE_COMPLETED  | AMOUNT_CENTS, CURRENCY, PRODUCT_ID, SUBSCRIPTION_TIER            |
| CALL_ENDED          | CALL_DURATION_SEC, CALL_TYPE, PARTICIPANT_COUNT                   |

---

## Required Attributes

Every `StorageRecord` always contains:

| Attribute | Type | Notes |
|-----------|------|-------|
| `TYPE`    | ENUM_STRING | one of 30 `UserActivityType` values; also goes to `attrs['type']` |
| `TIMESTAMP` | LONG | Unix epoch milliseconds; maps to `eventTime` field |
| `USER_ID` | LONG | maps to `userId` field |

---

## appendData Semantics Simulation

`SchemaAwareRecordGenerator.generate_batch(n, ...)` implements three observable effects:

| Real `appendData` behaviour | Simulation |
|-----------------------------|------------|
| Sorts records by timestamp before insertion | `sort_by_ts=True` (default) — `records.sort(key=TIMESTAMP)` |
| `skipDuplicates` flag: drop records with same (user_id, type, ts) | `skip_duplicates=True` — set-based dedup |
| `maxCapacity` truncation: drop oldest records when buffer full | `max_capacity_bytes=N` — reversed scan, keep newest until size fits |
| Binary `byteOffset` copy into middle of buffer | **NOT simulated** — only the final ordered list shape is reproduced |

**Why not byte-level copy?** The experiment scripts work at HTTP-batch granularity;
the internal buffer mechanics (byteOffset, partial overwrite) are invisible at that
level.  The three simulated effects are the ones that affect what ultimately lands
in ClickHouse.

---

## Integration with Experiments

`experiments_phase2.py` — `make_batch(n)` now calls:
```python
_thread_gen().make_api_batch(n, sort_by_ts=True, skip_duplicates=False)
```
Each writer thread gets its own `SchemaAwareRecordGenerator(seed=None)` via
`threading.local()`.  `seed=None` → non-deterministic, so concurrent threads
produce independent data.

`experiment_heavy_query.py` — the heavy-query SQL now filters:
```sql
record_type = 'SYSTEM' AND attrs['type'] = 'REGISTRATION'
```
This matches records generated for `UserActivityType.REGISTRATION` (~1/30 of
all records), which all carry `terminal_type`, `org_hash`, `gender`,
`user_active_status`, `user_device_type` — exactly the keys the SQL filters on.

---

## Seed Behaviour

| Context | Seed | Behaviour |
|---------|------|-----------|
| `SchemaAwareRecordGenerator(seed=42)` | 42 | Fully deterministic; same sequence every run |
| `SchemaAwareRecordGenerator(seed=None)` | random | Different sequence every instantiation |
| `make_schema_batch(n)` | None | Non-deterministic; suitable for load |
| `make_schema_batch(n, seed=42)` | 42 | Deterministic; suitable for unit testing |
