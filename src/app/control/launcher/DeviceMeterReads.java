package app.control.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * The platform reads behind the Deep view hub meters (v1.39).
 * Every read is app-side — no bridge calls — so the meters work
 * even when the bridge is off. Each read returns a
 * {@link DeviceMeters.Reading} that is either a real value or
 * honestly unavailable; nothing here invents a number.
 *
 * Callers run these on a background thread: the CPU read sleeps
 * between its two /proc/stat samples.
 */
public final class DeviceMeterReads {

    /** Delay between the two /proc/stat samples for CPU load. */
    public static final int CPU_SAMPLE_DELAY_MS = 300;

    private DeviceMeterReads() {}

    /** Memory: used = total − available, from
     *  ActivityManager.MemoryInfo. */
    public static DeviceMeters.Reading readMemory(Context context) {
        try {
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return DeviceMeters.Reading.unavailable();
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long total = info.totalMem;
            long avail = info.availMem;
            if (total <= 0 || avail < 0 || avail > total) {
                return DeviceMeters.Reading.unavailable();
            }
            long used = total - avail;
            return new DeviceMeters.Reading(true,
                    DeviceMeters.usedFraction(used, total),
                    DeviceMeters.formatUsedTotal(used, total), false);
        } catch (Exception e) {
            return DeviceMeters.Reading.unavailable();
        }
    }

    /** Battery: capacity from BatteryManager, with the sticky
     *  battery-changed intent as the fallback and the source of
     *  the charging state. No permission is needed for either. */
    public static DeviceMeters.Reading readBattery(Context context) {
        try {
            int percent = -1;
            try {
                BatteryManager bm = (BatteryManager)
                        context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    percent = bm.getIntProperty(
                            BatteryManager.BATTERY_PROPERTY_CAPACITY);
                }
            } catch (Exception ignored) {
            }
            Intent intent = null;
            try {
                intent = context.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Exception ignored) {
            }
            boolean charging = false;
            if (intent != null) {
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                if (percent < 0 || percent > 100) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        percent = Math.round(level * 100f / scale);
                    }
                }
            }
            if (percent < 0 || percent > 100) {
                return DeviceMeters.Reading.unavailable();
            }
            return new DeviceMeters.Reading(true, percent / 100.0,
                    DeviceMeters.batteryLabel(percent, charging),
                    DeviceMeters.batteryWarn(percent, charging));
        } catch (Exception e) {
            return DeviceMeters.Reading.unavailable();
        }
    }

    /** Storage: the data partition via StatFs — used = total −
     *  available. */
    public static DeviceMeters.Reading readStorage() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * blockSize;
            long avail = stat.getAvailableBlocksLong() * blockSize;
            if (total <= 0 || avail < 0 || avail > total) {
                return DeviceMeters.Reading.unavailable();
            }
            long used = total - avail;
            return new DeviceMeters.Reading(true,
                    DeviceMeters.usedFraction(used, total),
                    DeviceMeters.formatUsedTotal(used, total), false);
        } catch (Exception e) {
            return DeviceMeters.Reading.unavailable();
        }
    }

    /** Data: TrafficStats device totals since boot. The bar is
     *  the mobile share of all traffic when the mobile
     *  counters are available; when they are not, the bar
     *  stays empty and the numbers still carry the totals. */
    public static DeviceMeters.Reading readData() {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx < 0 || tx < 0) return DeviceMeters.Reading.unavailable();
            long mobileRx = TrafficStats.getMobileRxBytes();
            long mobileTx = TrafficStats.getMobileTxBytes();
            double share = DeviceMeters.mobileShare(mobileRx, mobileTx, rx, tx);
            String numbers = DeviceMeters.trafficLabel(rx, tx) + " · since boot";
            if (Double.isNaN(share)) {
                numbers += " · mobile share unknown";
            }
            return new DeviceMeters.Reading(true, share, numbers, false);
        } catch (Exception e) {
            return DeviceMeters.Reading.unavailable();
        }
    }

    /** Processing: overall CPU load from two /proc/stat
     *  samples {@link #CPU_SAMPLE_DELAY_MS} apart. Sleeps on
     *  the calling thread — background only. */
    public static DeviceMeters.Reading readCpu() {
        try {
            DeviceMeters.CpuSample before = readCpuSample();
            if (before == null) return DeviceMeters.Reading.unavailable();
            try {
                Thread.sleep(CPU_SAMPLE_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DeviceMeters.Reading.unavailable();
            }
            DeviceMeters.CpuSample after = readCpuSample();
            double usage = DeviceMeters.cpuUsage(before, after);
            if (Double.isNaN(usage)) return DeviceMeters.Reading.unavailable();
            return new DeviceMeters.Reading(true, usage,
                    DeviceMeters.cpuLabel(usage), false);
        } catch (Exception e) {
            return DeviceMeters.Reading.unavailable();
        }
    }

    private static DeviceMeters.CpuSample readCpuSample() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/stat"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("cpu ")) {
                    return DeviceMeters.parseCpuLine(line);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
