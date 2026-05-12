<!-- Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License. -->
# Lessons Learned

This document captures implementation and operational lessons accumulated across v0.4.0, v0.5.0, v0.5.1, v0.5.2, v0.6.x, v0.7.0, and v0.8.0.

---

## v0.4.0 — Wiresheet Runtime

v0.4.0 delivered a fully working, write-gated wiresheet runtime for Niagara stations:

- **`nmcp.wiresheet.plan`** — validates and dependency-sorts an operation list without touching the station.
- **`nmcp.wiresheet.diff`** — compares the current station graph against a desired operation set; returns creates/updates/linksToAdd/alreadyPresent.
- **`nmcp.wiresheet.apply`** — executes the operation list with per-step results; requires `readOnly=false` and allowlisted roots.
- **`nmcp.wiresheet.links`** — diagnostic tool for inspecting runtime links on a component; includes ORD metadata state.
- **Four writable point types supported:** `control:NumericWritable`, `control:BooleanWritable`, `control:EnumWritable`, `control:StringWritable`.
- **Deterministic link invocation** — uses explicit target-side `linkTo` per Tridium Javadoc; falls back to reflective scan only after preferred candidates fail.
- **ORD metadata repair** — `apply` repairs `endpointOrd`/`sourceOrd` relation fields on newly created links so Workbench "Goto Linked Component" works immediately.
- **`local:|foxwss:|` ORD normalization** — strips client-side ORD prefixes before allowlist check and `BOrd.make` resolution.

---

## 1. Link APIs are directional and easy to misuse

The most expensive debugging problem in v0.4.0 was links that appeared to work (values propagated) but were actually inverted — the source and target sides were swapped.

**Root cause sequence:**
1. Initial reflection scan used broad `linkArgCandidates` that included permutations where the slot arguments were swapped.
2. Niagara accepted these swapped calls in some cases (no exception), but the resulting `BLink` had the wrong direction.
3. Values seemed to propagate (coincidental direction match or bidirectional component behavior) which masked the bug.
4. Workbench link arrows pointed the wrong way; "Goto Linked Component" navigated to the wrong end.

**Fix:** Read the Tridium Javadoc explicitly. The documented method for programmatic linking is target-side: call `linkTo(sourceComponent, sourceSlot, targetSlot)` on the **target** component. Implemented `tryInvokePreferredTargetLinkWithDiagnostics()` which tries this signature first before falling back to the broader reflective scan.

**Indicators that confirmed the fix:**
- `matchedCandidateIndex: 100` — preferred candidate range (index ≥ 100 = explicit preferred method)
- `matchedSignature` showed `linkTo(BComponent, Slot, Slot)` or `linkTo(String, BComponent, Slot, Slot)` on the target component
- Workbench arrows pointed in the correct direction after repair

**Key rule:** Broad reflection fallback is valuable for resilience but must never be the *first* attempt for a well-documented API. Always implement the explicit documented path first, reflection second.

---

## 2. Runtime diagnostics are essential for reflective APIs

Without rich per-invocation diagnostics in `apply` step results, overload resolution bugs in reflective APIs are nearly impossible to debug remotely.

**Diagnostic fields added to link step results:**
- `matchedMethod` — name of the Java method that was invoked
- `matchedSignature` — full method signature (name + parameter types)
- `matchedCandidateIndex` — position in the candidate list (≥ 100 = preferred explicit method, < 100 = reflective fallback)
- `matchedArgumentCount` — number of arguments resolved
- `matchedOnObject` — which object the method was invoked on (source vs target component)
- `matchedArgs` — serialized argument list that was passed
- `ordFix` — summary of ORD metadata repair actions (`checked:N,fixed:N`)
- `ordStateAfter` — state of link ORD metadata fields after repair

**Practice:** Add diagnostic fields to the result contract *before* you need them. They cost nothing at runtime and are invaluable when debugging against a live station where you cannot attach a debugger.

---

## 3. ORD prefix normalization must be consistent

Clients may send ORDs in multiple formats:
- `station:|slot:/Drivers/...` (canonical)
- `local:|station:|slot:/...` (WebService client shorthand)
- `foxwss:|station:|slot:/...` (Fox WebSocket transport prefix)
- `local:|foxwss:|station:|slot:/...` (combined)

