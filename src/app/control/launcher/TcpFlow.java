package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * One TCP connection's state machine for the mesh NAT (v1.10).
 * Pure Java, no Android — the JVM harness exercises this exact
 * code.
 *
 * Role: TCP terminator. It speaks TCP to the local stack (via the
 * TUN) and a byte stream to the gateway (via SOCKS CONNECT). The
 * remote side is a stream, so there is no remote sequence space
 * to translate — only the local side's seq/ack accounting, which
 * this class owns completely.
 *
 * Deliberate v1 simplifications, all documented in BUILD_NOTES:
 *  - No window scaling, no SACK, no timestamps. Advertised window
 *    is a fixed 65535; the peer's window is respected as sent.
 *  - Out-of-order segments are dropped and re-ACKed (the sender
 *    retransmits); no reassembly buffer.
 *  - Retransmission is fast-retransmit only (3 duplicate ACKs).
 *    No RTO timer: the TUN path and the mesh TCP stream are both
 *    effectively lossless, so timers would almost never fire.
 *  - No TIME_WAIT: FIN_WAIT_2 + client FIN, or LAST_ACK + ACK,
 *    closes immediately.
 *  - Data received before the SOCKS connect completes is buffered
 *    (64 KB cap) and ACKed only once forwarded — the ack number
 *    always means "bytes written to the remote", never merely
 *    "bytes buffered".
 */
public class TcpFlow {

    public interface Sink {
        /** An IP packet to write to the TUN. */
        void emit(byte[] ipPacket);
        /** Client bytes to write to the remote stream. */
        void toRemote(byte[] data);
        /** Half-close (shutdownOutput) or full close of remote. */
        void closeRemote(boolean half);
        /** Start the SOCKS CONNECT (called once, at SYN time). */
        void openRemote();
        /** The flow is dead; the engine must drop it. */
        void remove();
    }

    public enum State {
        SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK,
        FIN_WAIT_1, FIN_WAIT_2, CLOSED
    }

    public static final int ADVERTISED_WINDOW = 65535;
    public static final int MY_MSS = 1460;
    public static final int PENDING_CAP = 65536;

    private static final long SYN_RECEIVED_TIMEOUT_MS = 30_000;
    private static final long CLOSE_TIMEOUT_MS = 60_000;

    private final int clientIp;
    private final int clientPort;
    private final int serverIp;
    private final int serverPort;
    private final Sink sink;

    private State state = State.SYN_RECEIVED;
    private long stateEnteredAt;

    // Local (client) sequence space.
    private long expectSeq;      // next client byte we will accept
    private long forwardedSeq;   // next client byte not yet written remote (= ack number)
    private int clientMss = 536;
    private int clientWindow = ADVERTISED_WINDOW;

    // Our sequence space.
    private final long isn;
    private long sendNext;       // next seq to send
    private long sendBase;       // oldest unacked seq
    private int ipId;

    private boolean remoteReady;
    private boolean remoteEof;
    private boolean finPending;  // client FIN arrived before remote connected
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final ByteArrayOutputStream outPending = new ByteArrayOutputStream();

    private static final class Sent {
        final long seq;
        final int seqLen;        // sequence numbers consumed: dataLen + SYN/FIN (1 each)
        final byte[] packet;
        final int dataLen;
        final boolean fin;
        Sent(long seq, byte[] packet, int dataLen, boolean fin, boolean syn) {
            this.seq = seq;
            this.packet = packet;
            this.dataLen = dataLen;
            this.fin = fin;
            this.seqLen = dataLen + (fin ? 1 : 0) + (syn ? 1 : 0);
        }
    }

    private final Queue<Sent> unacked = new ArrayDeque<>();
    private int dupAckCount;
    private long lastAckSeen = -1;

    public TcpFlow(int clientIp, int clientPort, int serverIp, int serverPort,
                   long isn, Sink sink, long nowMs) {
        this.clientIp = clientIp;
        this.clientPort = clientPort;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.isn = isn & 0xFFFFFFFFL;
        this.sink = sink;
        this.stateEnteredAt = nowMs;
        this.ipId = (int) (isn & 0xFFFF);
        this.sendNext = this.isn;
        this.sendBase = this.isn;
    }

    // ---- sequence arithmetic (RFC 1982 serial numbers) ----

    static int seqDiff(long a, long b) {
        return (int) ((a - b) & 0xFFFFFFFFL);
    }

    static boolean seqLt(long a, long b) {
        return seqDiff(a, b) < 0;
    }

    static boolean seqLe(long a, long b) {
        return seqDiff(a, b) <= 0;
    }

