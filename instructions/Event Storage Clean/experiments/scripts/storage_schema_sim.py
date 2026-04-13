#!/usr/bin/env python3
"""
storage_schema_sim.py — Schema-aware synthetic StorageRecord generator.

Simulates the core model from the real storage library:
  StorageAttribute<T>, StorageRecord, UserActivitySchema, SchemaStorageAttribute.

Also simulates the observable effects of appendData:
  - timestamp-ordered insertion
  - skip-duplicates
  - max-capacity truncation (drop oldest)

NOTE: ASSUMPTIONS (real prod schema is NDA-protected):
  - Attribute names/types are modeled from the task specification and
    observable behaviour; exact prod names/counts may differ.
  - UserActivityType→RecordType mapping table is synthetic.
  - appendData binary byteOffset copy is NOT reproduced; only the
    three observable effects above are simulated.

Usage:
    from storage_schema_sim import SchemaAwareRecordGenerator

    gen = SchemaAwareRecordGenerator(seed=42)
    batch = gen.make_api_batch(2000, sort_by_ts=True, skip_duplicates=False)
    # → list[dict] ready for POST /api/records/save-batch
"""
from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any


# ---------------------------------------------------------------------------
# AttrType — mirrors generic type parameter T of StorageAttribute<T>
# ---------------------------------------------------------------------------

class AttrType(Enum):
    LONG        = "Long"
    BOOLEAN     = "Boolean"
    STRING      = "String"
    ENUM_STRING = "EnumString"   # String restricted to a fixed value set


# ---------------------------------------------------------------------------
# StorageAttribute  (mirrors SchemaStorageAttribute<T>)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class StorageAttribute:
    """
    Immutable typed descriptor.  Mirrors StorageAttribute<T> / SchemaStorageAttribute<T>.

    name         — logical name (UPPER_SNAKE_CASE), used as schema key
    attr_type    — AttrType enum (mirrors .getType())
    enum_values  — allowed strings (only meaningful when attr_type=ENUM_STRING)
    """
    name:        str
    attr_type:   AttrType
    enum_values: tuple[str, ...] | None = None

    @property
    def ch_key(self) -> str:
        """ClickHouse attrs map key: lowercase_snake_case of logical name."""
        return self.name.lower()

    def validate(self, value: Any) -> None:
        """Mirrors attribute.getType() validation in setValue."""
        if self.attr_type is AttrType.LONG:
            if not isinstance(value, int) or isinstance(value, bool):
                raise TypeError(f"{self.name}: expected int, got {type(value).__name__}")
        elif self.attr_type is AttrType.BOOLEAN:
            if not isinstance(value, bool):
                raise TypeError(f"{self.name}: expected bool, got {type(value).__name__}")
        elif self.attr_type is AttrType.STRING:
            if not isinstance(value, str):
                raise TypeError(f"{self.name}: expected str, got {type(value).__name__}")
        elif self.attr_type is AttrType.ENUM_STRING:
            if value not in self.enum_values:  # type: ignore[operator]
                raise ValueError(f"{self.name}: '{value}' not in {self.enum_values}")

    def to_string(self, value: Any) -> str:
        """Serialize to Map(String, String) as ClickHouse stores attrs."""
        if self.attr_type is AttrType.BOOLEAN:
            return "1" if value else "0"
        return str(value)


# ---------------------------------------------------------------------------
# Attribute catalog — 50 attributes
# NOTE: ASSUMPTION — exact prod attribute set inferred from task spec.
# ---------------------------------------------------------------------------

_A = StorageAttribute   # brevity alias

# ── Required (always set) ──────────────────────────────────────────────────

_ACTIVITY_TYPE_VALUES: tuple[str, ...] = (
    "REGISTRATION", "LOGIN", "LOGOUT",
    "MESSAGE_SENT", "MESSAGE_DELETED", "MESSAGE_EDITED", "MESSAGE_READ",
    "REACTION_ADDED", "REACTION_REMOVED",
    "MODERATION_FLAG", "MODERATION_APPROVE", "MODERATION_REJECT",
    "PURCHASE_INITIATED", "PURCHASE_COMPLETED", "PURCHASE_REFUNDED",
    "PROFILE_UPDATED", "AVATAR_CHANGED", "STATUS_CHANGED",
    "CHANNEL_CREATED", "CHANNEL_JOINED", "CHANNEL_LEFT", "CHANNEL_DELETED",
    "FILE_UPLOADED", "FILE_DOWNLOADED", "FILE_DELETED",
    "CALL_STARTED", "CALL_ENDED", "CALL_MISSED",
    "SEARCH_PERFORMED", "NOTIFICATION_RECEIVED",
)                                   # 30 UserActivityType values