**Rule:** Normalize to `station:|slot:/...` before:
1. Allowlist comparison
2. `BOrd.make()` resolution
3. Diagnostic/logging output

Apply this consistently across all read/write paths. A mismatch between what the allowlist checks and what `BOrd.make` resolves causes silent allow-then-fail behavior that is hard to trace.

---

## 4. Workbench "Goto Linked Component" failure is a metadata problem, not a link problem

A link can successfully propagate values (data flows correctly) while still failing Workbench navigation. These are separate concerns.

**How it works:** Niagara links have runtime data-propagation behavior (slots connected) and separately carry relation ORD metadata fields (`endpointOrd`, `sourceOrd`) that Workbench uses for UI navigation. These fields are *not* set automatically by all link creation paths.

**The sentinel value trap:** The `endpointOrd`/`sourceOrd` fields are never Java `null` — they contain a Niagara null-ORD sentinel string. Repair logic written as `if (ord == null)` silently skips the repair. The fix was `isOrdUnset()` which treats both Java `null` and Niagara null-ORD string representations as unset.

**Repair pattern:**
```java
private boolean isOrdUnset(BOrd ord) {
    if (ord == null) return true;
    String s = ord.toString();
    return s.isEmpty() || s.equals("null") || s.equals("n:") || s.startsWith("null:");
}
```

**Practice:** After creating a link programmatically, immediately walk `getLinks()` on the target component and repair any unset ORD metadata using the component handle ORD.

---

## 5. Relation metadata repair should be minimal and guarded

- Repair only unset fields. Never overwrite non-null metadata.
- Prefer component handle ORD (from `getHandle().getOrd()`) over constructing ORD strings.
- When repairing `endpointOrd`, use the target component ORD. When repairing `sourceOrd`, use the source component ORD.
- Log repair counts (`checked:N,fixed:N`) in step results for observability.

---

## 6. Write support needs a single explicit safety switch

- `BMcpService.readOnly` is the primary write gate. `readOnly=true` (default) fails closed for all write-capable tools.
- `readOnly=false` enables write-capable tools but allowlists and tool-level validation still apply independently.
- Separating the write gate (readOnly) from the allowlist (per-tool) prevents either from becoming a single point of failure.

---

## 7. Runtime create support is a mapping problem as much as an API problem

Linking logic can be entirely correct while `createComponent` fails because the type mapping is incomplete. These failures are silent until you actually test `createComponent` for each type.

**v0.4.0 required explicit mappings for all four writable types:**
| User-facing type string | Java class name |
|---|---|
| `control:NumericWritable` | `javax.baja.control.BNumericWritable` |
| `control:BooleanWritable` | `javax.baja.control.BBooleanWritable` |
| `control:EnumWritable` | `javax.baja.control.BEnumWritable` |
| `control:StringWritable` | `javax.baja.control.BStringWritable` |

`StringWritable` was the last discovered gap — it was not in the initial type mapping and had to be added after observing create failures for string points.

**Rule:** Write a test or at minimum a smoke-call for every type you claim to support. "Link works" does not imply "create works."

---

## 8. Read the Javadoc before writing reflection code

The link direction bug was resolved by reading the Tridium Javadoc for `BComponent.linkTo`. The documented pattern was unambiguous: call on the target, pass source component and source slot. The correct invocation was sitting in the docs the entire time.

**Practice:** For Niagara API calls, especially those involving component relationships, always verify against the Javadoc before writing reflection-based code. The API is large and has many similar-sounding methods with different invocation sides and argument conventions.

---

## 9. Deployment discipline matters

On this station layout, the module must be copied to **both** active locations:
- `C:\Niagara\Niagara-<version>\modules\nMCP.jar` (Workbench/system modules directory)
- `C:\Users\makeitworkok\Niagara4.x\vykon\stations\mcp\sw\nMCP.jar` (station runtime directory)

Copying to only one location results in either Workbench or the station running old code — this has caused multiple debugging sessions where code changes appeared to have no effect. Station restart is required after deployment.

---

## 10. Keep validation and mutation paths separate

