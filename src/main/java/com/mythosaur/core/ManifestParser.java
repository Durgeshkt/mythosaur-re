package com.mythosaur.core;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls AndroidManifest.xml and extracts the component model: package, all components
 * (activity / service / receiver / provider) with exported flags, the launcher, and the
 * requested permissions.
 *
 * <p>Manifest source preference: the apktool-decoded {@code apktool-out/AndroidManifest.xml}
 * (gold-standard XML) if a Full Decode has been run, otherwise jadx's in-memory decode —
 * so the App Flow and Permissions views work instantly out of the box and get even more
 * accurate after a Full Decode.
 */
public class ManifestParser {

    public static final class Component {
        public final String name;       // fully-qualified class name
        public final String kind;       // activity | service | receiver | provider
        public final boolean exported;
        public final boolean launcher;
        public Component(String name, String kind, boolean exported, boolean launcher) {
            this.name = name; this.kind = kind; this.exported = exported; this.launcher = launcher;
        }
    }

    private String packageName = "";
    private String applicationClass = null;
    private String launcherActivity = null;
    private String versionName = null, versionCode = null;
    private String minSdk = null, targetSdk = null, compileSdk = null;
    private String source = "jadx";
    private final List<Component> activities = new ArrayList<>();
    private final List<Component> services = new ArrayList<>();
    private final List<Component> receivers = new ArrayList<>();
    private final List<Component> providers = new ArrayList<>();
    private final Set<String> activityNames = new HashSet<>();
    private final List<String> permissions = new ArrayList<>();
    private String rawXml = "";

    public ManifestParser(JadxDecompiler jadx) { this(jadx, null, null); }

    public ManifestParser(JadxDecompiler jadx, Path workspace) { this(jadx, workspace, null); }

    public ManifestParser(JadxDecompiler jadx, Path workspace, java.io.File workingApk) {
        String xml = loadFromApktool(workspace);
        if (looksLikeManifest(xml)) {
            source = "apktool";
        } else {
            xml = loadFromJadx(jadx);
            if (looksLikeManifest(xml)) {
                source = "jadx";
            } else {
                // jadx/apktool choked (e.g. a malware-tampered AXML string pool) — decode the raw
                // binary manifest ourselves with the tolerant decoder.
                String axml = loadFromAxml(workingApk);
                if (looksLikeManifest(axml)) { xml = axml; source = "axml (tolerant)"; }
            }
        }
        this.rawXml = xml == null ? "" : xml;
        if (!rawXml.isEmpty()) parse(rawXml);
        // apktool moves version/sdk out of the manifest into apktool.yml — backfill from there
        if (workspace != null) supplementFromApktoolYml(workspace);
    }

    private static boolean looksLikeManifest(String xml) {
        return xml != null && xml.contains("<manifest");
    }