ATTR_TYPE      = _A("TYPE",      AttrType.ENUM_STRING, _ACTIVITY_TYPE_VALUES)
ATTR_TIMESTAMP = _A("TIMESTAMP", AttrType.LONG)   # Unix epoch milliseconds
ATTR_USER_ID   = _A("USER_ID",   AttrType.LONG)

# ── Session / identity ─────────────────────────────────────────────────────

ATTR_SESSION_ID      = _A("SESSION_ID",       AttrType.STRING)
ATTR_USER_DEVICE_TYPE = _A("USER_DEVICE_TYPE", AttrType.ENUM_STRING, ("ANDROID", "IOS", "WEB", "DESKTOP"))
ATTR_PLATFORM_VER    = _A("PLATFORM_VERSION", AttrType.STRING)
ATTR_APP_VERSION     = _A("APP_VERSION",      AttrType.STRING)
ATTR_TERMINAL_TYPE   = _A("TERMINAL_TYPE",    AttrType.ENUM_STRING, ("ONEME", "BASIC", "PRO", "ENTERPRISE"))
ATTR_ORG_HASH        = _A("ORG_HASH",         AttrType.STRING)
ATTR_GENDER          = _A("GENDER",           AttrType.ENUM_STRING, ("0", "1", "2"))
ATTR_ACTIVE_STATUS   = _A("USER_ACTIVE_STATUS", AttrType.BOOLEAN)
ATTR_LANG            = _A("LANG",             AttrType.ENUM_STRING, ("ru", "en", "de", "fr", "zh", "es"))

# ── Message ────────────────────────────────────────────────────────────────

ATTR_CHAT_ID       = _A("CHAT_ID",          AttrType.LONG)
ATTR_MESSAGE_ID    = _A("MESSAGE_ID",       AttrType.LONG)
ATTR_MSG_LEN       = _A("MESSAGE_LENGTH",   AttrType.LONG)
ATTR_HAS_ATTACH    = _A("HAS_ATTACHMENTS",  AttrType.BOOLEAN)
ATTR_ATTACH_COUNT  = _A("ATTACHMENT_COUNT", AttrType.LONG)
ATTR_ATTACH_TYPE   = _A("ATTACHMENT_TYPE",  AttrType.ENUM_STRING,
                        ("IMAGE", "VIDEO", "AUDIO", "FILE", "STICKER"))
ATTR_IS_FORWARDED  = _A("IS_FORWARDED",     AttrType.BOOLEAN)
ATTR_IS_REPLY      = _A("IS_REPLY",         AttrType.BOOLEAN)
ATTR_REACTION_TYPE = _A("REACTION_TYPE",    AttrType.ENUM_STRING,
                        ("LIKE", "LOVE", "LAUGH", "SAD", "ANGRY", "WOW"))
ATTR_CHANNEL_ID    = _A("CHANNEL_ID",       AttrType.LONG)
ATTR_THREAD_ID     = _A("THREAD_ID",        AttrType.LONG)

# ── Moderation ─────────────────────────────────────────────────────────────

ATTR_MOD_SCORE    = _A("MODERATION_SCORE",    AttrType.LONG)
ATTR_MOD_CATEGORY = _A("MODERATION_CATEGORY", AttrType.ENUM_STRING,
                       ("SPAM", "HATE", "ADULT", "VIOLENCE", "OTHER"))
ATTR_MOD_ACTION   = _A("MODERATION_ACTION",   AttrType.ENUM_STRING,
                       ("FLAGGED", "APPROVED", "REJECTED", "DELETED"))
ATTR_REPORT_COUNT = _A("REPORT_COUNT",        AttrType.LONG)
ATTR_IS_AUTOMATED = _A("IS_AUTOMATED",        AttrType.BOOLEAN)

# ── Purchase ───────────────────────────────────────────────────────────────