- `plan` and `diff` must remain deterministic and side-effect free. Never introduce writes into these paths.
- `apply` is the only mutation path; it must be write-gated and return per-step execution details including failures.
- Keeping these separate means the plan/diff tools can be used freely (even by read-only clients) without risk.

---

## 11. Documentation must track behavior, not intent

- Remove "preview/scaffold" or "stubbed" language as soon as runtime behavior is live.
- Keep README, quickstart, and tools reference aligned with actual current-version behavior.
- Version stamps on docs prevent confusion about which capability belongs to which release.

---

## 12. Module verification mode affects both station and Workbench

For development and community modules without Tridium signing:
- Set `niagara.moduleVerificationMode=low` in `C:\Niagara\Niagara-<version>\defaults\system.properties`.
- `low` means warnings only — no loading enforcement.
- This setting must be applied before starting the station and before launching Workbench. It affects both processes.
- Without this, the station will refuse to load unsigned modules and the error message may not clearly identify the cause.
- If blacklist enforcement for this property is inactive/commented out, the setting applies.

---

## v0.5.0 — kitControl, Facets, and Composite Pins

## 13. kitControl types require an explicit module dependency


- `kitControl:Add`, `kitControl:GreaterThan`, etc. live in the `kitControl-rt` module, which is NOT loaded by default just because it is installed.
- Without `<dependency name="kitControl-rt">` in the module descriptor, the classloader cannot find `com.tridium.kitControl.*` types at runtime even if the module is present.
- Symptom: `ClassNotFoundException` or silent `null` from type registry lookups.

## 13. Engineering unit facets require `BFacets` reflective invocation

- `BFacets` has no public constructor for direct instantiation. Use `BFacets.make(BSimpleSet)` or the named-field `make(String...)` approach via reflection.
- Units are applied by setting `units`, `precision`, `min`, `max` (NumericWritable) or `trueText`, `falseText` (BooleanWritable).
- Facets must be applied after the component is added to the parent, using the component's slot reference.

## 14. `baja:Folder` IS the composite — there is no separate `BComposite` type

- In Niagara, a `BFolder` acts as its own composite boundary. Dynamic pin slots are added directly to the folder component.
- The API: `BComponent.add(String pinName, BValue typeInstance, int flags, BFacets.NULL, Context)`.
- Output pins (direction `out`): add a read-only slot (flags `1`) to the folder; add a `BLink(targetComp.getHandleOrd(), targetSlot, pinName, true)` to the folder.
- Input pins (direction `in`): add a writable slot (flags `0`) to the folder; add a `BLink(folder.getHandleOrd(), pinName, targetSlot, true)` to the target component.

## 15. Composite pin names share the slot namespace with child component names

- In Niagara's slot model, adding a child component named `SpaceTemperature` occupies the slot name `SpaceTemperature` on the parent.
- Attempting to add a composite pin with the same name as an existing child results in `DuplicateSlotException`.
- Use pin names that describe the interface role, not the internal implementation: `TemperatureIn` rather than `SpaceTemperature`; `CoolDemand` rather than `CoolOutput`.

## 16. `BComponent.getHandleOrd()` is required for stable BLink construction

- `BLink(BOrd source, String sourceSlot, String destSlot, boolean)` needs a stable component-handle ORD, not a path-based ORD.
- `getHandleOrd()` returns this handle ORD. It was absent from the compile stubs and must be added explicitly.

---

## v0.6.1 — Priority-Slot Writes on BNumericWritable

### 17. `BStatusNumeric` is in `javax.baja.status`, not `javax.baja.control`

The correct package for `BStatusNumeric` (and `BStatusBoolean`) is **`javax.baja.status`**, found in `baja.jar`. An earlier attempt used `javax.baja.control.BStatusNumeric`, which does not exist — `Class.forName()` returned a `ClassNotFoundException` at runtime, causing all priority-slot writes to silently fall through.

**Confirmed class locations (baja.jar):**
- `javax.baja.status.BStatusNumeric` — constructor `BStatusNumeric(double)`, no `make()` factory
- `javax.baja.status.BStatusBoolean` — constructor `BStatusBoolean(boolean)`, no `make()` factory

```java
// Correct: constructor, not factory
Class<?> cls = Class.forName("javax.baja.status.BStatusNumeric");
Object sn = cls.getConstructor(double.class).newInstance(42.0);
```

