<!-- Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License. -->
# MCP Tools Reference

All tools are read-only unless explicitly marked as write-mode required.
Every ORD argument is validated against the configured allowlist.
Sensitive slot names (`password`, `secret`, `token`, `key`, `credential`, `auth`) are masked as `***`.

## Write Mode Selector (`readOnly`)

- BMcpService `readOnly=true` (default): write-capable tools fail closed.
- BMcpService `readOnly=false`: write-capable tools are allowed to run, still subject to allowlists and tool-specific validation.
- This selector is persistent in station config and should be treated as an operational change-control toggle.

This reference matches current branch behavior for v0.8.0.

---

## Tool Inventory (v0.8.0)

v0.8.0 completes the slot sheet cleanup: legacy `eulaBlockEnabled` and `eulaOverridePassphrase` declared properties removed; only `runtimeProfile` override path remains.
All tool names use the `nmcp.*` namespace.

| Category | Tools |
|---|---|
| Station | `nmcp.station.info` |
| Components | `nmcp.component.read`, `nmcp.component.children`, `nmcp.component.slots`, `nmcp.component.search` |
| BQL | `nmcp.bql.query` |
| Alarms | `nmcp.alarm.query`, `nmcp.alarm.active`, `nmcp.alarm.ack` |
| History | `nmcp.history.list`, `nmcp.history.read`, `nmcp.trend.summary` |
| BACnet | `nmcp.bacnet.devices`, `nmcp.bacnet.discover` |
| Schedules | `nmcp.schedule.read`, `nmcp.schedule.list`, `nmcp.schedule.write` |
| Points | `nmcp.point.read`, `nmcp.point.search` |
| Equipment | `nmcp.equipment.status` |
| Diagnostics | `nmcp.fault.scan`, `nmcp.building.brief` |
| Haystack | `nmcp.haystack.getRuleset`, `nmcp.haystack.setRuleset`, `nmcp.haystack.applyRules`, `nmcp.haystack.scanPoints`, `nmcp.haystack.suggestTags` |
| Wiresheet | `nmcp.wiresheet.plan`, `nmcp.wiresheet.diff`, `nmcp.wiresheet.apply`, `nmcp.wiresheet.links` |
| Write | `nmcp.point.write`, `nmcp.point.override`, `nmcp.component.invokeAction`, `nmcp.station.restart`, `nmcp.driver.discoverAndAdd` |

Total: 36 tools.

---

## Station & Component

### `nmcp.station.info`

Returns station and platform metadata.

**Arguments:** none

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.station.info","arguments":{}}}'
```

**Example response:**
```json
{
  "stationName": "mcp3",
  "hostId": "Win-AAAA-BBBB-CCCC-DDDD",
  "platformVersion": "4.15.1.16",
  "niagaraVersion": "4.15.1.16",
  "currentCpuUsage": 7,
  "model": "Workstation",
  "modelVersion": null,
  "numCpus": 8,
  "osName": "Windows 11",
  "osVersion": "10.0",
  "overallCpuUsage": 7,
  "totalPhysicalMemory": 14626736,
  "moduleVersion": "0.8.0",
  "readOnly": false
}
```

`platformVersion` is the canonical field. `niagaraVersion` is preserved as a compatibility alias.

`stationName` and 'hostId' are sourced from the station first, then falls back to platform-service slots when station methods are unavailable.

---

### `nmcp.component.read`

Reads a single component by ORD and returns its slots.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Component ORD |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.component.read","arguments":{"ord":"station:|slot:/Drivers/BacnetNetwork"}}}'
```

---

### `nmcp.component.children`

Lists immediate children of a component.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Parent component ORD |
| `limit` | integer | No | Max children to return (default: `maxResults` config) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.component.children","arguments":{"ord":"station:|slot:/Drivers","limit":50}}}'
```

---

### `nmcp.component.slots`

Lists all slots on a component. Sensitive slot values are masked as `***`.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Component ORD |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.component.slots","arguments":{"ord":"station:|slot:/Drivers/BacnetNetwork"}}}'
```

