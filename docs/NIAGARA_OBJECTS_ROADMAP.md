<!-- Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License. -->
# nMCP Roadmap

**Current release: v0.8.5** — branch ready to publish

---

## What Has Shipped

### v0.1 — Core
- HTTP endpoint, JSON-RPC 2.0 handler, web authentication proxy
- `nmcp.station.info`
- `nmcp.component.read`, `nmcp.component.children`, `nmcp.component.slots`
- `nmcp.bql.query` (SELECT-only, mutation keywords rejected)
- `nmcp.bacnet.devices`
- Sensitive-slot masking, allowlist enforcement, result caps

### v0.2 — Operator Essentials
- `nmcp.alarm.active` — live unacknowledged alarms
- `nmcp.history.read`, `nmcp.history.list` — time-series records
- `nmcp.point.read`, `nmcp.point.search` — live values and cross-device search
- `nmcp.equipment.status` — driver network + device health summary
- `nmcp.schedule.read`, `nmcp.schedule.list` — occupancy state

### v0.3 — Runtime API Fix + Synthesis
- Alarm and history wired to real Niagara API chains (AlarmService, HistoryService)
- `nmcp.trend.summary` — min/max/avg over a time window
- `nmcp.fault.scan`, `nmcp.building.brief` — diagnostics and station overview
- `nmcp.component.search` — display name + type filter across the component tree
- `nmcp.haystack.getRuleset`, `nmcp.haystack.setRuleset`, `nmcp.haystack.applyRules`
- 110+ unit tests

### v0.4.0 — Wiresheet Runtime
- `nmcp.wiresheet.plan`, `nmcp.wiresheet.diff`, `nmcp.wiresheet.apply`, `nmcp.wiresheet.links`
- Create support for `control:NumericWritable`, `control:BooleanWritable`, `control:EnumWritable`, `control:StringWritable`
- Deterministic target-side `linkTo` invocation (link direction bug fixed)
- Automatic `endpointOrd`/`sourceOrd` repair so Workbench navigation works after programmatic linking
- `local:|foxwss:|` ORD normalization across all write paths

**See:** [LESSONS_LEARNED.md](LESSONS_LEARNED.md) for full debugging narrative.

### v0.5.0 — kitControl + Composite Pins
- `createComponent` expanded to kitControl logic blocks: `Add`, `Subtract`, `GreaterThan`, `LessThan`, `And`, `Or`, `Not`
- Engineering-unit facets on create: `units`, `precision`, `min`, `max`, `trueText`, `falseText`
- `addCompositePin` operation — adds a typed interface pin to a folder and wires the composite `BLink` in one step
- `strict=false` mode for `plan`/`diff` to allow `kitControl:` and `baja:` prefix types
- `kitControl-rt` optional module dependency added to the module descriptor
- `BComponent.getHandleOrd()` stub added for composite pin wiring
- Full MCP-agent-driven thermostat validation: 6 writable points + 4 kitControl logic blocks + 10 links across 3 apply calls
- License headers across all source files

### v0.5.2 — kitControl Expansion + Fixes ✅ Done
- Full kitControl type set: 32 types including `LoopPoint`, `Counter`, `MinMaxAvg`, `Ramp`, all switches (`NumericSwitch`, `BooleanSwitch`, `EnumSwitch`), latches, selects, timers, `Derivative`, `Modulus`, `SquareRoot`, `Negative`, `Limiter`, `Hysteresis`, `AbsValue`, `Line`
- `setSlot` named-setter fix: after generic method scan fails, tries `set + capitalize(slotName)` — resolves `proportionalConstant`, `integralConstant`, `derivativeConstant`, `countIncrement`
- `BMuxSwitch` excluded from type map — not instantiatable
- Same-call async init rule documented: `BNumericPoint`/`BBooleanPoint` subclasses (switches, LoopPoint, Counter) must be linked in a separate apply call from their creation
- Integration demo script (used during validation): 7-call sequence exercising all 32 kitControl types as a live integration test and MCP client reference

---

### v0.6.1 — Priority-Slot Writes + Alarm/Schedule Write Gates
- `nmcp.point.write` — writes a value to a writable point at operator priority (in10); `null` value releases the slot
- `nmcp.point.override` — writes at priority 8 (operator override level); `null` releases override
- `nmcp.component.invokeAction` — invokes a named action (e.g. `active`, `auto`, `emergencyAuto`) on any component
- `nmcp.station.restart` — controlled station restart via `BStation.restart()`
- `nmcp.driver.discoverAndAdd` — triggers BACnet/driver network device discovery
- `nmcp.alarm.ack` — acknowledges alarms by source ORD (write-gated); returns count acknowledged
- `nmcp.schedule.write` — sets default output state on a `BWeeklySchedule` (write-gated)
- Write API root cause resolved: `BStatusNumeric` is in `javax.baja.status` (constructor, not factory); null-release uses `setStatusNull(true)` on a no-arg instance
- 35 tools total; 171 unit tests, 0 failures

---

### v0.6.2 — BACnet Discovery ✅ Shipped
- `nmcp.bacnet.discover` — read-only; returns all devices in the BACnet stack's in-memory registry (WhoIs/IAm-heard), including devices not yet provisioned as station components; uses `BBacnetNetwork.getDeviceList()`
- `nmcp.bacnet.devices` — rewritten from TODO stub to real implementation; traverses `getChildComponents()` filtered to `BBacnetDevice` instances
- 5 new BACnet type stubs (`BBacnetNetwork`, `BBacnetDevice`, `BBacnetAddress`, `BBacnetObjectIdentifier`, `BBacnetOctetString`)
- 36 tools total; 185 unit tests, 0 failures

