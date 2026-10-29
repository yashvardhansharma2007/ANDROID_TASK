package com.tasklite.app;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Pro" mode: after a one-time `adb shell pm grant com.tasklite.app android.permission.DUMP`
 * the app may call the system's dumpsys service, which is the only supported way for a
 * non-root app to see every process (Android hides /proc/<pid> of other apps).
 */
final class Pro {
    static final int NEED_GRANT = 0, ACTIVE = 1, ERROR = 2;
    static final String PERMISSION = "android.permission.DUMP";

    private static final Pattern P_LOAD = Pattern.compile("^Load: ([0-9.]+) / ([0-9.]+) / ([0-9.]+)");
    private static final Pattern P_TOTAL = Pattern.compile("^\\s*([0-9.]+)% TOTAL:");
    // process lines are indented by 2 spaces, thread lines by 4 -> only 1..2 spaces match
    private static final Pattern P_PROC = Pattern.compile(
            "^ {1,2}[+-]?([0-9.]+)% (\\d+)/(.+?): ([0-9.]+)% user \\+ ([0-9.]+)% kernel");
    // real line (iQOO / Android 14): "  #83: vis    BFGS ---NFU-T 3414:com.android.launcher3/u0a106 act:activities|client"
    // older Android prefixes the line with "Proc" or "PERS", so that word is optional
    private static final Pattern P_LRU = Pattern.compile(
            "^\\s*(?:\\w+\\s+)?#\\s*\\d+:\\s+(\\S+)\\s.*?\\b(\\d+):(\\S+?)/\\S+");
    private static final Pattern P_MEM = Pattern.compile(
            "^\\s*([0-9,]+)K: (.+?) \\(pid (\\d+)(?: / [^)]*)?\\)");

    /** pid -> last known PSS, so cheap polls (cpu only) can still show memory. */
    private static final HashMap<Integer, Long> pssCache = new HashMap<>();

    private Pro() {}

