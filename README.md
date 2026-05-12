<!-- Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License. -->
# ⚠️ USE AT YOUR OWN RISK

This software is provided "AS IS", without any warranty. 

The authors, contributors, and copyright holders are not liable for any damages or liability arising from the use of this software.

This tool interacts with real building automation systems. Improper use can cause mechanical systems to behave unexpectedly. 

**This is not for a production environment.**

**This is for educational, testing, and development use only.**

**No compiled jar is distributed yet. Build from source only. Any compiled jar must not be redistributed.**

**This is a call for developers who want to participate.** A compiled release will be made available at version 1.0.

**Niagara EULA Notice — Version Scope**

This project (nMCP) is intended for use with **Niagara versions prior to 4.13 only**.

Tridium's EULA Section 3.1(q), which restricts the use of AI tools in connection with the Niagara Framework, was introduced in Niagara 4.13. Users running Niagara 4.13 or later must review their EULA before using this module; such use may not be permitted.

Users of this module are fully responsible for ensuring their use complies with Tridium's End User License Agreement (EULA).

See [NOTICES.md](NOTICES.md) for full details.

**nMCP** is an independent open-source project and is **not affiliated with, endorsed by, or supported by Tridium Inc.**

---
# nMCP Module

![Niagara](https://img.shields.io/badge/Niagara-prior%20to%204.13-blue)
![Version](https://img.shields.io/badge/version-0.8.0-orange)
![MCP](https://img.shields.io/badge/nmcp-JSON--RPC%202.0-0A7CFF)
![Write Gated](https://img.shields.io/badge/Safety-Write--Gated-success)
![Claude Validated](https://img.shields.io/badge/Claude-Validated-7B61FF)
![License](https://img.shields.io/badge/License-MIT-green)

A Niagara custom module (for stations prior to version 4.13) that exposes station data (read-mostly, write-gated)
through the
[Model Context Protocol (MCP)](https://modelcontextprotocol.io/specification/2025-03-26).

MCP-compatible clients like [nMCP-client](https://github.com/makeitworkok/nMCP-client) (or Claude Desktop, VS Code Copilot, etc.) can discover
and call tools that introspect the live station with explicit write gates and
allowlist enforcement.

> **Trademark Notice:** Niagara Framework® and JACE® are registered trademarks of Tridium Inc.
> This project is not affiliated with or endorsed by Tridium Inc.

---

## My Why

> I build to amplify people, not replace them.
> I automate repetition so human judgment can focus on design, commissioning, and real problem-solving.
> I treat safety as non-negotiable: explicit write gates, clear boundaries, and fail-closed defaults.
> I move fast, but verify in live systems, learn honestly, and document what reality teaches us.
> I believe AI in Niagara is no longer theoretical; it is practical, present, and ours to shape responsibly.
> 
> Why? 
> It's because I choose progress with accountability, and tools that elevate the craft.  - Chris Favre, April 30, 2026

---

## Documentation

| Document | Description |
|---|---|
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | Build, install, start proxy, verify end-to-end |
| [docs/TOOLS_REFERENCE.md](docs/TOOLS_REFERENCE.md) | All 36 tools with arguments, examples, and response shapes |
| [docs/NIAGARA_OBJECTS_ROADMAP.md](docs/NIAGARA_OBJECTS_ROADMAP.md) | Planned expansions (M1–M5) |
| [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md) | v0.4.0 through v0.8.0 implementation lessons and operational guidance |
| [nMCP-client](https://github.com/makeitworkok/nMCP-client) | Lightweight client for calling nMCP tools from scripts and apps |

---

## Status

| Area | Status |
|---|---|
| Live station integration | ✅ Validated |
| Claude MCP workflow | ✅ Validated |
| Write gating via `readOnly` selector | ✅ Enforced |
| Web auth proxy | ✅ Supported via companion client tooling |
| Unit tests | ✅ 185 tests, 0 failures |

---

## Architecture

```
Client (Claude Desktop / VS Code Copilot / curl / nMCP-client)
      └── Web auth proxy layer
        └── BMcpService  (BWebServlet at /nmcp)
              ├── McpJsonRpcHandler       JSON-RPC 2.0 dispatcher
              ├── McpToolRegistry         tool name → handler map
              ├── NiagaraSecurity         allowlist & sensitive-slot masking
              ├── NiagaraComponentTools   station.info, component.read/children/slots
              ├── NiagaraBqlTools         bql.query (SELECT only)
              ├── NiagaraAlarmTools       alarm.query, alarm.active, alarm.ack (write-gated)
              ├── NiagaraHistoryTools     history.list, history.read, trend.summary
              ├── NiagaraPointTools       point.read, point.search
              ├── NiagaraEquipmentTools   equipment.status
              ├── NiagaraScheduleTools    schedule.read, schedule.list, schedule.write (write-gated)
              ├── NiagaraFaultScanTool    fault.scan
              ├── NiagaraBuildingBriefTool building.brief
              ├── NiagaraHaystackTools    haystack.getRuleset/setRuleset/applyRules/scanPoints/suggestTags
              ├── NiagaraWiresheetTools   wiresheet.plan/diff/apply/links + addCompositePin
              ├── NiagaraWriteTools       point.write, point.override, component.invokeAction, station.restart, driver.discoverAndAdd (write-gated)
              ├── NiagaraBacnetTools      bacnet.devices, bacnet.discover
              └── NiagaraJson             zero-dependency JSON builder
```

---

## Quick Start

Prefer using the companion client for day-to-day tool calls:
- Repository: [nMCP-client](https://github.com/makeitworkok/nMCP-client)
- Usage guide: [nMCP-client README](https://github.com/makeitworkok/nMCP-client#readme)
- Example calls: [nMCP-client examples](https://github.com/makeitworkok/nMCP-client/tree/main/examples)

Typical flow:
1. Deploy this module (`nMCP`) to the station and add `BMcpService`.
2. Start your Niagara web-auth proxy using the companion client setup guide.
3. Use `nMCP-client` to call `tools/list` and `tools/call` against the proxy endpoint.

```powershell
# 1. Build
java -jar gradle\wrapper\gradle-wrapper.jar clean jar

# 2. Deploy jar to Niagara modules directory, add BMcpService to station Services

# 3. Start auth proxy (see nMCP-client README for the exact command)
# https://github.com/makeitworkok/nMCP-client#readme

# 4. Verify
curl -X POST http://127.0.0.1:8765/nmcp -H "X-MCP-Token: <your-token>" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

See [docs/QUICKSTART.md](docs/QUICKSTART.md) for the full walkthrough.

---

## Tools Summary

| Tool | Category | Description |
|---|---|---|
| `nmcp.station.info` | Station | Station name, platform version, read-only flag |
| `nmcp.component.read` | Component | Read component slots by ORD |
| `nmcp.component.children` | Component | List immediate children of an ORD |
| `nmcp.component.slots` | Component | List all slots (sensitive values masked) |
| `nmcp.component.search` | Component | Search components by name/type under a root |
| `nmcp.bql.query` | BQL | SELECT-only BQL query |
| `nmcp.alarm.query` | Alarms | Recent alarm records (all states) |
| `nmcp.alarm.active` | Alarms | Currently active / unacknowledged alarms |
| `nmcp.alarm.ack` | Alarms | Acknowledge alarms by source ORD (write-gated) |
| `nmcp.history.list` | History | Available history IDs and display names |
| `nmcp.history.read` | History | Time-series records for a history by ID |
| `nmcp.trend.summary` | History | Aggregated trend min/max/first/last over a time window |
| `nmcp.point.read` | Points | Current value of a single proxy point |
| `nmcp.point.search` | Points | Find points by name or type substring |
| `nmcp.equipment.status` | Equipment | All devices across all driver networks |
| `nmcp.schedule.read` | Schedules | Current state + next transition for one schedule |
| `nmcp.schedule.list` | Schedules | All schedules with current occupancy state |
| `nmcp.schedule.write` | Schedules | Set default output state on a schedule (write-gated) |
| `nmcp.fault.scan` | Diagnostics | Summarize fault/stale/overridden points |
| `nmcp.building.brief` | Diagnostics | Morning-briefing synthesis across alarms/faults/equipment |
| `nmcp.bacnet.devices` | BACnet | BACnet devices provisioned under a network ORD |
| `nmcp.bacnet.discover` | BACnet | All devices heard on the BACnet stack (read-only, does not add to station) |
| `nmcp.haystack.getRuleset` | Haystack | Read tagging ruleset file |
| `nmcp.haystack.setRuleset` | Haystack | Write tagging ruleset file (write mode required) |
| `nmcp.haystack.applyRules` | Haystack | Apply haystack tags from ruleset (write mode required) |
| `nmcp.haystack.scanPoints` | Haystack | Discover points and report existing haystack tags |
| `nmcp.haystack.suggestTags` | Haystack | Suggest haystack tags for points + generate ready-to-use ruleset |
| `nmcp.wiresheet.plan` | Wiresheet | Validate and normalize declarative wiresheet operations |
| `nmcp.wiresheet.diff` | Wiresheet | Deterministic desired-state diff for operation payloads |
| `nmcp.wiresheet.apply` | Wiresheet | Write-gated execution report with `dryRun` default true |
| `nmcp.wiresheet.links` | Wiresheet | Inspect runtime links for a component/slot (diagnostic) |
| `nmcp.point.write` | Write | Write a value to a writable point at operator priority (write-gated) |
| `nmcp.point.override` | Write | Override a point at priority 8 (write-gated) |
| `nmcp.component.invokeAction` | Write | Invoke a named action on a component (write-gated) |
| `nmcp.station.restart` | Write | Request a controlled station restart (write-gated) |
| `nmcp.driver.discoverAndAdd` | Write | Trigger driver network discovery (write-gated) |

Total tools in v0.8.0: 36.

Full argument and response documentation: [docs/TOOLS_REFERENCE.md](docs/TOOLS_REFERENCE.md)

---

## Building

Requires Java 8 or 11. No Niagara SDK installation needed — the project compiles
against included stubs.

```powershell
# Run unit tests (185 tests, no Niagara runtime required)
java -jar gradle\wrapper\gradle-wrapper.jar test

# Build JAR
java -jar gradle\wrapper\gradle-wrapper.jar jar
```

Output: `build/libs/nMCP.jar`

### Building against a real Niagara SDK

Replace `compileOnly project(':stubs')` in the Gradle build configuration with the actual Niagara
JAR paths:

```groovy
compileOnly fileTree(dir: System.getenv('NIAGARA_HOME') + '/lib', include: '*.jar')
```

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable / disable the MCP endpoint |
| `endpointPath` | `/nmcp` | HTTP path |
| `readOnly` | `true` | Global write gate: `true` blocks write-capable tools; `false` enables them |
| `allowBql` | `true` | Permit BQL SELECT queries |
| `maxResults` | `500` | Maximum rows / items per call |
| `allowlistedRoots` | see below | Comma-separated ORD prefixes |

Default allowlisted roots:
- `station:|slot:/Drivers`
- `station:|slot:/Services`
- `station:|slot:/Config`
- Read/write mode is controlled by the BMcpService `readOnly` slot in Workbench (default `true`).

Any ORD that does not start with one of these roots is immediately rejected.

### Module Verification Mode (Development)

- Set `niagara.moduleVerificationMode=low` in `C:\Niagara\Niagara-<version>\defaults\system.properties` for development/community modules.
- `low` means warnings only (no enforcement).
- If blacklist enforcement for this property is inactive/commented out, the setting applies.
- This affects both station process and Workbench behavior.

### `readOnly` selector behavior

- `readOnly=true` (default): all write-capable tools fail closed.
- `readOnly=false`: write-capable tools may execute, but still require allowlist compliance and tool-level validations.
- This selector is the primary operational safety switch for mutation behavior.

---

## Security

1. **Read-mostly with explicit write gates** — write-capable tools require `readOnly=false` and enforce allowlists.
2. **Allowlisted roots** — every ORD argument is validated against configured prefixes.
3. **Sensitive-slot masking** — slots named `password`, `secret`, `token`, `key`, `credential`, `auth` return `***`.
4. **BQL SELECT-only** — mutation keywords (`SET`, `DELETE`, `INSERT`, `UPDATE`, `DROP`, …) cause immediate rejection.
5. **Result caps** — all queries honour `maxResults`.
6. **Fail-closed** — uncertain security checks deny access.

---

## Development History

| Phase | Status | Scope |
|---|---|---|
| v0.1 — Core | ✅ Done | Module, HTTP endpoint, JSON-RPC, component tools, BQL, alarm/history/bacnet stubs, web auth proxy |
| v0.2 — Operator Essentials | ✅ Done | alarm.active, history.read, point.read/search, equipment.status, schedule.read/list |
| v0.3 — Runtime API Fix + Synthesis | ✅ Done | alarm/history real API chain, trend.summary, fault.scan, building.brief, component.search, 110 tests |
| v0.4.0 — Wiresheet Runtime | ✅ Done | wiresheet.plan/diff/apply/links, deterministic link invocation, ORD metadata repair for Workbench navigation, support for Numeric/Boolean/Enum/String writables |
| v0.5.0 — kitControl + Facets | ✅ Done | kitControl:Add/Subtract/GreaterThan/LessThan/And/Or/Not creates, engineering-unit facets (degreesFahrenheit, precision, min/max, trueText/falseText), full LLM-driven thermostat (3 apply calls: 6 points + fallbacks + 4 logic blocks + 10 links) |
| v0.5.2 — kitControl Expansion + Fixes | ✅ Done | Full kitControl type set (32 types including LoopPoint, Counter, MinMaxAvg, Ramp, all switches/latches/selects/timers), setSlot named-setter fix for primitive slots (proportionalConstant etc.), BMuxSwitch exclusion, same-call async init rule documented, integration demo script used during validation |
| v0.6.0 — Haystack Tag Discovery | ✅ Done | `haystack.scanPoints` (discover points + report existing `h4:*` tags), `haystack.suggestTags` (heuristic tag suggestions + ready-to-use ruleset); corrected tag storage to `baja:Marker` type with `METADATA` flag, `h4$3a` slot prefix |
| v0.6.1 — Priority-Slot Writes | ✅ Done | `point.write` (in10, operator priority), `point.override` (in8, override priority), `point.write null` releases slot via `setStatusNull(true)` on `BStatusNumeric`; `component.invokeAction`, `station.restart`, `driver.discoverAndAdd`; `alarm.ack` (acknowledge by source ORD); `schedule.write` (set default output on `BWeeklySchedule`); 35 tools, 171 unit tests |
| v0.6.2 — BACnet Discovery | ✅ Done | `bacnet.discover` — read-only BACnet stack device registry via `BBacnetNetwork.getDeviceList()` (all WhoIs/IAm-heard devices, unprovisioned included); `bacnet.devices` rewritten from stub to real child-component traversal; 5 new BACnet stubs; 36 tools, 185 unit tests |
| v0.7.0 — Version Check + Hidden Properties | ✅ Done | Startup now logs detected Niagara version and emits warning text for 4.13+ stations: "EULA of the version 4.13 and greater forbids use of AI, see Section 3.1(q) for details."; `enabled` and `readOnly` hidden by default for cleaner Workbench UI; 36 tools, 185 unit tests |
| v0.8.0 — Slot Sheet Cleanup | ✅ Done | Cleaner Workbench slot sheet; 36 tools, 185 unit tests |
| v0.8+ — Roadmap | 🔜 Planned | Object model enrichment, batch read, relationship traversal — see roadmap |


---

## Contributing / Collaborators

Collaborators are welcome. If you work in building automation, AI tooling, or Niagara development and want to help shape this project, open an issue or reach out directly on GitHub.

**Ground rules for contributors:**
- This project is for **educational, testing, and development purposes only** — no production deployments
- **Do not distribute the compiled jar** — no release jar exists yet; recipients must build from source until v1.0
- All contributions must be compatible with **Niagara versions prior to 4.13** and respect Tridium's EULA
- Security-sensitive changes (auth, write gates, allowlist logic) require test coverage before merge
- Read [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md) before touching wiresheet or kitControl code

---

## License

[MIT License](LICENSE)


[def]: NOTICE