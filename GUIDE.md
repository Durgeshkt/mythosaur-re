# Mythosaur RE — User Guide

An APK reverse-engineering tool for Android. It brings the Java, smali, native and
protection layers of an app into one window by embedding jadx, dexlib2, baksmali/smali
and apktool, so you can analyse and patch without juggling separate command-line tools.

Two things here that I haven't seen in other Android RE tools: an **App Navigation Flow**
map and a per-method **Control-Flow Graph**.

---

## 1. Requirements

If you install the native `.deb`/`.exe`, the analyse → patch → **sign** → verify pipeline
needs **no external tools** — APK alignment and v1/v2/v3 signing run in-process, and the
debug keystore uses the bundled runtime's `keytool`. Only these stay optional:

| Tool | Why | Install |
|------|-----|---------|
| `adb` *(optional)* | install a patched APK to a device | `sudo apt install adb` |
| a disassembler *(optional)* | native `.so` disassembly | `sudo apt install binutils-multiarch` (or `llvm`, or per-arch `binutils-aarch64-linux-gnu`) |
| **JDK 21+** *(source build only)* | run/build from source | `sudo apt install openjdk-21-jdk` |

Everything else — jadx, dexlib2, smali/baksmali, **apktool** — is embedded or
auto-fetched. You don't install them separately.

> The official `apktool_2.9.3.jar` is downloaded once (~23 MB) to
> `~/.mythosaur/tools/apktool.jar` the first time you use a full decode/rebuild.
> (The Kali-packaged apktool 2.7.0 has a rebuild bug, so Mythosaur ships its own.)

---

## 2. Build & run

```bash
cd mythosaur-jvm

# Run directly (first run downloads Gradle + libraries)
./gradlew run

# …or build a distribution and run the launcher
./gradlew installDist
java -cp "build/install/mythosaur-jvm/lib/*" com.mythosaur.Main [optional-path-to.apk]
```

Start in light theme from the CLI: add `-Dtheme=light`.

---

## 3. Opening an APK

**File → Open APK** (Ctrl+O), or pass a path on the command line.

On load, Mythosaur:
- decompiles to Java with **jadx** (embedded — no CLI),
- parses every DEX with **dexlib2** (classes, methods, strings, fields),
- creates a workspace at `~/.mythosaur/workspaces/<name>-<hash>/`.

The status bar shows totals, e.g. `1895 classes · 9716 methods · 12842 strings`.

---

## 4. The workspace layout

```
┌──────────────────────────────────────────────────────────┐
│ File  Build  View  Help                                  │
├───────────────┬──────────────────────────────────────────┤
│ Project       │   ApproveBeneficiary.java   [tabs]        │
│ Analysis      │   ───────────────────────────────         │
│ Native        │   decompiled Java (jadx) / smali          │
│ apktool*      │                                           │
│               ├──────────────────────────────────────────┤
│ (left tabs)   │ App Flow │ Protections │ Method CFG │ … │
│               │   graphs / tables / reports               │
├───────────────┴──────────────────────────────────────────┤
│ status: package · counts · build results                 │
└──────────────────────────────────────────────────────────┘
```

**Left tabs** = navigation. **Bottom tabs** = analysis views. **Centre** = code.

---

## 5. Reading the code

### Project tree (left → **Project**)
Browse packages → classes, or type in the **filter box** to narrow a huge app to the
classes/packages you want (matches auto-expand). Click a class to open its **decompiled
Java** (from jadx) in the centre, syntax-highlighted, in its own tab. Press **Ctrl+F**
inside a code tab to search that file (next/prev, match-case, highlight-all; Esc closes).

### Analysis (left → **Analysis**) — Cutter-style tables
Four filterable tabs, straight from dexlib2:
- **Classes** — access flags, name, superclass, method count
- **Methods** — return type, class, method, params
- **Imports** — methods from `android.*`/`java.*`/`androidx.*`/`kotlin.*`
- **Strings** — every string in the DEX string pool

