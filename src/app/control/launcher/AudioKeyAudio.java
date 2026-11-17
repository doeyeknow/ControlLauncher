package app.control.launcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Android audio glue for the key-sharing modem (v1.8): plays an
 * encoded frame through the speaker and listens for one through the
 * microphone. All callbacks arrive on the main thread. The modem
 * itself (AudioModem) is pure Java and never sees Android.
 *
 * No new permission: RECORD_AUDIO is already declared for the
 * walkie-talkie, and the listen flow requests it with the same
 * pattern the walkie uses. A missing mic permission surfaces as an
 * honest error, never a silent failure.
 */
public final class AudioKeyAudio {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AudioKeyAudio() {}

    // ---- player ----

    public static final class Player {

        public interface Cb {
            /** Playback progress, 0..1. */
            void onProgress(float frac);
            /** The whole frame played. */
            void onDone();
            /** Playback could not start. */
            void onError(String message);
        }

        private AudioTrack track;
        private Thread worker;
        private volatile boolean stopped;
        /** Bumped on every play/stop so a superseded worker's
         *  callbacks can never fire into a newer playback. */
        private volatile int generation;

        /**
         * Plays pcm (48 kHz mono PCM16 since v1.26) once, streaming (v1.9).
         *
         * v1.8 used MODE_STATIC with the whole frame in one buffer
         * and the track would not initialize on Adam's Galaxy A16
         * ("AudioTrack would not initialize"). Streaming uses a
         * small buffer from getMinBufferSize and writes the PCM in
         * chunks on the worker thread, which is the pattern the
         * static allocation could not satisfy on that device. Every
         * write result is checked, and every failure message
         * carries the track state, buffer size, sample rate, and
         * write code, so the next failure is diagnosable from the
         * screen text alone. The public contract is unchanged:
         * play/stop/isPlaying and the Cb callbacks behave as before.
         */
        public void play(short[] pcm, Cb cb) {
            stop();
            if (pcm == null || pcm.length == 0) {
                MAIN.post(() -> cb.onError(
                        "Couldn't play the sound: nothing to play."));
                return;
            }
            stopped = false;
            final int gen = ++generation;
            final int total = pcm.length;
            int minBuf;
            try {
                minBuf = AudioTrack.getMinBufferSize(AudioModem.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            } catch (Exception e) {
                minBuf = 0;
            }
            final int bufferSize = Math.max(minBuf, 8192);
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(AudioModem.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                    int state = t.getState();
                    safeRelease(t);
                    t = null;
                    final int failedState = state;
                    MAIN.post(() -> cb.onError("Couldn't play the sound: "
                            + "AudioTrack would not initialize"
                            + detail(failedState, bufferSize,
                                    Integer.MIN_VALUE, 0)));
                    return;
                }
                track = t;
                t.play();
            } catch (Exception e) {
                if (t != null) {
                    if (track == t) track = null;
                    safeRelease(t);
                }
                final String reason = String.valueOf(e.getMessage());
                MAIN.post(() -> cb.onError("Couldn't play the sound: " + reason
                        + detail(-1, bufferSize, Integer.MIN_VALUE, 0)));
                return;
            }
            final AudioTrack wt = t;
            final byte[] raw = toBytes(pcm);
            worker = new Thread(() -> {
                String failure = null;
                int writeCode = Integer.MIN_VALUE;
                int failState = -1;
                int offset = 0;
                try {
                    final int chunk = 4096;
                    while (offset < raw.length && !stopped && gen == generation) {
                        int n = Math.min(chunk, raw.length - offset);
                        int w = wt.write(raw, offset, n);
                        if (w < 0) {
                            writeCode = w;
                            failState = safeState(wt);
                            failure = "AudioTrack.write failed";
                            break;
                        }
                        if (w == 0) {
                            // A blocking write should not return 0;
                            // wait briefly rather than spin.
                            Thread.sleep(10);
                            continue;
                        }
                        offset += w;
                        final float frac = headFrac(wt, total);
                        if (gen == generation && !stopped) {
                            MAIN.post(() -> cb.onProgress(frac));
                        }
                    }
                    // Drain: onDone fires only once the head has
                    // actually played the final chunk.
                    while (failure == null && !stopped && gen == generation) {
                        int head = wt.getPlaybackHeadPosition();
                        final float frac = Math.min(1f,
                                Math.max(0f, head / (float) total));
                        MAIN.post(() -> cb.onProgress(frac));
                        if (head >= total) break;
                        Thread.sleep(80);
                    }
                } catch (Exception e) {
                    if (!stopped && gen == generation) {
                        failState = safeState(wt);
                        failure = "Playback failed: " + String.valueOf(e.getMessage());
                    }
                }
                boolean natural = failure == null && !stopped && gen == generation;
                releaseTrack(wt);
                if (stopped || gen != generation) return;
                if (failure != null) {
                    final String f = failure;
                    final int wc = writeCode;
                    final int fs = failState;
                    final int wrote = offset;
                    MAIN.post(() -> cb.onError("Couldn't play the sound: " + f
                            + detail(fs, bufferSize, wc, wrote)));
                } else if (natural) {
                    MAIN.post(cb::onDone);
                }
            }, "audio-key-play");
            worker.start();
        }

