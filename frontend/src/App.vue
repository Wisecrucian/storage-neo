<template>
  <div class="layout">
    <aside v-if="activePage === 'getRecords'" class="sidebar">
      <h3>Search</h3>

      <div class="card">
        <h4>UserActivityType</h4>
        <select v-model="selectedActivityType">
          <option value="">All</option>
          <option v-for="type in userActivityTypes" :key="type" :value="type">{{ type }}</option>
        </select>
      </div>

      <div class="card">
        <h4>StorageAttributes</h4>
        <div class="scroll-list">
          <div v-for="a in storageAttributes" :key="a" class="check-item">
            <span>{{ a }}</span>
          </div>
        </div>
      </div>

      <div class="card">
        <h4>customQuery</h4>
        <div v-for="(f, idx) in filters" :key="idx" class="query-param">
          <select v-model="f.field" @change="onFieldChange(f)">
            <option value="">field...</option>
            <option v-for="field in fieldOptions" :key="field.name" :value="field.name">{{ field.name }}</option>
          </select>
          <select v-if="isEnumField(f.field)" v-model="f.value">
            <option value="">value...</option>
            <option v-for="v in enumValuesFor(f.field)" :key="v" :value="v">{{ v }}</option>
          </select>
          <input v-else v-model="f.value" placeholder="value" />
          <button class="secondary" @click="removeFilter(idx)">x</button>
        </div>
        <div v-if="filters.length === 0" class="check-item">No filters</div>
        <div class="row">
          <button class="ghost" @click="addFilter">+ filter</button>
          <input v-model.number="searchLimit" type="number" min="1" max="1000" style="width: 90px" />
        </div>
        <div class="row" style="margin-top: 8px">
          <button @click="runSearch" :disabled="busy.events">Run query</button>
          <button class="secondary" @click="clearFilters" :disabled="busy.events">Clear</button>
        </div>
      </div>
    </aside>

    <main class="content">
      <div class="topbar">
        <button class="tab" :class="{ active: activePage === 'getRecords' }" @click="activePage = 'getRecords'">
          getRecords
        </button>
        <button class="tab" :class="{ active: activePage === 'storeRecords' }" @click="activePage = 'storeRecords'">
          storeRecords
        </button>
      </div>

      <h2 v-if="activePage === 'getRecords'">Records</h2>
      <h2 v-else>Store Records</h2>

      <div class="card" v-if="activePage === 'getRecords'">
        ClickHouse:
        <span :class="status.clickhouseUp ? 'ok' : 'bad'">{{ status.clickhouseUp ? "UP" : "DOWN" }}</span>
      </div>

      <div v-if="activePage === 'getRecords'" class="card">
        <h3>Result list</h3>
        <div v-if="busy.events">Loading...</div>
        <div v-else-if="records.length === 0">No records</div>
        <div v-for="(row, idx) in records" :key="row.eventId || idx" class="card" style="margin-bottom: 10px">
          <div class="row">
            <strong>{{ row.activityType || row.attrs?.type || "—" }}</strong>
            <code>{{ row.eventId }}</code>
          </div>
          <div style="margin-top: 6px">
            <strong>Top attributes:</strong>
            <div class="col" style="margin-top: 6px">
              <div v-for="item in topAttrs(row.attrs)" :key="item.key">
                <code>{{ item.key }}: {{ item.value }}</code>
              </div>
            </div>
          </div>
          <div style="margin-top: 6px">
            <button class="ghost" @click="toggleExpanded(idx)">
              {{ expandedRows[idx] ? "Hide all attrs" : "Show all attrs" }}
            </button>
            <pre v-if="expandedRows[idx]" style="margin-top: 8px">{{ pretty(row.attrs) }}</pre>
          </div>
        </div>
      </div>

      <div v-else class="card">
        <h3>Store actions</h3>
        <div class="row">
          <select v-model="singleRecord.activityType" style="min-width: 280px">
            <option v-for="t in userActivityTypes" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <p style="margin: 8px 0 4px">attrs (fields)</p>
        <div v-for="(a, idx) in singleRecordAttrs" :key="idx" class="query-param">
          <select v-model="a.key" @change="onSingleAttrKeyChange(a)">
            <option value="">attribute...</option>
            <option v-for="k in storageAttributes" :key="k" :value="k">{{ k }}</option>
          </select>
          <select v-if="isEnumAttr(a.key)" v-model="a.value">
            <option value="">value...</option>
            <option v-for="v in enumValuesForAttr(a.key)" :key="v" :value="v">{{ v }}</option>
          </select>
          <input v-else v-model="a.value" placeholder="value" />
          <button class="secondary" @click="removeSingleAttr(idx)">x</button>
        </div>
        <div class="row">
          <button class="ghost" @click="addSingleAttr">+ attribute</button>
        </div>
        <div class="row" style="margin-top: 8px">
          <button :disabled="busy.store" @click="saveSingleRecord">Save single record</button>
        </div>
        <hr style="margin: 12px 0; border: 0; border-top: 1px solid #e5e7eb" />
        <div class="row">
          <input v-model.number="storeCount" type="number" min="1" max="100000" placeholder="count" />
          <button :disabled="busy.store" @click="generateAndSave">Generate + save</button>
        </div>
        <div class="row" style="margin-top: 8px">
          <input v-model.number="storeRounds" type="number" min="1" max="200" placeholder="rounds" />
          <input v-model.number="storePerRound" type="number" min="500" max="2000" placeholder="perRound" />
          <button :disabled="busy.store" @click="simulateLoad">Simulate load</button>
        </div>
        <pre style="margin-top: 10px">{{ storeResult }}</pre>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";