### 18. Named setters on `BNumericWritable` are the correct write path

`BNumericWritable` (in `control-rt.jar`, package `javax.baja.control`) exposes explicit typed setters for each priority input:

```
public void setIn1(BStatusNumeric)
public void setIn8(BStatusNumeric)   // operator override priority
public void setIn10(BStatusNumeric)  // standard operator write priority
...
public void setIn16(BStatusNumeric)
```

The reflective strategy `set + capitalize(slotName)` (e.g. `setIn10`) correctly resolves these via `getClass().getMethods()` and `m.invoke(point, bval)`. This is the same pattern used for `setProportionalConstant` etc. in kitControl — it works universally for typed named setters.

### 19. Release a priority input via `setStatusNull(true)` on a null-constructed `BStatusNumeric`

To release (clear) a priority slot, do not pass `null` to `setIn10` — that will throw. Instead:

1. Construct `new BStatusNumeric()` (no-arg constructor)
2. Call `setStatusNull(true)` on it — this method is on `BStatusValue` (parent class) and is visible via `getMethod` upward lookup
3. Pass this null-status instance to `setIn10`

```java
Class<?> cls = Class.forName("javax.baja.status.BStatusNumeric");
Object obj = cls.getConstructor().newInstance();
cls.getMethod("setStatusNull", boolean.class).invoke(obj, true);
// now pass obj to setIn10 — slot is released at that priority level
```

This is how Niagara represents "no value at this priority" in the writable point's priority array.

### 20. Use `javap` against `baja.jar` to verify status type APIs

When debugging Niagara status/value types, inspect `baja.jar` not `control-rt.jar`:

```powershell
$javap = "C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot\bin\javap.exe"
& $javap -classpath "C:\Niagara\Niagara-<version>\modules\baja.jar" -p "javax.baja.status.BStatusNumeric"
```

Use the Eclipse Adoptium JDK's `javap` — the Niagara JRE does not include it.
- Using a path ORD instead of a handle ORD in a BLink produces links that do not resolve correctly in Workbench.

## 21. Reflective add() overload resolution needs type-relaxed guards

- `BComponent.add()` has multiple overloads. Matching by strict `isInstance` of parameter type fails when the classloader boundary between stubs and runtime types prevents `isAssignableFrom` from returning `true`.
- Guard only on the unambiguous parameters (String first, int/Integer third) and let the reflective invocation surface the actual exception if the types are wrong.
- Capture and report the `addError` in step results to distinguish "wrong overload" from "API error" during diagnosis.

---

## v0.7.0 — Rebranding + Servlet Consolidation

### 22. Gradle archiveFileName must be explicit to override rootProject.name fallback

During the v0.7.0 rebranding from `niagaraMcp` → `nMCP`, the Gradle build configuration was updated to:
```gradle
archiveBaseName = 'nMCP'
archiveVersion = ''
```

However, Gradle 7.6.4 was still producing `build/libs/niagaraMcp.jar` because when `archiveBaseName` alone is set with empty version, Gradle falls back to `rootProject.name` from the project settings. The fix was explicit:

```gradle
jar {
  archiveBaseName = 'nMCP'
  archiveVersion = ''
  archiveFileName = 'nMCP.jar'  // Explicit override prevents fallback
}
```

**Rule:** When changing artifact naming in Gradle, always set `archiveFileName` explicitly to prevent Gradle's implicit fallback to `rootProject.name`. Verify the output with `jar tf build/libs/nMCP.jar` to confirm the module name inside the manifest.

### 23. Servlet endpoint consolidation requires consistent client-side URL updates

In v0.7.0, the servlet endpoint was moved from `/mcp` to `/nmcp` to align the module name and endpoint path. This is a breaking change for existing client configurations:

**What changed:**
- Station endpoint: `/mcp` → `/nmcp` (e.g., `http://station/nmcp`)
- Tool URI scheme: `mcp://` → `nmcp://` (used internally in JSON responses)
- Proxy layer remains named as-is but listens on `/nmcp` and forwards to `{base}/nmcp`