        public boolean isPlaying() {
            return track != null && !stopped;
        }

        public void stop() {
            stopped = true;
            generation++;
            AudioTrack t = track;
            track = null;
            safeRelease(t);
            if (worker != null) {
                worker.interrupt();
                worker = null;
            }
        }

        /** Releases the worker's track, but only clears the field
         *  when it still holds that track — a newer playback's
         *  track is never touched. */
        private void releaseTrack(AudioTrack expected) {
            if (track == expected) track = null;
            safeRelease(expected);
        }

        private static void safeRelease(AudioTrack t) {
            if (t == null) return;
            try {
                t.stop();
            } catch (Exception ignored) {
            }
            try {
                t.release();
            } catch (Exception ignored) {
            }
        }

        /** Track state for a failure message; -1 when unreadable. */
        private static int safeState(AudioTrack t) {
            try {
                return t.getState();
            } catch (Exception e) {
                return -1;
            }
        }

        private static float headFrac(AudioTrack t, int total) {
            try {
                int head = t.getPlaybackHeadPosition();
                return Math.min(1f, Math.max(0f, head / (float) total));
            } catch (Exception e) {
                return 0f;
            }
        }

        private static byte[] toBytes(short[] pcm) {
            byte[] raw = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                raw[i * 2] = (byte) (pcm[i] & 0xFF);
                raw[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
            }
            return raw;
        }

        /** Failure details for the screen: state, buffer, rate,
         *  and — when a write failed — the write code and how much
         *  had already been written. */
        private static String detail(int state, int bufferSize,
                                     int writeCode, int bytesWritten) {
            StringBuilder sb = new StringBuilder(" (state=");
            if (state == AudioTrack.STATE_INITIALIZED) sb.append("initialized");
            else if (state == AudioTrack.STATE_UNINITIALIZED) sb.append("uninitialized");
            else if (state < 0) sb.append("unknown");
            else sb.append(state);
            sb.append(", buffer=").append(bufferSize).append(" bytes");
            sb.append(", rate=").append(AudioModem.SAMPLE_RATE).append(" Hz");
            if (writeCode != Integer.MIN_VALUE) sb.append(", write=").append(writeCode);
            if (bytesWritten > 0) sb.append(", wrote=").append(bytesWritten).append(" bytes");
            sb.append(")");
            return sb.toString();
        }
    }

    // ---- listener ----

    public static final class Listener {

        public interface Cb {
            /** Smoothed 0..1 mic level, ~10/sec, for a meter. */
            void onLevel(double level);
            /** A frame decoded and CRC-verified. */
            void onFrame(byte[] payload);
            /** The type tones were read, before the payload
             *  finished arriving — the UI can name what is
             *  coming. The kind is the AudioKeyPayload kind (or
             *  an unknown value, which the UI names honestly
             *  and the decoder later ignores). */
            void onTypeHeard(int kind);
            /** A frame-shaped sound failed its CRC — keep listening. */
            void onCrcError();
            /** Recording could not start (e.g. no mic permission). */
            void onError(String message);
        }

        private AudioRecord recorder;
        private Thread reader;
        private volatile boolean stopped;
        private AudioModem.Decoder decoder;

        /** Starts listening. Returns false when recording cannot
         *  start; cb.onError carries the reason. */
        public boolean start(Cb cb) {
            stop();
            stopped = false;
            decoder = new AudioModem.Decoder(new AudioModem.Decoder.Listener() {
                @Override public void onFrame(int type, byte[] payload) {
                    MAIN.post(() -> cb.onFrame(payload));
                }

                @Override public void onTypeHeard(int type) {
                    MAIN.post(() -> cb.onTypeHeard(type));
                }

                @Override public void onCrcError() {
                    MAIN.post(cb::onCrcError);
                }
            });
            try {
                int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16384));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord would not initialize");
                }
                recorder.startRecording();
            } catch (Exception e) {
                release();
                cb.onError("Couldn't open the microphone: "
                        + String.valueOf(e.getMessage()));
                return false;
            }
            reader = new Thread(() -> {
                short[] chunk = new short[1024];
                long lastLevel = 0;
                while (!stopped) {
                    AudioRecord r = recorder;
                    if (r == null) break;
                    int n;
                    try {
                        n = r.read(chunk, 0, chunk.length);
                    } catch (Exception e) {
                        break;
                    }
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION
                                || n == AudioRecord.ERROR_BAD_VALUE) break;
                        continue;
                    }
                    decoder.feed(chunk, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - lastLevel >= 100) {
                        lastLevel = now;
                        final double lvl = decoder.level();
                        MAIN.post(() -> cb.onLevel(lvl));
                    }
                }
            }, "audio-key-listen");
            reader.start();
            return true;
        }

        public void stop() {
            stopped = true;
            if (reader != null) {
                reader.interrupt();
                reader = null;
            }
            release();
        }

        private void release() {
            AudioRecord r = recorder;
            recorder = null;
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