---

### `nmcp.component.search`

Searches all components under a root ORD by display name and/or type substring.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `nameFilter` | string | No | Case-insensitive substring on component name/display name |
| `typeFilter` | string | No | Case-insensitive substring on type name |
| `root` | string | No | Root ORD to search (default `station:|slot:/Drivers`) |
| `limit` | integer | No | Max matches (default 50, capped by `maxResults`) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.component.search","arguments":{"nameFilter":"Damper","limit":25}}}'
```

---

## BQL

### `nmcp.bql.query`

Runs a read-only BQL SELECT query. Mutation keywords (`SET`, `DELETE`, `INSERT`, `UPDATE`, `DROP`, `CREATE`, `ALTER`, `EXECUTE`, `CALL`) are rejected immediately.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `query` | string | Yes | BQL SELECT statement |
| `limit` | integer | No | Max rows to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.bql.query","arguments":{"query":"SELECT * FROM control:NumericPoint","limit":50}}}'
```

---

## Alarms

### `nmcp.alarm.query`

Returns recent alarm records (all states) from the alarm database cursor.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `limit` | integer | No | Max records to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.alarm.query","arguments":{"limit":50}}}'
```

**Example response:**
```json
{
  "limit": 50,
  "count": 2,
  "alarms": [
    {
      "ackState": "unacked",
      "alarmClass": "LifeSafety",
      "priority": 1,
      "timestamp": "28-Apr-26 5:08 PM CDT",
      "timestampMillis": 1714342080000,
      "source": "station:|slot:/Drivers/Network/Device/ZoneTemp",
      "data": "{message=High temperature limit exceeded}"
    }
  ]
}
```

---

### `nmcp.alarm.active`

Lists active alarms by querying the alarm DB ack-pending cursor.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `limit` | integer | No | Max records returned |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.alarm.active","arguments":{}}}'
```


---

### `nmcp.alarm.ack`

**Write mode required.** Acknowledges alarms from a specific source ORD. Returns the count of alarms acknowledged.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `sourceOrd` | string | Yes | Source ORD of the alarm to acknowledge |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.alarm.ack","arguments":{"sourceOrd":"station:|slot:/Drivers/Network/Device/ZoneTemp"}}}'
```

**Example response:**
```json
{
  "sourceOrd": "station:|slot:/Drivers/Network/Device/ZoneTemp",
  "acknowledged": 2
}
```

---

## History / Trends

### `nmcp.history.list`

Lists available history (trend log) identifiers.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `limit` | integer | No | Max histories to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.history.list","arguments":{"limit":100}}}'
```

**Example response:**
```json
{
  "limit": 100,
  "count": 3,
  "histories": [
    { "id": "ZoneTemp_1h", "displayName": "Zone 1 Temperature (1hr)" },
    { "id": "CO2_15min",   "displayName": "Zone 1 CO2 (15min)" }
  ]
}
```

---

### `nmcp.history.read`

Reads time-series records for a history over an optional time window.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `id` | string | Yes | History ID from `nmcp.history.list` |
| `startTime` | integer | No | Start of window (epoch milliseconds) |
| `endTime` | integer | No | End of window (epoch milliseconds, default: now) |
| `limit` | integer | No | Max samples to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.history.read","arguments":{"id":"ZoneTemp_1h","startTime":1714132800000,"endTime":1714219200000,"limit":100}}}'
```

**Example response:**
```json
{
  "id": "ZoneTemp_1h",
  "startTime": 1714132800000,
  "endTime": 1714219200000,
  "count": 24,
  "records": [
    { "timestamp": 1714132800000, "value": "72.4" },
    { "timestamp": 1714136400000, "value": "73.1" }
  ]
}
```

---

### `nmcp.trend.summary`

Summarizes numeric trend behavior over a recent window. This returns aggregate values for narration rather than raw records.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `id` | string | Yes | History ID from `nmcp.history.list` |
| `hours` | integer | No | Window size in hours (default 4) |
| `limit` | integer | No | Max samples to read (default 200) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.trend.summary","arguments":{"id":"/nmcp/ZoneTempHistory","hours":4,"limit":200}}}'
```