**What must be updated by users:**
1. **Claude Desktop config** (`%APPDATA%\Claude\claude_desktop_config.json`): Change `"url"` from `/mcp` to `/nmcp`
2. **VS Code Copilot MCP settings** (`%APPDATA%\Code\User\settings.json` or workspace `.code/mcp.json`): Update URL accordingly
3. **Custom client scripts**: Any hardcoded endpoint URLs pointing to `/mcp` must change to `/nmcp`
4. **Proxy startup**: The proxy auto-discovers the backend token from `GET /nmcp` and forwards all requests to `/nmcp`

**Deployment checklist after rebuild:**
1. Copy rebuilt `nMCP.jar` to both Niagara locations (Workbench modules and station runtime)
2. Delete old `niagaraMcp.jar` from both locations (may be file-locked; requires station/Workbench shutdown)
3. Restart station to reload modules
4. Update all client configs to reference `/nmcp`
5. Restart clients (Claude Desktop, VS Code, proxy)

This consolidation ensures the module name, artifact name, and endpoint path are all consistently named `nMCP` / `nmcp`.

---

## v0.5.1 — kitControl Expansion + setSlot Named-Setter Fix

## 18. Control-point types (BNumericPoint/BBooleanPoint) cannot be linked within the same apply call that creates them

- `BNumericPoint` and `BBooleanPoint` subclasses (kitControl switches, `BLoopPoint`) undergo asynchronous initialization after being added to the component tree.
- `BOrd.make(compOrd).get(null, cx)` returns a non-`BComponent` result for these types until that initialization completes.
- Symptom: `link` operations in the same apply call as the `createComponent` fail with `"source or target ORD is not a component"`.
- Pure `BComponent` subclasses (`BLatch`, `BQuadMath`, `BDecaInputNumeric`, etc.) are immediately resolvable within the same call.
- **Fix:** Put `createComponent` in one apply call and the corresponding `link` operations in a subsequent apply call. The round-trip through the HTTP endpoint gives Niagara time to finish registering the control-point component.
- Idempotent re-runs of the combined script handle this automatically: creates are skipped, links are applied.

## 19. `setSlot` for primitive-typed slots requires the named-setter path (`setXxx(value)`)

- The generic `invokeSlotSetter` code looks for methods named `set`, `setSlot`, or `write` on the component — it does NOT try the specific `setProportionalConstant(double)` / `setCountIncrement(float)` pattern.
- Slots backed by primitives (`double`, `float`, `int`) have specific Java-named setters but no generic `set(Slot, BObject)` equivalent that the generic path can reach.
- Symptom: `"no compatible runtime set method found for slot 'proportionalConstant'"` even though `BLoopPoint.setProportionalConstant(double)` exists.
- **Fix:** After the generic candidate loop fails, try `invokeSetter(component, "set" + capitalize(slot), value)`. `convertScalar` already handles `double`, `float`, and `int` targets correctly.
- Slots backed by complex types (`BStatusNumeric`, `BStatusBoolean`) may still fail the named-setter path if the value cannot be converted by `convertScalar`; these should be set in Workbench.

## 20. Slot names for kitControl types — bytecode-verified corrections

| Type | Wrong assumption | Correct slot |
|---|---|---|
| `BNot` | `inA` | `in` |
| `BHysteresis` | `inA`, `inB` | `in` (live input); `inLow`, `inHigh` are double setSlots |
| `BLimiter` | `inB` (min), `inC` (max) | `inA` (value input only); `inMin`, `inMax` are double setSlots |
| `BLine` | setSlot `inA`/`inB`/`inC`/`inD` via double | These are `BStatusNumeric` — not settable via scalar; configure in Workbench |
| `BMuxSwitch` | createComponent | Not instantiatable on this Niagara version — excluded from class map |

---

## v0.5.2 — Documentation and Release Hygiene

## 21. Surface implementation guidance so Claude Code instances read it before touching code

- `docs/LESSONS_LEARNED.md` was written as a retrospective but contains hard-won runtime facts (slot names, async init rules, API gotchas) that prevent costly re-discovery.
- Without a prominent pointer in the internal agent instructions file, new Claude Code instances defaulted to API guesswork and rediscovered the same bugs.
- **Fix:** Added a "Read First" section to the internal agent instructions immediately after the Project Overview; it explains the runtime failures and lists section headers so the reader knows the content without navigating away.
- Four highest-impact lessons were also pulled inline into CLAUDE.md as they apply across all tools, not just wiresheet.

