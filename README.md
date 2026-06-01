# Mythosaur RE

An Android APK reverse-engineering tool. It pulls the decompile/analyze/patch toolchain
(jadx, dexlib2, baksmali/smali, apktool) into one Swing app, so you stop running five
command-line tools and grepping their output.

I built it for the APKs that don't cooperate: hardened, packed, heavily obfuscated. A few
things it does that a single-tool setup won't:

- Repairs a malformed or tampered APK archive so it opens at all. A lot of protected apps
  ship a broken ZIP that strict parsers reject even though Android installs them fine.
- Decompiles a class with four engines (jadx, CFR, Vineflower, Procyon) and keeps the best
  one. When jadx produces garbage on obfuscated control flow, another engine usually doesn't.
- Runs a single method in a sandboxed Dalvik interpreter, so you can execute a
  string-decryptor and read its output without a device.
- Falls back to bytecode (methods, strings, xrefs, CFG, editable smali) when a class refuses
  to decompile.

It also draws two things I haven't found in other Android RE tools: a map of which Activity
opens which, and a per-method control-flow graph.

It's static analysis and patching, not a dynamic or network suite. No traffic proxy, no
instrumentation.

## Install

The installer ships a JRE and the whole patch/sign pipeline, so there's nothing else to set up.

- **Linux:** [`mythosaur-re_1.0.0_amd64.deb`](https://github.com/Durgeshkt/mythosaur-re/releases/latest/download/mythosaur-re_1.0.0_amd64.deb)
  ```bash
  sudo apt install ./mythosaur-re_1.0.0_amd64.deb
  mythosaur-re
  ```
- **Windows:** `.exe` (coming soon).

Optional: `adb` to push a patched APK to a device, and a disassembler for native `.so`
(`binutils-multiarch`, `llvm`, or a per-arch `binutils-<arch>`; the stock x86-only `objdump`
can't handle ARM/AArch64).

## Build from source

Needs JDK 21+.

```bash
git clone https://github.com/Durgeshkt/mythosaur-re.git
cd mythosaur-re
./gradlew run            # or: ./gradlew installDist
```

## Usage

Open an APK with `File > Open APK` (Ctrl+O). Left tabs are navigation (project tree, analysis,
manifest, resources, native), the center is the decompiled code, and the bottom tabs hold the
graph and analysis views: App Flow, Method CFG, Protections, Permissions, Dry Run,
Cross-references, Call graph, Search.

To patch: `Build > Disassemble to Smali` (or `Full Decode` if you need resources or the
manifest), edit, then `Build > Rebuild Signed APK`. Alignment and v1/v2/v3 signing run
in-process, so no external `zipalign`/`apksigner` is required. `Build > Install Patched APK`
pushes it over adb.

Task-by-task walkthrough: [GUIDE.md](GUIDE.md). Every panel, menu and shortcut: [REFERENCE.md](REFERENCE.md).

## Shortcuts

| Key | Action |
|-----|--------|
| Ctrl+O | open APK |
| Ctrl+F | find in the current tab |
| Ctrl+Shift+F | global search |
| Ctrl+B | rebuild signed APK |
| Ctrl+Shift+B | rebuild via apktool |
| F12 | go to definition |

Graphs: drag to pan, scroll to zoom, double-click empty space to reset.

## Limitations

- Packers are detected and located, not auto-unpacked. That needs a runtime memory dump.
- Native code is disassembled, not decompiled to pseudo-C. (Planned.)
- String decryption isn't automatic; run the routine in Dry Run, or read it off the CFG.

## Built with

jadx, smali/baksmali/dexlib2, Apktool, CFR, Vineflower, Procyon, dex2jar, FlatLaf,
RSyntaxTextArea, and apksig for signing. Each is used under its own license.

## License

MIT, by durgeshkt.
