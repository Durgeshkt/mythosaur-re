package com.mythosaur.core;

import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.SwitchElement;
import org.jf.dexlib2.iface.instruction.SwitchPayload;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds a method's Control Flow Graph (Cutter-style "graph view"): splits the
 * method's dalvik bytecode into basic blocks at branch boundaries and connects
 * them with control-flow edges (fall-through, conditional true/false, goto,
 * switch cases, loop back-edges).
 */
public class CfgBuilder {

    public enum EdgeKind { FALL, BRANCH_TRUE, BRANCH_FALSE, JUMP, SWITCH, BACK }

    public static final class Insn {
        public final int addr;
        public final String text;
        Insn(int addr, String text) { this.addr = addr; this.text = text; }
    }

    public static final class Block {
        public int id;
        public final int startAddr;
        public final List<Insn> insns = new ArrayList<>();
        Block(int startAddr) { this.startAddr = startAddr; }
        public String label() { return "0x" + Integer.toHexString(startAddr); }
    }

    public static final class Edge {
        public final int from, to;
        public final EdgeKind kind;
        Edge(int from, int to, EdgeKind kind) { this.from = from; this.to = to; this.kind = kind; }
    }

    public static final class Cfg {
        public final List<Block> blocks = new ArrayList<>();
        public final List<Edge> edges = new ArrayList<>();
        public String methodName = "";
    }

    /** Flat instruction listing (address + text) — for the debugger's smali view. */
    public static List<Insn> linear(Method method) {
        List<Insn> out = new ArrayList<>();
        MethodImplementation impl = method.getImplementation();
        if (impl == null) return out;
        int addr = 0;
        for (Instruction insn : impl.getInstructions()) {
            if (!(insn instanceof SwitchPayload) && !(insn instanceof org.jf.dexlib2.iface.instruction.formats.ArrayPayload)) {
                out.add(new Insn(addr, format(insn, addr)));
            }
            addr += insn.getCodeUnits();
        }
        return out;
    }

    /** Build the CFG of a method, or an empty one if it has no body. */
    public static Cfg build(Method method) {
        Cfg cfg = new Cfg();
        cfg.methodName = DexAnalyzer.descToDotted(method.getDefiningClass()) + "." + method.getName();
        MethodImplementation impl = method.getImplementation();
        if (impl == null) return cfg;

        // 1) address each instruction
        List<Instruction> insns = new ArrayList<>();
        List<Integer> addrs = new ArrayList<>();
        Map<Integer, Integer> addrToIndex = new HashMap<>();
        int addr = 0;
        for (Instruction insn : impl.getInstructions()) {
            addrToIndex.put(addr, insns.size());
            addrs.add(addr);
            insns.add(insn);
            addr += insn.getCodeUnits();
        }
        if (insns.isEmpty()) return cfg;

        // 2) find block leaders
        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(0);
        for (int i = 0; i < insns.size(); i++) {
            Instruction insn = insns.get(i);
            int a = addrs.get(i);
            String name = insn.getOpcode().name;
            boolean isBranch = insn instanceof OffsetInstruction
                    && (name.startsWith("if-") || name.startsWith("goto") || name.contains("switch"));
            boolean isTerminal = !insn.getOpcode().canContinue();

            if (isBranch) {
                int off = ((OffsetInstruction) insn).getCodeOffset();
                if (name.contains("switch")) {
                    // offset points to the switch payload; read case targets (relative to switch addr)
                    Integer payloadIdx = addrToIndex.get(a + off);
                    if (payloadIdx != null && insns.get(payloadIdx) instanceof SwitchPayload sp) {
                        for (SwitchElement el : sp.getSwitchElements()) leaders.add(a + el.getOffset());
                    }
                } else {
                    leaders.add(a + off); // branch target
                }
            }
            if ((isBranch || isTerminal) && i + 1 < insns.size()) {
                leaders.add(addrs.get(i + 1)); // fall-through / next is a leader
            }
        }
        // payloads themselves aren't real blocks; but harmless if included

        // 3) build blocks
        List<Integer> leaderList = new ArrayList<>(leaders);
        Map<Integer, Block> blockByAddr = new HashMap<>();
        for (int li = 0; li < leaderList.size(); li++) {
            int start = leaderList.get(li);
            Integer startIdx = addrToIndex.get(start);
            if (startIdx == null) continue;
            int end = li + 1 < leaderList.size() ? leaderList.get(li + 1) : Integer.MAX_VALUE;

            Block block = new Block(start);
            for (int i = startIdx; i < insns.size(); i++) {
                int a = addrs.get(i);
                if (a >= end) break;
                Instruction insn = insns.get(i);
                if (insn instanceof SwitchPayload) break; // skip payload tables
                block.insns.add(new Insn(a, format(insn, a)));
            }
            if (!block.insns.isEmpty()) {
                block.id = cfg.blocks.size();
                cfg.blocks.add(block);
                blockByAddr.put(start, block);
            }
        }

        // 4) edges
        Map<Integer, Block> startToBlock = new HashMap<>();
        for (Block b : cfg.blocks) startToBlock.put(b.startAddr, b);

        for (Block b : cfg.blocks) {
            Insn last = b.insns.get(b.insns.size() - 1);
            Integer lastIdx = addrToIndex.get(last.addr);
            if (lastIdx == null) continue;
            Instruction li = insns.get(lastIdx);
            String name = li.getOpcode().name;
            int a = last.addr;

            if (li instanceof OffsetInstruction oi && name.startsWith("if-")) {
                addEdge(cfg, startToBlock, b, a + oi.getCodeOffset(), EdgeKind.BRANCH_TRUE);
                addEdge(cfg, startToBlock, b, a + li.getCodeUnits(), EdgeKind.BRANCH_FALSE);
            } else if (li instanceof OffsetInstruction oi && name.startsWith("goto")) {
                addEdge(cfg, startToBlock, b, a + oi.getCodeOffset(), EdgeKind.JUMP);
            } else if (li instanceof OffsetInstruction oi && name.contains("switch")) {
                Integer payloadIdx = addrToIndex.get(a + oi.getCodeOffset());
                if (payloadIdx != null && insns.get(payloadIdx) instanceof SwitchPayload sp) {
                    for (SwitchElement el : sp.getSwitchElements()) {
                        addEdge(cfg, startToBlock, b, a + el.getOffset(), EdgeKind.SWITCH);
                    }
                }
                addEdge(cfg, startToBlock, b, a + li.getCodeUnits(), EdgeKind.FALL); // default
            } else if (li.getOpcode().canContinue()) {
                addEdge(cfg, startToBlock, b, a + li.getCodeUnits(), EdgeKind.FALL);
            }
            // return/throw: no outgoing edges
        }

        markBackEdges(cfg);
        return cfg;
    }