Type in the filter box to narrow. **Double-click a row** to jump to the class — and,
for a method, to populate the Xref + Call Graph + **Method CFG** views.

### Go-to navigation
- In Java: **F12** on a class reference resolves it to its file.
- Method double-click drives the bottom analysis views.

---

## 6. Flow & graph views (bottom tabs)

### App Flow
A top-down **flowchart of the whole app**: the launcher Activity at the top, with
arrows to every Activity it opens via `startActivity(new Intent(this, X.class))`.
- **Gold** box = launcher (entry point)
- **Cyan** box = exported Activity
- **Orange** edge = forward navigation, **purple** edge = back navigation
- Click a box → open that Activity's source.

This is how the app actually moves screen-to-screen — no other Android RE tool draws
it. Drag a box to move it, drag empty space to pan, scroll to zoom, and
**double-click empty space to reset the view**.

### Method CFG — Cutter's control-flow graph
Double-click a method anywhere → its **basic blocks** are drawn as a flowchart:
- each block lists its dalvik instructions with hex addresses,
- **green** edge = branch taken, **red** = branch not-taken,
- **amber** = goto/fall-through, **cyan** = switch case, **purple** = loop back-edge.

Drag to pan, scroll to zoom, double-click empty space to reset. This is the
function-internals view jadx-gui doesn't have.

### Cross-References
Select a method → its **callers** (who calls it) and **callees** (what it calls).
Double-click to navigate.

### Call Graph
A caller ← target → callee graph for the selected method; click a node to recenter.

---

## 7. Protections analysis (bottom → **Protections**)

Auto-runs on load. Tells you what you're up against:

- **DEX Wrapper / Packer** — detects Bangcle/SecNeo, Qihoo Jiagu, Tencent Legu,
  Baidu, Ali, DexProtector, Ijiami, Naga, AppSealing, Promon… with confidence and
  the evidence (native libs, loader classes, custom `Application`).
- **Obfuscation** — a percentage + likely obfuscator (e.g. *70 % — ProGuard/R8*).
- **Anti-RE** — Frida / root / debugger / emulator checks baked into the app
  (scanned from DEX strings **and** native `.so` strings/JNI).
- **Encrypted payloads** — high Shannon-entropy files in the APK that are likely an
  encrypted/packed DEX.

> Static analysis can **detect and locate** a runtime packer but can't generically
> unpack it — that needs a memory dump (Frida) at runtime. The panel says so and
> points you at the payload.

### Deobfuscating with a ProGuard mapping
If you have the app's `mapping.txt`: **File → Load ProGuard Mapping…**. Mythosaur
reloads the APK through jadx with the mapping applied, restoring original
class/method names.

---

## 8. Native libraries (left → **Native**)

Appears when the APK contains `.so` files. A pure-Java ELF parser shows, per library:

- **Info** — architecture (ARM/AArch64/x86/x86-64), ELF class, section table with addresses
- **JNI** — exported `Java_*` / `JNI_OnLoad` functions (the native ↔ Java bridge)
- **Exports / Imports** — defined vs imported symbols (e.g. `fopen`, `__android_log_print`)
- **Strings** — printable strings in the binary
- **Disassembly** — full listing via an **architecture-aware** disassembler. Mythosaur
  picks a backend that targets the `.so`'s arch (GNU cross-`objdump` → multi-arch
  `objdump` → `llvm-objdump`); the button names the tool it will use. If none is
  installed for that arch, it shows the exact `apt install …` to run. (Stock x86-only
  `objdump` can't do ARM/AArch64 — see Requirements.)

This maps native code to the Java layer — e.g. spotting that
`Java_com_…_RootBeerNative_checkForRoot` is the native root check.

---

## 9. Patching: smali, manifest & resources

Mythosaur has **two patch pipelines**. Both end in zipalign → apksigner sign →
apksigner **verify** (reported as `1✓ 2✓ 3✓` for the v1/v2/v3 schemes).

### A) Fast — DEX-only (smali library)
For changing code only.
1. **Build → Disassemble to Smali** — a **Smali** left tab appears (baksmali output).
2. Open a `.smali` file → it's **editable** (green *EDITABLE* bar) → edit → **Save**.
3. **Build → Rebuild Signed APK (smali lib)** (Ctrl+B) → produces `build/patched.apk`,
   signed + verified, and offers to install via adb.

### B) Full — code + resources + manifest (apktool)
For editing the manifest, resources, or strings — not just code.
1. **Build → Full Decode (apktool)** — decodes resources.arsc + binary manifest +
   smali into editable text; an **apktool** left tab appears.
2. Edit anything: `AndroidManifest.xml`, `res/values/strings.xml`, smali, etc. → **Save**.
3. **Build → Rebuild with apktool + sign + verify** (Ctrl+Shift+B) → rebuilds
   everything, signs, verifies → `build/patched-apktool.apk`.

### Worked example — make an app debuggable
A common RE step (lets you attach a debugger to a release build to inspect state):

1. **Build → Full Decode (apktool)**
2. apktool tab → open **AndroidManifest.xml**, change
   `<application …>` to `<application android:debuggable="true" …>` → **Save**
3. **Build → Rebuild with apktool + sign + verify**
4. The dialog confirms `Signature: 1✓ 2✓ 3✓`. The output APK is debuggable —
   verifiable with `aapt2 dump badging patched-apktool.apk` (shows
   `application-debuggable`).
5. Install: **Build → Install Patched APK** (needs adb + a connected device).

Other useful manifest/smali patches while reversing: relax `allowBackup` to pull app
data, export a component to reach it, or no-op an anti-RE check in smali and rebuild.

---

## 10. Themes

**View → Dark / Light** switches the whole UI live (FlatLaf Darcula ↔ IntelliJ),
including the code editor and the graph views. Or start with `-Dtheme=light`.

---

## 11. Exporting / where things live

Each project is a folder under `~/.mythosaur/workspaces/<name>-<hash>/`:

```
<apk>.apk            copy of the imported APK
jadx-out/            jadx decompiler output dir
apktool-out/         full apktool decode (after Full Decode)
smali-out/           baksmali output (after Disassemble to Smali)
dex/                 extracted classes.dex (for the binary parser)
build/               patched / aligned / signed APKs
debug.keystore       auto-generated debug signing key
```

Recent projects appear on the welcome screen.

---

## 12. Keyboard shortcuts

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

## 13. Feature map (what's under the hood)

| Layer | Powered by | What you get |
|-------|-----------|--------------|
| Java | jadx (embedded) | decompiled source, tree, syntax highlight |
| Bytecode | dexlib2 | classes/methods/strings/fields, xrefs, **Method CFG** |
| App model | manifest + dexlib2 | **App Navigation Flow** |
| Native | pure-Java ELF + objdump | JNI/symbols/strings/disasm of `.so` |
| Protections | dexlib2 + ELF + entropy | packer / obfuscation / anti-RE detection |
| Patching | smali + apktool + apksigner | DEX **and** resource/manifest patching, signed + verified |

---

## 14. Scope & limitations

Mythosaur is a **reverse-engineering** tool: static analysis + patching. It is *not*
a network/dynamic suite — there is no traffic proxy or built-in instrumentation, by
design. Use it to understand and modify an app's code, resources, and structure.

- **Packed apps**: detected and located, **not** auto-unpacked (needs a runtime dump).
- **String decryption**: not automated — run the decryptor in **Dry Run**, or read it via
  the Method CFG.
- Native support is **disassembly** (arch-aware objdump/llvm-objdump), not pseudo-C
  decompilation or an interactive native graph (yet).

---

*Mythosaur RE — built in Java, embedding the Android RE toolchain as libraries.*
