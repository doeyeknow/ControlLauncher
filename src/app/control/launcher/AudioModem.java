package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Acoustic modem (v1.8): keys travel by sound instead of QR codes.
 * Pure Java, no native code, no libraries — the manual build has no
 * NDK/Gradle, and the same code runs on the JVM harness.
 *
 * Modulation: dual-tone 16-ary FSK. Each symbol is one tone from a
 * low group and one from a high group, so one symbol carries one
 * nibble (4 bits). Tones sit in the voice band phone speakers and
 * microphones reproduce well, 200 Hz apart, with no tone a harmonic
 * of another in the set:
 *
 *   low  = 1250, 1450, 1650, 1850 Hz  (nibble bits 3..2)
 *   high = 2150, 2350, 2550, 2750 Hz  (nibble bits 1..0)
 *
 * Symbols are 32 ms (512 samples at 16 kHz) with 4 ms raised-cosine
 * edge ramps, so a symbol survives a real room: the direct path
 * dominates at the close range the UI asks for, and the ramps keep
 * spectral splatter down. Robustness was chosen over speed — a
 * contact card takes about six seconds to play.
 *
 * Frame on the air:
 *   [100 ms silence][8 preamble symbols: 0x0,0xF alternating]
 *   [sync 0x6, 0x9][length:1][payload][CRC-16/CCITT:2][50 ms silence]
 * The length byte caps the payload at 200 bytes; key payloads are
 * far smaller (see AudioKeyPayload).
 *
 * Decoding never assumes the sender's and receiver's sample clocks
 * agree: the decoder classifies the stream on a fine 64-sample grid
 * with Goertzel magnitudes (phase-insensitive, so a 1% clock offset
 * only shifts tones ~1% — far inside the 200 Hz spacing), then
 * segments the fine nibble stream into symbols by run length, which
 * follows the actual symbol boundaries instead of a predicted grid.
 * A CRC failure is reported, never a wrong payload — the sender
 * replays, the receiver keeps listening.
 */
public final class AudioModem {

    public static final int SAMPLE_RATE = 16000;
    /** Samples per symbol: 32 ms at 16 kHz. */
    public static final int SYMBOL_SAMPLES = 512;
    /** Fine-classification hop: 4 ms. */
    public static final int FINE_STEP = 64;
    /** Goertzel window for one fine classification: 24 ms. */
    public static final int WINDOW_SAMPLES = 384;
    public static final int[] LOW_TONES = {1250, 1450, 1650, 1850};
    public static final int[] HIGH_TONES = {2150, 2350, 2550, 2750};
    public static final int LEAD_SILENCE = 1600; // 100 ms
    public static final int TAIL_SILENCE = 800;  // 50 ms
    public static final int MAX_PAYLOAD = 200;

    private static final int[] PREAMBLE = {0x0, 0xF, 0x0, 0xF, 0x0, 0xF, 0x0, 0xF};
    private static final int SYNC_A = 0x6;
    private static final int SYNC_B = 0x9;
    private static final int RAMP = 64; // 4 ms raised-cosine edges
    private static final double AMP = 0.5; // per tone; two tones sum to <= 1.0
    /** Minimum window RMS (of 32768) for a window to count as signal. */
    private static final double ENERGY_FLOOR = 250.0;
    /** Winner must beat the runner-up in its group by this factor. */
    private static final double DOMINANCE = 1.4;

    private AudioModem() {}

    // ---- CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) ----

