package com.mythosaur.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure-Java ELF parser for Android native libraries (.so). Extracts architecture,
 * sections, exported/imported symbols, JNI functions (Java_*), and strings —
 * no external tools, no JNI. Enough to map native code to the Java layer.
 */
public class ElfFile {

    public static final class Section {
        public String name;
        public long addr, offset, size, entSize;
        public int type, link;
    }

    public static final class Symbol {
        public String name;
        public long value, size;
        public boolean function;
        public boolean exported; // defined here (vs imported/undefined)
    }

    private final ByteBuffer buf;
    private boolean is64;
    private String arch = "unknown";
    private final List<Section> sections = new ArrayList<>();
    private final List<Symbol> exports = new ArrayList<>();
    private final List<Symbol> imports = new ArrayList<>();
    private final List<Symbol> jni = new ArrayList<>();
    private final TreeSet<String> strings = new TreeSet<>();
    private boolean valid = false;

    public ElfFile(byte[] data) {
        this.buf = ByteBuffer.wrap(data);
        try {
            parse(data);
            valid = true;
        } catch (Exception ignored) {}
    }

    private void parse(byte[] data) {
        // magic
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            throw new IllegalArgumentException("not ELF");
        }
        is64 = data[4] == 2;
        buf.order(data[5] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        int eMachine = u16(18);
        arch = switch (eMachine) {
            case 0x28 -> "ARM (32-bit)";
            case 0xB7 -> "AArch64 (ARM 64-bit)";
            case 0x03 -> "x86";
            case 0x3E -> "x86-64";
            case 0x08 -> "MIPS";
            default -> "machine 0x" + Integer.toHexString(eMachine);
        };

        long eShoff;
        int eShentsize, eShnum, eShstrndx;
        if (is64) {
            eShoff = u64(0x28);
            eShentsize = u16(0x3a);
            eShnum = u16(0x3c);
            eShstrndx = u16(0x3e);
        } else {
            eShoff = u32(0x20);
            eShentsize = u16(0x2e);
            eShnum = u16(0x30);
            eShstrndx = u16(0x32);
        }

        // read section headers
        List<long[]> shRaw = new ArrayList<>(); // [name, type, offset, size, link, entSize, addr]
        for (int i = 0; i < eShnum; i++) {
            long base = eShoff + (long) i * eShentsize;
            long shName = u32((int) base);
            long shType = u32((int) base + 4);
            long shAddr, shOffset, shSize, shEntSize;
            int shLink;
            if (is64) {
                shAddr = u64((int) base + 16);
                shOffset = u64((int) base + 24);
                shSize = u64((int) base + 32);
                shLink = (int) u32((int) base + 40);
                shEntSize = u64((int) base + 56);
            } else {
                shAddr = u32((int) base + 12);
                shOffset = u32((int) base + 16);
                shSize = u32((int) base + 20);
                shLink = (int) u32((int) base + 24);
                shEntSize = u32((int) base + 36);
            }
            shRaw.add(new long[]{shName, shType, shOffset, shSize, shLink, shEntSize, shAddr});
        }

        // shstrtab to resolve section names
        long shstrOff = eShstrndx < shRaw.size() ? shRaw.get(eShstrndx)[2] : 0;
        for (long[] sh : shRaw) {
            Section s = new Section();
            s.name = cstr((int) (shstrOff + sh[0]));
            s.type = (int) sh[1];
            s.offset = sh[2];
            s.size = sh[3];
            s.link = (int) sh[4];
            s.entSize = sh[5];
            s.addr = sh[6];
            sections.add(s);
        }

        // symbol tables: .dynsym (type 11) and .symtab (type 2); strtab via sh_link
        for (Section s : sections) {
            if (s.type == 11 || s.type == 2) { // SHT_DYNSYM / SHT_SYMTAB
                Section strtab = s.link < sections.size() ? sections.get(s.link) : null;
                if (strtab != null) parseSymbols(s, strtab);
            }
        }

        extractStrings(data);
    }

    private void parseSymbols(Section symtab, Section strtab) {
        int entSize = is64 ? 24 : 16;
        if (symtab.entSize > 0) entSize = (int) symtab.entSize;
        long count = symtab.size / entSize;
        for (long i = 0; i < count; i++) {
            int base = (int) (symtab.offset + i * entSize);
            long stName;
            long stValue, stSize;
            int stInfo, stShndx;
            if (is64) {
                stName = u32(base);
                stInfo = u8(base + 4);
                stShndx = u16(base + 6);
                stValue = u64(base + 8);
                stSize = u64(base + 16);
            } else {
                stName = u32(base);
                stValue = u32(base + 4);
                stSize = u32(base + 8);
                stInfo = u8(base + 12);
                stShndx = u16(base + 14);
            }
            String name = cstr((int) (strtab.offset + stName));
            if (name.isEmpty()) continue;

            Symbol sym = new Symbol();
            sym.name = name;
            sym.value = stValue;
            sym.size = stSize;
            int type = stInfo & 0xf;
            sym.function = type == 2; // STT_FUNC
            sym.exported = stShndx != 0; // SHN_UNDEF == 0

            if (sym.exported) {
                exports.add(sym);
                if (name.startsWith("Java_") || name.equals("JNI_OnLoad")) jni.add(sym);
            } else {
                imports.add(sym);
            }
        }
    }

    private void extractStrings(byte[] data) {
        StringBuilder cur = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xff;
            if (c >= 0x20 && c < 0x7f) {
                cur.append((char) c);
            } else {
                if (cur.length() >= 4) strings.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() >= 4) strings.add(cur.toString());
    }

    // ---- little readers ----
    private int u8(int off) { return buf.get(off) & 0xff; }
    private int u16(int off) { return buf.getShort(off) & 0xffff; }
    private long u32(int off) { return buf.getInt(off) & 0xffffffffL; }
    private long u64(int off) { return buf.getLong(off); }

    private String cstr(int off) {
        if (off < 0 || off >= buf.capacity()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < buf.capacity(); i++) {
            byte b = buf.get(i);
            if (b == 0) break;
            sb.append((char) (b & 0xff));
        }
        return sb.toString();
    }

    public boolean isValid() { return valid; }
    public String getArch() { return arch; }
    public boolean is64Bit() { return is64; }
    public List<Section> getSections() { return sections; }
    public List<Symbol> getExports() { return exports; }
    public List<Symbol> getImports() { return imports; }
    public List<Symbol> getJniFunctions() { return jni; }
    public List<String> getStrings() { return new ArrayList<>(strings); }
}
