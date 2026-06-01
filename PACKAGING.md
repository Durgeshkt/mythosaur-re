# Packaging Mythosaur RE — Native Installers

Mythosaur RE ships as a **native installer** for Linux and Windows, built with the JDK's
own `jpackage`. Each installer **bundles its own Java runtime**, so end users do **not**
need Java installed. A **Terms & Conditions** step is presented in two ways:

1. **During installation** — the installer shows the terms (jpackage `--license-file`).
2. **On first launch** — the app shows a Terms & Conditions dialog that must be accepted
   before use (works on every platform; acceptance is remembered in
   `~/.mythosaur/.terms-accepted`).

> `jpackage` can only build an installer for the OS it runs on — it cannot cross-build.
> Build the Linux installer on Linux and the Windows installer on Windows.

---

## Linux (`.deb`)

**Requirements:** JDK 21+, `dpkg-deb`, `fakeroot`.

```bash
./packaging/package-linux.sh
```

Output: `dist/mythosaur-re_1.0.0_amd64.deb`

Install / run:
```bash
sudo apt install ./dist/mythosaur-re_1.0.0_amd64.deb
# then launch from the app menu:  Development → Mythosaur RE
# or directly:                    /opt/mythosaur-re/bin/mythosaur-re
```
Uninstall: `sudo apt remove mythosaur-re`

(For an `.rpm` instead, change `--type deb` to `--type rpm` in the script — needs `rpm-build`.)

---

## Windows (`.exe`)

**Requirements:** JDK 21+ on `PATH`, and **Inno Setup 6+** (https://jrsoftware.org/isdl.php)
on `PATH` for `--type exe` (or the **WiX Toolkit** for `--type msi`).

```bat
packaging\package-windows.bat
```

Output: `dist\Mythosaur RE-1.0.0.exe`

The installer shows the Terms & Conditions acceptance page, lets the user choose the
install directory, and creates Start-menu / desktop shortcuts.

---

## What gets bundled

`jpackage` links a trimmed Java runtime (via `jlink`, debug/man stripped) containing only
the modules the app needs (computed with `jdeps`, plus the runtime-only modules for
crypto, logging, XML, zip and the debugger). The app's own jars (jadx, dexlib2,
baksmali/smali, CFR, Vineflower, Procyon, dex2jar, FlatLaf, RSyntaxTextArea, …) ship under
the install directory.

Approximate sizes: ~40 MB installer, ~113 MB installed.

---

## Notes

- App version, vendor and JVM options live at the top of each packaging script.
- The app icon ships in `packaging/` (`icon.png` for Linux, `icon.ico` for Windows) and is
  wired into both scripts via `--icon`; the same artwork is the window/taskbar icon
  (bundled `icon-*.png` resources).
- The same `src/main/resources/TERMS.txt` is used both as the installer license file and
  as the in-app first-run agreement, so the two always match. Bump `TermsDialog.VERSION`
  when the terms change to re-prompt users who already accepted.