**Example response:**
```json
{
  "id": "/nmcp/ZoneTempHistory",
  "windowHours": 4,
  "sampleCount": 87,
  "min": "68.3",
  "max": "84.1",
  "first": "71.2",
  "last": "83.6",
  "startTime": 1714332000000,
  "endTime": 1714346400000
}
```

---

## Points

### `nmcp.point.read`

Reads the current value and status of a single proxy point by ORD.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Proxy point ORD (must be within allowlisted roots) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.point.read","arguments":{"ord":"station:|slot:/Drivers/Network/Device/ZoneTemp"}}}'
```

**Example response:**
```json
{
  "ord": "station:|slot:/Drivers/Network/Device/ZoneTemp",
  "displayName": "Zone 1 Temperature",
  "type": "NumericPoint",
  "value": "72.4 °F"
}
```

---

### `nmcp.point.search`

Finds proxy points under the Drivers tree filtered by display name substring and/or type substring.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `nameFilter` | string | No | Case-insensitive substring match on display name |
| `typeFilter` | string | No | Substring match on component type (e.g. `NumericPoint`, `BooleanPoint`) |
| `limit` | integer | No | Max results to return |

**Example — find all CO2 sensors:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.point.search","arguments":{"nameFilter":"CO2","limit":50}}}'
```

**Example — find all boolean points:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.point.search","arguments":{"typeFilter":"BooleanPoint","limit":100}}}'
```

---

## Equipment

### `nmcp.equipment.status`

Summarizes all devices across all driver networks: name, type, ORD, child point count, and status slot value. Useful for answering "what devices are in the station?" or "what is offline?".

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `limit` | integer | No | Max devices to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.equipment.status","arguments":{}}}'
```

**Example response:**
```json
{
  "count": 2,
  "devices": [
    {
      "name": "AHU-1",
      "displayName": "Air Handler Unit 1",
      "type": "BacnetDevice",
      "ord": "station:|slot:/Drivers/BacnetNetwork/AHU-1",
      "network": "BacnetNetwork",
      "pointCount": 42,
      "status": "ok"
    },
    {
      "name": "FCU-3",
      "displayName": "Fan Coil Unit 3",
      "type": "BacnetDevice",
      "ord": "station:|slot:/Drivers/BacnetNetwork/FCU-3",
      "network": "BacnetNetwork",
      "pointCount": 18,
      "status": "fault {comms}"
    }
  ]
}
```

> **Note:** This tool walks one level below each network in the Drivers tree. Devices nested deeper will not appear.

---

## Diagnostics

### `nmcp.fault.scan`

Scans points under a root and summarizes fault/stale/overridden states.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to scan (default `station:|slot:/Drivers`) |
| `limit` | integer | No | Max matches to return per category |

**Example response shape:**
```json
{
  "root": "station:|slot:/Drivers",
  "scanned": 47,
  "faultCount": 2,
  "staleCount": 5,
  "overriddenCount": 1,
  "faultPoints": [],
  "stalePoints": [],
  "overriddenPoints": []
}
```

---

### `nmcp.building.brief`

Returns a synthesized operational briefing: alarms + fault summary + equipment summary.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `alarmLimit` | integer | No | Max alarms to include (default 20) |

**Example response shape:**
```json
{
  "stationName": "mcp",
  "moduleVersion": "0.6.1",
  "timestamp": 1714346400000,
  "alarmSummary": {
    "totalQueried": 20,
    "unackedCount": 0,
    "recentAlarms": []
  },
  "faultSummary": {
    "faultCount": 0,
    "staleCount": 1,
    "overriddenCount": 0,
    "faultPoints": []
  },
  "equipmentSummary": {
    "networkCount": 3,
    "deviceCount": 24
  }
}
```