    private static void addEdge(Cfg cfg, Map<Integer, Block> startToBlock, Block from, int toAddr, EdgeKind kind) {
        Block to = startToBlock.get(toAddr);
        if (to != null) cfg.edges.add(new Edge(from.id, to.id, kind));
    }

    /** Mark edges whose target id <= source id as BACK (loop) edges, for rendering. */
    private static void markBackEdges(Cfg cfg) {
        for (int i = 0; i < cfg.edges.size(); i++) {
            Edge e = cfg.edges.get(i);
            if (e.to <= e.from && e.kind != EdgeKind.BACK) {
                cfg.edges.set(i, new Edge(e.from, e.to, EdgeKind.BACK));
            }
        }
    }

    // ---- instruction formatting (compact, Cutter-like) ----

    private static String format(Instruction insn, int addr) {
        StringBuilder sb = new StringBuilder(insn.getOpcode().name);
        String regs = registers(insn);
        if (!regs.isEmpty()) sb.append(' ').append(regs);
        if (insn instanceof NarrowLiteralInstruction nl && !(insn instanceof ReferenceInstruction)) {
            sb.append(", ").append(nl.getNarrowLiteral());
        } else if (insn instanceof WideLiteralInstruction wl && !(insn instanceof ReferenceInstruction)) {
            sb.append(", ").append(wl.getWideLiteral());
        }
        if (insn instanceof ReferenceInstruction ri) {
            sb.append(", ").append(refStr(ri.getReference()));
        }
        if (insn instanceof OffsetInstruction oi && insn.getOpcode().name.startsWith("if-")) {
            sb.append(" -> 0x").append(Integer.toHexString(addr + oi.getCodeOffset()));
        } else if (insn instanceof OffsetInstruction oi && insn.getOpcode().name.startsWith("goto")) {
            sb.append(" -> 0x").append(Integer.toHexString(addr + oi.getCodeOffset()));
        }
        return sb.toString();
    }

    private static String registers(Instruction insn) {
        List<String> r = new ArrayList<>();
        if (insn instanceof RegisterRangeInstruction rr) {
            return "v" + rr.getStartRegister() + "..v" + (rr.getStartRegister() + rr.getRegisterCount() - 1);
        }
        if (insn instanceof OneRegisterInstruction o) r.add("v" + o.getRegisterA());
        if (insn instanceof TwoRegisterInstruction t) r.add("v" + t.getRegisterB());
        if (insn instanceof ThreeRegisterInstruction th) r.add("v" + th.getRegisterC());
        return String.join(", ", r);
    }

    private static String refStr(Reference ref) {
        if (ref instanceof StringReference s) {
            String v = s.getString();
            if (v.length() > 24) v = v.substring(0, 22) + "…";
            return "\"" + v + "\"";
        }
        if (ref instanceof MethodReference m) {
            return DexAnalyzer.simpleType(DexAnalyzer.descToDotted(m.getDefiningClass())) + "." + m.getName() + "()";
        }
        if (ref instanceof FieldReference f) {
            return DexAnalyzer.simpleType(DexAnalyzer.descToDotted(f.getDefiningClass())) + "." + f.getName();
        }
        if (ref instanceof TypeReference t) {
            return DexAnalyzer.simpleType(DexAnalyzer.descToDotted(t.getType()));
        }
        return ref.toString();
    }
}