ATTR_AMOUNT_CENTS    = _A("AMOUNT_CENTS",      AttrType.LONG)
ATTR_CURRENCY        = _A("CURRENCY",          AttrType.ENUM_STRING, ("USD", "EUR", "RUB", "GBP"))
ATTR_PRODUCT_ID      = _A("PRODUCT_ID",        AttrType.STRING)
ATTR_PAYMENT_METHOD  = _A("PAYMENT_METHOD",    AttrType.ENUM_STRING, ("CARD", "WALLET", "INVOICE"))
ATTR_IS_TRIAL        = _A("IS_TRIAL",          AttrType.BOOLEAN)
ATTR_SUB_TIER        = _A("SUBSCRIPTION_TIER", AttrType.ENUM_STRING,
                          ("FREE", "BASIC", "PRO", "ENTERPRISE"))

# ── Profile ────────────────────────────────────────────────────────────────

ATTR_EMAIL_VERIFIED = _A("EMAIL_VERIFIED", AttrType.BOOLEAN)
ATTR_PHONE_VERIFIED = _A("PHONE_VERIFIED", AttrType.BOOLEAN)
ATTR_AGE_GROUP      = _A("AGE_GROUP",      AttrType.ENUM_STRING,
                         ("18-24", "25-34", "35-44", "45-54", "55+"))
ATTR_COUNTRY_CODE   = _A("COUNTRY_CODE",   AttrType.STRING)
ATTR_TIMEZONE       = _A("TIMEZONE",       AttrType.STRING)

# ── Channel ────────────────────────────────────────────────────────────────

ATTR_MEMBER_COUNT = _A("MEMBER_COUNT",  AttrType.LONG)
ATTR_IS_PUBLIC    = _A("IS_PUBLIC",     AttrType.BOOLEAN)
ATTR_CHANNEL_TYPE = _A("CHANNEL_TYPE",  AttrType.ENUM_STRING,
                       ("GROUP", "CHANNEL", "DM", "BROADCAST"))

# ── File ───────────────────────────────────────────────────────────────────