    public static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[off + i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    // ---- encode ----

    /** Renders one symbol's tones into pcm at offset. Phase restarts
     *  per symbol; the edge ramps hide the joins. */
    private static void renderSymbol(short[] pcm, int off, int nibble) {
        int fl = LOW_TONES[(nibble >> 2) & 3];
        int fh = HIGH_TONES[nibble & 3];
        double wl = 2.0 * Math.PI * fl / SAMPLE_RATE;
        double wh = 2.0 * Math.PI * fh / SAMPLE_RATE;
        for (int i = 0; i < SYMBOL_SAMPLES; i++) {
            double env = 1.0;
            if (i < RAMP) env = 0.5 * (1.0 - Math.cos(Math.PI * i / RAMP));
            else if (i >= SYMBOL_SAMPLES - RAMP) {
                env = 0.5 * (1.0 - Math.cos(Math.PI
                        * (SYMBOL_SAMPLES - 1 - i) / RAMP));
            }
            double v = AMP * (Math.sin(wl * i) + Math.sin(wh * i)) * env;
            pcm[off + i] = (short) Math.round(v * 32767.0);
        }
    }

    /** Encodes a payload to PCM, or null when the payload is empty
     *  or over MAX_PAYLOAD. */
    public static short[] encode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) {
            return null;
        }
        int frameLen = 1 + payload.length + 2;
        byte[] frame = new byte[frameLen];
        frame[0] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        int crc = crc16(frame, 0, 1 + payload.length);
        frame[frameLen - 2] = (byte) (crc >> 8);
        frame[frameLen - 1] = (byte) crc;

