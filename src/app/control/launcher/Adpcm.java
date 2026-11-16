package app.control.launcher;

/**
 * IMA-ADPCM codec (4 bits per sample, 4:1) for voice bursts and the
 * Bluetooth stream. 16 kHz mono PCM16 in/out. Call reset() at the start
 * of every spurt; encoder and decoder keep matching state across the
 * frames of one spurt.
 *
 * Chosen because it is pure Java (no native codec in the manual build
 * pipeline), tiny, and at 64 kbps it sounds clearly like a person —
 * which is all a walkie-talkie needs.
 */
public final class Adpcm {
    private static final int[] STEP = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };
    private static final int[] INDEX_DELTA = {
        -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8
    };

    private int predicted;
    private int stepIndex;

    public void reset() {
        predicted = 0;
        stepIndex = 0;
    }

    /** Encodes PCM16 samples to ADPCM bytes (two samples per byte). */
    public byte[] encode(short[] pcm) {
        byte[] out = new byte[(pcm.length + 1) / 2];
        for (int i = 0; i < pcm.length; i++) {
            int code = encodeSample(pcm[i]);
            if ((i & 1) == 0) out[i / 2] = (byte) (code & 0x0F);
            else out[i / 2] |= (byte) (code << 4);
        }
        return out;
    }

    private int encodeSample(short sample) {
        int step = STEP[stepIndex];
        int diff = sample - predicted;
        int code = 0;
        if (diff < 0) {
            code = 8;
            diff = -diff;
        }
        int quant = 0;
        int tempStep = step;
        if (diff >= tempStep) { code |= 4; diff -= tempStep; quant += step; }
        tempStep >>= 1;
        if (diff >= tempStep) { code |= 2; diff -= tempStep; quant += tempStep; }
        tempStep >>= 1;
        if (diff >= tempStep) { code |= 1; quant += tempStep; }
        predicted += (code & 8) != 0 ? -quant : quant;
        if (predicted > 32767) predicted = 32767;
        if (predicted < -32768) predicted = -32768;
        stepIndex += INDEX_DELTA[code];
        if (stepIndex < 0) stepIndex = 0;
        if (stepIndex > 88) stepIndex = 88;
        return code;
    }

    /** Decodes ADPCM bytes back to PCM16 samples. */
    public short[] decode(byte[] data, int sampleCount) {
        short[] out = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int b = data[i / 2] & 0xFF;
            int code = (i & 1) == 0 ? (b & 0x0F) : (b >> 4) & 0x0F;
            out[i] = (short) decodeSample(code);
        }
        return out;
    }

    private int decodeSample(int code) {
        int step = STEP[stepIndex];
        int diff = step >> 3;
        if ((code & 4) != 0) diff += step;
        if ((code & 2) != 0) diff += step >> 1;
        if ((code & 1) != 0) diff += step >> 2;
        predicted += (code & 8) != 0 ? -diff : diff;
        if (predicted > 32767) predicted = 32767;
        if (predicted < -32768) predicted = -32768;
        stepIndex += INDEX_DELTA[code];
        if (stepIndex < 0) stepIndex = 0;
        if (stepIndex > 88) stepIndex = 88;
        return predicted;
    }

    /** Whole-burst helpers (state reset included). */
    public static byte[] encodeBurst(short[] pcm) {
        Adpcm a = new Adpcm();
        a.reset();
        return a.encode(pcm);
    }

    public static short[] decodeBurst(byte[] data, int sampleCount) {
        Adpcm a = new Adpcm();
        a.reset();
        return a.decode(data, sampleCount);
    }

    /** Sample count for a burst: 2 samples per byte (may over-report by 1
     *  for odd counts; the writer of the burst stores the exact count). */
    public static int samplesIn(byte[] data) {
        return data.length * 2;
    }
}