ATTR_FILE_SIZE_KB = _A("FILE_SIZE_KB", AttrType.LONG)
ATTR_FILE_TYPE    = _A("FILE_TYPE",    AttrType.ENUM_STRING,
                       ("IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "ARCHIVE"))
ATTR_MIME_TYPE    = _A("MIME_TYPE",    AttrType.STRING)

# ── Call ───────────────────────────────────────────────────────────────────

ATTR_CALL_DURATION     = _A("CALL_DURATION_SEC",  AttrType.LONG)
ATTR_CALL_TYPE         = _A("CALL_TYPE",           AttrType.ENUM_STRING,
                             ("AUDIO", "VIDEO", "SCREEN_SHARE"))
ATTR_PARTICIPANT_COUNT = _A("PARTICIPANT_COUNT",   AttrType.LONG)

# ── Search / misc ──────────────────────────────────────────────────────────

ATTR_SOURCE       = _A("SOURCE",       AttrType.ENUM_STRING, ("web", "ios", "android"))
ATTR_QUERY_LENGTH = _A("QUERY_LENGTH", AttrType.LONG)

# ---------------------------------------------------------------------------
# Ordered catalog (used for iteration / docs)
# ---------------------------------------------------------------------------

ALL_ATTRIBUTES: tuple[StorageAttribute, ...] = (
    # required
    ATTR_TYPE, ATTR_TIMESTAMP, ATTR_USER_ID,
    # session/identity
    ATTR_SESSION_ID, ATTR_USER_DEVICE_TYPE, ATTR_PLATFORM_VER, ATTR_APP_VERSION,
    ATTR_TERMINAL_TYPE, ATTR_ORG_HASH, ATTR_GENDER, ATTR_ACTIVE_STATUS, ATTR_LANG,
    # message
    ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MSG_LEN, ATTR_HAS_ATTACH, ATTR_ATTACH_COUNT,
    ATTR_ATTACH_TYPE, ATTR_IS_FORWARDED, ATTR_IS_REPLY, ATTR_REACTION_TYPE,
    ATTR_CHANNEL_ID, ATTR_THREAD_ID,
    # moderation
    ATTR_MOD_SCORE, ATTR_MOD_CATEGORY, ATTR_MOD_ACTION, ATTR_REPORT_COUNT, ATTR_IS_AUTOMATED,
    # purchase
    ATTR_AMOUNT_CENTS, ATTR_CURRENCY, ATTR_PRODUCT_ID, ATTR_PAYMENT_METHOD,
    ATTR_IS_TRIAL, ATTR_SUB_TIER,
    # profile
    ATTR_EMAIL_VERIFIED, ATTR_PHONE_VERIFIED, ATTR_AGE_GROUP, ATTR_COUNTRY_CODE, ATTR_TIMEZONE,
    # channel
    ATTR_MEMBER_COUNT, ATTR_IS_PUBLIC, ATTR_CHANNEL_TYPE,
    # file
    ATTR_FILE_SIZE_KB, ATTR_FILE_TYPE, ATTR_MIME_TYPE,
    # call
    ATTR_CALL_DURATION, ATTR_CALL_TYPE, ATTR_PARTICIPANT_COUNT,
    # search/misc
    ATTR_SOURCE, ATTR_QUERY_LENGTH,
)
assert len(ALL_ATTRIBUTES) == 50, f"Catalog must have 50 attrs, got {len(ALL_ATTRIBUTES)}"

# Required attributes — always populated on every StorageRecord.
REQUIRED_ATTRS: frozenset[StorageAttribute] = frozenset({
    ATTR_TYPE, ATTR_TIMESTAMP, ATTR_USER_ID,
})


# ---------------------------------------------------------------------------
# UserActivityType → RecordType mapping
# Maps the 30 UserActivityType values to the 5 Java RecordType values.
# NOTE: ASSUMPTION — prod mapping may differ.
# ---------------------------------------------------------------------------

_ACTIVITY_TO_RECORD_TYPE: dict[str, str] = {
    "REGISTRATION":         "SYSTEM",
    "LOGIN":                "SYSTEM",
    "LOGOUT":               "SYSTEM",
    "MESSAGE_SENT":         "MESSAGE",
    "MESSAGE_DELETED":      "MESSAGE",
    "MESSAGE_EDITED":       "MESSAGE",
    "MESSAGE_READ":         "MESSAGE",
    "REACTION_ADDED":       "MESSAGE",
    "REACTION_REMOVED":     "MESSAGE",
    "MODERATION_FLAG":      "MODERATION",
    "MODERATION_APPROVE":   "MODERATION",
    "MODERATION_REJECT":    "MODERATION",
    "PURCHASE_INITIATED":   "PURCHASE",
    "PURCHASE_COMPLETED":   "PURCHASE",
    "PURCHASE_REFUNDED":    "PURCHASE",
    "PROFILE_UPDATED":      "SYSTEM",
    "AVATAR_CHANGED":       "SYSTEM",
    "STATUS_CHANGED":       "SYSTEM",
    "CHANNEL_CREATED":      "SYSTEM",
    "CHANNEL_JOINED":       "MESSAGE",
    "CHANNEL_LEFT":         "MESSAGE",
    "CHANNEL_DELETED":      "SYSTEM",
    "FILE_UPLOADED":        "MESSAGE",
    "FILE_DOWNLOADED":      "MESSAGE",
    "FILE_DELETED":         "MESSAGE",
    "CALL_STARTED":         "MESSAGE",
    "CALL_ENDED":           "MESSAGE",
    "CALL_MISSED":          "MESSAGE",
    "SEARCH_PERFORMED":     "OTHER",
    "NOTIFICATION_RECEIVED": "OTHER",
}
assert set(_ACTIVITY_TO_RECORD_TYPE.keys()) == set(_ACTIVITY_TYPE_VALUES)


# ---------------------------------------------------------------------------
# Per-UserActivityType attribute sets (extra, beyond required)
# Each type: 2–15 extra attributes.
# NOTE: ASSUMPTION — exact per-type sets are synthetic.
# ---------------------------------------------------------------------------

_TYPE_EXTRA: dict[str, tuple[StorageAttribute, ...]] = {
    "REGISTRATION": (
        ATTR_SESSION_ID, ATTR_USER_DEVICE_TYPE, ATTR_TERMINAL_TYPE, ATTR_ORG_HASH,
        ATTR_GENDER, ATTR_ACTIVE_STATUS, ATTR_EMAIL_VERIFIED, ATTR_PHONE_VERIFIED,
        ATTR_LANG, ATTR_COUNTRY_CODE, ATTR_TIMEZONE, ATTR_AGE_GROUP,
        ATTR_APP_VERSION, ATTR_SOURCE,
    ),                                              # 14 extra → total 17
    "LOGIN": (
        ATTR_SESSION_ID, ATTR_USER_DEVICE_TYPE, ATTR_TERMINAL_TYPE, ATTR_ORG_HASH,
        ATTR_ACTIVE_STATUS, ATTR_LANG, ATTR_COUNTRY_CODE, ATTR_SOURCE,
    ),                                              # 8 extra → total 11
    "LOGOUT": (
        ATTR_SESSION_ID, ATTR_USER_DEVICE_TYPE, ATTR_SOURCE,
    ),                                              # 3 extra → total 6
    "MESSAGE_SENT": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MSG_LEN, ATTR_HAS_ATTACH,
        ATTR_ATTACH_COUNT, ATTR_ATTACH_TYPE, ATTR_IS_FORWARDED, ATTR_IS_REPLY,
        ATTR_LANG, ATTR_CHANNEL_ID, ATTR_THREAD_ID, ATTR_SOURCE,
    ),                                              # 12 extra → total 15
    "MESSAGE_DELETED": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_CHANNEL_ID,
    ),                                              # 3
    "MESSAGE_EDITED": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MSG_LEN, ATTR_CHANNEL_ID,
    ),                                              # 4
    "MESSAGE_READ": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_CHANNEL_ID, ATTR_THREAD_ID,
    ),                                              # 4
    "REACTION_ADDED": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_REACTION_TYPE, ATTR_CHANNEL_ID,
    ),                                              # 4
    "REACTION_REMOVED": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_REACTION_TYPE,
    ),                                              # 3
    "MODERATION_FLAG": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MOD_SCORE, ATTR_MOD_CATEGORY,
        ATTR_MOD_ACTION, ATTR_REPORT_COUNT, ATTR_IS_AUTOMATED, ATTR_CHANNEL_ID,
    ),                                              # 8
    "MODERATION_APPROVE": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MOD_ACTION, ATTR_IS_AUTOMATED,
    ),                                              # 4
    "MODERATION_REJECT": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_MOD_CATEGORY, ATTR_MOD_ACTION,
        ATTR_REPORT_COUNT, ATTR_IS_AUTOMATED,
    ),                                              # 6
    "PURCHASE_INITIATED": (
        ATTR_AMOUNT_CENTS, ATTR_CURRENCY, ATTR_PRODUCT_ID, ATTR_PAYMENT_METHOD,
        ATTR_IS_TRIAL, ATTR_SUB_TIER, ATTR_SOURCE,
    ),                                              # 7
    "PURCHASE_COMPLETED": (
        ATTR_AMOUNT_CENTS, ATTR_CURRENCY, ATTR_PRODUCT_ID, ATTR_PAYMENT_METHOD,
        ATTR_IS_TRIAL, ATTR_SUB_TIER, ATTR_SOURCE,
    ),                                              # 7
    "PURCHASE_REFUNDED": (
        ATTR_AMOUNT_CENTS, ATTR_CURRENCY, ATTR_PRODUCT_ID,
    ),                                              # 3
    "PROFILE_UPDATED": (
        ATTR_EMAIL_VERIFIED, ATTR_PHONE_VERIFIED, ATTR_LANG,
        ATTR_COUNTRY_CODE, ATTR_TIMEZONE, ATTR_AGE_GROUP,
    ),                                              # 6
    "AVATAR_CHANGED": (
        ATTR_FILE_SIZE_KB, ATTR_MIME_TYPE,
    ),                                              # 2
    "STATUS_CHANGED": (
        ATTR_ACTIVE_STATUS, ATTR_LANG,
    ),                                              # 2
    "CHANNEL_CREATED": (
        ATTR_CHANNEL_ID, ATTR_MEMBER_COUNT, ATTR_IS_PUBLIC, ATTR_CHANNEL_TYPE, ATTR_LANG,
    ),                                              # 5
    "CHANNEL_JOINED": (
        ATTR_CHANNEL_ID, ATTR_MEMBER_COUNT, ATTR_IS_PUBLIC, ATTR_CHANNEL_TYPE,
    ),                                              # 4
    "CHANNEL_LEFT": (
        ATTR_CHANNEL_ID, ATTR_MEMBER_COUNT,
    ),                                              # 2
    "CHANNEL_DELETED": (
        ATTR_CHANNEL_ID, ATTR_MEMBER_COUNT, ATTR_IS_PUBLIC,
    ),                                              # 3
    "FILE_UPLOADED": (
        ATTR_CHAT_ID, ATTR_MESSAGE_ID, ATTR_FILE_SIZE_KB, ATTR_FILE_TYPE,
        ATTR_MIME_TYPE, ATTR_CHANNEL_ID,
    ),                                              # 6
    "FILE_DOWNLOADED": (
        ATTR_FILE_SIZE_KB, ATTR_FILE_TYPE, ATTR_MIME_TYPE,
    ),                                              # 3
    "FILE_DELETED": (
        ATTR_FILE_SIZE_KB, ATTR_FILE_TYPE,
    ),                                              # 2
    "CALL_STARTED": (
        ATTR_CHAT_ID, ATTR_CALL_TYPE, ATTR_PARTICIPANT_COUNT, ATTR_CHANNEL_ID,
    ),                                              # 4
    "CALL_ENDED": (
        ATTR_CHAT_ID, ATTR_CALL_TYPE, ATTR_CALL_DURATION, ATTR_PARTICIPANT_COUNT,
    ),                                              # 4
    "CALL_MISSED": (
        ATTR_CHAT_ID, ATTR_CALL_TYPE,
    ),                                              # 2
    "SEARCH_PERFORMED": (
        ATTR_QUERY_LENGTH, ATTR_LANG, ATTR_SOURCE,
    ),                                              # 3
    "NOTIFICATION_RECEIVED": (
        ATTR_SOURCE, ATTR_LANG,
    ),                                              # 2
}
assert len(_TYPE_EXTRA) == 30, f"Expected 30 UserActivityType entries, got {len(_TYPE_EXTRA)}"
assert set(_TYPE_EXTRA.keys()) == set(_ACTIVITY_TYPE_VALUES), "Type key mismatch"
for _t, _attrs in _TYPE_EXTRA.items():
    assert 2 <= len(_attrs) <= 15, (
        f"{_t}: extra attr count {len(_attrs)} outside [2, 15]"
    )