## 22. Integration demo scripts serve as both live test and LLM client reference

- A dedicated integration demo script was created to exercise all 32 kitControl types against a live station in a 7-call sequence.
- A re-runnable script is more reliable than notes: it fails loudly when a type or slot assumption is wrong, and it becomes a reference for LLM clients on correct call structure.
- **Practice:** For each new batch of supported types, write a corresponding demo script. Put creates and links in separate calls for control-point types (BNumericPoint/BBooleanPoint subclasses) to avoid async init failures.

## 23. Module version string is the authoritative version indicator for MCP clients

- `BMcpService.MODULE_VERSION` is returned in every `initialize` and `station.info` response.
- If this string is out of sync with the Gradle project version and module descriptor version, MCP clients cannot tell which release they are talking to.
- **Practice:** When cutting a release branch, update `MODULE_VERSION`, the Gradle project version, and all module descriptor version fields atomically — and then update all documentation files together. Don't treat these as independent changes.

---

## v0.6.0 — Project Haystack Tag Storage

**Delivered:** `nmcp.haystack.scanPoints`, `nmcp.haystack.suggestTags`; corrected tag slot
format to match native Niagara haystack conventions (`h4:*` marker slots, `baja:Marker` type,
`METADATA` flag).

## 24. Niagara encodes `:` as `$3a` in slot names

- Haystack tags follow the `h4:<tagName>` convention, but Niagara stores the slot internally with `:` URL-encoded as `$3a`, so `h4:temp` appears in the slot sheet as `h4$3atemp`.
- Using the literal `:` character in `add()`, `getProperty()`, or `get()` silently fails to find the slot.
- **Fix:** `HS_SLOT_PREFIX = "h4$3a"` — always use the encoded form in code; the decoded form (`h4:temp`) is only ever seen in Workbench UI.

## 25. Haystack marker tags must use `baja:Marker`, not `baja:String`

- `BString.make("m:")` produces a value that serializes correctly to JSON but shows the wrong type (`baja:String`) in Workbench's slot sheet and is not recognized by Niagara's native haystack module.
- The correct type for a Project Haystack marker tag is `baja:Marker`, written as `BMarker.DEFAULT`.
- **Rule:** Use `BMarker.DEFAULT` for every tag whose value is `"m:"`. For string-valued tags (e.g. a site ref), `BString.make(val)` is correct.

## 26. `comp.set()` only updates existing slots; `comp.add()` is required for new dynamic slots

- `BComponent.set(String slotName, BValue value)` silently does nothing if the named slot does not already exist — it is an update-only operation.
- To create a new dynamic slot, call `comp.add(String slotName, BValue value)`.
- **Pattern:** Check existence first — if `comp.getProperty(slotName) != null` then `set()`; else `add()`. Getting this wrong causes `applyRules` to complete without error but `scanPoints` to report zero tagged points.

## 27. Set `Flags.METADATA` immediately after `comp.add()`, using the returned Property

- Project Haystack tooling and Workbench's tag view both require slots to carry the `METADATA` flag (value `16384` in `javax.baja.sys.Flags`). Without it, slots are written but not recognized as haystack metadata by other tools.
- `comp.add()` returns a `Property` — capture it and immediately call `comp.setFlags(prop, Flags.METADATA)`.
- Apply the same flag on the existing-slot path: `comp.getProperty(slotName)` also returns a `Property` you can pass to `setFlags`.

## 28. `getSlotCursor()` returns null in some runtime contexts; use `getDynamicPropertiesArray()`

- `BComplex.getSlotCursor()` and `BComplex.properties()` can return null depending on Niagara version and component type.
- `getDynamicPropertiesArray()` (the array form, no cursor needed) is the reliable way to enumerate dynamic slots at runtime across Niagara 4 versions.
- **Guidance:** Always use the array form when iterating a component's dynamic properties in tool code.

## 29. Niagara security manager rejects relative file paths for ruleset storage

