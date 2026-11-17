package app.control.launcher;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Off-device verification for the v1.24 cross-transport
 * bridging, exercising the exact shipping pure-Java code
 * (BridgeRule, RelayIngest):
 *
 * Legs:
 *   1. ENVELOPE: a relayed relay-text payload round-trips
 *      every provenance field (unicode/quotes/newlines in
 *      the text and names, the low-data marker, an empty
 *      origin pubkey), a bad "ots" reads as 0, and direct
 *      texts, malformed payloads, and relayed payloads
 *      missing the bid or the origin read as direct.
 *   2. BURST: the burst header round-trips with the ADPCM
 *      body byte-identical (including 0x0A bytes), raw
 *      ADPCM and malformed headers read as direct bursts,
 *      and an empty body still parses.
 *   3. PACKET KEYS: the mesh/Bluetooth packet keys
 *      round-trip through org.json for texts and
 *      spurt-starts, "ots" tolerates a number or a bad
 *      string, and the traversed-set wire form round-trips
 *      while ignoring unknown tokens and blanks.
 *   4. FAN-OUT: the targets matrix across all three arrival
 *      roads and connected sets, the switch-off / private /
 *      already-forwarded / transmitting gates, and the
 *      traversed subtraction (mesh-&gt;relay-&gt;mesh blocked).
 *   5. BIDS: arrival-bid derivation — relayed wins, the
 *      "m:" client message id is the bid verbatim, other
 *      relay ids get the "r:" prefix, local mids get the
 *      "m:" prefix, missing identities mint fresh bids,
 *      and the originator's relay post shares the bid
 *      gateways derive (no doubling).
 *   6. LOOPS: a two-phone ping-pong terminates by both
 *      mechanisms (the thread bid check and the traversed
 *      subtraction), a three-phone chain terminates, two
 *      gateways forwarding the same message mint the same
 *      bid so the receiver lands it once, and an
 *      already-forwarded bid never forwards again.
 *   7. STAMPING: entry stamping and reconstruction
 *      round-trip, the cross-road duplicate check, the
 *      bid-only stamp, and the one-decision bridge-seen
 *      flag.
 *   8. INGEST: RelayIngest decodes sealed and open relayed
 *      texts and bursts with their provenance intact (the
 *      burst header stripped, the body pure ADPCM), direct
 *      messages decode with no provenance, and wrong-key /
 *      tampered blobs decode to null.
 *   9. FORWARD STAMP: provenance construction rejects a
 *      missing bid or origin, and forwarded() keeps the
 *      bid and origin, renames the gateway, and accumulates
 *      the traversed set.
 *
 * Run (org.json jar AND bcprov on the classpath — RelayKeys
 * uses Bouncy Castle at compile time, as in
 * SubscriptionHarness):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar:libs/bcprov-jdk18on.jar \
 *     -d harness/classes harness/BridgingHarness.java
 *   java -cp harness/classes:harness/org-json.jar:libs/bcprov-jdk18on.jar \
 *     app.control.launcher.BridgingHarness
 * No Android classes, no network.
 */
public final class BridgingHarness {

    private static int checks;
    private static int passed;

