package com.mythosaur.core;

import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the app-level navigation flow: which Activity leads to which other Activity.
 *
 * Detection (static, no runtime):
 *  - explicit intents `new Intent(this, Target.class)` → a `const-class Target`
 *  - `setClassName(ctx, "com.x.Target")` → a `const-string "com.x.Target"`
 * scanned across EVERY class (not just Activities), because real apps — especially
 * SDK / KYC ones — often launch screens from non-Activity helper classes.
 *
 * Attribution:
 *  1) If the referencing class is an Activity (or an inner class of one), the edge is
 *     that Activity → Target (a precise navigation edge).
 *  2) If it's a helper/SDK class, the exact trigger screen isn't statically known, so
 *     the Target is connected under the launcher as an "indirect" edge (so it's shown
 *     as part of the app's reachable flow rather than floating).
 */
public class FlowAnalyzer {

    public static final class Edge {
        public final String from, to;
        public final boolean indirect; // true = inferred (helper-launched), not a direct activity edge
        public Edge(String from, String to, boolean indirect) {
            this.from = from; this.to = to; this.indirect = indirect;
        }
    }

    private final ManifestParser manifest;
    private final List<DexBackedDexFile> dexFiles;

    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, Set<String>> adjacency = new LinkedHashMap<>();

    public FlowAnalyzer(ManifestParser manifest, List<DexBackedDexFile> dexFiles) {
        this.manifest = manifest;
        this.dexFiles = dexFiles;
        analyze();
    }

    private void analyze() {
        Set<String> activities = new LinkedHashSet<>(manifest.getActivityNames());
        if (activities.isEmpty()) activities = detectActivitiesFromDex(); // manifest parse failed
        if (activities.isEmpty()) return;
        String launcher = manifest.getLauncherActivity();

        // class -> set of Activity targets it references (const-class or const-string)
        Map<String, Set<String>> refsByClass = new LinkedHashMap<>();

        for (DexBackedDexFile dex : dexFiles) {
            for (ClassDef cls : dex.getClasses()) {
                String fromClass = DexAnalyzer.descToDotted(cls.getType());
                Set<String> targets = null;
                for (Method m : cls.getMethods()) {
                    MethodImplementation impl = m.getImplementation();
                    if (impl == null) continue;
                    for (Instruction insn : impl.getInstructions()) {
                        if (!(insn instanceof ReferenceInstruction)) continue;
                        ReferenceInstruction ri = (ReferenceInstruction) insn;
                        String target = null;
                        if (ri.getReferenceType() == ReferenceType.TYPE) {
                            Reference ref = ri.getReference();
                            if (ref instanceof TypeReference) {
                                String t = DexAnalyzer.descToDotted(((TypeReference) ref).getType());
                                if (activities.contains(t)) target = t;
                            }
                        } else if (ri.getReferenceType() == ReferenceType.STRING) {
                            Reference ref = ri.getReference();
                            if (ref instanceof StringReference) {
                                String s = ((StringReference) ref).getString();
                                String dotted = s.replace('/', '.');
                                if (activities.contains(dotted)) target = dotted;
                                else if (activities.contains(s)) target = s;
                            }
                        }
                        if (target != null && !target.equals(fromClass)) {
                            if (targets == null) targets = refsByClass.computeIfAbsent(fromClass, k -> new LinkedHashSet<>());
                            targets.add(target);
                        }
                    }
                }
            }
        }

        Set<String> hasIncoming = new LinkedHashSet<>();

        // Pass 1 — precise edges: the referencing class is (or belongs to) an Activity
        for (Map.Entry<String, Set<String>> e : refsByClass.entrySet()) {
            String owner = nearestActivity(e.getKey(), activities);
            if (owner == null) continue;
            for (String t : e.getValue()) {
                if (t.equals(owner)) continue;
                addEdge(owner, t, false);
                hasIncoming.add(t);
            }
        }

        // Choose a guaranteed root: launcher, else a Main/Splash/Home-named activity,
        // else the activity with the most outgoing edges, else the first one.
        String root = chooseRoot(launcher, activities);

        // Pass 2 — helper/SDK references: attach referenced-but-orphan Activities under root
        if (root != null) {
            for (Map.Entry<String, Set<String>> e : refsByClass.entrySet()) {
                if (nearestActivity(e.getKey(), activities) != null) continue; // handled in pass 1
                for (String t : e.getValue()) {
                    if (!hasIncoming.contains(t) && !t.equals(root)) {
                        addEdge(root, t, true);
                        hasIncoming.add(t);
                    }
                }
            }
            // Pass 3 — anything STILL orphan (declared but never referenced statically:
            // deep links, manifest-only, framework-launched) → connect under root so the
            // flow is always a connected tree, never a flat disconnected row.
            for (String act : activities) {
                if (!act.equals(root) && !hasIncoming.contains(act)) {
                    addEdge(root, act, true);
                    hasIncoming.add(act);
                }
            }
        }

        for (Map.Entry<String, Set<String>> e : adjacency.entrySet()) {
            for (String to : e.getValue()) {
                edges.add(new Edge(e.getKey(), to, indirectEdges.contains(e.getKey() + "\0" + to)));
            }
        }
    }

    private final Set<String> indirectEdges = new LinkedHashSet<>();

    private void addEdge(String from, String to, boolean indirect) {
        adjacency.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        if (indirect) indirectEdges.add(from + "\0" + to);
        else indirectEdges.remove(from + "\0" + to); // a precise edge supersedes an indirect one
    }

    private String nearestActivity(String className, Set<String> activities) {
        if (activities.contains(className)) return className;
        int dollar = className.indexOf('$');
        if (dollar > 0) {
            String outer = className.substring(0, dollar);
            if (activities.contains(outer)) return outer;
        }
        return null;
    }

    /** Pick a sensible root so the flow is always anchored, even with no launcher. */
    private String chooseRoot(String launcher, Set<String> activities) {
        if (launcher != null && activities.contains(launcher)) return launcher;
        // name heuristic
        for (String a : activities) {
            String s = a.substring(a.lastIndexOf('.') + 1).toLowerCase();
            if (s.contains("main") || s.contains("splash") || s.contains("launch") || s.contains("home")) return a;
        }
        // most outgoing edges
        String best = null; int bestN = -1;
        for (Map.Entry<String, Set<String>> e : adjacency.entrySet()) {
            if (activities.contains(e.getKey()) && e.getValue().size() > bestN) {
                bestN = e.getValue().size(); best = e.getKey();
            }
        }
        if (best != null) return best;
        // fallback: first activity (deterministic)
        return activities.stream().sorted().findFirst().orElse(null);
    }

    /** Fallback when the manifest can't be parsed: treat classes that extend an
     *  *Activity base as activities (heuristic, by superclass name). */
    private Set<String> detectActivitiesFromDex() {
        Set<String> found = new LinkedHashSet<>();
        for (DexBackedDexFile dex : dexFiles) {
            for (ClassDef cls : dex.getClasses()) {
                String sup = cls.getSuperclass();
                if (sup == null) continue;
                String supName = DexAnalyzer.descToDotted(sup);
                if (supName.endsWith("Activity")) {
                    found.add(DexAnalyzer.descToDotted(cls.getType()));
                }
            }
        }
        return found;
    }

    public List<Edge> getEdges() { return edges; }
    public Map<String, Set<String>> getAdjacency() { return adjacency; }

    public Set<String> targetsOf(String activity) {
        return adjacency.getOrDefault(activity, Set.of());
    }
}