        int symbols = PREAMBLE.length + 2 + frameLen * 2;
        short[] pcm = new short[LEAD_SILENCE + symbols * SYMBOL_SAMPLES + TAIL_SILENCE];
        int pos = LEAD_SILENCE;
        for (int n : PREAMBLE) {
            renderSymbol(pcm, pos, n);
            pos += SYMBOL_SAMPLES;
        }
        renderSymbol(pcm, pos, SYNC_A);
        pos += SYMBOL_SAMPLES;
        renderSymbol(pcm, pos, SYNC_B);
        pos += SYMBOL_SAMPLES;
        for (int i = 0; i < frameLen; i++) {
            renderSymbol(pcm, pos, (frame[i] >> 4) & 0xF);
            pos += SYMBOL_SAMPLES;
            renderSymbol(pcm, pos, frame[i] & 0xF);
            pos += SYMBOL_SAMPLES;
        }
        return pcm;
    }

    /** Air time of an encoded payload, in milliseconds. */
    public static int airTimeMs(byte[] payload) {
        if (payload == null) return 0;
        int frameLen = 1 + payload.length + 2;
        int symbols = PREAMBLE.length + 2 + frameLen * 2;
        int samples = LEAD_SILENCE + symbols * SYMBOL_SAMPLES + TAIL_SILENCE;
        return (int) Math.round(samples * 1000.0 / SAMPLE_RATE);
    }

    // ---- Goertzel ----

    /** Magnitude of one tone over pcm[off, off+len). */
    static double goertzel(short[] pcm, int off, int len, int freq) {
        double w = 2.0 * Math.PI * freq / SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < len; i++) {
            double s0 = pcm[off + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double mag2 = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return Math.sqrt(Math.max(0.0, mag2));
    }

    /** Classifies one fine window starting at off: the nibble, or -1
     *  when the window is too quiet or no tone pair dominates. */
    static int classify(short[] pcm, int off) {
        double energy = 0;
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            double v = pcm[off + i];
            energy += v * v;
        }
        double rms = Math.sqrt(energy / WINDOW_SAMPLES);
        if (rms < ENERGY_FLOOR) return -1;
        double[] low = new double[4];
        double[] high = new double[4];
        for (int i = 0; i < 4; i++) low[i] = goertzel(pcm, off, WINDOW_SAMPLES, LOW_TONES[i]);
        for (int i = 0; i < 4; i++) high[i] = goertzel(pcm, off, WINDOW_SAMPLES, HIGH_TONES[i]);
        int li = argmax(low);
        int hi = argmax(high);
        if (!dominant(low, li) || !dominant(high, hi)) return -1;
        return (li << 2) | hi;
    }

    private static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    private static boolean dominant(double[] a, int winner) {
        double second = 0;
        for (int i = 0; i < a.length; i++) {
            if (i != winner && a[i] > second) second = a[i];
        }
        return a[winner] > DOMINANCE * second;
    }

    // ---- streaming decoder ----

    public static final class Decoder {

        public interface Listener {
            /** A frame decoded and CRC-verified. */
            void onFrame(byte[] payload);
            /** A frame-shaped transmission failed its CRC — the
             *  payload is NOT delivered; the sender should replay. */
            void onCrcError();
        }

        private static final int SEARCH = 0;
        private static final int SYNC = 1;
        private static final int DATA = 2;

        private final Listener listener;
        private short[] buf = new short[65536];
        /** Absolute sample index of buf[0]. */
        private long baseAbs = 0;
        private int len = 0;
        private long fed = 0;

        private int state = SEARCH;
        /** Absolute position of the next fine window to classify. */
        private long nextFine = 0;
        /** Fine classifications, oldest first: nibble per step, -1 unclear. */
        private final List<Integer> fine = new ArrayList<>();
        /** Absolute position of fine.get(0). */
        private long fineBase = 0;
        /** Runs derived from the fine stream: {symbol, span in fine
         *  steps, clear steps}. Span runs from the run's first clear
         *  step to the next run's first clear step, so the unclear
         *  boundary steps belong to a run instead of vanishing —
         *  under echo, boundary windows go unclear, and counting
         *  only clear steps under-measures every symbol. */
        private final List<int[]> runs = new ArrayList<>();

        private double finePerSymbol = 8.0; // 512 / 64, refined from preamble
        private long dataStartAbs = -1;
        private long dataDeadlineAbs = -1;
        private int runCursor = 0; // runs consumed while segmenting DATA

        private double level = 0;

        public Decoder(Listener listener) {
            this.listener = listener;
        }

        /** Smoothed 0..1 level of recently fed audio, for a UI meter. */
        public double level() {
            return level;
        }

        public void reset() {
            len = 0;
            baseAbs = fed;
            state = SEARCH;
            nextFine = fed;
            fine.clear();
            fineBase = fed;
            runs.clear();
            finePerSymbol = 8.0;
            dataStartAbs = -1;
            runCursor = 0;
        }

        public void feed(short[] pcm, int off, int count) {
            if (pcm == null || count <= 0) return;
            ensureCapacity(count);
            System.arraycopy(pcm, off, buf, len, count);
            len += count;
            fed += count;
            double energy = 0;
            for (int i = 0; i < count; i++) {
                double v = pcm[off + i];
                energy += v * v;
            }
            double rms = Math.sqrt(energy / count) / 32768.0;
            level = 0.7 * level + 0.3 * Math.min(1.0, rms * 3.0);
            process();
            compact();
        }

        private void ensureCapacity(int extra) {
            if (len + extra <= buf.length) return;
            int need = len + extra;
            int grown = buf.length;
            while (grown < need) grown *= 2;
            short[] next = new short[grown];
            System.arraycopy(buf, 0, next, 0, len);
            buf = next;
        }

        /** Drops fully consumed audio, keeping a small overlap so a
         *  frame straddling the cut is never lost. */
        private void compact() {
            long keepFrom = Math.min(nextFine, dataStartAbs < 0 ? Long.MAX_VALUE : dataStartAbs);
            keepFrom = Math.min(keepFrom, fineBase);
            long dropUntil = keepFrom - 2048;
            if (dropUntil <= baseAbs + 8192) return;
            int drop = (int) (dropUntil - baseAbs);
            if (drop <= 0 || drop >= len) return;
            System.arraycopy(buf, drop, buf, 0, len - drop);
            len -= drop;
            baseAbs += drop;
        }

        private int idx(long abs) {
            return (int) (abs - baseAbs);
        }

        private void process() {
            // Classify every fine step that has a full window.
            while (nextFine + WINDOW_SAMPLES <= fed) {
                int nib = classify(buf, idx(nextFine));
                fine.add(nib);
                if (fine.size() == 1) fineBase = nextFine;
                nextFine += FINE_STEP;
                rebuildRuns();
                if (state == SEARCH) {
                    tryAcquire();
                } else if (state == SYNC) {
                    trySync();
                } else {
                    tryData();
                }
            }
            // DATA watchdog: a stale partial frame must not swallow a replay.
            if (state == DATA && dataDeadlineAbs > 0 && fed > dataDeadlineAbs) {
                toSearch(nextFine);
            }
        }

        /** Rebuilds run-length encoding of the fine stream. A run's
         *  span covers its clear steps plus the unclear boundary
         *  steps up to the next run; flicker runs under 3 clear
         *  steps are dropped, merging into a same-symbol neighbor. */
        private void rebuildRuns() {
            runs.clear();
            int cur = -2; // -2 = no open run
            int firstIdx = 0;
            int clear = 0;
            int lastClear = -1;
            for (int idx = 0; idx < fine.size(); idx++) {
                int n = fine.get(idx);
                if (n < 0) continue;
                if (n == cur) {
                    clear++;
                    lastClear = idx;
                    continue;
                }
                if (cur >= 0) closeRun(cur, idx - firstIdx, clear);
                cur = n;
                firstIdx = idx;
                clear = 1;
                lastClear = idx;
            }
            if (cur >= 0) {
                int tailUnclear = fine.size() - 1 - lastClear;
                int span = (lastClear - firstIdx + 1) + Math.min(tailUnclear, 2);
                closeRun(cur, span, clear);
            }
        }

        private void closeRun(int sym, int span, int clear) {
            if (clear < 3) return; // flicker: dropped entirely
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[0] == sym) {
                int[] last = runs.get(runs.size() - 1);
                last[1] += span;
                last[2] += clear;
                return;
            }
            runs.add(new int[]{sym, span, clear});
        }

        private void tryAcquire() {
            // Preamble: >= 6 runs alternating 0x0/0xF, each 4..12 steps.
            for (int i = 0; i + 5 < runs.size(); i++) {
                boolean ok = true;
                int total = 0;
                for (int k = 0; k < 6; k++) {
                    int[] r = runs.get(i + k);
                    int want = (k % 2 == 0) ? 0x0 : 0xF;
                    if (r[0] != want || r[1] < 4 || r[1] > 12) {
                        ok = false;
                        break;
                    }
                    total += r[1];
                }
                if (!ok) continue;
                finePerSymbol = Math.max(6.0, Math.min(10.0, total / 6.0));
                runCursor = i + 6;
                state = SYNC;
                // From acquisition, the remaining preamble (2 symbols)
                // plus sync (2 symbols) is 32 fine steps; allow 48.
                dataDeadlineAbs = fed + (long) (48 * FINE_STEP);
                return;
            }
            // Keep the fine history bounded while searching.
            if (fine.size() > 400) {
                int drop = fine.size() - 200;
                for (int i = 0; i < drop; i++) fine.remove(0);
                fineBase += (long) drop * FINE_STEP;
            }
        }

        private void trySync() {
            // Expect run(0x6) then run(0x9) among the next few runs.
            for (int i = runCursor; i + 1 < runs.size() && i < runCursor + 4; i++) {
                int[] a = runs.get(i);
                int[] b = runs.get(i + 1);
                if (a[0] == SYNC_A && b[0] == SYNC_B
                        && a[1] >= 3 && a[1] <= 12 && b[1] >= 3 && b[1] <= 12) {
                    // Refine the symbol length from the 8 complete
                    // preamble runs (median resists the long first
                    // run, which catches the silence transition).
                    if (i >= 8) {
                        int[] lens = new int[8];
                        for (int k = 0; k < 8; k++) lens[k] = runs.get(i - 8 + k)[1];
                        java.util.Arrays.sort(lens);
                        finePerSymbol = Math.max(6.0,
                                Math.min(10.0, (lens[3] + lens[4]) / 2.0));
                    }
                    // Data starts at the end of the sync-B run.
                    int steps = 0;
                    for (int k = 0; k <= i + 1; k++) steps += runs.get(k)[1];
                    // Recompute the absolute end: walk the fine stream.
                    dataStartAbs = fineStartOfRun(i + 1) + (long) b[1] * FINE_STEP;
                    runCursor = i + 2;
                    state = DATA;
                    dataDeadlineAbs = dataStartAbs
                            + (long) ((2 * (1 + MAX_PAYLOAD + 2) + 4)
                            * finePerSymbol * FINE_STEP);
                    return;
                }
            }
            if (fed > dataDeadlineAbs) {
                toSearch(nextFine);
            }
        }

        /** Absolute start of a run, by counting fine steps. Unclear
         *  steps make exact counting impossible, so this walks the
         *  fine list skipping unclear steps, matching rebuildRuns. */
        private long fineStartOfRun(int runIndex) {
            int run = -1;
            int cur = -2;
            long pos = fineBase;
            for (int n : fine) {
                if (n < 0) {
                    pos += FINE_STEP;
                    continue;
                }
                if (n != cur) {
                    run++;
                    if (run == runIndex) return pos;
                    cur = n;
                }
                pos += FINE_STEP;
            }
            return pos;
        }

        private void tryData() {
            // Segment runs after the sync into symbols by rounding
            // each run's length against the measured symbol length.
            List<Integer> symbols = new ArrayList<>();
            for (int i = runCursor; i < runs.size(); i++) {
                int[] r = runs.get(i);
                int count = Math.max(1, (int) Math.round(r[1] / finePerSymbol));
                for (int k = 0; k < count; k++) symbols.add(r[0]);
            }
            if (symbols.size() < 2) return;
            int payloadLen = (symbols.get(0) << 4) | symbols.get(1);
            if (payloadLen < 1 || payloadLen > MAX_PAYLOAD) {
                toSearch(dataStartAbs + SYMBOL_SAMPLES);
                return;
            }
            int totalSymbols = 2 * (1 + payloadLen + 2);
            if (symbols.size() < totalSymbols) return; // still arriving
            // Don't finalize on a run that is still growing: wait until
            // a symbol past the frame has started, or the trailing run
            // holds a full symbol's worth of steps (tail silence
            // follows the frame, so no new run will appear).
            int[] lastRun = runs.get(runs.size() - 1);
            if (symbols.size() == totalSymbols
                    && lastRun[1] < finePerSymbol - 1) {
                return;
            }
            byte[] frame = new byte[1 + payloadLen + 2];
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (byte) ((symbols.get(i * 2) << 4) | symbols.get(i * 2 + 1));
            }
            int want = ((frame[frame.length - 2] & 0xFF) << 8)
                    | (frame[frame.length - 1] & 0xFF);
            int got = crc16(frame, 0, 1 + payloadLen);
            long frameEnd = dataStartAbs + (long) (totalSymbols * finePerSymbol * FINE_STEP);
            if (want == got) {
                byte[] payload = new byte[payloadLen];
                System.arraycopy(frame, 1, payload, 0, payloadLen);
                toSearch(frameEnd);
                listener.onFrame(payload);
            } else {
                toSearch(frameEnd);
                listener.onCrcError();
            }
        }

        private void toSearch(long resumeFrom) {
            state = SEARCH;
            dataStartAbs = -1;
            runCursor = 0;
            // Drop fine history before the resume point so the next
            // acquisition starts clean; keep a small overlap.
            long keepFrom = resumeFrom - 8L * FINE_STEP;
            while (!fine.isEmpty() && fineBase < keepFrom) {
                fine.remove(0);
                fineBase += FINE_STEP;
            }
            if (nextFine < resumeFrom) nextFine = resumeFrom;
            rebuildRuns();
        }
    }
}
