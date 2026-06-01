package com.mythosaur.core;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps requested permissions to where (if anywhere) the app actually exercises them.
 *
 * <p>For each permission it knows the Android APIs that require it; it scans the DEX for
 * call sites of those APIs and links the permission to the using classes. This turns the
 * flat manifest permission list into an <b>abuse map</b>: which dangerous permissions are
 * genuinely used in code (and where), which are declared but unused (over-privilege), and
 * which abuse-prone permissions (overlay, accessibility, install-packages, …) are present.
 */
public class PermissionAnalyzer {

    public enum Level { DANGEROUS, ABUSE_PRONE, NORMAL, SIGNATURE }

    /** One API call site that evidences a permission's use. */
    public record Site(String inClass, String api) {}

    public static final class Perm {
        public final String name;          // android.permission.CAMERA
        public final String shortName;     // CAMERA
        public Level level;
        public boolean declared;           // present in the manifest
        public final List<Site> sites = new ArrayList<>();   // where it's used in code
        public final Set<String> usingClasses = new TreeSet<>();
        public Perm(String name, Level level, boolean declared) {
            this.name = name; this.level = level; this.declared = declared;
            int dot = name.lastIndexOf('.');
            this.shortName = dot >= 0 ? name.substring(dot + 1) : name;
        }
        public boolean used() { return !sites.isEmpty(); }
        /** Declared + dangerous/abuse-prone + actually used = the real red flags. */
        public boolean abused() { return declared && used() && (level == Level.DANGEROUS || level == Level.ABUSE_PRONE); }
        /** Declared dangerous permission with no detected use = over-privilege. */
        public boolean overPrivileged() { return declared && !used() && level == Level.DANGEROUS; }
    }

    public static final class Report {
        public final List<Perm> permissions = new ArrayList<>();   // declared + any used-undeclared
        public int dangerousCount, abuseProneCount, usedUndeclared, overPrivilegedCount;
    }

    // ---- permission → API indicators (ownerDescriptorPrefix, optional method substring) ----
    private record Rule(String perm, String ownerPrefix, String member, String label) {}
    private static final List<Rule> RULES = new ArrayList<>();
    private static void r(String p, String owner, String member, String label) {
        RULES.add(new Rule(p, owner, member, label));
    }
    static {
        r("CAMERA", "Landroid/hardware/Camera;", null, "Camera");
        r("CAMERA", "Landroid/hardware/camera2/", null, "camera2");
        r("RECORD_AUDIO", "Landroid/media/MediaRecorder;", null, "MediaRecorder");
        r("RECORD_AUDIO", "Landroid/media/AudioRecord;", null, "AudioRecord");
        r("ACCESS_FINE_LOCATION", "Landroid/location/LocationManager;", null, "LocationManager");
        r("ACCESS_FINE_LOCATION", "Lcom/google/android/gms/location/", null, "GMS location");
        r("ACCESS_COARSE_LOCATION", "Landroid/location/LocationManager;", null, "LocationManager");
        r("READ_PHONE_STATE", "Landroid/telephony/TelephonyManager;", "getDeviceId", "getDeviceId (IMEI)");
        r("READ_PHONE_STATE", "Landroid/telephony/TelephonyManager;", "getImei", "getImei");
        r("READ_PHONE_STATE", "Landroid/telephony/TelephonyManager;", "getSubscriberId", "getSubscriberId (IMSI)");
        r("READ_PHONE_STATE", "Landroid/telephony/TelephonyManager;", "getSimSerialNumber", "getSimSerial");
        r("READ_PHONE_NUMBERS", "Landroid/telephony/TelephonyManager;", "getLine1Number", "getLine1Number");
        r("SEND_SMS", "Landroid/telephony/SmsManager;", "sendTextMessage", "SmsManager.sendText");
        r("SEND_SMS", "Landroid/telephony/SmsManager;", "sendMultipartTextMessage", "SmsManager.sendMultipart");
        r("READ_SMS", "Landroid/telephony/SmsMessage;", null, "SmsMessage");
        r("READ_CONTACTS", "Landroid/provider/ContactsContract", null, "ContactsContract");
        r("READ_CALL_LOG", "Landroid/provider/CallLog", null, "CallLog");
        r("READ_CALENDAR", "Landroid/provider/CalendarContract", null, "CalendarContract");
        r("GET_ACCOUNTS", "Landroid/accounts/AccountManager;", null, "AccountManager");
        r("CALL_PHONE", "Landroid/telephony/TelephonyManager;", "call", "TelephonyManager.call");
        r("BODY_SENSORS", "Landroid/hardware/SensorManager;", null, "SensorManager");
        r("READ_EXTERNAL_STORAGE", "Landroid/os/Environment;", "getExternalStorage", "Environment.externalStorage");
        r("WRITE_EXTERNAL_STORAGE", "Landroid/os/Environment;", "getExternalStorage", "Environment.externalStorage");
        r("BLUETOOTH_CONNECT", "Landroid/bluetooth/BluetoothAdapter;", null, "BluetoothAdapter");
        r("BLUETOOTH_SCAN", "Landroid/bluetooth/le/", null, "BLE scanner");
        r("INTERNET", "Ljava/net/HttpURLConnection;", null, "HttpURLConnection");
        r("INTERNET", "Ljava/net/Socket;", null, "Socket");
        r("INTERNET", "Lokhttp3/", null, "OkHttp");
        r("INTERNET", "Ljava/net/URL;", "openConnection", "URL.openConnection");
        // abuse-prone / high-risk
        r("SYSTEM_ALERT_WINDOW", "Landroid/view/WindowManager;", "addView", "WindowManager overlay");
        r("REQUEST_INSTALL_PACKAGES", "Landroid/content/pm/PackageInstaller", null, "PackageInstaller");
        r("WRITE_SETTINGS", "Landroid/provider/Settings$System;", null, "Settings.System");
        r("PACKAGE_USAGE_STATS", "Landroid/app/usage/UsageStatsManager;", null, "UsageStatsManager");
        r("GET_TASKS", "Landroid/app/ActivityManager;", "getRunningTasks", "getRunningTasks");
    }