# ---------------------------------------------------------------------------
# StorageRecord  (mirrors StorageRecord with Map<StorageAttribute, Object>)
# ---------------------------------------------------------------------------

class StorageRecord:
    """
    Python equivalent of StorageRecord.
    Internally: Map<StorageAttribute, Object> with type validation on set.
    """
    __slots__ = ("_data",)

    def __init__(self) -> None:
        self._data: dict[StorageAttribute, Any] = {}

    # mirrors setAttribute(attribute, value)
    def set_attribute(self, attr: StorageAttribute, value: Any) -> "StorageRecord":
        attr.validate(value)
        self._data[attr] = value
        return self

    # mirrors getAttribute(attribute)
    def get_attribute(self, attr: StorageAttribute) -> Any:
        return self._data.get(attr)

    # mirrors getAttributes()
    def get_attributes(self) -> list[tuple[StorageAttribute, Any]]:
        return list(self._data.items())

    def to_dict(self) -> dict[str, Any]:
        """Raw representation: attr.name → value."""
        return {a.name: v for a, v in self._data.items()}

    def to_api_dict(self) -> dict:
        """
        Convert to the JSON shape expected by POST /api/records/save-batch.
        CHAT_ID and MESSAGE_ID are promoted to first-class fields.
        All other non-identity attrs go into 'attrs' as Map(String, String).
        """
        ts_ms   = self._data.get(ATTR_TIMESTAMP, int(time.time() * 1000))
        act_type = self._data.get(ATTR_TYPE, "OTHER")
        record_type = _ACTIVITY_TO_RECORD_TYPE.get(str(act_type), "OTHER")

        event_time = time.strftime(
            "%Y-%m-%dT%H:%M:%SZ", time.gmtime(int(ts_ms) / 1000)
        )
        user_id    = self._data.get(ATTR_USER_ID,    0)
        chat_id    = self._data.get(ATTR_CHAT_ID,    0)
        message_id = self._data.get(ATTR_MESSAGE_ID, 0)

        # Everything except the four fields that become first-class API fields
        _first_class = {ATTR_TIMESTAMP, ATTR_USER_ID, ATTR_CHAT_ID, ATTR_MESSAGE_ID}
        attrs: dict[str, str] = {
            attr.ch_key: attr.to_string(val)
            for attr, val in self._data.items()
            if attr not in _first_class
        }

        return {
            "eventTime":  event_time,
            "recordType": record_type,
            "userId":     user_id,
            "chatId":     chat_id,
            "messageId":  message_id,
            "attrs":      attrs,
        }