---

## Schedules

### `nmcp.schedule.read`

Reads a schedule component by ORD: current occupancy state, next transition time (epoch ms), and next state.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | `BWeeklySchedule` component ORD (must be within allowlisted roots) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.schedule.read","arguments":{"ord":"station:|slot:/Config/Schedules/OccupancySchedule"}}}'
```

**Example response:**
```json
{
  "ord": "station:|slot:/Config/Schedules/OccupancySchedule",
  "currentState": "occupied",
  "nextTransitionMs": 1714262400000,
  "nextState": "unoccupied"
}
```

> **Note:** Only `BWeeklySchedule` components are supported. `BDailySchedule` and `BCalendarSchedule` will return an error.

---

### `nmcp.schedule.list`

Lists all `BWeeklySchedule` components in the station with their current state. Quick building-wide occupancy overview.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `limit` | integer | No | Max schedules to return |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.schedule.list","arguments":{}}}'
```

---

### `nmcp.schedule.write`

**Write mode required.** Sets the default output state on a `BWeeklySchedule` component (the value used when no time-of-day event is active).

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | `BWeeklySchedule` component ORD |
| `state` | string | Yes | New default output state (e.g. `"unoccupied"`, `"occupied"`) |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.schedule.write","arguments":{"ord":"station:|slot:/Config/Schedules/OccupancySchedule","state":"unoccupied"}}}'
```

**Example response:**
```json
{
  "ord": "station:|slot:/Config/Schedules/OccupancySchedule",
  "state": "unoccupied",
  "status": "applied"
}
```

---

## Haystack

### `nmcp.haystack.getRuleset`

Reads the current Haystack tagging ruleset JSON.

**Arguments:** none

---

### `nmcp.haystack.setRuleset`

**Write mode required.** Writes/replaces the Haystack ruleset JSON file.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ruleset` | string | Yes | Full ruleset JSON content |

---

### `nmcp.haystack.applyRules`

**Write mode required.** Applies configured rules to components under a root ORD.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to apply rules under (default `station:|slot:/Drivers`) |
| `dryRun` | boolean | No | If `true` (default), reports what would change without writing |

---

### `nmcp.haystack.scanPoints`

Scans points and reports existing Haystack tags. Read-only.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to scan (default `station:|slot:/Drivers`) |
| `limit` | integer | No | Max points to return |

---

### `nmcp.haystack.suggestTags`

Suggests Haystack tags for discovered points and generates a ready-to-use ruleset. Read-only.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to scan |
| `limit` | integer | No | Max points to analyse |

---

## Wiresheet

### `nmcp.wiresheet.plan`

Validates and normalizes a declarative wiresheet operation list without touching the station. Returns a sorted, dependency-resolved plan. Safe to call in read-only mode.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `operations` | array | Yes | Declarative operation list (createComponent / setSlot / link) |
| `strict` | boolean | No | If `true` (default), rejects kitControl/baja prefix types |

---

### `nmcp.wiresheet.diff`

Compares a desired operation set against the current station graph. Returns `creates`, `updates`, `linksToAdd`, `alreadyPresent` arrays. Deterministic and side-effect free.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `operations` | array | Yes | Desired operation list |
| `root` | string | Yes | Root ORD to diff against |

---

### `nmcp.wiresheet.apply`

**Write mode required.** Executes a declarative operation list and returns per-step results. Defaults to `dryRun=true` to prevent accidental writes.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `operations` | array | Yes | Operation list |
| `root` | string | Yes | Root ORD to apply under |
| `dryRun` | boolean | No | If `true` (default), validates without writing |

---

### `nmcp.wiresheet.links`