    static boolean seqGt(long a, long b) {
        return seqDiff(a, b) > 0;
    }

    // ---- inspection (harness + engine) ----

    public State state() {
        return state;
    }

    public long expectSeq() {
        return expectSeq;
    }

    public long forwardedSeq() {
        return forwardedSeq;
    }

    public long sendNext() {
        return sendNext;
    }

    public long sendBase() {
        return sendBase;
    }

    public int unackedSegments() {
        return unacked.size();
    }

    public int unackedBytes() {
        int n = 0;
        for (Sent s : unacked) n += s.dataLen;
        return n;
    }

    public int pendingLen() {
        return pending.size();
    }

    /** Bytes held locally (unacked + unsent): the host throttles
     *  remote reads while this is large. */
    public int backlogBytes() {
        return unackedBytes() + outPending.size() + pending.size();
    }

    // ---- handshake ----

    /** The client sent SYN. Emits SYN-ACK and asks the host to open
     *  the remote connection. */
    public void onSyn(long seq, int mss, int window, long nowMs) {
        if (mss > 0) clientMss = Math.min(mss, 65535);
        clientWindow = window; // 0 is a real window: stop sending
        expectSeq = (seq + 1) & 0xFFFFFFFFL;
        forwardedSeq = expectSeq;
        stateEnteredAt = nowMs;
        sendSynAck();
        sink.openRemote();
    }

    private void sendSynAck() {
        byte[] seg = NatPackets.buildTcp(serverIp, clientIp, serverPort, clientPort,
                isn, expectSeq, NatPackets.TCP_SYN | NatPackets.TCP_ACK,
                ADVERTISED_WINDOW, MY_MSS, new byte[0], 0, 0);
        sink.emit(wrap(seg));
        // The SYN consumes one sequence number; it is acked by the
        // client's third-leg ACK. Track it so sendBase advances.
        unacked.add(new Sent(isn, lastEmitted, 0, false, true));
        sendNext = (isn + 1) & 0xFFFFFFFFL;
    }

    private byte[] lastEmitted;

    private byte[] wrap(byte[] tcpSegment) {
        lastEmitted = NatPackets.buildIp(serverIp, clientIp, NatPackets.PROTO_TCP,
                tcpSegment, ipId++);
        return lastEmitted;
    }

    private void sendAck() {
        byte[] seg = NatPackets.buildTcp(serverIp, clientIp, serverPort, clientPort,
                sendNext, forwardedSeq, NatPackets.TCP_ACK,
                ADVERTISED_WINDOW, 0, new byte[0], 0, 0);
        sink.emit(wrap(seg));
    }

    private void sendRst() {
        byte[] seg = NatPackets.buildTcp(serverIp, clientIp, serverPort, clientPort,
                sendNext, forwardedSeq, NatPackets.TCP_RST | NatPackets.TCP_ACK,
                0, 0, new byte[0], 0, 0);
        sink.emit(wrap(seg));
    }

    // ---- segments from the client ----

    /** Handles a non-SYN segment (or a retransmitted SYN). */
    public void onSegment(int flags, long seq, long ack, int window,
                          byte[] payload, int payloadOff, int payloadLen, long nowMs) {
        if (state == State.CLOSED) return;
        if ((flags & NatPackets.TCP_RST) != 0) {
            // RST accepted regardless of sequence: the peer is the
            // local stack, and a reset means the socket is gone.
            close(nowMs);
            sink.closeRemote(false);
            return;
        }
        if ((flags & NatPackets.TCP_SYN) != 0 && state == State.SYN_RECEIVED) {
            // Retransmitted SYN: the client never saw our SYN-ACK.
            sendSynAckRetransmit();
            return;
        }
        clientWindow = window; // 0 is a real window: stop sending

        boolean ackedSomething = false;
        if ((flags & NatPackets.TCP_ACK) != 0) {
            ackedSomething = processAck(ack, nowMs);
            if (state == State.CLOSED) return; // LAST_ACK completed
        }

        boolean accepted = false;
        if (payloadLen > 0) {
            if (seq == expectSeq) {
                if (remoteReady) {
                    byte[] copy = new byte[payloadLen];
                    System.arraycopy(payload, payloadOff, copy, 0, payloadLen);
                    sink.toRemote(copy);
                    forwardedSeq = (forwardedSeq + payloadLen) & 0xFFFFFFFFL;
                    expectSeq = forwardedSeq;
                    accepted = true;
                } else if (pending.size() + payloadLen <= PENDING_CAP) {
                    pending.write(payload, payloadOff, payloadLen);
                    expectSeq = (expectSeq + payloadLen) & 0xFFFFFFFFL;
                    accepted = true;
                } else {
                    // Buffer full: drop and re-ACK what we have
                    // forwarded; the client retransmits.
                    sendAck();
                }
            } else if (seqLt(seq, expectSeq)) {
                // Retransmit of bytes already accepted: drop, re-ACK.
                sendAck();
            } else {
                // Gap: drop, re-ACK to trigger retransmission.
                sendAck();
            }
        }

        if ((flags & NatPackets.TCP_FIN) != 0) {
            long finSeq = (seq + payloadLen) & 0xFFFFFFFFL;
            if (finSeq == expectSeq || (payloadLen > 0 && seq == expectSeq && !accepted)) {
                // FIN in order (or FIN riding a dropped-buffer segment:
                // accept the FIN itself; the data will be retransmitted
                // and the FIN re-sent in order — handle by consuming
                // only when the data was accepted).
                if (payloadLen == 0 || accepted) {
                    expectSeq = (expectSeq + 1) & 0xFFFFFFFFL;
                    onClientFin(nowMs);
                    sendAck();
                    return;
                }
            }
            // Out-of-order FIN: ignore; the re-ACK above (or below)
            // prompts a retransmit.
            if (payloadLen == 0 && !accepted) sendAck();
        }

        if (accepted) {
            sendAck();
        } else if (ackedSomething) {
            // Window may have opened: try to flush buffered output.
            tryFlush(nowMs);
        }
    }