# ---------------------------------------------------------------------------
# Value samplers (one per attribute, deterministic via caller-supplied RNG)
# ---------------------------------------------------------------------------

# Shared pool for org_hash values (4 are used in the heavy-query SQL)
_ORG_HASH_POOL: tuple[str, ...] = (
    "-7207924629501764854",
    "-4231934642844376041",
    "1136490941434531770",
    "4369003663347448391",
    "9876543210123456789",
    "1111222233334444555",
)

_COUNTRY_CODES: tuple[str, ...] = ("RU", "US", "DE", "FR", "CN", "BR", "GB", "JP", "IN")
_TIMEZONES: tuple[str, ...]     = ("Europe/Moscow", "UTC", "America/New_York",
                                   "Asia/Shanghai", "Europe/Berlin", "Asia/Tokyo")
_MIME_TYPES: tuple[str, ...]    = ("image/jpeg", "image/png", "video/mp4",
                                   "application/pdf", "audio/ogg")


def _sample_value(attr: StorageAttribute, rng: random.Random) -> Any:
    """Return a valid random value for attr using rng."""
    t = attr.attr_type

    if t is AttrType.ENUM_STRING:
        return rng.choice(attr.enum_values)  # type: ignore[arg-type]

    if t is AttrType.BOOLEAN:
        return rng.choice((True, False))

    if t is AttrType.STRING:
        if attr is ATTR_SESSION_ID:
            return f"sess-{rng.randint(100_000, 999_999)}"
        if attr is ATTR_PLATFORM_VER:
            return f"{rng.randint(8, 15)}.{rng.randint(0, 9)}"
        if attr is ATTR_APP_VERSION:
            return f"3.{rng.randint(0, 9)}.{rng.randint(0, 20)}"
        if attr is ATTR_ORG_HASH:
            return rng.choice(_ORG_HASH_POOL)
        if attr is ATTR_PRODUCT_ID:
            return f"prod-{rng.randint(1, 200)}"
        if attr is ATTR_COUNTRY_CODE:
            return rng.choice(_COUNTRY_CODES)
        if attr is ATTR_TIMEZONE:
            return rng.choice(_TIMEZONES)
        if attr is ATTR_MIME_TYPE:
            return rng.choice(_MIME_TYPES)
        return f"val-{rng.randint(0, 9_999)}"

    # LONG — attribute-specific ranges
    if attr is ATTR_TIMESTAMP:
        return int(time.time() * 1000) - rng.randint(0, 86_400_000)
    if attr is ATTR_USER_ID:
        return rng.randint(1, 500_000)
    if attr is ATTR_CHAT_ID:
        return rng.randint(1, 100_000)
    if attr is ATTR_MESSAGE_ID:
        return rng.randint(1, 50_000_000)
    if attr is ATTR_CHANNEL_ID:
        return rng.randint(1, 10_000)
    if attr is ATTR_THREAD_ID:
        return rng.randint(1, 5_000_000)
    if attr is ATTR_MSG_LEN:
        return rng.randint(1, 4_096)
    if attr is ATTR_MOD_SCORE:
        return rng.randint(0, 100)
    if attr is ATTR_AMOUNT_CENTS:
        return rng.randint(100, 100_000)
    if attr is ATTR_MEMBER_COUNT:
        return rng.randint(2, 100_000)
    if attr is ATTR_FILE_SIZE_KB:
        return rng.randint(1, 102_400)
    if attr is ATTR_CALL_DURATION:
        return rng.randint(1, 7_200)
    if attr is ATTR_PARTICIPANT_COUNT:
        return rng.randint(2, 100)
    if attr is ATTR_ATTACH_COUNT:
        return rng.randint(1, 10)
    if attr is ATTR_REPORT_COUNT:
        return rng.randint(1, 500)
    if attr is ATTR_QUERY_LENGTH:
        return rng.randint(1, 200)
    return rng.randint(0, 10_000)