    public static void main(String[] args) {
        envelope();
        burst();
        packetKeys();
        fanout();
        bids();
        loops();
        stamping();
        ingest();
        forwardStamp();
        System.out.println("BridgingHarness: " + passed + "/" + checks
                + " checks passed");
        if (passed != checks) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            passed++;
        } else {
            System.out.println("FAIL: " + name);
        }
    }

    private static BridgeRule.Relayed relayed(String bid) {
        return BridgeRule.Relayed.of(bid, "Ana", "pub-1", 1799999999999L,
                "Gail", EnumSet.of(BridgeRule.Transport.MESH));
    }

    private static void envelope() {
        BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-1",
                "Ana\u00efs \"Q\" \u2603", "pubkey-xyz", 1799999999999L,
                "Gateway G", EnumSet.of(BridgeRule.Transport.MESH));
        String text = "H\u00e9llo \"world\"\nline\\two \u2603";
        String payload = BridgeRule.buildRelayedText(text, true, r);
        BridgeRule.Relayed p = BridgeRule.relayedFromTextPayload(payload);
        check("envelope parses", p != null);
        check("envelope bid", p != null && "bid-1".equals(p.bid));
        check("envelope orig", p != null && r.origName.equals(p.origName));
        check("envelope origPub", p != null && "pubkey-xyz".equals(p.origPub));
        check("envelope ots", p != null && p.origTs == 1799999999999L);
        check("envelope via", p != null && "Gateway G".equals(p.viaName));
        check("envelope trav", p != null
                && p.traversed.equals(EnumSet.of(BridgeRule.Transport.MESH)));
        try {
            JSONObject o = new JSONObject(payload);
            check("envelope uses w key",
                    o.has("w") && !o.has("text") && !o.has("wd"));
            WordDict.Result decoded = WordDict.decodeCoded(
                    o.optString("w", ""), null);
            check("envelope text intact",
                    decoded.note == null && text.equals(decoded.text));
            check("envelope ld kept", o.optBoolean("ld", false));
            check("envelope t=text", "text".equals(o.optString("t", "")));
        } catch (Exception e) {
            check("envelope is valid JSON", false);
        }
        String plain = BridgeRule.buildRelayedText("hi", false, r);
        try {
            check("envelope no ld when clear",
                    !new JSONObject(plain).has("ld"));
        } catch (Exception e) {
            check("envelope no ld when clear", false);
        }
        BridgeRule.Relayed noPub = BridgeRule.Relayed.of("b2", "Ned", "",
                5L, "", EnumSet.noneOf(BridgeRule.Transport.class));
        BridgeRule.Relayed p2 = BridgeRule.relayedFromTextPayload(
                BridgeRule.buildRelayedText("x", false, noPub));
        check("envelope empty origPub", p2 != null && "".equals(p2.origPub));
        check("envelope empty trav", p2 != null && p2.traversed.isEmpty());
        String badOts = "{\"t\":\"text\",\"text\":\"x\",\"relayed\":true,"
                + "\"bid\":\"b3\",\"orig\":\"Ned\",\"ots\":\"not-a-number\","
                + "\"via\":\"\",\"trav\":\"\"}";
        BridgeRule.Relayed p3 = BridgeRule.relayedFromTextPayload(badOts);
        check("envelope bad ots reads 0", p3 != null && p3.origTs == 0);
        check("direct text reads null", BridgeRule.relayedFromTextPayload(
                LowDataEnvelope.build("hi", false)) == null);
        check("direct ld text reads null", BridgeRule.relayedFromTextPayload(
                LowDataEnvelope.build("hi", true)) == null);
        check("malformed reads null",
                BridgeRule.relayedFromTextPayload("{not json") == null);
        check("null reads null", BridgeRule.relayedFromTextPayload(null) == null);
        check("empty reads null", BridgeRule.relayedFromTextPayload("") == null);
        String noBid = "{\"t\":\"text\",\"text\":\"x\",\"relayed\":true,"
                + "\"orig\":\"Ned\"}";
        check("relayed without bid reads null",
                BridgeRule.relayedFromTextPayload(noBid) == null);
        String noOrig = "{\"t\":\"text\",\"text\":\"x\",\"relayed\":true,"
                + "\"bid\":\"b4\"}";
        check("relayed without orig reads null",
                BridgeRule.relayedFromTextPayload(noOrig) == null);
        String flagOff = "{\"t\":\"text\",\"text\":\"x\",\"relayed\":false,"
                + "\"bid\":\"b5\",\"orig\":\"Ned\"}";
        check("relayed false reads null",
                BridgeRule.relayedFromTextPayload(flagOff) == null);
        String wrongT = "{\"t\":\"burst\",\"relayed\":true,"
                + "\"bid\":\"b6\",\"orig\":\"Ned\"}";
        check("wrong t reads null",
                BridgeRule.relayedFromTextPayload(wrongT) == null);
    }

    private static void burst() {
        BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-9",
                "Zo\u00eb \u2603", "pub-9", 42L, "Gail",
                EnumSet.of(BridgeRule.Transport.BLUETOOTH));
        byte[] body = new byte[]{0x0A, 0x00, (byte) 0xFF, 0x0A, 0x42, 0x0A};
        byte[] payload = BridgeRule.buildRelayedBurstPayload(r, body);
        BridgeRule.BurstHeader h = BridgeRule.parseBurstHeader(payload);
        check("burst parses", h != null);
        check("burst bid", h != null && "bid-9".equals(h.relayed.bid));
        check("burst orig", h != null && r.origName.equals(h.relayed.origName));
        check("burst ots", h != null && h.relayed.origTs == 42L);
        check("burst trav", h != null && h.relayed.traversed.equals(
                EnumSet.of(BridgeRule.Transport.BLUETOOTH)));
        check("burst body identical", h != null && Arrays.equals(
                Arrays.copyOfRange(payload, h.bodyOffset, payload.length), body));
        check("burst body starts after header",
                h != null && h.bodyOffset > 0 && h.bodyOffset < payload.length);
        byte[] raw = new byte[]{1, 2, 3, 10, 4, 5};
        check("raw ADPCM reads null", BridgeRule.parseBurstHeader(raw) == null);
        check("null reads null", BridgeRule.parseBurstHeader(null) == null);
        check("empty reads null",
                BridgeRule.parseBurstHeader(new byte[0]) == null);
        check("short reads null",
                BridgeRule.parseBurstHeader(new byte[]{'{', '"'}) == null);
        String noNl = "{\"t\":\"rburst\",\"bid\":\"b\",\"orig\":\"N\"}";
        check("header without newline reads null", BridgeRule.parseBurstHeader(
                noNl.getBytes(StandardCharsets.UTF_8)) == null);
        String noBid = "{\"t\":\"rburst\",\"orig\":\"N\"}\n";
        check("header without bid reads null", BridgeRule.parseBurstHeader(
                noBid.getBytes(StandardCharsets.UTF_8)) == null);
        byte[] emptyBody = BridgeRule.buildRelayedBurstPayload(r, new byte[0]);
        BridgeRule.BurstHeader he = BridgeRule.parseBurstHeader(emptyBody);
        check("empty body parses", he != null);
        check("empty body offset at end",
                he != null && he.bodyOffset == emptyBody.length);
        byte[] tricky = new byte[]{0x0A, 0x0A, 0x0A};
        byte[] tp = BridgeRule.buildRelayedBurstPayload(r, tricky);
        BridgeRule.BurstHeader ht = BridgeRule.parseBurstHeader(tp);
        check("newline-only body identical", ht != null && Arrays.equals(
                Arrays.copyOfRange(tp, ht.bodyOffset, tp.length), tricky));
    }

    private static void packetKeys() {
        BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-7", "Ana", "pub-1",
                123L, "Gail", EnumSet.of(BridgeRule.Transport.RELAY));
        try {
            JSONObject o = new JSONObject();
            o.put("from", "Ana");
            o.put("text", "hello");
            o.put("channel", "open:3");
            BridgeRule.putRelayKeys(o, r);
            BridgeRule.Relayed p = BridgeRule.relayedFromJson(o, "from");
            check("packet text round-trips", p != null);
            check("packet bid", p != null && "bid-7".equals(p.bid));
            check("packet orig", p != null && "Ana".equals(p.origName));
            check("packet ots", p != null && p.origTs == 123L);
            check("packet trav", p != null && p.traversed.equals(
                    EnumSet.of(BridgeRule.Transport.RELAY)));
            JSONObject s = new JSONObject();
            s.put("name", "Ana");
            BridgeRule.putRelayKeys(s, r);
            BridgeRule.Relayed ps = BridgeRule.relayedFromJson(s, "name");
            check("packet spurt round-trips", ps != null
                    && "bid-7".equals(ps.bid) && "Ana".equals(ps.origName));
            JSONObject plain = new JSONObject();
            plain.put("from", "Ana");
            plain.put("text", "hi");
            check("packet without flag reads null",
                    BridgeRule.relayedFromJson(plain, "from") == null);
            JSONObject noBid = new JSONObject();
            noBid.put("from", "Ana");
            noBid.put("relayed", true);
            check("packet without bid reads null",
                    BridgeRule.relayedFromJson(noBid, "from") == null);
            JSONObject noName = new JSONObject();
            noName.put("relayed", true);
            noName.put("bid", "b");
            check("packet without name reads null",
                    BridgeRule.relayedFromJson(noName, "from") == null);
            check("packet null reads null",
                    BridgeRule.relayedFromJson(null, "from") == null);
            JSONObject numOts = new JSONObject();
            numOts.put("from", "Ana");
            numOts.put("relayed", true);
            numOts.put("bid", "b8");
            numOts.put("ots", 98765);
            BridgeRule.Relayed pn = BridgeRule.relayedFromJson(numOts, "from");
            check("packet numeric ots", pn != null && pn.origTs == 98765L);
            JSONObject badOts = new JSONObject();
            badOts.put("from", "Ana");
            badOts.put("relayed", true);
            badOts.put("bid", "b9");
            badOts.put("ots", "junk");
            BridgeRule.Relayed pb = BridgeRule.relayedFromJson(badOts, "from");
            check("packet bad ots reads 0", pb != null && pb.origTs == 0);
            BridgeRule.putRelayKeys(null, r);
            BridgeRule.putRelayKeys(new JSONObject(), null);
            check("putRelayKeys null-safe", true);
        } catch (Exception e) {
            check("packetKeys no exception: " + e, false);
        }
        EnumSet<BridgeRule.Transport> all = EnumSet.allOf(BridgeRule.Transport.class);
        check("trav round-trips all",
                BridgeRule.parseTrav(BridgeRule.travString(all)).equals(all));
        check("trav empty round-trips",
                BridgeRule.parseTrav(BridgeRule.travString(
                        EnumSet.noneOf(BridgeRule.Transport.class))).isEmpty());
        check("trav ignores unknowns", BridgeRule.parseTrav(
                "mesh, bogus, relay").equals(EnumSet.of(
                BridgeRule.Transport.MESH, BridgeRule.Transport.RELAY)));
        check("trav trims blanks", BridgeRule.parseTrav(" mesh , ,bt ")
                .equals(EnumSet.of(BridgeRule.Transport.MESH,
                        BridgeRule.Transport.BLUETOOTH)));
        check("trav null reads empty", BridgeRule.parseTrav(null).isEmpty());
        check("trav empty reads empty", BridgeRule.parseTrav("").isEmpty());
        check("travString null reads empty",
                "".equals(BridgeRule.travString(null)));
    }

    private static EnumSet<BridgeRule.Transport> allRoads() {
        return EnumSet.allOf(BridgeRule.Transport.class);
    }

    private static EnumSet<BridgeRule.Transport> tgt(BridgeRule.Transport on,
            EnumSet<BridgeRule.Transport> connected, boolean bridgingOn,
            boolean isPrivate, boolean holdsKey, boolean already,
            boolean transmitting, EnumSet<BridgeRule.Transport> trav) {
        return BridgeRule.targets(on, connected, bridgingOn, isPrivate,
                holdsKey, already, transmitting, trav);
    }

    private static void fanout() {
        EnumSet<BridgeRule.Transport> none = EnumSet.noneOf(BridgeRule.Transport.class);
        EnumSet<BridgeRule.Transport> btRelay = EnumSet.of(
                BridgeRule.Transport.BLUETOOTH, BridgeRule.Transport.RELAY);
        EnumSet<BridgeRule.Transport> meshRelay = EnumSet.of(
                BridgeRule.Transport.MESH, BridgeRule.Transport.RELAY);
        EnumSet<BridgeRule.Transport> meshBt = EnumSet.of(
                BridgeRule.Transport.MESH, BridgeRule.Transport.BLUETOOTH);
        check("fanout mesh->bt+relay", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, false, false, none)
                .equals(btRelay));
        check("fanout bt->mesh+relay", tgt(BridgeRule.Transport.BLUETOOTH,
                allRoads(), true, false, false, false, false, none)
                .equals(meshRelay));
        check("fanout relay->mesh+bt", tgt(BridgeRule.Transport.RELAY,
                allRoads(), true, false, false, false, false, none)
                .equals(meshBt));
        check("fanout mesh->relay only", tgt(BridgeRule.Transport.MESH,
                EnumSet.of(BridgeRule.Transport.MESH,
                        BridgeRule.Transport.RELAY),
                true, false, false, false, false, none).equals(
                EnumSet.of(BridgeRule.Transport.RELAY)));
        check("fanout arrival road only", tgt(BridgeRule.Transport.MESH,
                EnumSet.of(BridgeRule.Transport.MESH),
                true, false, false, false, false, none).isEmpty());
        check("fanout nothing connected", tgt(BridgeRule.Transport.MESH,
                none, true, false, false, false, false, none).isEmpty());
        check("fanout switch off", tgt(BridgeRule.Transport.MESH,
                allRoads(), false, false, false, false, false, none).isEmpty());
        check("fanout private no key", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, true, false, false, false, none).isEmpty());
        check("fanout private with key", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, true, true, false, false, none)
                .equals(btRelay));
        check("fanout open no key", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, false, false, none)
                .equals(btRelay));
        check("fanout already forwarded", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, true, false, none).isEmpty());
        check("fanout transmitting", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, false, true, none).isEmpty());
        check("fanout traversed mesh blocks relay->mesh",
                tgt(BridgeRule.Transport.RELAY, allRoads(),
                        true, false, false, false, false,
                        EnumSet.of(BridgeRule.Transport.MESH)).equals(
                        EnumSet.of(BridgeRule.Transport.BLUETOOTH)));
        check("fanout traversed all blocks all",
                tgt(BridgeRule.Transport.BLUETOOTH, allRoads(),
                        true, false, false, false, false,
                        EnumSet.of(BridgeRule.Transport.MESH,
                                BridgeRule.Transport.RELAY)).isEmpty());
        check("fanout traversed bt blocks mesh->bt",
                tgt(BridgeRule.Transport.MESH, allRoads(),
                        true, false, false, false, false,
                        EnumSet.of(BridgeRule.Transport.BLUETOOTH)).equals(
                        EnumSet.of(BridgeRule.Transport.RELAY)));
        check("fanout null connected", tgt(BridgeRule.Transport.MESH,
                null, true, false, false, false, false, none).isEmpty());
        check("fanout null traversed", tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, false, false, null)
                .equals(btRelay));
        check("fanout null arrival", tgt(null, allRoads(),
                true, false, false, false, false, none).equals(allRoads()));
    }

    private static void bids() {
        check("relay bid r-prefix",
                "r:abc".equals(BridgeRule.relayArrivalBid("abc", null)));
        check("relay bid m-prefix verbatim", "m:uuid-1".equals(
                BridgeRule.relayArrivalBid("m:uuid-1", null)));
        check("relay bid relayed wins", "carried".equals(
                BridgeRule.relayArrivalBid("abc", relayed("carried"))));
        String f1 = BridgeRule.relayArrivalBid("", null);
        String f2 = BridgeRule.relayArrivalBid("", null);
        check("relay bid empty mints fresh", !f1.isEmpty() && !f1.equals(f2));
        check("relay bid null mints fresh",
                !BridgeRule.relayArrivalBid(null, null).isEmpty());
        check("local bid m-prefix",
                "m:mid-1".equals(BridgeRule.localArrivalBid("mid-1", null)));
        check("local bid relayed wins", "carried".equals(
                BridgeRule.localArrivalBid("mid-1", relayed("carried"))));
        String g1 = BridgeRule.localArrivalBid("", null);
        String g2 = BridgeRule.localArrivalBid("", null);
        check("local bid empty mints fresh", !g1.isEmpty() && !g1.equals(g2));
        check("local bid null mints fresh",
                !BridgeRule.localArrivalBid(null, null).isEmpty());
        String mid = "spurt-mid-42";
        check("originator post == gateway bid", BridgeRule.localArrivalBid(mid, null)
                .equals(BridgeRule.relayArrivalBid("m:" + mid, null)));
        check("two gateways same bid", BridgeRule.localArrivalBid(mid, null)
                .equals(BridgeRule.localArrivalBid(mid, null)));
        check("two relay pulls same bid", BridgeRule.relayArrivalBid("cmid-9", null)
                .equals(BridgeRule.relayArrivalBid("cmid-9", null)));
        check("newBid unique", !BridgeRule.newBid().equals(BridgeRule.newBid()));
        check("newBid non-empty", !BridgeRule.newBid().isEmpty());
    }

    private static void loops() {
        // Phone A hears a mesh text (mid M) and forwards it.
        String mid = "mesh-mid-7";
        String bid = BridgeRule.localArrivalBid(mid, null);
        List<JSONObject> threadA = new ArrayList<>();
        check("loop A first sight unseen",
                !BridgeRule.entryWithBid(threadA, bid));
        EnumSet<BridgeRule.Transport> toA = tgt(BridgeRule.Transport.MESH,
                allRoads(), true, false, false, false, false,
                EnumSet.noneOf(BridgeRule.Transport.class));
        check("loop A forwards to bt+relay", toA.equals(EnumSet.of(
                BridgeRule.Transport.BLUETOOTH, BridgeRule.Transport.RELAY)));
        BridgeRule.Relayed stampA = BridgeRule.Relayed.of(bid, "Ana", "", 1L,
                "", EnumSet.noneOf(BridgeRule.Transport.class))
                .forwarded("PhoneA", BridgeRule.Transport.MESH);
        check("loop A stamp trav mesh", stampA.traversed.equals(
                EnumSet.of(BridgeRule.Transport.MESH)));
        check("loop A stamp via", "PhoneA".equals(stampA.viaName));
        try {
            JSONObject e = new JSONObject();
            e.put("bid", bid);
            threadA.add(e);
        } catch (Exception ex) {
            check("loop A entry build", false);
        }
        // Phone B pulls the relay copy: same bid, and the
        // traversed set keeps it off the mesh (no
        // mesh->relay->mesh), so it forwards to Bluetooth
        // only.
        String bidB = BridgeRule.relayArrivalBid("relay-cmid-1", stampA);
        check("loop B same bid", bid.equals(bidB));
        EnumSet<BridgeRule.Transport> toB = tgt(BridgeRule.Transport.RELAY,
                allRoads(), true, false, false, false, false,
                EnumSet.copyOf(stampA.traversed));
        check("loop B forwards to bt only", toB.equals(
                EnumSet.of(BridgeRule.Transport.BLUETOOTH)));
        BridgeRule.Relayed stampB = stampA.forwarded("PhoneB",
                BridgeRule.Transport.RELAY);
        check("loop B trav accumulates", stampB.traversed.equals(EnumSet.of(
                BridgeRule.Transport.MESH, BridgeRule.Transport.RELAY)));
        // Phone A hears the Bluetooth copy: the thread bid
        // check suppresses it, AND the traversed set leaves
        // no target even if the entry were missing.
        check("loop A sees own bid in thread",
                BridgeRule.entryWithBid(threadA, bidB));
        check("loop A no target on return", tgt(BridgeRule.Transport.BLUETOOTH,
                allRoads(), true, false, false, false, false,
                EnumSet.copyOf(stampB.traversed)).isEmpty());
        // Three-phone chain: C receives B's Bluetooth
        // forward (trav mesh+relay) and has nowhere to send.
        check("loop C chain terminates", tgt(BridgeRule.Transport.BLUETOOTH,
                allRoads(), true, false, false, false, false,
                EnumSet.copyOf(stampB.traversed)).isEmpty());
        // Two gateways, one mesh message: the same bid, so
        // the receiver lands it once.
        String g1 = BridgeRule.localArrivalBid(mid, null);
        String g2 = BridgeRule.localArrivalBid(mid, null);
        check("loop gateways same bid", g1.equals(g2));
        List<JSONObject> threadR = new ArrayList<>();
        try {
            JSONObject first = new JSONObject();
            first.put("bid", g1);
            threadR.add(first);
        } catch (Exception ex) {
            check("loop receiver entry build", false);
        }
        check("loop receiver dedups second gateway",
                BridgeRule.entryWithBid(threadR, g2));
        // An already-forwarded bid never forwards again,
        // even on a fresh arrival road.
        check("loop forwarded never re-forwards",
                tgt(BridgeRule.Transport.BLUETOOTH, allRoads(),
                        true, false, false, true, false,
                        EnumSet.noneOf(BridgeRule.Transport.class)).isEmpty());
        // A relayed message that already traversed the
        // relay never republishes to the relay.
        check("loop relay traversed blocks republish",
                tgt(BridgeRule.Transport.MESH, allRoads(),
                        true, false, false, false, false,
                        EnumSet.of(BridgeRule.Transport.RELAY)).equals(
                        EnumSet.of(BridgeRule.Transport.BLUETOOTH)));
    }

    private static void stamping() {
        try {
            BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-s", "Ana",
                    "pub-s", 777L, "Gail",
                    EnumSet.of(BridgeRule.Transport.MESH));
            JSONObject e = new JSONObject();
            e.put("from", "Gail");
            e.put("sender", "gateway-pub");
            e.put("ts", 999L);
            BridgeRule.stampRelayedEntry(e, r);
            check("stamp from is origin", "Ana".equals(e.optString("from", "")));
            check("stamp sender is origin pub",
                    "pub-s".equals(e.optString("sender", "")));
            check("stamp ts is origin ts", e.optLong("ts", 0) == 777L);
            check("stamp bid", "bid-s".equals(e.optString("bid", "")));
            check("stamp relayed flag", e.optBoolean("relayed", false));
            check("stamp via", "Gail".equals(e.optString("via", "")));
            check("stamp trav", "mesh".equals(e.optString("trav", "")));
            BridgeRule.Relayed back = BridgeRule.relayedFromEntry(e);
            check("stamp round-trips", back != null
                    && "bid-s".equals(back.bid)
                    && "Ana".equals(back.origName)
                    && "pub-s".equals(back.origPub)
                    && back.origTs == 777L
                    && "Gail".equals(back.viaName)
                    && back.traversed.equals(
                            EnumSet.of(BridgeRule.Transport.MESH)));
            JSONObject direct = new JSONObject();
            direct.put("from", "Ana");
            check("direct entry reads null",
                    BridgeRule.relayedFromEntry(direct) == null);
            check("null entry reads null",
                    BridgeRule.relayedFromEntry(null) == null);
            BridgeRule.stampRelayedEntry(null, r);
            BridgeRule.stampRelayedEntry(new JSONObject(), null);
            check("stampRelayedEntry null-safe", true);
            List<JSONObject> thread = new ArrayList<>();
            thread.add(null);
            thread.add(direct);
            thread.add(e);
            check("entryWithBid match",
                    BridgeRule.entryWithBid(thread, "bid-s"));
            check("entryWithBid miss",
                    !BridgeRule.entryWithBid(thread, "nope"));
            check("entryWithBid empty never matches",
                    !BridgeRule.entryWithBid(thread, ""));
            check("entryWithBid null list",
                    !BridgeRule.entryWithBid(null, "bid-s"));
            JSONObject b = new JSONObject();
            BridgeRule.stampBid(b, "bid-only");
            check("stampBid writes", "bid-only".equals(b.optString("bid", "")));
            JSONObject b2 = new JSONObject();
            BridgeRule.stampBid(b2, "");
            check("stampBid empty no-op", !b2.has("bid"));
            BridgeRule.stampBid(null, "x");
            check("stampBid null-safe", true);
            JSONObject s = new JSONObject();
            check("bridgeSeen initially false",
                    !BridgeRule.isBridgeSeen(s));
            BridgeRule.markBridgeSeen(s);
            check("bridgeSeen after mark", BridgeRule.isBridgeSeen(s));
            BridgeRule.markBridgeSeen(s);
            check("bridgeSeen idempotent", BridgeRule.isBridgeSeen(s));
            check("bridgeSeen null false", !BridgeRule.isBridgeSeen(null));
            BridgeRule.markBridgeSeen(null);
            check("markBridgeSeen null-safe", true);
        } catch (Exception ex) {
            check("stamping no exception: " + ex, false);
        }
    }

    private static void ingest() {
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        for (int i = 0; i < 32; i++) {
            k1[i] = (byte) (i + 1);
            k2[i] = (byte) (i + 7);
        }
        BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-i", "Ana", "pub-i",
                555L, "Gail", EnumSet.of(BridgeRule.Transport.MESH));
        String payload = BridgeRule.buildRelayedText("relay me", true, r);
        RelayIngest.Decoded d = RelayIngest.decode(k1, RelayCrypto.seal(
                k1, payload.getBytes(StandardCharsets.UTF_8)));
        check("ingest sealed relayed text", d != null && d.isText);
        // v1.32: the bridged text codes, so the lowercase
        // sentence start normalizes.
        check("ingest sealed text", d != null && "Relay me".equals(d.text));
        check("ingest sealed ld", d != null && d.lowData);
        check("ingest sealed provenance", d != null && d.relayed != null
                && "bid-i".equals(d.relayed.bid)
                && "Ana".equals(d.relayed.origName)
                && "pub-i".equals(d.relayed.origPub)
                && d.relayed.origTs == 555L
                && "Gail".equals(d.relayed.viaName));
        RelayIngest.Decoded direct = RelayIngest.decode(k1, RelayCrypto.seal(
                k1, LowDataEnvelope.build("plain", false)
                        .getBytes(StandardCharsets.UTF_8)));
        check("ingest sealed direct text",
                direct != null && direct.isText && direct.relayed == null);
        byte[] body = new byte[]{9, 8, 7, 10, 6, 5, 4, 3};
        byte[] burstPayload = BridgeRule.buildRelayedBurstPayload(r, body);
        RelayIngest.Decoded b = RelayIngest.decode(
                null, RelayKeys.b64Std(burstPayload));
        check("ingest open relayed burst", b != null && !b.isText);
        check("ingest burst body pure ADPCM",
                b != null && Arrays.equals(b.plain, body));
        check("ingest burst samples", b != null && b.samples == body.length * 2);
        check("ingest burst provenance", b != null && b.relayed != null
                && "bid-i".equals(b.relayed.bid)
                && "Ana".equals(b.relayed.origName));
        RelayIngest.Decoded raw = RelayIngest.decode(null, RelayKeys.b64Std(body));
        check("ingest open direct burst",
                raw != null && !raw.isText && raw.relayed == null
                        && Arrays.equals(raw.plain, body));
        RelayIngest.Decoded sealedBurst = RelayIngest.decode(
                k1, RelayCrypto.seal(k1, burstPayload));
        check("ingest sealed relayed burst", sealedBurst != null
                && !sealedBurst.isText && sealedBurst.relayed != null
                && Arrays.equals(sealedBurst.plain, body));
        check("ingest wrong key null", RelayIngest.decode(k2,
                RelayCrypto.seal(k1, payload.getBytes(StandardCharsets.UTF_8)))
                == null);
        String sealed = RelayCrypto.seal(k1,
                payload.getBytes(StandardCharsets.UTF_8));
        byte[] tampered = RelayKeys.b64StdDecode(sealed);
        tampered[tampered.length - 1] ^= 0x01;
        check("ingest tampered null", RelayIngest.decode(
                k1, RelayKeys.b64Std(tampered)) == null);
        RelayIngest.Decoded noLd = RelayIngest.decode(k1, RelayCrypto.seal(
                k1, BridgeRule.buildRelayedText("x", false, r)
                        .getBytes(StandardCharsets.UTF_8)));
        check("ingest relayed text no ld", noLd != null && !noLd.lowData);
    }

    private static void forwardStamp() {
        check("of null bid null", BridgeRule.Relayed.of(
                null, "Ana", "", 0L, "", null) == null);
        check("of empty bid null", BridgeRule.Relayed.of(
                "", "Ana", "", 0L, "", null) == null);
        check("of empty orig null", BridgeRule.Relayed.of(
                "b", "", "", 0L, "", null) == null);
        check("of null orig null", BridgeRule.Relayed.of(
                "b", null, "", 0L, "", null) == null);
        BridgeRule.Relayed base = BridgeRule.Relayed.of("b-f", "Ana", null,
                11L, null, null);
        check("of nulls default", base != null && "".equals(base.origPub)
                && "".equals(base.viaName) && base.traversed.isEmpty());
        BridgeRule.Relayed f1 = base.forwarded("Gail", BridgeRule.Transport.MESH);
        check("forwarded keeps bid", "b-f".equals(f1.bid));
        check("forwarded keeps origin", "Ana".equals(f1.origName)
                && f1.origTs == 11L);
        check("forwarded names gateway", "Gail".equals(f1.viaName));
        check("forwarded adds road", f1.traversed.equals(
                EnumSet.of(BridgeRule.Transport.MESH)));
        BridgeRule.Relayed f2 = f1.forwarded("Hank", BridgeRule.Transport.RELAY);
        check("forwarded accumulates", f2.traversed.equals(EnumSet.of(
                BridgeRule.Transport.MESH, BridgeRule.Transport.RELAY)));
        check("forwarded renames gateway", "Hank".equals(f2.viaName));
        check("forwarded keeps bid across hops", "b-f".equals(f2.bid));
        BridgeRule.Relayed f3 = base.forwarded(null, null);
        check("forwarded null-safe", "".equals(f3.viaName)
                && f3.traversed.isEmpty());
        check("forwarded does not mutate base", base.traversed.isEmpty()
                && "".equals(base.viaName));
    }
}