    private void sendSynAckRetransmit() {
        // Re-emit the stored SYN-ACK segment (first unacked entry).
        Sent first = unacked.peek();
        if (first != null) sink.emit(first.packet);
    }

    /** Returns true when the ACK advanced sendBase. */
    private boolean processAck(long ack, long nowMs) {
        if (seqLe(ack, sendBase)) {
            // Duplicate or stale ACK. Only ACKs that arrive while
            // data is unacked count toward fast retransmit — an ACK
            // that arrives with an empty unacked queue must not seed
            // the counter, or a later loss would retransmit early.
            if (unacked.isEmpty()) {
                dupAckCount = 0;
                lastAckSeen = ack;
                return false;
            }
            if (ack == sendBase && lastAckSeen == ack) {
                dupAckCount++;
                if (dupAckCount >= 3) {
                    dupAckCount = 0;
                    Sent oldest = unacked.peek();
                    if (oldest != null) sink.emit(oldest.packet);
                }
            } else if (ack == sendBase) {
                lastAckSeen = ack;
                dupAckCount = 1;
            }
            return false;
        }
        if (seqGt(ack, sendNext)) {
            // ACK beyond anything sent: invalid, ignore.
            return false;
        }
        // New ACK: drop fully-acked segments.
        while (!unacked.isEmpty()) {
            Sent s = unacked.peek();
            long end = (s.seq + s.seqLen) & 0xFFFFFFFFL;
            if (seqLe(end, ack)) {
                unacked.poll();
            } else {
                break;
            }
        }
        sendBase = ack;
        dupAckCount = 0;
        lastAckSeen = ack;
        if (state == State.SYN_RECEIVED && seqLe(sendNext, ack)) {
            setState(State.ESTABLISHED, nowMs);
        } else if (state == State.FIN_WAIT_1 && seqLe(sendNext, ack)) {
            setState(State.FIN_WAIT_2, nowMs);
        } else if (state == State.LAST_ACK && seqLe(sendNext, ack)) {
            close(nowMs);
        }
        return true;
    }

    private void onClientFin(long nowMs) {
        switch (state) {
            case ESTABLISHED:
                setState(State.CLOSE_WAIT, nowMs);
                halfCloseRemote();
                break;
            case FIN_WAIT_1:
                // Simultaneous close: our FIN is out; wait for its ACK.
                setState(State.LAST_ACK, nowMs);
                halfCloseRemote();
                break;
            case FIN_WAIT_2:
                close(nowMs);
                break;
            case SYN_RECEIVED:
                setState(State.CLOSE_WAIT, nowMs);
                halfCloseRemote();
                break;
            default:
                break;
        }
    }

    private void halfCloseRemote() {
        if (remoteReady) {
            sink.closeRemote(true);
        } else {
            finPending = true;
        }
    }

    private void setState(State s) {
        setState(s, System.currentTimeMillis());
    }

    private void setState(State s, long nowMs) {
        state = s;
        stateEnteredAt = nowMs;
    }