const status = reactive({ clickhouseUp: false });
const busy = reactive({ events: false, store: false });
const activePage = ref("getRecords");
const records = ref([]);
const expandedRows = reactive({});
const searchLimit = ref(100);
const selectedActivityType = ref("");
const filters = ref([{ field: "USER_ID", value: "" }, { field: "LANG", value: "" }]);
const storeCount = ref(1000);
const storeRounds = ref(10);
const storePerRound = ref(1000);
const storeResult = ref("");
const singleRecord = reactive({
  activityType: "MESSAGE_SENT"
});
const singleRecordAttrs = ref([
  { key: "USER_ID", value: "1001" },
  { key: "LANG", value: "ru" },
  { key: "SOURCE", value: "mobile" }
]);

const userActivityTypes = [
  "REGISTRATION", "LOGIN", "LOGOUT",
  "MESSAGE_SENT", "MESSAGE_DELETED", "MESSAGE_EDITED", "MESSAGE_READ",
  "REACTION_ADDED", "REACTION_REMOVED",
  "MODERATION_FLAG", "MODERATION_APPROVE", "MODERATION_REJECT",
  "PURCHASE_INITIATED", "PURCHASE_COMPLETED", "PURCHASE_REFUNDED",
  "PROFILE_UPDATED", "AVATAR_CHANGED", "STATUS_CHANGED",
  "CHANNEL_CREATED", "CHANNEL_JOINED", "CHANNEL_LEFT", "CHANNEL_DELETED",
  "FILE_UPLOADED", "FILE_DOWNLOADED", "FILE_DELETED",
  "CALL_STARTED", "CALL_ENDED", "CALL_MISSED",
  "SEARCH_PERFORMED", "NOTIFICATION_RECEIVED"
];

const storageAttributes = [
  "USER_ID", "LANG", "SOURCE", "PAYLOAD", "TERMINAL_TYPE",
  "USER_DEVICE_TYPE", "USER_ACTIVE_STATUS", "GENDER", "ORG_HASH", "HAS_ATTACHMENTS",
  "USER_GROUP_ADMIN", "USER_CHAT_ADMIN", "SOURCE_CHAT_ID", "GROUP_PROMO", "AMOUNT", "DISCOUNT"
];

const fieldOptions = [
  { name: "USER_ID", type: "number" },
  { name: "RECORD_TYPE", type: "enum", values: ["MESSAGE", "MODERATION", "PURCHASE", "SYSTEM", "OTHER"] },
  { name: "TYPE", type: "enum", values: userActivityTypes },
  { name: "LANG", type: "enum", values: ["ru", "en", "de", "fr"] },
  { name: "SOURCE", type: "enum", values: ["mobile", "web", "backend", "desktop"] },
  { name: "TERMINAL_TYPE", type: "enum", values: ["ONEME", "ONEME_BIZ", "WEB"] },
  { name: "USER_DEVICE_TYPE", type: "enum", values: ["ANDROID", "IOS", "WEB"] },
  { name: "USER_ACTIVE_STATUS", type: "enum", values: ["0", "1"] },
  { name: "GENDER", type: "enum", values: ["0", "1", "2"] },
  { name: "ORG_HASH", type: "text" }
];
const attrEnumValues = {
  LANG: ["ru", "en", "de", "fr"],
  SOURCE: ["mobile", "web", "backend", "desktop"],
  TERMINAL_TYPE: ["ONEME", "ONEME_BIZ", "WEB"],
  USER_DEVICE_TYPE: ["ANDROID", "IOS", "WEB"],
  USER_ACTIVE_STATUS: ["0", "1"],
  GENDER: ["0", "1", "2"],
  HAS_ATTACHMENTS: ["true", "false"],
  USER_GROUP_ADMIN: ["true", "false"],
  USER_CHAT_ADMIN: ["true", "false"],
  GROUP_PROMO: ["true", "false"]
};