# ---------------------------------------------------------------------------
# SchemaAwareRecordGenerator
# ---------------------------------------------------------------------------

class SchemaAwareRecordGenerator:
    """
    Deterministic (by seed) generator of valid StorageRecord instances.

    Every generated record:
      - has all REQUIRED_ATTRS (TYPE, TIMESTAMP, USER_ID),
      - has exactly the attribute set defined for its UserActivityType,
      - passes StorageAttribute.validate() for every field.

    Parameters
    ----------
    seed : int | None
        Fixed seed for reproducible output.  None = non-deterministic.
    """

    def __init__(self, seed: int | None = 42) -> None:
        self._rng   = random.Random(seed)
        self._types = list(_ACTIVITY_TYPE_VALUES)

    def generate_record(self) -> StorageRecord:
        """Generate one valid StorageRecord."""
        act_type = self._rng.choice(self._types)
        rec = StorageRecord()

        rec.set_attribute(ATTR_TYPE,      act_type)
        rec.set_attribute(ATTR_TIMESTAMP, _sample_value(ATTR_TIMESTAMP, self._rng))
        rec.set_attribute(ATTR_USER_ID,   _sample_value(ATTR_USER_ID,   self._rng))

        for attr in _TYPE_EXTRA[act_type]:
            rec.set_attribute(attr, _sample_value(attr, self._rng))

        return rec

    def generate_batch(
        self,
        n: int,
        *,
        sort_by_ts: bool = True,
        skip_duplicates: bool = False,
        max_capacity_bytes: int | None = None,
    ) -> list[StorageRecord]:
        """
        Generate n records and apply appendData-like semantics:

        1. sort_by_ts=True   → sort ascending by TIMESTAMP (mirrors appendSorted /
                               timestamp ordering in appendData).
        2. skip_duplicates   → deduplicate by (USER_ID, TYPE, TIMESTAMP); keep first
                               occurrence (mirrors skipDuplicates flag).
        3. max_capacity_bytes → drop the oldest records until total serialized size
                               fits within the limit (mirrors maxCapacity truncation;
                               the implementation truncates from the front = oldest).

        NOTE: SIMPLIFICATION — the real implementation does binary byteOffset copy
        and can insert into the middle of an existing buffer.  Here we only reproduce
        the three observable effects on the final ordered record list.
        """
        records = [self.generate_record() for _ in range(n)]

        if sort_by_ts:
            records.sort(key=lambda r: r.get_attribute(ATTR_TIMESTAMP))  # type: ignore[return-value]

        if skip_duplicates:
            seen: set[tuple] = set()
            deduped: list[StorageRecord] = []
            for r in records:
                key = (
                    r.get_attribute(ATTR_USER_ID),
                    r.get_attribute(ATTR_TYPE),
                    r.get_attribute(ATTR_TIMESTAMP),
                )
                if key not in seen:
                    seen.add(key)
                    deduped.append(r)
            records = deduped

        if max_capacity_bytes is not None:
            # Keep newest (= tail); drop oldest (= head) until fits.
            total = 0
            kept: list[StorageRecord] = []
            for r in reversed(records):
                est = len(json.dumps(r.to_api_dict()).encode())
                if total + est <= max_capacity_bytes:
                    total += est
                    kept.append(r)
            records = list(reversed(kept))

        return records

    def make_api_batch(
        self,
        n: int,
        *,
        sort_by_ts: bool = True,
        skip_duplicates: bool = False,
        max_capacity_bytes: int | None = None,
    ) -> list[dict]:
        """
        Convenience wrapper: generate_batch → list[dict] for the Java API.
        Drop-in replacement for the old make_batch(n).
        """
        return [
            r.to_api_dict()
            for r in self.generate_batch(
                n,
                sort_by_ts=sort_by_ts,
                skip_duplicates=skip_duplicates,
                max_capacity_bytes=max_capacity_bytes,
            )
        ]


