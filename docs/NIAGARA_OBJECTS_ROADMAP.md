<!-- Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License. -->
# nMCP Roadmap

**Current release: v0.8.0** — merged to `main`

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
- Full LLM-driven thermostat validation: 6 writable points + 4 kitControl logic blocks + 10 links across 3 apply calls
- License headers across all source files

### v0.5.2 — kitControl Expansion + Fixes ✅ Current
- Full kitControl type set: 32 types including `LoopPoint`, `Counter`, `MinMaxAvg`, `Ramp`, all switches (`NumericSwitch`, `BooleanSwitch`, `EnumSwitch`), latches, selects, timers, `Derivative`, `Modulus`, `SquareRoot`, `Negative`, `Limiter`, `Hysteresis`, `AbsValue`, `Line`
- `setSlot` named-setter fix: after generic method scan fails, tries `set + capitalize(slotName)` — resolves `proportionalConstant`, `integralConstant`, `derivativeConstant`, `countIncrement`
- `BMuxSwitch` excluded from type map — not instantiatable
- Same-call async init rule documented: `BNumericPoint`/`BBooleanPoint` subclasses (switches, LoopPoint, Counter) must be linked in a separate apply call from their creation
- Integration demo script (used during validation): 7-call sequence exercising all 32 kitControl types as a live integration test and LLM client reference

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

### v0.8.0 — Slot Sheet Cleanup ✅ Current
- Removed legacy `eulaBlockEnabled` and `eulaOverridePassphrase` declared properties from `BMcpService`
- Only `runtimeProfile` slot remains for the runtime compliance override path
- Cleaner Workbench slot sheet: `readOnly` and `runtimeProfile` are the only two configurable hidden properties
- 36 tools total; 185 unit tests, 0 failures

---

## v0.9.0 — Object Model Enrichment (Next)

**Goal:** Give LLM clients richer, more structured component data without requiring them to know internal slot names.

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

## Longer-Term (v0.8.0+)

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