Inspects runtime links on a component or slot. Returns source/target ORDs, slot names, and ORD metadata state. Useful for diagnosing wiring issues.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Component ORD |
| `slot` | string | No | Slot name to filter links (if omitted, all links on the component) |

---

## Write Tools

All write tools require `BMcpService.readOnly=false` and the target ORD to be within the configured allowlisted roots.

### `nmcp.point.write`

Writes a value to a writable point at operator priority (in10, priority level 10).

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | `BNumericWritable` or `BBooleanWritable` ORD |
| `value` | number/boolean/null | Yes | Value to write; `null` releases the slot at this priority |

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.point.write","arguments":{"ord":"station:|slot:/Drivers/sandbox/N1","value":42.0}}}'
```

**Example response:**
```json
{
  "ord": "station:|slot:/Drivers/sandbox/N1",
  "slot": "in10",
  "value": 42.0,
  "status": "applied",
  "detail": "set via setIn10(BValue)"
}
```

> Pass `"value": null` to release (clear) the slot at priority 10.

---

### `nmcp.point.override`

Overrides a writable point at priority 8 (operator override level). Higher urgency than `point.write`.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | `BNumericWritable` or `BBooleanWritable` ORD |
| `value` | number/boolean/null | Yes | Override value; `null` releases the override |

**Example response:**
```json
{
  "ord": "station:|slot:/Drivers/sandbox/N1",
  "slot": "in8",
  "value": 99.0,
  "status": "applied",
  "detail": "set via setIn8(BValue)"
}
```

---

### `nmcp.component.invokeAction`

**Write mode required.** Invokes a named action on a component (e.g. `active`, `auto`, `emergencyAuto`).

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `ord` | string | Yes | Component ORD |
| `action` | string | Yes | Action name (e.g. `active`, `auto`, `override`) |
| `arg` | string/number | No | Optional action argument |

---

### `nmcp.station.restart`

**Write mode required.** Requests a controlled station restart via `BStation.restart()`. Use with caution.

**Arguments:** none

---

### `nmcp.driver.discoverAndAdd`

**Write mode required.** Triggers driver network device discovery on a BACnet or other network component.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `networkOrd` | string | Yes | Network component ORD (e.g. `station:|slot:/Drivers/BacnetNetwork`) |

Writes/replaces the Haystack ruleset JSON file.

Requires MCP write mode to be enabled.

### `nmcp.haystack.applyRules`

Applies configured rules to components under a root.

Requires MCP write mode to be enabled.

### `nmcp.haystack.scanPoints`

Walks the component tree under a root ORD and finds all points, reporting which Project Haystack
tags each already carries. Tags are read from dynamic slots prefixed with `h4:` (stored internally
as `h4$3a<tagName>`, type `baja:Marker`, written by `applyRules`).

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to scan (default `station:\|slot:/Drivers`) |
| `limit` | integer | No | Max points to inspect |

**Response:** `root`, `totalPoints`, `taggedPoints`, `untaggedPoints`, `points[]`
(each with `ord`, `displayName`, `typeName`, `existingTags`, `tagCount`).

### `nmcp.haystack.suggestTags`

Discovers all points under a root ORD and suggests Project Haystack tags for each one based on
component type, display name patterns (SAT → temp/air/supply, CO2, pressure, etc.), and parent
component name. Returns per-point suggestions with reasoning plus a ready-to-use ruleset JSON
that can be passed directly to `setRuleset` and then applied with `applyRules`.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `root` | string | No | Root ORD to scan (default `station:\|slot:/Drivers`) |
| `limit` | integer | No | Max points to inspect |

**Response:** `root`, `totalPoints`, `suggestions[]`
(each with `ord`, `displayName`, `typeName`, `suggestedTags`, `reasoning`), `suggestedRuleset`.

---

## Wiresheet (v0.6.1)

### `nmcp.wiresheet.plan`

Validates and normalizes declarative wiresheet operations with deterministic execution ordering.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `rootOrd` | string | Yes | Allowlisted root ORD for planning scope |
| `operations` | array | Yes | Declarative operation list (see operation types below) |
| `strict` | boolean | No | Enforce `control:*`-only type checks (default `true`); set `false` to allow `kitControl:*`, `baja:*` |

---

### `nmcp.wiresheet.diff`

Builds a deterministic desired-state diff from the same operation payload used by plan/apply.

**Arguments:** same as `nmcp.wiresheet.plan`.

---

### `nmcp.wiresheet.apply`

Write-gated execution pass with `dryRun=true` default.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `rootOrd` | string | Yes | Allowlisted root ORD for apply scope |
| `operations` | array | Yes | Declarative operation list |
| `strict` | boolean | No | Enforce `control:*`-only type checks (default `true`) |
| `dryRun` | boolean | No | If omitted, defaults to `true` |
| `requestId` | string | No | Optional correlation id |

**Runtime behavior:**
- In `dryRun=true`, returns `planned` step results only.
- In `dryRun=false`, requires `readOnly=false`; create/set/link/addCompositePin operations execute at runtime and return per-step diagnostics.
- Link execution prefers explicit target-side `makeLink`/`linkTo` semantics and falls back to broader reflection only if needed.
- `local:|foxwss:|station:|slot:/...` ORDs are normalized for allowlist and component resolution.
- Runtime link repair populates relation ORD metadata when missing so Workbench link navigation can resolve endpoint hyperlinks.

**Operation types:**

#### `createComponent`

| Field | Type | Required | Description |
|---|---|---|---|
| `parentOrd` | string | Yes | ORD of the parent component |
| `name` | string | Yes | Slot name for the new component |
| `componentType` | string | Yes | Type spec: `control:NumericWritable`, `control:BooleanWritable`, `control:EnumWritable`, `control:StringWritable`, `baja:Folder`; kitControl types: `kitControl:Add`, `kitControl:Subtract`, `kitControl:Multiply`, `kitControl:Divide`, `kitControl:Average`, `kitControl:Minimum`, `kitControl:Maximum`, `kitControl:AbsValue`, `kitControl:Negative`, `kitControl:Modulus`, `kitControl:SquareRoot`, `kitControl:Derivative`, `kitControl:Limiter`, `kitControl:Line`, `kitControl:GreaterThan`, `kitControl:LessThan`, `kitControl:GreaterThanEqual`, `kitControl:LessThanEqual`, `kitControl:Equal`, `kitControl:NotEqual`, `kitControl:Hysteresis`, `kitControl:And`, `kitControl:Or`, `kitControl:Not`, `kitControl:Nand`, `kitControl:Nor`, `kitControl:Xor`, `kitControl:NumericSwitch`, `kitControl:BooleanSwitch`, `kitControl:EnumSwitch`, `kitControl:BooleanLatch`, `kitControl:NumericLatch`, `kitControl:EnumLatch`, `kitControl:StringLatch`, `kitControl:NumericSelect`, `kitControl:BooleanSelect`, `kitControl:EnumSelect`, `kitControl:BooleanDelay`, `kitControl:NumericDelay`, `kitControl:OneShot`, `kitControl:LoopPoint`, `kitControl:Counter`, `kitControl:MinMaxAvg`, `kitControl:Ramp` |
| `facets` | object | No | Engineering unit facets (see below) |

**Supported `facets` fields:**

| Field | Applies to | Description |
|---|---|---|
| `units` | NumericWritable | Engineering unit string, e.g. `degreesFahrenheit`, `percentRH`, `cfm` |
| `precision` | NumericWritable | Display decimal places (integer) |
| `min` | NumericWritable | Minimum value (double) |
| `max` | NumericWritable | Maximum value (double) |
| `trueText` | BooleanWritable | Display text for `true`, e.g. `Cooling` |
| `falseText` | BooleanWritable | Display text for `false`, e.g. `Idle` |

#### `setSlot`

| Field | Type | Required | Description |
|---|---|---|---|
| `componentOrd` | string | Yes | ORD of the target component |
| `slot` | string | Yes | Slot name to write |
| `value` | any | Yes | Value to set (number, boolean, string) |

#### `link`

| Field | Type | Required | Description |
|---|---|---|---|
| `from` | string | Yes | Source slot ORD, e.g. `station:|slot:/…/SpaceTemperature/out` |
| `to` | string | Yes | Destination slot ORD, e.g. `station:|slot:/…/CoolComparator/inA` |

#### `addCompositePin`

Adds a composite interface pin to a `baja:Folder`, exposing an internal slot at the folder boundary for external wiring.

| Field | Type | Required | Description |
|---|---|---|---|
| `folderOrd` | string | Yes | ORD of the `baja:Folder` component that will host the pin |
| `pinName` | string | Yes | Name for the new pin slot; must not collide with any existing child component name |
| `targetComponentOrd` | string | Yes | ORD of the internal component the pin maps to |
| `targetSlot` | string | Yes | Slot name on the target component, e.g. `in10`, `out` |
| `direction` | string | Yes | `in` (external → internal) or `out` (internal → external) |

> **Note:** Pin names share the same slot namespace as child component names on the parent folder. Choose distinct names that describe the interface role (e.g. `TemperatureIn`, `CoolDemand`) rather than mirroring internal component names.

---

### `nmcp.wiresheet.links`

Inspects runtime wiresheet links for a component, with optional slot filtering.
Useful for validating applied link direction and persisted relation metadata.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `componentOrd` | string | Yes | Component ORD to inspect |
| `slot` | string | No | Optional slot filter (for example `out` or `in10`) |

---

## BACnet

### `nmcp.bacnet.devices`

Lists BACnet devices **provisioned as station components** under a network ORD (child `BBacnetDevice` instances).

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `networkOrd` | string | Yes | ORD of the BACnet network component |
| `limit` | integer | No | Max devices to return |

**Response fields per device:** `name`, `ord`, `instanceNumber`, `address`, `networkNumber`

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.bacnet.devices","arguments":{"networkOrd":"station:|slot:/Drivers/BacnetNetwork","limit":100}}}'
```