async function fetchJson(url, options) {
  const r = await fetch(url, options);
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
  return await r.json();
}

async function loadStatus() {
  try {
    const j = await fetchJson("/actuator/health");
    status.clickhouseUp = String(j.status || "").toUpperCase() === "UP";
  } catch {
    status.clickhouseUp = false;
  }
}

function addFilter() {
  filters.value.push({ field: "", value: "" });
}

function removeFilter(idx) {
  filters.value.splice(idx, 1);
}

function onFieldChange(filterRow) {
  filterRow.value = "";
}

function isEnumField(field) {
  return fieldOptions.find((f) => f.name === field)?.type === "enum";
}

function enumValuesFor(field) {
  return fieldOptions.find((f) => f.name === field)?.values || [];
}

function clearFilters() {
  selectedActivityType.value = "";
  filters.value = [{ field: "", value: "" }];
  records.value = [];
}

function addSingleAttr() {
  singleRecordAttrs.value.push({ key: "", value: "" });
}

function removeSingleAttr(idx) {
  singleRecordAttrs.value.splice(idx, 1);
  if (singleRecordAttrs.value.length === 0) {
    singleRecordAttrs.value.push({ key: "", value: "" });
  }
}

function onSingleAttrKeyChange(attrRow) {
  attrRow.value = "";
}

function isEnumAttr(key) {
  return !!attrEnumValues[key];
}

function enumValuesForAttr(key) {
  return attrEnumValues[key] || [];
}

async function runSearch() {
  const bodyFilters = {};
  for (const f of filters.value) {
    const key = String(f.field || "").trim();
    const value = String(f.value || "").trim();
    if (!key || !value) continue;
    bodyFilters[key] = value;
  }
  if (selectedActivityType.value) {
    bodyFilters.TYPE = selectedActivityType.value;
  }

  busy.events = true;
  try {
    records.value = await fetchJson("/api/records/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        filters: bodyFilters,
        limit: searchLimit.value || 100
      })
    });
    for (const k of Object.keys(expandedRows)) {
      delete expandedRows[k];
    }
  } catch (e) {
    records.value = [];
    console.error(e);
  } finally {
    busy.events = false;
  }
}

async function generateAndSave() {
  busy.store = true;
  try {
    const data = await fetchJson(`/api/records/generate-and-save?count=${encodeURIComponent(storeCount.value || 1000)}`, {
      method: "POST"
    });
    storeResult.value = JSON.stringify(data, null, 2);
  } catch (e) {
    storeResult.value = String(e);
  } finally {
    busy.store = false;
  }
}

async function saveSingleRecord() {
  busy.store = true;
  try {
    let attrs = {};
    for (const row of singleRecordAttrs.value) {
      const key = String(row.key || "").trim();
      const value = String(row.value || "").trim();
      if (!key || !value) {
        continue;
      }
      attrs[key] = value;
    }
    const payload = [{
      activityType: singleRecord.activityType,
      eventTime: new Date().toISOString(),
      attrs
    }];
    const data = await fetchJson("/api/records/save-batch", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    storeResult.value = JSON.stringify(data, null, 2);
  } catch (e) {
    storeResult.value = String(e);
  } finally {
    busy.store = false;
  }
}

async function simulateLoad() {
  busy.store = true;
  try {
    const q = new URLSearchParams({
      rounds: String(storeRounds.value || 10),
      perRound: String(storePerRound.value || 1000)
    });
    const data = await fetchJson(`/api/records/simulate-load?${q.toString()}`, { method: "POST" });
    storeResult.value = JSON.stringify(data, null, 2);
  } catch (e) {
    storeResult.value = String(e);
  } finally {
    busy.store = false;
  }
}

function topAttrs(attrs) {
  if (!attrs || typeof attrs !== "object") return [];
  return Object.entries(attrs).slice(0, 5).map(([key, value]) => ({ key, value }));
}

function toggleExpanded(idx) {
  expandedRows[idx] = !expandedRows[idx];
}

function pretty(obj) {
  return JSON.stringify(obj || {}, null, 2);
}

onMounted(async () => {
  await loadStatus();
  await runSearch();
});
</script>
