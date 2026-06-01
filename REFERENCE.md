# Mythosaur RE — Reference Manual

A complete reference to every feature, panel, menu, and workflow in **Mythosaur RE**,
a dedicated open-source **APK reverse-engineering IDE**.

This document is the *reference* (what each thing is and does). For a guided
walkthrough see **GUIDE.md**; for a quick overview see **README.md**.

---

## Table of contents

1. [What it is & scope](#1-what-it-is--scope)
2. [Architecture](#2-architecture)
3. [Install & launch](#3-install--launch)
4. [The workspace & reopening projects](#4-the-workspace--reopening-projects)
5. [Menus & shortcuts](#5-menus--shortcuts)
6. [Panels reference](#6-panels-reference)
7. [Workflows](#7-workflows)
8. [Files & directories](#8-files--directories)
9. [Limitations (honest)](#9-limitations-honest)

---

## 1. What it is & scope

Mythosaur RE unifies the Java, smali, native, and protection layers of an Android app
into one tool — think *Cutter / jadx-gui, unified*. It is a **static reverse-engineering
+ patching** tool, plus an optional **on-device debugger**.

**In scope:** decompilation, DEX structural analysis, cross-references, call/flow/CFG
graphs, string & native inspection, protection/packer detection, smali & full-APK
patching with re-signing, sandboxed method emulation, and JDWP smali debugging.

**Deliberately out of scope:** network/traffic interception, Frida/dynamic
instrumentation, automated vulnerability findings, and report generation. Mythosaur is
a reverse-engineering tool, not a pentest suite. It operates only on APKs you supply.

---

## 2. Architecture

The whole Android RE toolchain is JVM-native, so Mythosaur **embeds it as libraries**
and calls typed APIs directly instead of shelling out to CLIs and parsing text:

| Concern | Library / tool | Mode |
|---|---|---|
| Decompile DEX→Java | **jadx-core 1.5.2** | embedded library (`JadxDecompiler`) |
| Alternate decompilers | **CFR 0.152 · Vineflower 1.11 · Procyon 0.6** | embedded libraries (second opinion) |
| DEX→JAR bridge | **dex2jar 2.4.28** | embedded library (feeds the above) |
| DEX structural parse | **dexlib2 2.5.2** | embedded library |
| DEX↔smali | **baksmali / smali 2.5.2** | embedded library |
| Full decode (res+manifest) | **apktool 2.9.3** | bundled jar, auto-fetched |
| Align / sign / verify | `zipalign`, `apksigner` | CLI |
| Debug keystore | `keytool` | CLI (JDK) |
| Native `.so` parse | built-in ELF parser | pure Java |
| Native disassembly | arch-aware `objdump` / `llvm-objdump` | CLI (optional) |
| On-device debug | **JDI** (`jdk.jdi`) + `adb` | JDK module + CLI (optional) |

UI is Java Swing + FlatLaf; the code editor is RSyntaxTextArea; graphs are custom
Java2D. Heavy work runs on background `SwingWorker` threads with a 6 GB heap (G1GC) for
very large apps (40k+ classes).

---

## 3. Install & launch

**Requirements:** JDK 21+. Optional: `zipalign`, `apksigner` (patching), `adb`
(install/debug), and an arch-capable disassembler for native `.so` — `binutils-multiarch`,
`llvm`, or a per-arch `binutils-<arch>` (stock x86-only `objdump` can't do ARM/AArch64).

```bash
# build a runnable distribution
./gradlew installDist

# launch (optionally pass an APK to open immediately)
java -cp "build/install/mythosaur-jvm/lib/*" com.mythosaur.Main [app.apk]
# or
./build/install/mythosaur-jvm/bin/mythosaur-jvm [app.apk]
```

Flags: `-Dtheme=light` starts in the light theme. The first full-decode/rebuild fetches
`apktool_2.9.3.jar` (~23 MB) to `~/.mythosaur/tools/`.

---

## 4. The workspace & reopening projects

Every opened APK gets a deterministic workspace:

```
~/.mythosaur/workspaces/<apk-name>-<hash>/
```

The `<hash>` is derived from the APK's content, so **the same APK always maps to the
same workspace** — reopening it reuses your previous output. The workspace holds:

| Subfolder / file | Contents |
|---|---|
| `jadx-out/` | jadx decompile output |
| `smali-out/classesN/` | baksmali disassembly (editable) |
| `apktool-out/` | full apktool decode (smali + res + `AndroidManifest.xml`) |
| `build/` | rebuilt + signed APKs |
| `dex/` | extracted `classes*.dex` |
| `mythosaur.project` | descriptor: original APK path, mapping, name, timestamp |

### Reopening a project

There are three ways to come back to earlier work (**File** menu):

- **Open APK…** — pick the APK again (re-decompiles; reuses the same workspace).
- **Recent Projects ▸** — a live list of recently opened projects, newest first, shown
  with their last-opened time. Greyed out if the original APK moved.
- **Open Workspace…** — pick a workspace folder directly; Mythosaur reads its
  `mythosaur.project` descriptor and reopens.

On reopen, if the original APK has moved or been deleted, Mythosaur asks you to
**locate it**. When the project loads, any existing `smali-out/` or `apktool-out/`
output is **automatically restored** as the **Smali** / **apktool** left-pane tabs, so
your in-progress edits and rebuilt APKs are right where you left them.

> Decompilation itself is re-run on reopen (jadx works on live objects, not a cache),
> but your smali edits, full decodes, and patched APKs persist on disk in the workspace.

---

## 5. Menus & shortcuts

### File
| Item | Shortcut | Action |
|---|---|---|
| Open APK… | `Ctrl+O` | load an APK via jadx + dexlib2 |
| (find in tab) | `Ctrl+F` | find within the current code/smali editor (next/prev, match-case, highlight-all; Esc closes) |
| (global search) | `Ctrl+Shift+F` | jump to the Search tab (classes/methods/strings) |
| Open Workspace… | — | reopen by picking a workspace folder |
| Recent Projects ▸ | — | reopen from the recents list |
| Load ProGuard Mapping… | — | apply a `mapping.txt` to restore original names (reloads) |
| Exit | — | quit |

### Build
| Item | Shortcut | Action |
|---|---|---|
| Disassemble to Smali | — | baksmali → editable `smali-out/`; adds the **Smali** tab |
| Rebuild Signed APK (smali lib) | `Ctrl+B` | reassemble edited smali → repackage → align → sign |
| Full Decode (apktool) | — | decode res + manifest + smali; adds the **apktool** tab |
| Rebuild with apktool + sign + verify | `Ctrl+Shift+B` | apktool build → align → sign → `apksigner verify` |
| Install Patched APK (adb) | — | `adb install -r` the last build |

After a rebuild, signature status is shown as `1✓ 2✓ 3✓` (APK signature schemes v1/v2/v3).

### View
Dark / Light theme (applies live to all panels and open editors).

### Help
About dialog.

---

## 6. Panels reference

The window is a three-zone layout: **left** tabs (project/analysis), **center** code
editor, **bottom** tabs (analysis & graph views). Double-clicking a method anywhere
primes the bottom views (CFG, Dry Run, Debugger) and the cross-reference / call-graph.

### Left pane

#### Overview
The at-a-glance APK summary, gathered on load: **file** (name, size, SHA-256), **app**
(package, version name/code, min/target/compile SDK, application class, launcher),
**component** and **permission** counts, **code** stats (DEX/class/method/string counts),
native library count, and the **signature** — verification status with the v1/v2/v3
scheme results and the signer certificate(s) (subject, issuer, serial, validity, key,
SHA-256 / SHA-1 fingerprints). Version/SDK values are backfilled from `apktool.yml` when a
Full Decode is present (apktool stores them outside the manifest).

#### Project
A package → class tree built from jadx's class list. Click a class to open its
decompiled Java in the code view.

#### Analysis
Four filterable tables from dexlib2:
- **Classes** — every class (with access flags, superclass). Double-click → open.
- **Methods** — every method (signature, params, return). Double-click → open class +
  prime the bottom views for that method.
- **Strings** — the full string pool. The fastest way to find a target.
- **Imports** — referenced external types.

Each tab has a filter box for instant narrowing.

#### Manifest
The decoded `AndroidManifest.xml`, read-only with XML syntax highlighting (apktool's XML
if a Full Decode was run, otherwise jadx's decode).

#### Resources
A browser of everything decoded from the APK — `res/` (layouts, values, drawables),
`assets/`, fonts, `resources.arsc`, etc. Selecting a node previews it: decoded text/XML
for resources, an image preview for drawables, or a size note for raw binary.

#### Native *(shown only if the APK contains `.so` files)*
Per-library detail from the built-in ELF parser: **Info** (arch, type, section table with
addresses), **JNI** (exported `Java_*` / `JNI_OnLoad`), **Exports**, **Imports**,
**Strings**, and **Disassembly** via an arch-aware backend (GNU cross-`objdump` →
multi-arch `objdump` → `llvm-objdump`; the button names the tool, or shows an install hint).

#### Smali / apktool *(appear after Disassemble / Full Decode, or on reopen)*
File trees over `smali-out/` and `apktool-out/`. Click a file to open it in an
**editable** editor tab; save, then rebuild from the Build menu.

### Center

#### Code view
RSyntaxTextArea tabs. Decompiled Java is read-only with syntax highlighting; opened
smali files are editable (with a Save bar). Supports go-to-definition. Re-themes live
when you switch Dark/Light.

**Multi-decompiler (second opinion).** Each class tab has a **Decompiler** selector:
`jadx · CFR · Vineflower · Procyon · Best ★`. jadx (DEX-direct) is the default; the
other three are open-source JVM-bytecode decompilers reached through an embedded
**dex2jar** bridge (the APK is converted to a JAR once, cached in the workspace as
`alt-decompile.jar` on first use). Because different decompilers fail on different
constructs, when jadx chokes on obfuscated control flow another engine often
reconstructs it cleanly.

Each result is given a **quality score (0–100)** — failure markers, `// Couldn't be
decompiled` stubs, raw `goto` fallbacks and `UnsupportedOperationException` method stubs
lower it; reconstructed control flow raises it. **Best ★** runs every engine and shows
the highest-scoring output, labelled with which engine won and its score (e.g.
`Best → Procyon · quality 100/100`). All engines are embedded libraries — nothing
shells out.

**Auto-fallback.** You don't have to babysit the selector: when a class opens and jadx's
output scores poorly (incomplete decompilation — common on obfuscated code), Mythosaur
automatically runs the alternates in the background and switches to the best one,
showing e.g. `auto → CFR · quality 92/100 (jadx was 41)`. Clean classes stay on jadx
instantly; only low-quality ones trigger the fallback.

### Bottom pane

#### App Flow
A screen-to-screen **navigation map** of the app — a view no other Android RE tool has.
It reads the decoded manifest (apktool's gold-standard `AndroidManifest.xml` if a Full Decode
has been run, otherwise jadx's decode — activities + launcher) and scans DEX instructions for
inter-activity references (`new Intent(this, X.class)`, `setClassName`). Rendered as a
top-down flowchart: gold launcher at top, **solid amber** = direct edges, **dashed
grey** = inferred/indirect (helper/SDK-launched), **purple** = back-edge/loop, cyan box
= exported activity. Click a box to open its class. The tree is guaranteed connected
(only the root has no incoming edge). Pan/zoom supported. Heavy apps take a moment as
the scan runs on a background thread.

#### Protections
An auto-running report (re-run with **Analyze Protections**) covering:
- **DEX Wrapper / Packer** — signature DB (Bangcle, Qihoo, Tencent Legu, DexProtector,
  Promon, etc.) plus runtime-DEX-loading detection (`DexClassLoader` /
  `InMemoryDexClassLoader` + crypto ⇒ DexGuard-style RASP wrapper).
- **Obfuscation %** — framework-aware (ignores android/androidx/kotlin/etc.), measuring
  the app's own short names and single-letter packages; labels the likely obfuscator.
- **Anti-RE** — Frida / root / debugger / emulator detection strings.
- **Encrypted / hidden payloads** — Shannon-entropy scan of zip entries (>7.5 ⇒ likely
  encrypted), with size and a note.

> Honest limitation: Mythosaur can **detect and locate** a runtime packer but cannot
> generically unpack it — that needs a runtime memory dump.

#### Permissions
A **permission abuse map** — a flow chart linking each requested permission to where the
app actually exercises it. It reads `<uses-permission>` from the manifest, classifies each
(dangerous / abuse-prone / normal / signature), then scans the DEX for the Android APIs
that each permission gates and links it to the using classes. The header summarises:
total · dangerous · abuse-prone · **abused** (dangerous & used in code) · **over-privileged**
(declared but never used) · **used-undeclared** (API used without the matching permission).

In the chart each permission node is colour-coded (red = dangerous, orange = abuse-prone
like overlay/install-packages/accessibility, purple = signature, grey = normal); dashed =
over-privileged. Edges run to the using classes (capped, with “+N more”). Click a class to
open it. This turns a flat permission list into "which dangerous capabilities does this app
use, and exactly where" — e.g. `SEND_SMS → com.app.SendSMSModule`.

#### Method CFG
A Cutter-style **control-flow graph** of the selected method: basic blocks split at
branch boundaries, connected by colored edges — **green** true / **red** false /
**amber** goto+fall / **cyan** switch / **purple** loop back-edge. Pan/zoom.

#### Dry Run *(sandboxed Dalvik method emulator)*
**Run a single method** — typically an obfuscated string-decryptor — and see its actual
result, without a device. Select a method (it auto-primes here), enter comma-separated
arguments (`5, "text", 100L, 0x1f, true`), and press **Run**.

- Registers hold real Java objects; calls into standard JDK classes (`String`,
  `StringBuilder`, `Base64`, `javax.crypto`, …) execute **for real** via reflection, so
  decryptors produce true output. In-dex helper calls are interpreted recursively.
- **Security:** the APK's own classes are never loaded into the JVM — only interpreted.
  Reflective execution is restricted to a pure-computation whitelist; `Runtime`,
  `ProcessBuilder`, `ClassLoader`, file/network I/O, `System.exit`, native loading are
  blocked. Untrusted decryptor logic cannot escape the sandbox.
- Output shows the **return value** (byte arrays as hex + ASCII) and a **step trace**
  (address / instruction / effect). Status is **✓ fully emulated**, **⚠ partial**
  (with the reason it stopped — never faked), or **⏱ budget exhausted** (loop/too large).

#### Debugger *(live JDWP smali debugger — optional, needs `adb` + a device)*
Standard ART debugging over JDWP (the same mechanism Android Studio uses — **not**
instrumentation), built on the JDK's JDI.

- **Device** / **Process** dropdowns + **⟳** refresh. **Install + Launch** pushes the
  patched APK (or original) and starts it. **Attach** / **Detach**.
- The selected method's smali is listed on the left. **Click the left gutter to toggle a
  breakpoint** on that instruction (addressed by its exact dalvik code offset).
  Breakpoints are global — they persist as you switch between methods.
- When a breakpoint hits, execution pauses: the **call stack** and **registers / locals**
  appear, and the smali jumps to the paused instruction (highlighted). Click any stack
  frame to open that method's smali at its current line.
- Step with **Step Into / Over / Out** (instruction granularity), then **Resume**.

> Release builds strip the local-variable table; in that case the debugger shows `this`
> and arguments and notes that register names are unavailable.

> Prerequisite: the app must be **debuggable**. Use Build → Full Decode → Rebuild to
> produce a debuggable, signed APK (or debug an already-debuggable build).

#### Search
Global search (also **Ctrl+Shift+F**) across **class names**, **method signatures**, the
**string pool**, and — optionally — the **full decompiled source** of every class, with
case-insensitive substring or **regex** matching and per-category toggles. Results show
type + match + location; **double-click** to jump — a class/method opens its source, a
**code** result scrolls to the matching line (highlighted), and a string is resolved to the
first class that references it (e.g. `"/api/user/login"` → the login class).

**Code (full-text)** is opt-in because it decompiles every class — it streams results as it
goes, shows scan progress, and has a **Stop** button. Results are capped for responsiveness.

#### Cross-References
For the selected method: **callers** (who calls it) and **callees** (what it calls), as
tables. Double-click to navigate.

#### Call Graph
A Java2D call-flow graph centered on the selected method — callers to one side, callees
to the other, with clickable nodes to recenter. Pan/zoom.

---

## 7. Workflows

### Deobfuscate a string
1. **Analysis → Strings** or **Methods**: find the decryptor (often `static String x(int)`).
2. Double-click it → it primes the **Dry Run** tab.
3. Enter the argument(s) → **Run** → read the decoded string + trace.
4. For names, also try **File → Load ProGuard Mapping…** if you have `mapping.txt`.

### Patch & re-sign
1. **Build → Disassemble to Smali** (or **Full Decode** for res/manifest changes).
2. Edit the smali / manifest in the **Smali** / **apktool** tab (editable).
3. **Build → Rebuild Signed APK** (`Ctrl+B`) or **Rebuild with apktool + sign + verify**
   (`Ctrl+Shift+B`). Confirm `1✓ 2✓ 3✓`.
4. Optionally **Install Patched APK (adb)**.

### Live debug on a device
1. Produce a debuggable build (Full Decode → Rebuild) and ensure `adb` + a device with
   USB debugging.
2. **Debugger** tab → **⟳** → pick device + process → **Install + Launch** (or attach to
   a running process).
3. Open the target method, click the gutter to set breakpoints, **Attach**, trigger the
   code path, then step and inspect.

---

## 8. Files & directories

```
~/.mythosaur/
├── tools/apktool.jar                  # auto-fetched apktool 2.9.3
├── debug.keystore                     # auto-generated for signing
├── recent.txt                         # recent workspaces (newest first)
└── workspaces/<name>-<hash>/
    ├── mythosaur.project              # reopen descriptor
    ├── jadx-out/  smali-out/  apktool-out/  build/  dex/
```

---

## 9. Limitations (honest)

- **Packers** are detected and located but not generically unpacked (needs a runtime
  memory dump of the loaded DEX).
- **Dry Run** emulates pure computation + standard-JDK calls; it cannot run methods that
  depend on live Android framework state, JNI/native code, or app field storage — those
  are reported as *partial*, never faked.
- **Debugger** requires `adb`, a connected device/emulator, and a **debuggable** app.
  Release builds without a local-variable table expose only `this` + arguments.
- Reopen re-runs decompilation (jadx has no on-disk class cache); smali/apktool edits and
  built APKs do persist in the workspace.
- No traffic interception, no dynamic instrumentation, no automated findings — by design.
```

This is a reverse-engineering tool. Use it only on applications you are authorized to analyse.