- A relative path like `"files/nmcp-haystack-rules.json"` triggers `java.io.FilePermission` denial at runtime because Niagara's security manager does not grant relative-path access to the station JVM process.
- **Fix:** Use an absolute path. `System.getProperty("java.io.tmpdir")` is the safe, cross-platform default (`C:\Windows\Temp` on Windows station hosts). This principle applies to any file I/O written inside MCP tool code.

---

## v0.7.0 — Version Check, Hidden Properties, and Startup Logging

## 30. `BStation.getNiagaraVersion()` does not exist on Niagara 4.15

- The method `getNiagaraVersion()` is not present on `BStation` at runtime on Niagara 4.15.1.16. Calling it directly will produce a `NoSuchMethodException` (reflective) or a compile error against the stubs.
- Live test confirmed: `nmcp.station.info` returns `"niagaraVersion": "unknown"` when this is the only detection path.
- **Fix:** Use a reflective fallback chain across multiple candidates. The working pattern:
  1. Try static `Sys.getVersion()` — platform-first reflective lookup
  2. Try static `Sys.getVersionString()` — platform-first fallback
  3. Try `station.getNiagaraVersion()` — reflective fallback for older stacks
  4. Try `station.getVersion()` — reflective station fallback
  5. If all return `null`/empty, log a warning that version could not be detected
- Use `tryInvokeToString(Object target, String methodName)` that calls `target.getClass().getMethod(methodName)` and returns `null` on any `Throwable` — this keeps the fallback chain clean and non-fatal.
- **Note:** The version string format, if/when a method does resolve, is expected to be `"4.15.1.16"` (dot-separated, 4 components). Use a non-digit split (`split("[^0-9]+")`) rather than `split("\\.")` to handle any variant safely.

## 31. `LOG.warning()` alone is not guaranteed to appear in the Niagara station console

- Niagara's station console log output is filtered by the logging configuration in `LoggingService`. `WARNING` level messages from application loggers may not show depending on the log handler config at runtime.
- Verified: adding `System.err.println(msg)` alongside `LOG.warning(msg)` ensures the message appears in the process stderr stream regardless of logging config.
- **Pattern for guaranteed-visible startup messages:**
  ```java
  LOG.warning(msg);       // captured by Niagara log infrastructure
  System.err.println(msg); // always visible in raw process output
  ```
- Use this dual-emit pattern only for high-importance startup notices (EULA compliance warnings, security-gate status). Do not use it for routine diagnostics.

## 32. `module.palette` stream-closed error on Workbench palette open