    private void close(long nowMs) {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        stateEnteredAt = nowMs;
        sink.remove();
    }

    // ---- events from the remote side ----

    /** The SOCKS CONNECT completed. Flushes buffered client data. */
    public void onRemoteConnected(long nowMs) {
        if (state == State.CLOSED) return;
        remoteReady = true;
        if (pending.size() > 0) {
            byte[] data = pending.toByteArray();
            pending.reset();
            sink.toRemote(data);
            forwardedSeq = (forwardedSeq + data.length) & 0xFFFFFFFFL;
            sendAck();
        }
        if (finPending) {
            finPending = false;
            sink.closeRemote(true);
        }
    }

    /** The SOCKS CONNECT failed (or the gateway refused). The
     *  client sees a RST, exactly as a refused local connection. */
    public void onRemoteFailed(long nowMs) {
        if (state == State.CLOSED) return;
        sendRst();
        close(nowMs);
    }

    /** Bytes arrived from the remote stream. Segmented at the
     *  client's MSS, gated by the client's advertised window;
     *  anything that does not fit now is buffered for the next
     *  window update. */
    public void onRemoteData(byte[] data, int off, int len, long nowMs) {
        if (state == State.CLOSED || remoteEof) return;
        if (len > 0) outPending.write(data, off, len);
        tryFlush(nowMs);
    }

    /** The remote stream hit EOF. All buffered data is flushed,
     *  then our FIN is sent. */
    public void onRemoteClosed(long nowMs) {
        if (state == State.CLOSED) return;
        if (state == State.SYN_RECEIVED) {
            // Handshake never completed: fail cleanly.
            sendRst();
            close(nowMs);
            return;
        }
        remoteEof = true;
        tryFlush(nowMs);
        if (state == State.ESTABLISHED || state == State.CLOSE_WAIT) {
            sendFin(nowMs);
        }
        // FIN_WAIT_1/2: our FIN is already out; LAST_ACK: same.
    }

    private void sendFin(long nowMs) {
        byte[] seg = NatPackets.buildTcp(serverIp, clientIp, serverPort, clientPort,
                sendNext, forwardedSeq, NatPackets.TCP_FIN | NatPackets.TCP_ACK,
                ADVERTISED_WINDOW, 0, new byte[0], 0, 0);
        sink.emit(wrap(seg));
        unacked.add(new Sent(sendNext, lastEmitted, 0, true, false));
        sendNext = (sendNext + 1) & 0xFFFFFFFFL;
        setState(state == State.CLOSE_WAIT ? State.LAST_ACK : State.FIN_WAIT_1, nowMs);
    }

    /** Sends buffered output while the client's window allows. */
    private void tryFlush(long nowMs) {
        if (state == State.CLOSED) return;
        if (state == State.SYN_RECEIVED) {
            // Do not send data before the handshake completes.
            return;
        }
        while (outPending.size() > 0) {
            int inFlight = seqDiff(sendNext, sendBase);
            int available = clientWindow - inFlight;
            if (available <= 0) return;
            byte[] all = outPending.toByteArray();
            int chunk = Math.min(Math.min(all.length, clientMss), available);
            byte[] seg = NatPackets.buildTcp(serverIp, clientIp, serverPort, clientPort,
                    sendNext, forwardedSeq,
                    NatPackets.TCP_PSH | NatPackets.TCP_ACK,
                    ADVERTISED_WINDOW, 0, all, 0, chunk);
            sink.emit(wrap(seg));
            unacked.add(new Sent(sendNext, lastEmitted, chunk, false, false));
            sendNext = (sendNext + chunk) & 0xFFFFFFFFL;
            outPending.reset();
            if (chunk < all.length) {
                outPending.write(all, chunk, all.length - chunk);
            }
        }
    }

    /** Periodic sweep. Returns true when the flow has been idle in
     *  a half-open state long enough to drop. */
    public boolean onTick(long nowMs) {
        if (state == State.CLOSED) return true;
        long idle = nowMs - stateEnteredAt;
        if (state == State.SYN_RECEIVED && idle > SYN_RECEIVED_TIMEOUT_MS) {
            sendRst();
            close(nowMs);
            return true;
        }
        if ((state == State.LAST_ACK || state == State.FIN_WAIT_2)
                && idle > CLOSE_TIMEOUT_MS) {
            close(nowMs);
            return true;
        }
        return false;
    }

    /** Force-close from the engine (shutdown / gateway loss). */
    public void abort(long nowMs) {
        if (state == State.CLOSED) return;
        sendRst();
        sink.closeRemote(false);
        close(nowMs);
    }
}