    // Classic Android "dangerous" runtime permissions.
    private static final Set<String> DANGEROUS = Set.of(
            "READ_CALENDAR","WRITE_CALENDAR","CAMERA","READ_CONTACTS","WRITE_CONTACTS","GET_ACCOUNTS",
            "ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","ACCESS_BACKGROUND_LOCATION","RECORD_AUDIO",
            "READ_PHONE_STATE","READ_PHONE_NUMBERS","CALL_PHONE","ANSWER_PHONE_CALLS","READ_CALL_LOG",
            "WRITE_CALL_LOG","ADD_VOICEMAIL","USE_SIP","PROCESS_OUTGOING_CALLS","BODY_SENSORS","SEND_SMS",
            "RECEIVE_SMS","READ_SMS","RECEIVE_WAP_PUSH","RECEIVE_MMS","READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE","ACCESS_MEDIA_LOCATION","ACTIVITY_RECOGNITION","READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO","READ_MEDIA_AUDIO","POST_NOTIFICATIONS","BLUETOOTH_SCAN","BLUETOOTH_CONNECT",
            "BLUETOOTH_ADVERTISE","NEARBY_WIFI_DEVICES");

    // Not "dangerous" by group, but heavily abused by malware/spyware.
    private static final Set<String> ABUSE_PRONE = Set.of(
            "SYSTEM_ALERT_WINDOW","REQUEST_INSTALL_PACKAGES","BIND_ACCESSIBILITY_SERVICE","BIND_DEVICE_ADMIN",
            "WRITE_SETTINGS","PACKAGE_USAGE_STATS","MANAGE_EXTERNAL_STORAGE","REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "RECEIVE_BOOT_COMPLETED","READ_LOGS","GET_TASKS","REORDER_TASKS","DISABLE_KEYGUARD",
            "REQUEST_DELETE_PACKAGES","BIND_NOTIFICATION_LISTENER_SERVICE","WRITE_SECURE_SETTINGS");

    private final Project project;
    public PermissionAnalyzer(Project project) { this.project = project; }

    public Report analyze() {
        Report report = new Report();
        List<String> declared = project.getManifest().getPermissions();

        // index declared permissions by short name
        Map<String, Perm> byShort = new LinkedHashMap<>();
        for (String full : declared) {
            String shortName = full.substring(full.lastIndexOf('.') + 1);
            Perm p = new Perm(full, classify(shortName), true);
            byShort.put(shortName, p);
            report.permissions.add(p);
        }

        // scan the DEX once for permission-bearing API call sites
        for (DexBackedDexFile dex : project.getDexFiles()) {
            for (ClassDef cls : dex.getClasses()) {
                String inClass = DexAnalyzer.descToDotted(cls.getType());
                for (Method m : cls.getMethods()) {
                    if (m.getImplementation() == null) continue;
                    for (Instruction insn : m.getImplementation().getInstructions()) {
                        if (!(insn instanceof ReferenceInstruction ri)) continue;
                        match(ri.getReference(), inClass, byShort, report);
                    }
                }
            }
        }

        for (Perm p : report.permissions) {
            if (p.level == Level.DANGEROUS) report.dangerousCount++;
            if (p.level == Level.ABUSE_PRONE) report.abuseProneCount++;
            if (p.overPrivileged()) report.overPrivilegedCount++;
        }
        // sort: abused first, then dangerous, then by usage
        report.permissions.sort((a, b) -> {
            int r = Integer.compare(rank(b), rank(a));
            return r != 0 ? r : Integer.compare(b.sites.size(), a.sites.size());
        });
        return report;
    }

    private static int rank(Perm p) {
        if (p.abused()) return 4;
        if (p.level == Level.DANGEROUS) return 3;
        if (p.level == Level.ABUSE_PRONE) return 2;
        if (p.level == Level.NORMAL) return 1;
        return 0;
    }

    private void match(Reference ref, String inClass, Map<String, Perm> byShort, Report report) {
        String owner, member;
        if (ref instanceof MethodReference mr) { owner = mr.getDefiningClass(); member = mr.getName(); }
        else if (ref instanceof FieldReference fr) { owner = fr.getDefiningClass(); member = fr.getName(); }
        else return;

        for (Rule rule : RULES) {
            if (!owner.startsWith(rule.ownerPrefix())) continue;
            if (rule.member() != null && (member == null || !member.contains(rule.member()))) continue;
            Perm p = byShort.get(rule.perm());
            if (p == null) {
                // API used but permission NOT declared — used-undeclared (interesting)
                p = new Perm("android.permission." + rule.perm(), classify(rule.perm()), false);
                byShort.put(rule.perm(), p);
                report.permissions.add(p);
                report.usedUndeclared++;
            }
            if (p.usingClasses.add(inClass) && p.sites.size() < 200) {
                p.sites.add(new Site(inClass, rule.label()));
            }
            break; // first matching rule is enough for this reference
        }
    }

    private static Level classify(String shortName) {
        if (DANGEROUS.contains(shortName)) return Level.DANGEROUS;
        if (ABUSE_PRONE.contains(shortName)) return Level.ABUSE_PRONE;
        if (shortName.contains("BIND_") || shortName.startsWith("MANAGE_")) return Level.SIGNATURE;
        return Level.NORMAL;
    }
}