- Symptom: `javax.baja.sys.BajaRuntimeException` with nested `java.io.IOException: Stream closed` when opening the module palette in Workbench.
- The stack trace originates in `BModulePaletteNode.<init>` parsing the palette XML — the XML stream is closed before it is fully consumed.
- Root cause is typically a malformed or inconsistently encoded `module.palette` file (incorrect indentation relative to the root element, mixed whitespace, or encoding issues produced by editing tools).
- **Fix:** Rewrite `module.palette` as a cleanly indented, consistently encoded UTF-8 file. Canonical working format:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <bajaObjectGraph version="1.0">
    <p h="1" m="b=baja" t="b:UnrestrictedFolder">
      <p n="Services" t="b:UnrestrictedFolder">
        <p n="BMcpService" m="m=nMCP" t="m:McpService"/>
      </p>
    </p>
  </bajaObjectGraph>
  ```
- The type reference `t="m:McpService"` must exactly match the `name` attribute in the module descriptor `<type name="McpService">`. A mismatch causes the same stream-closed error at a different code path.

## 33. Use `Flags.HIDDEN` to hide component properties from the Workbench Config view

- Properties declared with `newProperty(0, ...)` (flags = 0) are visible in the Workbench Config view by default.
- To hide a property from the UI while keeping it accessible programmatically, declare it with `Flags.HIDDEN`:
  ```java
  public static final Property readOnly = newProperty(Flags.HIDDEN, BBoolean.TRUE, null);
  ```
- The property remains fully readable/writable via Niagara's property API and MCP tools — `Flags.HIDDEN` only suppresses the Workbench UI row.
- Requires `import javax.baja.sys.Flags;`
- The `enabled` field on `BMcpService` is a plain Java instance field (not a declared `Property`), so it has no Workbench row at all and does not need the HIDDEN flag.

## 34. Always emit a diagnostic log line regardless of the version-check outcome

- Version detection can silently fail (method not found, parse error, `null` station) in ways that look identical to "everything is fine" from the outside.
- **Pattern:** Always emit at least one INFO or WARNING log line from `checkNiagaraVersion()`, covering every branch:
  - Detected version → `LOG.info("BMcpService: detected Niagara platform version " + versionStr)`
  - Version >= 4.13 → `LOG.warning("EULA of the version 4.13 and greater forbids use of AI...")`
  - Unparseable version → `LOG.warning("Niagara platform version '" + raw + "' is not parseable...")`
  - Detection failed entirely → `LOG.warning("Niagara platform version could not be detected...")`
- Silent fallback (`catch (Throwable e) { /* ignore */ }`) is appropriate for individual reflective attempts inside the detection chain, but the outer method must always produce visible output.

## 35. Keep startup version detection and `nmcp.station.info` detection in lockstep

- `BMcpService.checkNiagaraVersion()` and `NiagaraComponentTools.stationInfo()` must use equivalent fallback behavior. If one path is simplified while the other still relies on deeper fallbacks, startup logs and tool output will diverge.
- Observed regression: startup detected platform version correctly, but `nmcp.station.info` returned `"platformVersion":"unknown"` because `/Services` tree fallback was missing in the tool path.
- **Fix:** Reintroduce the same final `/Services` scan fallback in `NiagaraComponentTools` that startup uses.
- **Practice:** Any change to version detection order or candidates must be applied to both paths in the same PR and validated with one live startup plus one live `nmcp.station.info` call.

## 36. `BSystemPlatformService` slot access is the most reliable source for host/system metadata

- `BSystemPlatformService` exposes `hostId`, `niagaraVersion`, and system summary slots (`currentCpuUsage`, `model`, `modelVersion`, `numCpus`, `osName`, `osVersion`, `overallCpuUsage`, `stationName`, `totalPhysicalMemory`) as readonly transient properties.
- In live stations, `BStation.getHostId()` may return unavailable/unknown while platform-service slot reads succeed.
- **Working pattern:**
  1. Try station fields first for compatibility.
  2. Fall back to platform service `get(slotName)` for missing values.
  3. If direct service ORDs fail, scan `/Services` recursively for platform-like components and probe slots there.
- Some slots can legitimately be `null` (for example `modelVersion` on generic workstation hosts); preserve `null` rather than forcing placeholder strings.

---

## v0.8.0 — Slot Sheet Cleanup

## 37. Removing legacy declared `Property` entries from `BMcpService` requires a clean slot-sheet migration

- `BMcpService` had two legacy declared properties (`eulaBlockEnabled`, `eulaOverridePassphrase`) retained as compatibility stubs to prevent Niagara from throwing `TypeIntrospectionException` on load when station XML still referenced those slot names.
- Once the station instance was refreshed (service removed and re-added from palette), those XML entries were gone and the compatibility stubs were safe to delete.
- **Removal steps:**
  1. Remove `newProperty(...)` declarations for the legacy slots.
  2. Remove all matching bean accessors (`get`, `is`, `set` methods) — Niagara introspection validates that every declared property has a getter.
  3. Remove any code paths that read or wrote those legacy slot names.
  4. Build, deploy, and restart. If startup succeeds without `TypeIntrospectionException`, the removal is clean.
- After removal the slot sheet only shows the minimal set: `readOnly` and `runtimeProfile` (both `Flags.HIDDEN`).

## 38. Niagara `TypeIntrospectionException` at station load = declared property without a matching getter

- If a `newProperty(...)` is declared as a static field but the corresponding `getXxx()` / `isXxx()` method is absent, Niagara's type system throws `TypeIntrospectionException` at class initialization — before `started()` is called.
- The station will fail to load the service component, not just emit a warning.
- **Rule:** Every `public static final Property foo = newProperty(...)` requires at minimum:
  ```java
  public <type> getFoo() { ... }
  public void   setFoo(<type> v) { ... }
  ```
- Bean convention: `boolean` properties also need `isFoo()` returning the same value as `getFoo()`.
- This applies equally to properties declared with `Flags.HIDDEN` — hidden means hidden from UI, not hidden from introspection.