---

### `nmcp.bacnet.discover`

Returns the BACnet stack's **in-memory device registry** — all devices heard via WhoIs/IAm, including devices not yet provisioned as station components. Read-only; does not add devices to the station.

**Arguments:**

| Name | Type | Required | Description |
|---|---|---|---|
| `networkOrd` | string | Yes | ORD of the BACnet network component |
| `limit` | integer | No | Max devices to return |

**Response fields per device:** `name`, `ord`, `instanceNumber`, `address`, `networkNumber`

**Example request:**
```bash
curl -X POST http://127.0.0.1:8765/nmcp \
  -H "X-MCP-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"nmcp.bacnet.discover","arguments":{"networkOrd":"station:|slot:/Drivers/BacnetNetwork"}}}'
```

---

## Common Error Responses

All tools return a consistent error shape when something goes wrong:

```json
{
  "error": "ORD not within allowlisted roots: station:|slot:/System/Security"
}
```

Common error messages:

| Message | Cause |
|---|---|
| `ORD not within allowlisted roots: ...` | ORD blocked by allowlist |
| `AlarmService not available` | AlarmService not running in station |
| `HistoryService not available` | HistoryService not running in station |
| `History not found: <id>` | Unknown history ID |
| `ORD is not a component: ...` | ORD resolved to a non-component type |
| `ORD is not a BWeeklySchedule: ...` | Schedule ORD points to a non-weekly schedule |
| `Missing required argument: <name>` | Required argument missing from call |
| `Drivers tree not accessible` | `/Drivers` ORD not resolvable |