    static ProResult poll(Context ctx, boolean withMem, AppIndex idx) {
        ProResult r = new ProResult();
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            r.state = NEED_GRANT;
            return r;
        }
        String cpuTxt = exec(6, "cpuinfo");
        LinkedHashMap<Integer, ProcRow> map = new LinkedHashMap<>();
        parseCpu(cpuTxt, map, r);
        if (r.cpuTotal < 0 && map.isEmpty()) {
            // this firmware hides the cpuinfo service from apps: fall back to the activity manager's process list
            String lruTxt = exec(8, "activity", "lru");
            parseLru(lruTxt, map);
            if (map.isEmpty()) {
                lruTxt = exec(12, "activity", "processes");
                parseLru(lruTxt, map);
            }
            if (map.isEmpty()) {
                r.state = ERROR;
                r.message = brief(cpuTxt) + " | " + brief(lruTxt);
                return r;
            }
            r.limited = true;
            r.state = ACTIVE;
            for (ProcRow p : map.values()) resolve(p, idx);
            r.rows.addAll(map.values());
            return r;
        }
        r.state = ACTIVE;
        if (withMem) {
            parseMem(exec(20, "meminfo"), map);
            r.memLoaded = true;
        }
        for (ProcRow p : map.values()) {
            if (p.pssKb < 0) {
                Long cached = pssCache.get(p.pid);
                if (cached != null) p.pssKb = cached;
            }
            resolve(p, idx);
        }
        r.rows.addAll(map.values());
        return r;
    }

    private static void resolve(ProcRow p, AppIndex idx) {
        HashMap<String, String> labels = idx.label;
        String base = p.name;
        int colon = base.indexOf(':');
        if (colon > 0) base = base.substring(0, colon);
        for (int i = 0; i < 3 && !labels.containsKey(base); i++) {
            int dot = base.lastIndexOf('.');
            if (dot <= 0) break;
            base = base.substring(0, dot);
        }
        String lab = labels.get(base);
        if (lab != null) {
            p.pkg = base;
            p.label = lab;
        } else {
            p.label = p.name;
        }
    }

    private static void parseCpu(String text, LinkedHashMap<Integer, ProcRow> map, ProResult r) {
        for (String line : text.split("\n")) {
            Matcher m = P_LOAD.matcher(line);
            if (m.find()) {
                r.load1 = parseF(m.group(1));
                continue;
            }
            m = P_TOTAL.matcher(line);
            if (m.find()) {
                r.cpuTotal = parseF(m.group(1));
                continue;
            }
            m = P_PROC.matcher(line);
            if (m.find()) {
                ProcRow p = new ProcRow();
                p.cpu = parseF(m.group(1));
                p.pid = Integer.parseInt(m.group(2));
                p.name = m.group(3);
                map.put(p.pid, p);
            }
        }
    }


    private static void parseLru(String text, LinkedHashMap<Integer, ProcRow> map) {
        for (String line : text.split("\n")) {
            Matcher m = P_LRU.matcher(line);
            if (!m.find()) continue;
            ProcRow p = new ProcRow();
            p.pid = Integer.parseInt(m.group(2));
            p.name = m.group(3);
            p.cpu = -1f;
            setImportance(p, m.group(1));
            map.put(p.pid, p);
        }
    }

    /** oom_adj label printed by Android (ntv, sys, pers, fore, vis, prcp, svc, home, prev, svcb, cch+N). */
    private static void setImportance(ProcRow p, String tag) {
        String t = tag.toLowerCase();
        if (t.startsWith("ntv") || t.startsWith("sys") || t.startsWith("pers") || t.startsWith("psvc")) { p.importance = "System"; p.rank = 0; }
        else if (t.startsWith("fore")) { p.importance = "Foreground"; p.rank = 1; }
        else if (t.startsWith("vis")) { p.importance = "Visible"; p.rank = 2; }
        else if (t.startsWith("prcp") || t.startsWith("bkup")) { p.importance = "Perceptible"; p.rank = 3; }
        else if (t.startsWith("hvy")) { p.importance = "Heavy"; p.rank = 4; }
        else if (t.startsWith("svc")) { p.importance = "Service"; p.rank = 5; }
        else if (t.startsWith("home")) { p.importance = "Home"; p.rank = 6; }
        else if (t.startsWith("prev")) { p.importance = "Previous"; p.rank = 7; }
        else if (t.startsWith("cch")) { p.importance = "Cached"; p.rank = 8; }
        else { p.importance = tag; p.rank = 9; }
    }

    private static void parseMem(String text, LinkedHashMap<Integer, ProcRow> map) {
        boolean inSection = false, found = false;
        for (String line : text.split("\n")) {
            if (!inSection) {
                if (line.startsWith("Total PSS by process:")) {
                    inSection = true;
                    found = true;
                }
                continue;
            }
            if (line.trim().isEmpty()) break;
            Matcher m = P_MEM.matcher(line);
            if (!m.find()) continue;
            long kb = Long.parseLong(m.group(1).replace(",", ""));
            int pid = Integer.parseInt(m.group(3));
            ProcRow p = map.get(pid);
            if (p == null) {
                p = new ProcRow();
                p.pid = pid;
                p.name = m.group(2);
                map.put(pid, p);
            }
            p.pssKb = kb;
            pssCache.put(pid, kb);
        }
        if (!found) pssCache.clear();
    }

    private static float parseF(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static String brief(String s) {
        String t = s == null ? "" : s.trim().replace('\n', ' ');
        if (t.isEmpty()) return "dumpsys returned no output";
        return t.length() > 140 ? t.substring(0, 140) + "..." : t;
    }

    /** Runs `dumpsys <service>` and returns its output (empty string on failure). */
    private static String exec(int timeoutSec, String... args) {
        Process p = null;
        try {
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
            cmd.add("/system/bin/dumpsys");
            for (String a : args) cmd.add(a);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder(16384);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()), 16384);
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (System.currentTimeMillis() > deadline) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
