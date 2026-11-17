package app.control.launcher;

import java.util.Locale;

/**
 * Device status meters for the Deep view hub (v1.39). Pure Java —
 * no Android classes — so the harness exercises the exact
 * shaping the hub renders: byte formatting, used/total
 * fractions, /proc/stat CPU math, battery wording, and traffic
 * totals. The platform reads live in {@link DeviceMeterReads};
 * this class only shapes numbers into honest display values.
 *
 * Honesty rules, pinned by the harness:
 *   - A meter whose inputs are missing or impossible (zero
 *     total, negative values, unparseable input) is
 *     UNAVAILABLE — the hub shows "Unavailable", never a fake
 *     number and never a zero presented as real.
 *   - A fraction the inputs cannot support is NaN (no bar
 *     fill), which is distinct from 0% (an empty-but-real
 *     reading, e.g. a battery at 0%).
 *   - CPU load is an approximation from two /proc/stat
 *     samples; the label says so.
 */
public final class DeviceMeters {

    /** The text a meter shows when it cannot be read. */
    public static final String UNAVAILABLE = "Unavailable";

    /** One meter's display value: what the hub renders. */
    public static final class Reading {
        /** False when the meter could not be read honestly. */
        public final boolean available;
        /** Bar fill, 0..1; NaN when no fill is honest (the
         *  bar draws its empty track and the numbers say why). */
        public final double fraction;
        /** Short numbers text, e.g. "6.1 / 8.0 GB". */
        public final String numbers;
        /** True when the fill should warn (low battery). */
        public final boolean warn;

        public Reading(boolean available, double fraction, String numbers,
                       boolean warn) {
            this.available = available;
            this.fraction = fraction;
            this.numbers = numbers;
            this.warn = warn;
        }

        /** The unavailable reading: no fill, honest text. */
        public static Reading unavailable() {
            return new Reading(false, Double.NaN, UNAVAILABLE, false);
        }
    }

    private DeviceMeters() {}

    /** Human bytes, 1024-based: "512 B", "840 KB", "6.1 GB".
     *  GB/MB carry one decimal; KB rounds; bytes are exact.
     *  Negative input is impossible and reads as unavailable. */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return UNAVAILABLE;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return Math.round(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f GB",
                bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** "used / total" in one shared unit, chosen by the total:
     *  "6.1 / 8.0 GB". Both sides share the unit so the pair
     *  reads at a glance. Impossible inputs read unavailable. */
    public static String formatUsedTotal(long used, long total) {
        if (used < 0 || total <= 0 || used > total) return UNAVAILABLE;
        if (total < 1024L * 1024L) {
            return Math.round(used / 1024.0) + " / " + Math.round(total / 1024.0) + " KB";
        }
        if (total < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f / %.1f MB",
                    used / (1024.0 * 1024.0), total / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f / %.1f GB",
                used / (1024.0 * 1024.0 * 1024.0),
                total / (1024.0 * 1024.0 * 1024.0));
    }

    /** Used fraction of a total, clamped to 0..1; NaN when the
     *  inputs cannot support a fraction (zero/negative total,
     *  negative used, used above total). */
    public static double usedFraction(long used, long total) {
        if (total <= 0 || used < 0 || used > total) return Double.NaN;
        return (double) used / (double) total;
    }

    /** Whole-percent label: "63%". NaN reads as unavailable. */
    public static String formatPercent(double fraction) {
        if (Double.isNaN(fraction)) return UNAVAILABLE;
        return Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 100) + "%";
    }

    // ---- CPU: /proc/stat deltas ----

    /** One "cpu ..." line from /proc/stat, reduced to the two
     *  totals the usage math needs. */
    public static final class CpuSample {
        public final long total;
        public final long idle;

        public CpuSample(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    /** Parse the aggregate "cpu" line of /proc/stat. The line
     *  is "cpu user nice system idle iowait irq softirq steal
     *  guest guest_nice" — every field counts toward the
     *  total, and idle time is idle + iowait. Anything else
     *  (per-cpu lines, short lines, non-numbers) returns null
     *  rather than a guessed sample. */
    public static CpuSample parseCpuLine(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5 || !"cpu".equals(parts[0])) return null;
        long total = 0;
        long idle = 0;
        try {
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                if (v < 0) return null;
                total += v;
                if (i == 4 || i == 5) idle += v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (total <= 0 || idle < 0 || idle > total) return null;
        return new CpuSample(total, idle);
    }

    /** CPU usage between two samples: 1 minus the idle share
     *  of the elapsed time. NaN when the samples are null,
     *  identical, or move backwards — no delta, no number. */
    public static double cpuUsage(CpuSample before, CpuSample after) {
        if (before == null || after == null) return Double.NaN;
        long totalDelta = after.total - before.total;
        long idleDelta = after.idle - before.idle;
        if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) {
            return Double.NaN;
        }
        return 1.0 - ((double) idleDelta / (double) totalDelta);
    }

    /** CPU label: "63% · approx". The approximation is part
     *  of the label, not a footnote. */
    public static String cpuLabel(double usage) {
        if (Double.isNaN(usage)) return UNAVAILABLE;
        return formatPercent(usage) + " · approx";
    }

    // ---- Battery ----

    /** Battery label: "82%", plus " · Charging" while plugged
     *  in and " · Full" at 100% on the charger. A negative or
     *  >100 capacity is impossible and reads unavailable. */
    public static String batteryLabel(int percent, boolean charging) {
        if (percent < 0 || percent > 100) return UNAVAILABLE;
        if (charging && percent >= 100) return "100% · Full";
        if (charging) return percent + "% · Charging";
        return percent + "%";
    }

    /** Warn fill: low and not charging. */
    public static boolean batteryWarn(int percent, boolean charging) {
        return percent >= 0 && percent < 20 && !charging;
    }

    // ---- Data: TrafficStats totals ----

    /** Traffic totals label: "RX 1.2 GB · TX 340 MB". Either
     *  side unknown (TrafficStats.UNSUPPORTED) reads
     *  unavailable — a half-total would mislead. */
    public static String trafficLabel(long rxBytes, long txBytes) {
        if (rxBytes < 0 || txBytes < 0) return UNAVAILABLE;
        return "RX " + formatBytes(rxBytes) + " · TX " + formatBytes(txBytes);
    }

    /** Mobile share of all device traffic, for the data bar:
     *  NaN when mobile totals are unknown or the device total
     *  is zero — the bar draws empty and the numbers carry the
     *  totals, so nothing is faked. */
    public static double mobileShare(long mobileRx, long mobileTx,
                                     long totalRx, long totalTx) {
        if (mobileRx < 0 || mobileTx < 0 || totalRx < 0 || totalTx < 0) {
            return Double.NaN;
        }
        long mobile = mobileRx + mobileTx;
        long total = totalRx + totalTx;
        if (total <= 0 || mobile > total) return Double.NaN;
        return (double) mobile / (double) total;
    }
}