# ---------------------------------------------------------------------------
# Module-level helper (for use from other scripts without instantiating)
# ---------------------------------------------------------------------------

def make_schema_batch(n: int, seed: int | None = None) -> list[dict]:
    """
    Functional shorthand.  seed=None → non-deterministic (suitable for
    multi-threaded writers where each call should differ).
    """
    return SchemaAwareRecordGenerator(seed=seed).make_api_batch(n)


# ---------------------------------------------------------------------------
# Self-test / demo  (python3 storage_schema_sim.py)
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("=== storage_schema_sim self-test ===\n")
    gen = SchemaAwareRecordGenerator(seed=0)

    print(f"Attributes in catalog : {len(ALL_ATTRIBUTES)}")
    print(f"UserActivityType count: {len(_ACTIVITY_TYPE_VALUES)}")
    print()

    print("Sample records (JSON):")
    for _ in range(3):
        r = gen.generate_record()
        print(json.dumps(r.to_api_dict(), ensure_ascii=False, indent=2))
        print()

    print("Batch of 10 (sorted, no dedup, no capacity limit):")
    batch = gen.make_api_batch(10)
    for rec in batch:
        print(f"  {rec['eventTime']}  {rec['recordType']:12}  uid={rec['userId']}"
              f"  type={rec['attrs'].get('type', '?')}")

    print("\nappendData simulation: 20 records, capacity=4096 bytes, skip_duplicates=True")
    small = gen.generate_batch(
        20, sort_by_ts=True, skip_duplicates=True, max_capacity_bytes=4096
    )
    print(f"  kept {len(small)} / 20 records after capacity trim")