    /** Last-resort: pull AndroidManifest.xml bytes from the (repaired) APK and decode the binary
     *  XML with {@link AxmlDecoder}, which tolerates the string-pool tampering that defeats
     *  jadx/aapt/apktool. */
    private String loadFromAxml(java.io.File apk) {
        if (apk == null || !apk.isFile()) return null;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry e = zip.getEntry("AndroidManifest.xml");
            if (e == null) return null;
            byte[] bytes;
            try (java.io.InputStream in = zip.getInputStream(e)) { bytes = in.readAllBytes(); }
            return AxmlDecoder.decode(bytes);
        } catch (Exception ex) {
            return null;
        }
    }

    private void supplementFromApktoolYml(Path workspace) {
        if (versionCode != null && versionName != null && minSdk != null && targetSdk != null) return;
        try {
            Path yml = workspace.resolve("apktool-out").resolve("apktool.yml");
            if (!Files.isRegularFile(yml)) return;
            String y = Files.readString(yml);
            if (versionCode == null) versionCode = first(y, "versionCode:\\s*'?([^'\\n\\r]+)'?");
            if (versionName == null) versionName = first(y, "versionName:\\s*'?([^'\\n\\r]+)'?");
            if (minSdk == null) minSdk = first(y, "minSdkVersion:\\s*'?([^'\\n\\r]+)'?");
            if (targetSdk == null) targetSdk = first(y, "targetSdkVersion:\\s*'?([^'\\n\\r]+)'?");
        } catch (Exception ignored) {}
    }

    private String loadFromApktool(Path workspace) {
        if (workspace == null) return null;
        try {
            Path mf = workspace.resolve("apktool-out").resolve("AndroidManifest.xml");
            if (Files.isRegularFile(mf)) return Files.readString(mf);
        } catch (Exception ignored) {}
        return null;
    }

    private String loadFromJadx(JadxDecompiler jadx) {
        try {
            for (ResourceFile rf : jadx.getResources()) {
                if (rf.getType() == ResourceType.MANIFEST) {
                    return rf.loadContent().getText().getCodeStr();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void parse(String xml) {
        Matcher pkg = Pattern.compile("package=\"([^\"]+)\"").matcher(xml);
        if (pkg.find()) packageName = pkg.group(1);

        versionCode = first(xml, "android:versionCode=\"([^\"]+)\"");
        versionName = first(xml, "android:versionName=\"([^\"]+)\"");
        minSdk = first(xml, "minSdkVersion=\"([^\"]+)\"");
        targetSdk = first(xml, "targetSdkVersion=\"([^\"]+)\"");
        compileSdk = first(xml, "compileSdkVersion=\"([^\"]+)\"");
        if (compileSdk == null) compileSdk = first(xml, "platformBuildVersionCode=\"([^\"]+)\"");

        // <application android:name="..."> — packers replace this with their loader
        Matcher appTag = Pattern.compile("<application\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(xml);
        if (appTag.find()) {
            String name = attr(appTag.group(0), "android:name");
            if (name != null) applicationClass = resolveName(name);
        }

        // requested permissions (incl. uses-permission-sdk-23)
        Matcher perm = Pattern.compile(
                "<uses-permission(?:-sdk-\\d+)?\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(xml);
        Set<String> seen = new LinkedHashSet<>();
        while (perm.find()) seen.add(perm.group(1));
        permissions.addAll(seen);

        scanComponents(xml, "activity", activities);
        scanComponents(xml, "service", services);
        scanComponents(xml, "receiver", receivers);
        scanComponents(xml, "provider", providers);

        for (Component a : activities) {
            activityNames.add(a.name);
            if (a.launcher && launcherActivity == null) launcherActivity = a.name;
        }
    }

    /**
     * Manual tag scan: each {@code <tag ...>} opening tag plus its body up to {@code </tag>}.
     * (A single regex breaks on the inner self-closing {@code <action .../>} tags.)
     */
    private void scanComponents(String xml, String tag, List<Component> out) {
        String open = "<" + tag, close = "</" + tag + ">";
        int i = 0;
        while ((i = xml.indexOf(open, i)) >= 0) {
            char after = i + open.length() < xml.length() ? xml.charAt(i + open.length()) : ' ';
            if (after != ' ' && after != '\t' && after != '\n' && after != '\r' && after != '>') {
                i += open.length(); continue; // e.g. "<activity-alias" when scanning "<activity"
            }
            int tagEnd = xml.indexOf('>', i);
            if (tagEnd < 0) break;
            String openTag = xml.substring(i, tagEnd + 1);
            boolean selfClosing = openTag.endsWith("/>");

            String body;
            if (selfClosing) { body = openTag; i = tagEnd + 1; }
            else {
                int c = xml.indexOf(close, tagEnd);
                body = c >= 0 ? xml.substring(i, c) : xml.substring(i);
                i = c >= 0 ? c + close.length() : xml.length();
            }

            String name = attr(openTag, "android:name");
            if (name == null) continue;
            name = resolveName(name);
            boolean exported = "true".equalsIgnoreCase(attr(openTag, "android:exported"));
            boolean isLauncher = body.contains("android.intent.action.MAIN")
                    && body.contains("android.intent.category.LAUNCHER");
            out.add(new Component(name, tag, exported, isLauncher));
        }
    }

    /** ".Foo" -> package.Foo ; "Foo" -> package.Foo ; "com.x.Foo" -> unchanged. */
    private String resolveName(String name) {
        if (name.startsWith(".")) return packageName + name;
        if (!name.contains(".")) return packageName + "." + name;
        return name;
    }

    private static String attr(String attrs, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String first(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    public String getPackageName() { return packageName; }
    public String getApplicationClass() { return applicationClass; }
    public String getLauncherActivity() { return launcherActivity; }
    public List<Component> getActivities() { return activities; }
    public List<Component> getServices() { return services; }
    public List<Component> getReceivers() { return receivers; }
    public List<Component> getProviders() { return providers; }
    public Set<String> getActivityNames() { return activityNames; }
    public List<String> getPermissions() { return permissions; }
    public boolean isActivity(String className) { return activityNames.contains(className); }
    public String getRawXml() { return rawXml; }
    public String getVersionName() { return versionName; }
    public String getVersionCode() { return versionCode; }
    public String getMinSdk() { return minSdk; }
    public String getTargetSdk() { return targetSdk; }
    public String getCompileSdk() { return compileSdk; }
    /** "apktool" if the gold-standard decoded XML was used, else "jadx". */
    public String getSource() { return source; }
}
