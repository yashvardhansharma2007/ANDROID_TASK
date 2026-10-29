package com.tasklite.app;

import java.io.FileInputStream;
import java.io.IOException;

/** Tiny, allocation-light helpers for reading /proc and /sys (the kernel's "system call" window). */
final class ProcFs {
    private ProcFs() {}

    /** Reads up to buf.length bytes of a small file. Returns bytes read, or -1 if unreadable. */
    static int read(String path, byte[] buf) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return total;
        } catch (IOException e) {
            return -1;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    static String readString(String path, int maxBytes) {
        byte[] b = new byte[maxBytes];
        int n = read(path, b);
        return n <= 0 ? null : new String(b, 0, n).trim();
    }

    /** Parses consecutive unsigned integers on one line starting at 'start'. Returns how many were found. */
    static int parseLongs(byte[] b, int len, int start, long[] out) {
        int count = 0, i = start;
        while (i < len && b[i] != '\n' && count < out.length) {
            while (i < len && b[i] != '\n' && (b[i] < '0' || b[i] > '9')) i++;
            if (i >= len || b[i] == '\n') break;
            long v = 0;
            while (i < len && b[i] >= '0' && b[i] <= '9') {
                v = v * 10 + (b[i] - '0');
                i++;
            }
            out[count++] = v;
        }
        return count;
    }

    /** Value of a "Key:   123 kB" style line, or -1 when the key is missing. */
    static long field(String text, String key) {
        String k = key + ":";
        int valStart;
        if (text.startsWith(k)) {
            valStart = k.length();
        } else {
            int at = text.indexOf("\n" + k);
            if (at < 0) return -1;
            valStart = at + 1 + k.length();
        }
        int end = text.indexOf('\n', valStart);
        if (end < 0) end = text.length();
        long v = 0;
        boolean any = false;
        for (int j = valStart; j < end; j++) {
            char c = text.charAt(j);
            if (c >= '0' && c <= '9') {
                v = v * 10 + (c - '0');
                any = true;
            } else if (any) {
                break;
            }
        }
        return any ? v : -1;
    }
}