### v0.7.0 — Version Check + Hidden Properties ✅ Done
- Niagara 4.13+ EULA warning: service logs this warning on startup for platform versions >= 4.13: "EULA of the version 4.13 and greater forbids use of AI, see Section 3.1(q) for details."
- `readOnly` property marked `Flags.HIDDEN` for cleaner Workbench UI (still configurable programmatically)
- Version detection now logs the detected Niagara platform version and warns if version detection or parsing fails
- 36 tools total; 185 unit tests, 0 failures

---

## Guiding Constraints (Ongoing)

- Write operations stay gated behind `BMcpService.readOnly=false`. Default is `true` (fail-closed).
- Every ORD argument validated against allowlisted roots before any read or write.
- Sensitive-slot masking (`password`, `secret`, `token`, `key`, `credential`, `auth`) applies everywhere.
- BQL: SELECT-only. Mutation keywords cause immediate rejection.
- Prefer incremental releases with tests at each milestone.

---

### v0.8.1 — Autopilot Hardening + Write Gate Centralization ✅ Done
- Deterministic structured validation errors for wiresheet plan/diff/apply (`code`, `message`, `path`, `hint`, `allowedValues`)
- Added `nmcp.wiresheet.schema` for operation-shape introspection and one-retry client self-correction
- Runtime `readOnly` gate now updates live through a mutable NiagaraSecurity policy propagated from BMcpService
- Component allowlist failures now return structured security payloads (no fallback to unknown error)

### v0.8.2 — Runtime BQL + History Provisioning + Search Filtering ✅ Done
- `nmcp.bql.query` replaced TODO scaffolding with runtime BQL execution and reflective fallback diagnostics
- `nmcp.history.provisionOnPoint` hardened for Niagara 4.15 history DB APIs with connection-based `createHistory(BHistoryConfig)` fallback and live-verified creation behavior
- `nmcp.component.search` filtering normalized (`trim` + case-insensitive matching) and type filtering expanded to support qualified type expressions (for example `control:numeric`)

### v0.8.3 — BACnet Runtime Hardening + Restart Workflow ✅ Done
- `nmcp.bacnet.devices` and `nmcp.bacnet.discover` now catch runtime linkage/classloading failures and return structured MCP errors instead of servlet-level HTTP 500 responses.
- BACnet ORDs are normalized before allowlist checks for consistency with other tool paths.
- Added restart helper workflow (`temp/restart_and_wait.py`) to fire the sandbox one-shot restart, wait 60 seconds, and poll `nmcp.station.info` for recovery.

### v0.8.4 — Wiresheet Layout + Text Blocks ✅ Done
- Added `nmcp.wiresheet.layout` as a separate readability/layout tool with dry-run-by-default behavior.
- Runtime layout now uses size-aware rectangle placement, visible-slot-driven height estimation, and overlap avoidance for live wiresheet organization.
- `wsAnnotation` persistence now follows the Workbench controller add-or-set path, and `baja:TextBlock` creation is supported through the runtime wiresheet text block type.

### v0.8.5 — Audit Log Agent Identity ✅ Current
- `X-MCP-Agent` request header carries the MCP agent/client identity for every `tools/call`.
- Agent identity is sanitized server-side before logging or audit emission.
- Application Director lines now show `user=<agent>` instead of `user=unknown`.
- Niagara Audit Log receives `AuditEvent.INVOKED` entries through `station:|slot:/Services/AuditHistoryService`, with the tool name as the audited slot and the agent as `userName`.
- Proxy gains `--agent` so local MCP clients can opt into explicit audit identity without changing token format.

---

## v0.9.0 — Object Model Enrichment (Next)

**Goal:** Give MCP agents/clients richer, more structured component data without requiring them to know internal slot names.

| Tool | Purpose |
|---|---|
| `nmcp.component.readDetailed` | Full component envelope: facets, units, flags, parent ORD, child count |
| `nmcp.component.batchRead` | Read multiple ORDs in one call; essential for dashboard-style queries |
| `nmcp.component.ancestors` | Walk up the slot tree to the root; answers "where does this point live?" |
| `nmcp.component.descendants` | Walk down with configurable depth + count limits |
| `nmcp.point.batchRead` | Read live values for a list of ORDs in one call |
| `nmcp.network.status` | Per-network health: enabled state, fault cause, device count, comms errors |
| `nmcp.alarm.history` | Query alarm records over a time window (complements `alarm.active`) |
| `nmcp.tag.query` | Query by Haystack/custom semantic tags — for stations using tag-based navigation |

**Definition of done per tool:**
- Tests covering security checks, masking, and allowlist enforcement
- README and TOOLS_REFERENCE updated
- Manual verification from MCP client (`tools/list` + representative call)

---

## Longer-Term (v0.8.1+)

- `nmcp.bacnet.virtualObjects` — nav-tree traversal for BACnet virtual objects (see note below)
- Graph-style relationship traversal (`nmcp.component.relationships`)
- Integration tests at scale (deep trees, large driver networks, concurrent requests)
- Sample LLM prompt workflows for common operator scenarios

---

## Known Limitations

### BACnet Virtual Object Access
BACnet virtual objects (e.g. `analogInput_1` under `/Virtual`) are **not accessible via `station:|slot:/...` ORDs**. They are stored as dynamic nav-tree children inside `BacnetVirtualGateway` and are only exposed through Niagara's `BINavNode`/`NavContext` nav framework.

These objects carry BACnet data not on the proxy point: `eventState`, `statusFlags`, `reliability`, `ackedTransitions`, alarm limits, and manufacturer proprietary properties.

**What's needed:** Nav-based traversal using `BINavNode`/`NavContext` stubs and a new `nmcp.bacnet.virtualObjects` tool that walks the nav tree instead of the component slot tree.

**Impact:** Medium — only relevant for stations that use BACnet virtual objects for alarm/event access rather than standard proxy points.


