# Mythosaur RE

An APK reverse-engineering tool for Android, built for the apps that break a normal
workflow: hardened, packed, and heavily obfuscated ones.

That's the whole point of it. When a single decompiler gives up on obfuscated control
flow, Mythosaur tries several and keeps the cleanest result. When a hardened APK won't
even open in the usual tools because its archive is deliberately malformed, Mythosaur
repairs it and opens it anyway. When strings are decrypted at runtime, it runs that
decryptor in a sandbox and shows you the real values, no device needed. And when a class
refuses to decompile at all, you still get its bytecode, cross-references, control-flow
graph and editable smali.

Decompiling, DEX analysis, patching and re-signing all live in one window, so you're not
stitching five command-line tools together by hand. It also has two views I haven't seen
in other Android RE tools: a map of how the app moves from Activity to Activity, and a
per-method control-flow graph.

Static analysis and patching only. No traffic proxy, no dynamic instrumentation, that part
is out of scope by design.

---

## Contents

1. [Features](#features)
2. [Requirements](#requirements)
3. [Build & run](#build--run-from-source)
4. [Quick start — your first 5 minutes](#quick-start--your-first-5-minutes)
5. [The workspace](#the-workspace)
6. [Guide: every panel, what it's for](#guide-every-panel-what-its-for)
7. [Patching & re-signing](#patching--re-signing)
8. [Keyboard shortcuts](#keyboard-shortcuts)
9. [Where things live on disk](#where-things-live-on-disk)
10. [Native installers](#native-installers)
11. [Scope & limitations](#scope--limitations)
12. [Roadmap · built with · license](#roadmap)

---

## Features

### Where it actually earns its place

Most tools assume a clean, well-formed APK and one decompiler. Malware and protected apps
are not like that, and that gap is what Mythosaur is built around.

- **Opens APKs that other tools won't.** Hardened and tampered packages often carry a
  deliberately broken ZIP structure that strict parsers reject outright, even though
  Android installs them fine. Mythosaur detects that, repairs the archive into a clean
  copy, and analyses it anyway, so you get in where the usual decode just errors out.
- **Four decompilers, automatic fallback.** Every class can be reconstructed four
  different ways, scored for quality, with the best picked for you. When the primary
  engine spits out broken control flow on an obfuscated method, it quietly switches to
  whichever engine actually rebuilds it. One tool's "couldn't be decompiled" is not the
  end of the road here.
- **Beats string encryption with no device.** A locked-down Dalvik interpreter runs a
  single method (say, an obfuscated string-decryptor) and shows its real return value plus
  a register trace. No emulator, no rooted phone, and you never actually run the malware.
- **Tolerant manifest parsing.** Binary manifests mangled to trip up decoders are read
  with a fault-tolerant parser, so you still recover the package, components and permissions.
- **Bytecode when source fails.** Even if a class never decompiles cleanly, you still get
  its methods, strings, cross-references, control-flow graph and editable smali from the DEX.

### Read and understand the app
- **Decompiler view** with syntax highlighting, per-class tabs, and in-editor find (Ctrl+F).
- **Project tree with live filter** to cut a 40k-class app down to what you care about.
- **DEX tables** for classes, methods, imports and strings, all filterable.
- **Cross-references and a call graph** for any method, click a node to recenter.
- **App Navigation Flow**: a top-down map of which Activity opens which.
- **Method control-flow graph**: basic blocks with branch and loop edges.
- **Permission abuse map** linking each permission to the code that uses it, flagging
  over-privilege and APIs called without a declared permission.
- **Global search** across class names, methods, the string pool, or full decompiled source.
- **Native `.so` inspection**: architecture, sections, JNI entry points, symbols, strings,
  and architecture-aware disassembly for ARM64/ARM/x86 (see Requirements).

### Spot the protections
- Packer and DEX-wrapper detection (Bangcle, Jiagu, Legu, DexProtector and more).
- Obfuscation level and likely obfuscator; load a ProGuard `mapping.txt` to restore names.
- Anti-RE checks (Frida, root, debugger, emulator detection).
- Entropy scan for encrypted or hidden payloads.

### Patch and re-sign
- Edit smali for code-only changes, or do a full decode for resources and the manifest.
- Rebuild, align, sign and verify (v1/v2/v3), then optionally install over adb.
- **Self-contained**: alignment and signing run in-process — no external `zipalign`,
  `apksigner` or JDK needed. Install the `.deb`/`.exe` and the patch pipeline just works.

### Quality-of-life
- Optional live smali debugger (breakpoints, stepping, registers) on a debuggable device.
- Reusable per-APK workspaces that restore your edits and patched APKs on reopen.
- Live dark / light theme.

---

## Requirements

If you install the native `.deb`/`.exe`, **nothing else is needed** for the full
analyse → patch → sign → verify workflow. The Java runtime, decompilers, smali tools and
APK signing are all bundled; alignment and v1/v2/v3 signing run **in-process** (no external
`zipalign`/`apksigner`), and the debug keystore uses the bundled runtime's `keytool`. Two
things stay optional:

| Tool | Why | Install (Debian/Kali) |
|------|-----|------------------------|
| `adb` *(optional)* | install a patched APK onto a device | `sudo apt install adb` |
| a disassembler *(optional)* | native `.so` disassembly | see [below](#native-disassembly-toolchain) |

Building from source instead needs **JDK 21+** (`sudo apt install openjdk-21-jdk`); the
first run downloads the Gradle deps. The full apktool decode path fetches `apktool.jar`
once to `~/.mythosaur/tools/`.

### Native disassembly toolchain

Most Android libraries ship **arm64-v8a** and **armeabi-v7a**, but a stock `objdump`
(from `binutils`) is usually built **x86-only** — so plain `objdump` fails on ARM with
*"can't disassemble for architecture UNKNOWN"*. Mythosaur picks a disassembler that
actually targets each `.so`'s architecture, in this order:

1. a **GNU cross-objdump** for the arch (`aarch64-linux-gnu-objdump`, `arm-linux-gnueabihf-objdump`, …),
2. the **host `objdump`** *if* it was built with that target (e.g. on `binutils-multiarch`),
3. **`llvm-objdump`** — architecture-agnostic, handles them all.

If none can handle the arch, the Disassembly tab shows a clear install hint instead of a
cryptic error. To cover every Android ABI, install **any one** of:

```bash
sudo apt install binutils-aarch64-linux-gnu binutils-arm-linux-gnueabihf   # GNU cross
# …or…
sudo apt install binutils-multiarch                                        # one objdump, all arches
# …or…
sudo apt install llvm                                                      # llvm-objdump
```

ELF parsing, symbols, JNI, sections and strings are **pure Java** and always work — a
disassembler is only needed for the instruction listing.

---

## Build & run (from source)

```bash
cd mythosaur-jvm

# Run directly (first run downloads Gradle + libraries)
./gradlew run

# …or build a distribution and launch it
./gradlew installDist
java -cp "build/install/mythosaur-jvm/lib/*" com.mythosaur.Main [optional-app.apk]
```

- Start in light theme: add `-Dtheme=light` (e.g. `./gradlew run` won't pass it; use the
  `java -cp …` form: `java -Dtheme=light -cp "…" com.mythosaur.Main`).
- Headless end-to-end smoke test: `./gradlew smoke --args="/path/a.apk /path/b.apk"`.

---

## Quick start — your first 5 minutes

1. **File → Open APK** (Ctrl+O), or pass a path on the command line. Mythosaur decompiles
   with jadx, parses every DEX with dexlib2, and creates a workspace. The status bar shows
   totals like `1978 classes · 8966 methods · 7587 strings`.
2. **Get oriented** — the **Overview** tab (left) gives identity, hash, package/SDK,
   component & permission counts, and the signing certificate.
3. **Find code fast** — **Project** tab, type in the filter box (e.g. `Login` or `com.app`).
   Click a class to open its decompiled Java; press **Ctrl+F** inside it to search the source.
4. **Understand the app's shape** — **App Flow** (bottom) draws Activity→Activity navigation;
   **Protections** (bottom) tells you about packers/obfuscation/anti-RE.
5. **Dig into a method** — double-click a method in **Analysis → Methods**: it populates
   **Cross-References**, **Call Graph**, and the **Method CFG**, and primes **Dry Run**.

---

## The workspace

```
┌──────────────────────────────────────────────────────────┐
│ File   Build   View   Help                                │
├───────────────┬──────────────────────────────────────────┤
│ Overview      │   LoginActivity.java        [tabs]  [×]   │
│ Project       │   ──────────────────────────────────────  │
│ Analysis      │   decompiled Java (jadx) / editable smali │
│ Manifest      │   Ctrl+F find bar ───────────────         │
│ Resources     ├──────────────────────────────────────────┤
│ Native*       │ App Flow │ Protections │ Permissions │ … │
│ Smali* apktool*│   graphs / tables / reports              │
├───────────────┴──────────────────────────────────────────┤
│ status: name · counts · build results                     │
└──────────────────────────────────────────────────────────┘
```

**Left tabs** = navigation/identity · **Centre** = code (with Ctrl+F find) · **Bottom
tabs** = analysis views. Tabs marked `*` appear only when relevant (Native when the APK
has `.so`; Smali/apktool after you disassemble/decode).

---

## Guide: every panel, what it's for

**Overview** — at-a-glance: file name/size/SHA-256, package, version, min/target/compile
SDK, launcher + application class, component & permission counts, code/native stats, and
the full signing certificate with v1/v2/v3 verification.

**Project** — package → class tree with a **live filter box**. Click a class to open it.

**Analysis** — four filterable, Cutter-style dexlib2 tables (Classes / Methods / Imports /
Strings). Double-click a row to jump to the class and (for a method) drive the bottom views.

**Manifest** / **Resources** — the decoded `AndroidManifest.xml`, and a filterable tree of
everything jadx decoded (res/, assets/, arsc) with text/XML/image preview.

**App Flow** — top-down flowchart of the whole app. Gold = launcher, cyan = exported
Activity; orange edge = forward navigation, purple = back, dashed grey = inferred
(helper-launched). Click a box to open its source; **drag a box to move it**, drag empty
space to pan, scroll to zoom, **double-click empty space to reset the view**.

**Method CFG** — a method's basic blocks as a flowchart with hex addresses; green =
branch-taken, red = not-taken, amber = goto/fall, cyan = switch case, purple = loop back-edge.

**Cross-References** / **Call Graph** — callers/callees of the selected method, and a
caller ← target → callee graph (click a node to recenter).

**Permissions** — abuse map linking each permission to the classes that exercise it;
dangerous = red, abuse-prone = orange, over-privileged (declared-unused) = dashed,
used-undeclared = flagged. Click a usage node to open that class.

**Protections** — packer/DEX-wrapper, obfuscation %, anti-RE checks, and an entropy scan
for encrypted payloads. (Static analysis *detects and locates* a runtime packer; it can't
generically unpack one — that needs a memory dump.)

**Dry Run** — pick a method, supply args, and the sandboxed Dalvik emulator runs it and
shows the real return value + a register-level trace. Ideal for string-decryptors.

**Debugger** *(optional)* — live JDWP smali debugging of a debuggable app on a device.

**Native** *(when `.so` present)* — per library: Info (arch + section table with addresses),
JNI functions, Exports, Imports, Strings, and **Disassembly** via the arch-aware backend
above (the button names the tool it will use).

**Search** — global search (Ctrl+Shift+F): class names, methods, the string pool, or the
**full decompiled source** of every class (opt-in, streamed, cancelable).

> The graph views (App Flow / Method CFG / Call Graph) all share the same controls:
> **drag to pan · scroll to zoom · double-click empty space to reset**. A hint line is
> drawn at the bottom of each.

---

## Patching & re-signing

Two pipelines; both end in zipalign → apksigner sign → **verify** (reported as
`1✓ 2✓ 3✓` for the v1/v2/v3 schemes).

**A) Fast — DEX-only (smali library)** — for code-only changes:
1. **Build → Disassemble to Smali** → a **Smali** tab appears.
2. Open a `.smali` file (it's editable — green *EDITABLE* bar) → edit → **Save**.
3. **Build → Rebuild Signed APK** (Ctrl+B) → `build/patched.apk`, signed + verified.

**B) Full — code + resources + manifest (apktool)** — for manifest/resource edits:
1. **Build → Full Decode (apktool)** → an **apktool** tab appears.
2. Edit `AndroidManifest.xml`, `res/…`, smali, etc. → **Save**.
3. **Build → Rebuild with apktool + sign + verify** (Ctrl+Shift+B) → `build/patched-apktool.apk`.

Then **Build → Install Patched APK** (needs `adb` + a device). To deobfuscate first,
**File → Load ProGuard Mapping…** reloads the APK through jadx with names restored.

---

## Keyboard shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+O | Open APK |
| Ctrl+F | Find in the current code/smali tab |
| Ctrl+Shift+F | Global search |
| Ctrl+B | Rebuild Signed APK (smali lib) |
| Ctrl+Shift+B | Rebuild with apktool + sign + verify |
| F12 | Go to definition (in Java) |
| Scroll / Drag | Zoom / pan any graph view |
| Double-click empty | Reset a graph view's pan/zoom |

---

## Where things live on disk

Each project is a folder under `~/.mythosaur/workspaces/<name>-<hash>/`:

```
mythosaur.project    descriptor (original APK path, mapping, name, opened-at)
normalized.apk       repaired archive (only if the original ZIP was malformed)
jadx-out/            jadx decompiler output
smali-out/           baksmali output (after Disassemble to Smali)
apktool-out/         full apktool decode (after Full Decode)
build/               patched / aligned / signed APKs
debug.keystore       auto-generated debug signing key
```

Recent projects appear under **File → Recent Projects**; `~/.mythosaur/recent.txt` lists
them newest-first. First-run **Terms & Conditions** acceptance is stored in
`~/.mythosaur/.terms-accepted`.

---

## Native installers

Mythosaur ships as a native installer that **bundles its own Java runtime** — end users
don't need Java. A Terms & Conditions step is shown during installation and again on
first launch (must be accepted to use the app).

- **Linux (`.deb`)** — `./packaging/package-linux.sh` → `dist/mythosaur-re_1.0.0_amd64.deb`,
  then `sudo apt install ./dist/mythosaur-re_1.0.0_amd64.deb` (launch from the app menu).
- **Windows (`.exe`)** — `packaging\package-windows.bat` → `dist\Mythosaur RE-1.0.0.exe`.

Each installer is built on its own OS. Full details in [PACKAGING.md](PACKAGING.md).

---

## Scope & limitations

Mythosaur is a **reverse-engineering** tool: static analysis + patching. It is *not* a
network/dynamic suite — there's no traffic proxy or built-in instrumentation, by design.

- **Packed apps**: detected and located, **not** auto-unpacked (needs a runtime dump).
- **String decryption**: not automated — run the decryptor in **Dry Run**, or read it via
  the Method CFG.
- **Native code**: disassembly only (arch-aware objdump/llvm-objdump). True native
  decompilation to pseudo-C is on the roadmap, not in this release.

Task-oriented walkthrough: [GUIDE.md](GUIDE.md) · complete panel/menu reference:
[REFERENCE.md](REFERENCE.md).

---

## Roadmap

- Interactive native (ARM) control-flow graph + pseudo-C native decompilation
- Class-rename refactoring across the project
- Debugger: conditional breakpoints + watch expressions

## Built with

Mythosaur wraps a lot of other people's work. Credit where it's due, each used under its
own license:

- **jadx** (Skylot) — Apache-2.0
- **smali / baksmali / dexlib2** (Ben Gruver) — BSD-3-Clause
- **Apktool** (Connor Tumbleson, Ryszard Wiśniewski) — Apache-2.0
- **CFR** (Lee Benfield) — MIT
- **Vineflower** — Apache-2.0
- **Procyon** (Mike Strobel) — Apache-2.0
- **dex2jar** (pxb1988) — Apache-2.0
- **FlatLaf** (FormDev) — Apache-2.0
- **RSyntaxTextArea** (Fifesoft) — modified BSD

Native disassembly shells out to `objdump` (binutils) or `llvm-objdump` if they're installed.

## Author

Created by **durgeshkt**.

## License

Mythosaur RE is released under the MIT License.
