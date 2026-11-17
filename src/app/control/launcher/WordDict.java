package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The word-dictionary codec (v1.28 codec, v1.29 table): common words travel as short
 * codes instead of spelled-out text, so walkie texts and chat
 * messages cost fewer bytes on every road — mesh/LAN, Bluetooth,
 * and the relay. The audio modem is untouched: it carries keys
 * and cards, never chat text.
 *
 * CODE SPACE: a code is one base symbol plus one digit plus a
 * position bit. 62 base symbols (a-z, A-Z, 0-9) x 10 digits x 2
 * positions (superscript / subscript) = 1,240 codes. The
 * dictionary below is the table: a word's code index is its
 * position in WORDS, and the index arithmetic derives the
 * symbol, digit, and position — no per-word table beyond the
 * list itself.
 *
 * WIRE FORM: two bytes per code. The first byte is the base
 * symbol; the second is a base-20 digit: '0'-'9' for superscript
 * 0-9, 'a'-'j' for subscript 0-9. Both bytes are printable
 * ASCII, so a pre-v1.28 build receiving coded text sees
 * odd-but-printable characters, never garbage bytes — and never
 * crashes: it just shows the codes. DISPLAY FORM (for humans,
 * never sent): the symbol plus a real Unicode superscript or
 * subscript digit, via displayForm().
 *
 * ESCAPES: words outside the dictionary — names, places, rare
 * words, numbers, emoji, ALL-CAPS — travel spelled out inside an
 * escape: '~' + two decimal digits of length + the raw run
 * (chunked at 99 chars). Nothing ever fails to send.
 * Punctuation and whitespace pass through verbatim, so spacing
 * and punctuation reattach exactly.
 *
 * CAPITALIZATION: one rule. A word encodes when it matches the
 * dictionary ignoring case AND (it is all-lowercase, or it is
 * exactly the stored form, or it is Capitalized at a sentence
 * start). Sentence start = the first word, or a word after
 * sentence-ending punctuation plus whitespace. On decode, a
 * sentence-start code ALWAYS decodes capitalized — a lowercase
 * word at a sentence start normalizes to a capital, the way
 * phone keyboards autocapitalize. Every other capital form
 * travels raw and reproduces exactly. The dictionary stores
 * "I" and the "I" contractions in canonical capitals, so they
 * always decode capitalized ("i" normalizes to "I", "i'm" to
 * "I'm"). Those two normalizations — sentence-start capitals
 * and the "I" forms — are the only cases where decode does
 * not reproduce the input byte for byte; everything else
 * round-trips exactly, and the harness proves both the
 * round-trips and the normalizations.
 *
 * FRAMING (v1.32): every message is coded — there is no
 * plain-text form anymore and no per-message marker. The
 * wire form is self-identifying by SHAPE: mesh/Bluetooth
 * coded texts ride a dedicated packet type, and relay/chat
 * coded texts ride a dedicated envelope key, so the receiver
 * knows to decode without any header spending bytes. Legacy
 * messages carrying the v1.28 "wd" marker still decode
 * forever (decodeMarked), and legacy plain messages still
 * display as plain text — the receiver distinguishes by
 * shape, never by guessing content. A payload that fails
 * strict parsing renders UNDECODED with an honest note
 * instead of decoding wrong — decodeCoded() never throws
 * and never silently mis-decodes.
 *
 * v1.29 TABLE: the codec reads from an active WordTable. At
 * class load it is the built-in v1 table (WORDS below); once
 * the background dictionary build lands, DictStore swaps in
 * the full table built from the repo file, learned state
 * carried over. The first 1,200 entries are frozen in both.
 * Indices at and above 1240 use the self-delimiting
 * four-byte long form (symbol + lead + two base-62 digits).
 * The encoder prefers BASE codes when a word exists in both
 * sections; the decoder accepts codes from either section.
 * Learned codes ride only to scopes already taught them;
 * encodeScoped() emits the teach block for the rest, and
 * decodeMarked() applies a received teach block before
 * decoding. Learning itself is explicit — never a side
 * effect of decode.
 *
 * Pure Java, no Android types: the harness proves the codec
 * with the exact shipping code.
 */
public final class WordDict {
    private WordDict() {}

    /** Codec version. 1 = v1.28 short codes only; 2 = long
     *  codes + teaching. Bumped only when the wire format
     *  changes — the dictionary FILE has its own version. */
    public static final int VERSION = 2;

    /** 62 symbols x 10 digits x 2 positions. */
    public static final int MAX_CODES = 1240;

    /** Base symbols: index = code / 20. */
    static final String SYMBOLS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Second wire byte, a base-20 digit: 0-9 = superscript,
     *  a-j = subscript. Index = code % 20; digit = index / 2,
     *  position = index % 2 (0 superscript, 1 subscript). */
    static final String DIGIT_CHARS = "0123456789abcdefghij";

    /** Lead bytes for long codes: the 42 symbol characters
     *  that are NOT base-20 digits. A second byte in this set
     *  means "four-byte code follows"; a second byte in
     *  DIGIT_CHARS means "two-byte code". The sets are
     *  disjoint by construction, which is what makes the
     *  wire self-delimiting. */
    static final String LEAD_CHARS =
            "klmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** First index using the long form. */
    static final int LONG_BASE = 1240;
    /** Long-form capacity: 62 x 42 x 62 x 62. */
    static final int LONG_SPAN = 62 * 42 * 62 * 62;
    /** Highest encodable index. */
    static final int MAX_INDEX = LONG_BASE + LONG_SPAN - 1;

    static final char ESCAPE = '~';
    static final int MAX_ESCAPE_RUN = 99;

    /** Cap on an inbound teach block, in bytes (a safety
     *  net on top of the entry cap; oversized blocks are
     *  ignored whole). */
    static final int MAX_TEACH_BLOCK_BYTES = 4096;

    /** Max teach entries attached to one message; the rest
     *  wait for a later message. */
    static final int TEACH_PER_MESSAGE = 50;

    static final String NOTE_NEWER =
            "Sent with a newer word list — shown as sent.";
    static final String NOTE_CORRUPT =
            "Couldn't decode this message — shown as sent.";
    static final String NOTE_UNTAUGHT =
            "Uses words this phone hasn't learned yet — shown as sent.";

    static final String SUPER_DIGITS = "\u2070\u00b9\u00b2\u00b3\u2074\u2075\u2076\u2077\u2078\u2079";
    static final String SUB_DIGITS = "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089";

    // ---- the dictionary: ordered by rough frequency; the order
    // IS the code table, so entries are never reordered or
    // removed within a version — only appended, with a version
    // bump. All lowercase except "I" and its contractions,
    // which decode capitalized (see the class doc). ----

    static final String[] WORDS = {
            // Function words, pronouns, auxiliaries.
            "the", "of", "and", "a", "to", "in", "is", "you",
            "that", "it", "he", "was", "for", "on", "are", "as",
            "with", "his", "they", "I", "at", "be", "this", "have",
            "from", "or", "one", "had", "by", "but", "not", "what",
            "all", "were", "we", "when", "your", "can", "said",
            "there", "use", "an", "each", "which", "she", "do",
            "how", "their", "if", "will", "up", "other", "about",
            "out", "many", "then", "them", "these", "so", "some",
            "her", "would", "make", "like", "him", "into", "time",
            "has", "look", "two", "more", "write", "go", "see",
            "number", "no", "way", "could", "people", "my", "than",
            "first", "water", "been", "call", "who", "its", "now",
            "find", "long", "down", "day", "did", "get", "come",
            "made", "may", "part", "over", "new", "sound", "take",
            "only", "little", "work", "know", "place", "year",
            "live", "me", "back", "give", "most", "very", "after",
            "thing", "our", "just", "name", "good", "sentence",
            "man", "think", "say", "great", "where", "help",
            "through", "much", "before", "line", "right", "too",
             "old", "any", "same", "tell", "boy", "follow",
            "came", "want", "show", "also", "around", "form",
            "three", "small", "set", "put", "end", "does",
            "another", "well", "large", "must", "big", "even",
            "such", "because", "turn", "here", "why", "ask",
            "went", "men", "read", "need", "land", "different",
            "home", "us", "move", "try", "kind", "hand", "picture",
            "again", "change", "off", "play", "spell", "air",
            "away", "animal", "house", "point", "page", "letter",
            "mother", "answer", "found", "study", "still", "learn",
            "should", "world", "high", "every", "near", "add",
            "food", "between", "own", "below", "country", "plant",
            "last", "school", "father", "keep", "tree", "never",
            "start", "city", "earth", "eye", "light", "thought",
            "head", "under", "story", "saw", "left", "few",
            "while", "along", "might", "close", "something",
            "seem", "next", "hard", "open", "example", "begin",
            "life", "always", "those", "both", "paper", "together",
            "got", "group", "often", "run", "dog", "horse", "cat",
            "fish", "bird", "friend", "four", "five", "six",
            "seven", "eight", "nine", "ten", "zero", "hundred",
            "thousand", "million", "second", "third", "once",
            "twice", "am",  "cannot", "yet", "until",
            "since", "although", "though", "whether", "either",
            "neither",   "among", "within", "without",
            "toward", "towards", "across", "behind", "beside",
              "during", "except", "inside",
            "outside",  "above",  
            "against",     "ago",
             "thus",  "however", 
               
             "perhaps", "maybe", "probably", 
            "certainly", "definitely",  "really", 
            "quite", "rather",   "almost",
            "nearly", "hardly",   
                 
            "ever",    "sometimes",
            "rarely",  "usually",  
              "daily", 
               "soon", "already",
               "anyway", 
            "anywhere", "somewhere", "nowhere", "everywhere",
            "anyone", "someone", "everyone",   "nobody",
               "anything",
             "everything", "nothing", 
               
            "whose",    "myself",
            "yourself", "himself", "herself", 
              "themselves", "mine",
            "yours",  "ours",    
                  
                   
            "being",    "having",  
             "done", "doing",   
                 
               
            // Common verbs, base forms and frequent irregulars.
                  
                  
                  "feel",
            "become", "leave",    "let",
               "talk",  
             "hear",    
            "believe", "hold", "bring", "happen",  "sit",
            "stand", "lose", "pay", "meet", "include", "continue",
               "lead", "understand",
            "watch",  "stop", "create", "speak", 
            "spend", "grow",  "walk", "teach", "offer",
            "remember", "love", "consider", "appear", "buy",
            "wait", "serve", "die", "send", "expect", "build",
            "stay", "fall", "cut", "reach", "kill", "remain",
            "suggest", "raise", "pass", "sell", "require",
            "report", "decide", "pull", "return", "explain",
            "hope", "develop", "carry", "break", "receive",
            "agree", "support", "hit", "produce", "cover",
            "catch", "draw", "choose", "eat", "drink", "sleep",
            "wake", "drive", "ride", "fly", "swim", "jump",
            "climb", "dance", "sing", "laugh", "cry", "smile",
            "shout", "whisper", "listen", "smell", "taste",
            "touch",  "clean", "wash", "fix", "check",
            "wear",  "save", "pick", "throw", "push",
            "lift", "drop", "grab", "share", "search", 
            "reply", "thank", "welcome", "invite", "visit",
            "travel", "arrive", "enter", "exit", "cross", "guide",
            "plan", "prefer", "wish", "wonder", "guess",
            "suppose", "realize", "notice", "forget", "remind",
            "promise", "argue", "fight", "win", "beat", "defend",
            "protect", "attack", "hide", "seek", "avoid",
            "escape", "chase", "steal", "borrow", "lend", "owe",
            "cost", "earn", "trade", "order", "deliver", "ship",
            "pack", "store", "load", "fill", "empty", 
                 
             "burn", "smoke",  
                "wave", 
            "ring", "flash",  "shine",  "rise",
               "blow",  
                 
               "rush", "hurry", 
            "race",     "explore",
            "discover", "invent", "design", "paint", "record",
            "film", "copy", "paste", "print", "scan", "file",
            "type", "text", "message", "email", "post", "publish",
            "sign", "seal", "stamp", "mark", "label", "tag",
            "greet", "introduce", "present", "represent",
            "describe", "define", "discuss", "mention",
            "comment", "note", "quote", "refer", "recommend",
            "advise", "warn", "inform", "announce", "declare",
            "state", "claim", "insist", "demand", "request",
            "beg", "pray",   "swear", 
             "consent", "approve", "allow", "permit",
            "forbid", "ban", "block", "prevent", "halt", "pause",
            "resume", "finish", "complete", "quit", "cancel",
            "delay", "postpone", "speed", "slow", "park",
            "crash", "bump", "explode", "collapse", "destroy",
            "ruin", "damage", "harm", "hurt", "injure", "heal",
            "cure", "treat", "examine", "test", "measure",
            "weigh", "count", "calculate",  "subtract",
            "multiply", "divide", "estimate", "solve", "prove",
            "confirm",    
                
                
               
               
             "train",  "practice", 
            "research",   "review",
             "verify",   
              "join",  
                "vote",
                
             "control",  
               
             "install",  
                
               
                
               "garden",  
                
                 
                 
                
                 
               
               
                
               
               "appreciate",
            "admire",   "obey", 
              "resist",  
               
                
                
                "told",
             "knew", "took",   
            "gave",   "felt", "became", "brought",
            "began", "kept", "held", "wrote", "stood", "heard",
             "meant",  "met", "ran", "paid", "sat",
            "spoke", "lay", "led", "understood", 
               "spent", "grew",
              "taught", 
               
            "bought",    "sent",
             "built",  "fell", 
               
             "sold",   
               
              "broke", 
               
            "caught", "drew", "chose", "ate", "drank", "slept",
            "woke", "drove", "rode", "flew", "swam", 
                
               
                
                "threw",
                
               
               
               
               
             "forgot",  
             "fought", "won",  "forgotten",
            "given", "taken", "seen", "known", 
             "begun",   "written",
                 "spoken",
             "grown",    
            "fallen",    "drawn",
            "chosen", "eaten", "driven", "flown", "sworn",
            // Common nouns.
                 
            "woman",  "child",   
            "family", "student",   "problem",
               "case", "week", "company",
            "system", "program", "question",  "government",
            "night",   "room",  "area",
            "money",  "fact", "month", "lot", 
            "book",  "job", "word", "business", "issue",
            "side",     
             "power", "hour", "game",  
            "member", "law", "car",  "community", 
            "team", "minute", "idea", "kid", "body",
            "information",  "parent", "face", "others",
            "level", "office", "door", "health", "person", "art",
            "war", "history", "party", "result", 
            "morning",   "girl", "guy",
            "moment",  "teacher", "force", "education",
            "foot",  "age", "policy", "process", "music",
            "human", "market", "sense", "nation", 
            "college", "interest", "death", "course", "experience",
            "effect", "class",  "care", "field",
            "development", "role", "effort", "rate", "heart",
            "drug", "leader", "voice", "wife", "police", "mind",
            "price", "decision", "son", "view", "relationship",
            "town", "road",  "arm", "difference",
            "value", "building", "action", "model", "season",
            "society", "tax", "director", "position", "player",
              "space", "ground", 
            "event", "official", "matter", "center", "couple",
            "site", "project", "activity", "star", "table",
            "court", "oil", "situation",  "industry",
            "figure", "street", "image", "phone", "data",
              "piece",  "product",
            "doctor", "wall", "patient", "worker", "news",
            "movie", "north", "technology", "step", "baby",
            "computer",  "attention",  
            "source", "organization", "cause", "hair", "century",
            "evidence", "window", "energy", "period", "summer",
            "opportunity", "term",  "condition",
            "choice",  "daughter", "south", "husband",
            "floor",  "material", "population",
            "economy", "hospital", "church",  
            "future", "security", "bank", "west", "sport",
            "board", "subject", "officer", "rest", "behavior",
            "deal", "performance", "goal", "bed", 
            "author", "focus", "blood", "agency", "nature",
            "color",   "movement", 
            "concern", "worth",   "chair",
             "kitchen", "bathroom", "bedroom", "garage",
            "yard",   "roof",  
            "stairs",    
            "key", "lock",    
                "toilet",
                
                
             "truck", "bus",  "plane", "bike",
             "boat",   
              "bridge",  "corner",
             "traffic",   
            "parking", "gas", "station",  "shop",
              "restaurant",  
            "hotel",     
                
                 
                
                 
               
                
               "university",
               "airport",
                
                  
              "chicken",   
                 
                
                
              "orange",  
            "bread", "cheese", "milk", "egg", "butter", "rice",
             "pizza",   
             "meat",    
                
                "coffee", "tea",
                 "breakfast",
            "lunch", "dinner",   
               "ice", 
            "shirt", "pants", "dress",  
             "hat",  "shoes",  
              "glasses",  
              "wallet",  "bag",
               
                
               "battery",
                
                "card",
                
               "song", 
                 
              "tool", 
                
               "box", 
            "cup", "glass", "plate", "bowl",  "knife",
                 
                
               "map", 
               "dollar", 
               "bill",  
            "sale", "discount",   
            // Adjectives, adverbs, time, numbers, colors, family.
             "bad",    "young",
            "hot", "cold", "warm", "cool", "fast", 
            "strong", "weak", "happy", "sad", "angry", "tired",
            "hungry", "thirsty", "sick", "busy", "free", "ready",
            "sure", "wrong", "true", "false", "real", "important",
               "best", "worst",
            "better", "worse", "less", "least",  
             "whole", "full",   "safe",
               "quiet", 
             "dark",    
                
                 
                
                
               
             "sorry",   
                
              "clear",  
              "dead",  
                
                
               
               
               
               "special", 
                
                
              "friendly",  
                
            "red", "blue", "green", "yellow", "black", "white",
             "purple", "pink", "brown", "gray", 
              "today", "tomorrow", "yesterday",
            "later",  "afternoon", "evening", 
            "tonight",    
            "date", "weekend", "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday", "sunday",
            "january", "february",  "april", "june",
            "july", "august", "september", "october",
            "november", "december", "spring",  "winter",
            "eleven", "twelve",  
               
             "twenty", "thirty", "forty", "fifty",
                
             "sister", "brother",  
             "children", "kids", "parents", "grandma",
            "grandpa",   
                
               
                
                
                
                
            "yes", "yeah", "yep", "nope", "okay", "ok", "hi",
            "hello", "hey", "bye", "goodbye", "please", "thanks",
               
               
               
             "rain", "snow",   
            "sun", "moon",  "sky",  
                 
                 
                
                
            // Contractions.
            "don't", "can't", "won't", "isn't", "aren't",
            "wasn't", "weren't", "haven't", "hasn't", "hadn't",
            "doesn't", "didn't", "couldn't", "shouldn't",
            "wouldn't", "mustn't", "let's", "that's", "there's",
            "here's", "what's", "where's", "when's", "why's",
            "how's", "who's", "she's", "he's", "we're",
            "they're", "we've", "they've", "you've", "you'll",
            "we'll", "they'll", "he'll", "she'll", "it'll",
            "that'll", "there'll", "you'd", "we'd", "they'd",
            "he'd", "she'd", "it'd", "that'd", "I'm", "I'll",
            "I'd", "I've", "you're", "it's",  "gonna",
            "wanna", "gotta",   
            // The app's own vocabulary.
            "channel", "channels", "mesh", "relay", "walkie",
            "talkie", "listening",  "bridged",
            "bridging", "gateway", "contact", "contacts",
             "invites",   "filter",
            "frequency", "tune", "tuned",  "signal",
            "broadcast", "volume", "speaker", "microphone",
             "antenna", "transmit", "receiver",
            "sender", "audio", "phones", "android", "update",
            "updated", "version", "profile", "keys",
            "encryption", "encrypted", "password", "settings",
            "notification", "notifications", "subscription",
            "subscribed", "subscribe",  "favorite",
            "favorites", "blocked", "blocklist", "browser",
            "bookmark", "bookmarks", "download", "upload",
             "installed", "hotspot", "bluetooth",
            "wifi", "network", "internet", "offline", "online",
            "connect", "connected", "connection", "disconnect",
            "disconnected", "server", "device", "devices",
            "button", "menu",  "swipe", "theme", "backup",
            "restore", "import", "export",  "sharing",
            "host", "hosting",  "joined", 
            "members", "thread", "conversation", "forward",
            "delete", "deleted", "remove",  "info",
            "status", "error", "failed", "success", "warning",
            "alert", "emergency", "location", "gps", 
            "directions",   "arrival",
            "launcher", "app", "apps",  
              
               
            "account",    "code",
            "codes",    
              "range",  
                "roger",
                
              "base", "mobile", 
    };

    /** The built-in v1 table: WORDS through the same
     *  DictFile build the downloaded file takes. */
    private static final WordTable BUILTIN = WordTable.fromWords(WORDS);

    /** The active table: the built-in one until DictStore
     *  swaps in the full table built from the repo file.
     *  Volatile + fully-built swap: every encode/decode
     *  captures one table reference and uses it throughout,
     *  so a swap mid-message can never mix tables. */
    private static volatile WordTable ACTIVE = BUILTIN;

    /** The active table (the built-in one until the
     *  background build lands). */
    static WordTable activeTable() {
        return ACTIVE;
    }

    /** Swaps the active table (DictStore, after a background
     *  build). Null resets to the built-in table. */
    public static void setActiveTable(WordTable table) {
        ACTIVE = table == null ? BUILTIN : table;
    }

    /** The built-in v1 table, for the harness and for the
     *  fallback path. */
    static WordTable builtinTable() {
        return BUILTIN;
    }

    /** Number of dictionary entries. */
    public static int size() {
        return WORDS.length;
    }

    /** The stored form of one entry, or "" out of range. */
    static String wordAt(int index) {
        return index >= 0 && index < WORDS.length ? WORDS[index] : "";
    }

    /** The wire code for a dictionary index: two bytes below
     *  LONG_BASE, four bytes (symbol + lead + two base-62
     *  digits) at and above it. "" when out of range. */
    static String codeFor(int index) {
        if (index < 0 || index > MAX_INDEX) return "";
        if (index < LONG_BASE) {
            int sym = index / 20;
            int rem = index % 20;
            return "" + SYMBOLS.charAt(sym) + DIGIT_CHARS.charAt(rem);
        }
        int v = index - LONG_BASE;
        int perSym = 42 * 62 * 62;
        int sym = v / perSym;
        int r = v % perSym;
        int lead = r / (62 * 62);
        int d = r % (62 * 62);
        return "" + SYMBOLS.charAt(sym) + LEAD_CHARS.charAt(lead)
                + SYMBOLS.charAt(d / 62) + SYMBOLS.charAt(d % 62);
    }

    /** Parses one code at position {@code at} in {@code s}:
     *  returns {index, length} (length 2 or 4), or null when
     *  no valid code starts there. The second byte decides
     *  the form: a base-20 digit means two bytes, a lead
     *  byte means four. Never throws. */
    static int[] parseCode(String s, int at) {
        if (s == null || at < 0 || at >= s.length()) return null;
        int sym = SYMBOLS.indexOf(s.charAt(at));
        if (sym < 0) return null;
        if (at + 1 >= s.length()) return null;
        char second = s.charAt(at + 1);
        int dig = DIGIT_CHARS.indexOf(second);
        if (dig >= 0) {
            return new int[] { sym * 20 + dig, 2 };
        }
        int lead = LEAD_CHARS.indexOf(second);
        if (lead < 0) return null;
        if (at + 3 >= s.length()) return null;
        int d1 = SYMBOLS.indexOf(s.charAt(at + 2));
        int d2 = SYMBOLS.indexOf(s.charAt(at + 3));
        if (d1 < 0 || d2 < 0) return null;
        int v = (sym * 42 + lead) * 62 * 62 + d1 * 62 + d2;
        return new int[] { LONG_BASE + v, 4 };
    }

    /** Parses a standalone code string (a teach entry's
     *  code): the index, or -1 when the whole string is not
     *  exactly one code. */
    static int parseCodeString(String code) {
        if (code == null) return -1;
        int[] p = parseCode(code, 0);
        if (p == null || p[1] != code.length()) return -1;
        return p[0];
    }

    /** The human-readable form of a code: the symbol plus a
     *  real superscript or subscript digit. Never sent; the
     *  wire always carries the two-byte form. */
    public static String displayForm(int index) {
        if (index < 0 || index > MAX_INDEX) return "?";
        if (index >= LONG_BASE) return codeFor(index);
        int rem = index % 20;
        int digit = rem / 2;
        boolean sub = (rem % 2) == 1;
        return "" + SYMBOLS.charAt(index / 20)
                + (sub ? SUB_DIGITS.charAt(digit)
                       : SUPER_DIGITS.charAt(digit));
    }

    /**
     * The human-readable form of a whole wire payload: each
     * code rendered with displayForm(), escapes shown as
     * their raw run, literals verbatim — the coded message
     * as Adam designed it, for the long-press view. Returns
     * null when the payload does not parse entirely as
     * valid coded form (the caller then shows the raw wire
     * instead of a half-rendered guess). Never throws.
     */
    public static String wireDisplayForm(String wire) {
        if (wire == null) return null;
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = wire.length();
        try {
            while (i < n) {
                char c = wire.charAt(i);
                if (c == ESCAPE) {
                    if (i + 3 > n) return null;
                    int len = parseEscapeLength(wire.charAt(i + 1),
                            wire.charAt(i + 2));
                    if (len < 1 || i + 3 + len > n) return null;
                    out.append(wire, i + 3, i + 3 + len);
                    i += 3 + len;
                } else if (isSymbol(c)) {
                    int[] parsed = parseCode(wire, i);
                    if (parsed == null) return null;
                    out.append(displayForm(parsed[0]));
                    i += parsed[1];
                } else if (isLiteral(c)) {
                    out.append(c);
                    i++;
                } else {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return out.toString();
    }

    /** A decoded message: the text to show, plus an honest
     *  note when the payload could not be decoded (the text
     *  is then the raw payload, shown as sent). */
    public static final class Result {
        public final String text;
        /** Null when the message decoded cleanly. */
        public final String note;
        /** Learned indices adopted from a teach block on
         *  this message (empty when none). The receiver
         *  marks the sender's scope taught for these — the
         *  sender knows them by construction. */
        public final Set<Integer> adopted;

        Result(String text, String note) {
            this(text, note, new HashSet<Integer>());
        }

        Result(String text, String note, Set<Integer> adopted) {
            this.text = text;
            this.note = note;
            this.adopted = adopted == null
                    ? new HashSet<Integer>() : adopted;
        }
    }

    /** A scope-aware encode: the wire form (always
     *  non-null — v1.32 codes every message, short ones
     *  included), the wire generation (1 when every code
     *  is a v1-head code, 2 otherwise — metadata for the
     *  caller, no longer sent), the teach block for words
     *  this scope has not been taught yet ("" when none),
     *  and the indices the scope is now taught (to
     *  persist). */
    public static final class Encoded {
        public final String wire;
        public final int version;
        public final String teach;
        public final Set<Integer> newlyTaught;

        Encoded(String wire, int version, String teach,
                Set<Integer> newlyTaught) {
            this.wire = wire;
            this.version = version;
            this.teach = teach;
            this.newlyTaught = newlyTaught;
        }
    }

    /**
     * Encodes a message. v1.32: every message is coded —
     * the wire form is always returned, short messages
     * included (a one-word message costs its code, nothing
     * more, since no marker rides anymore). Null/empty
     * input returns null. No scope, so no teaching:
     * learned words travel raw. Scope-aware sends use
     * encodeScoped().
     */
    public static String encode(String text) {
        Encoded e = encodeScoped(text, new HashSet<Integer>(), false);
        return e == null ? null : e.wire;
    }

    /**
     * Encodes a message for a scope (a peer or channel).
     * Returns null only for null/empty input. Otherwise
     * the result always carries the wire form AND the
     * teach block (possibly empty): untaught learned
     * words travel raw in this message's wire while the
     * teach block carries their assignment, so the scope
     * codes them from the next message on. Teaching is a
     * one-time cost per word per scope, bounded by
     * TEACH_PER_MESSAGE. The {@code taught} set is never
     * modified; the new teachings come back in the result
     * for the caller to persist.
     */
    public static Encoded encodeScoped(String text, Set<Integer> taught) {
        return encodeScoped(text, taught, true);
    }

    private static Encoded encodeScoped(String text, Set<Integer> taught,
                                        boolean collectTeach) {
        if (text == null || text.isEmpty()) return null;
        WordTable table = ACTIVE;
        Set<Integer> known = taught == null
                ? new HashSet<Integer>() : taught;
        List<TeachBlock.Entry> teach = new ArrayList<>();
        WireBuild wb = buildWireScoped(text, table, known, teach, collectTeach);
        String teachBlock = collectTeach && !teach.isEmpty()
                ? TeachBlock.build(teach) : "";
        // The version describes the WIRE's generation, not
        // a marker (v1.32 sends none): a head-only wire
        // stays generation 1 so legacy decoders reading a
        // stored "wd" value keep their meaning. Only a code
        // past the frozen head needs 2.
        int version = wb.maxIndex >= WORDS.length ? 2 : 1;
        Set<Integer> newly = new HashSet<>();
        if (!teachBlock.isEmpty()) {
            for (TeachBlock.Entry e : teach) newly.add(e.index);
        }
        return new Encoded(wb.wire, version, teachBlock, newly);
    }

    /** The wire form plus the highest code index used.
     *  Package-visible so the harness can assert exactly
     *  which code the encoder chose. */
    static final class WireBuild {
        final String wire;
        final int maxIndex;

        WireBuild(String wire, int maxIndex) {
            this.wire = wire;
            this.maxIndex = maxIndex;
        }
    }

    /** The wire form with NO dictionary lookup: the whole
     *  text as escape runs. The senders use it only when
     *  encoding itself fails, so a message still arrives
     *  readable in valid coded form instead of vanishing.
     *  Never throws for non-null input. */
    public static String escapeOnly(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int len = Math.min(MAX_ESCAPE_RUN, text.length() - i);
            sb.append(ESCAPE);
            sb.append((char) ('0' + len / 10));
            sb.append((char) ('0' + len % 10));
            sb.append(text, i, i + len);
            i += len;
        }
        return sb.toString();
    }

    /** Builds the wire form unconditionally. */
    static String buildWire(String text) {
        return buildWireScoped(text, ACTIVE, new HashSet<Integer>(),
                new ArrayList<TeachBlock.Entry>(), false).wire;
    }

    /** Builds the wire form against one table and taught set,
     *  collecting teach entries for learned words the scope
     *  has not been taught yet (when {@code collectTeach}).
     *  Untaught learned words travel raw; the teach block
     *  carries their assignment for next time. */
    static WireBuild buildWireScoped(String text, WordTable table,
                                     Set<Integer> taught,
                                     List<TeachBlock.Entry> teachOut,
                                     boolean collectTeach) {
        StringBuilder out = new StringBuilder();
        SentenceState st = new SentenceState();
        int maxIndex = -1;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isWordChar(c)) {
                int j = i + 1;
                while (j < n && isWordChar(text.charAt(j))) j++;
                String word = text.substring(i, j);
                if (hasLetter(word)) {
                    Integer idx = encodable(word, st, table, taught,
                            teachOut, collectTeach);
                    if (idx != null) {
                        out.append(codeFor(idx));
                        if (idx > maxIndex) maxIndex = idx;
                    } else {
                        appendEscaped(out, word);
                    }
                } else {
                    // Apostrophes only: not a word, escape it.
                    appendEscaped(out, word);
                }
                st.advance(word);
                i = j;
            } else if (isLiteral(c)) {
                int j = i + 1;
                while (j < n && isLiteral(text.charAt(j))) j++;
                String run = text.substring(i, j);
                out.append(run);
                st.advance(run);
                i = j;
            } else {
                int j = i + 1;
                while (j < n && !isWordChar(text.charAt(j))
                        && !isLiteral(text.charAt(j))) j++;
                appendEscaped(out, text.substring(i, j));
                st.advance(text.substring(i, j));
                i = j;
            }
        }
        return new WireBuild(out.toString(), maxIndex);
    }

    /** The dictionary index for a word, or null when the word
     *  must travel raw (unknown, a capital form the rule does
     *  not cover, or a learned word the scope has not been
     *  taught yet — in the last case a teach entry is
     *  collected when {@code collectTeach}). Graduation: the
     *  base code wins when both sections hold the word. */
    private static Integer encodable(String word, SentenceState st,
                                     WordTable table, Set<Integer> taught,
                                     List<TeachBlock.Entry> teachOut,
                                     boolean collectTeach) {
        String lower = word.toLowerCase(Locale.ROOT);
        Integer base = table.baseIndex(lower);
        if (base != null) {
            String stored = table.baseWordAt(base);
            if (stored != null && formMatches(word, lower, stored, st)) return base;
            return null;
        }
        Integer learned = table.learnedIndex(lower);
        if (learned == null) return null;
        String stored = table.wordAt(learned);
        if (stored == null || !formMatches(word, lower, stored, st)) return null;
        if (taught.contains(learned)) return learned;
        if (collectTeach && teachOut != null && teachOut.size() < TEACH_PER_MESSAGE) {
            boolean already = false;
            for (TeachBlock.Entry e : teachOut) {
                if (e.index == learned) { already = true; break; }
            }
            if (!already) teachOut.add(new TeachBlock.Entry(stored, learned));
        }
        return null;
    }

    /** The capitalization rule: the stored form, the
     *  lowercase form, or a Capitalized word at a sentence
     *  start. */
    private static boolean formMatches(String word, String lower,
                                       String stored, SentenceState st) {
        if (word.equals(stored)) return true;
        if (word.equals(lower)) return true;
        return st.sentenceStart() && isCapitalized(word);
    }

    /** First letter upper, the rest lower (a lone capital
     *  letter counts). */
    static boolean isCapitalized(String word) {
        if (word.isEmpty()) return false;
        if (!Character.isUpperCase(word.charAt(0))) return false;
        for (int i = 1; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c) && !Character.isLowerCase(c)) return false;
        }
        return true;
    }

    static boolean isAllLower(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c) && !Character.isLowerCase(c)) return false;
        }
        return true;
    }

    /** Word characters: letters plus the ASCII apostrophe
     *  (contractions). A curly apostrophe is not a word
     *  character, so curly-apostrophe words travel raw and
     *  reproduce exactly. */
    static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'';
    }

    static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    /** Characters that pass through verbatim: whitespace and
     *  the punctuation set. Deliberately excludes every
     *  alphanumeric (those belong to codes) and the escape
     *  character (a '~' in a message travels inside an
     *  escape, so it can never be mistaken for one). */
    static boolean isLiteral(char c) {
        if (Character.isWhitespace(c)) return true;
        switch (c) {
            case '.': case ',': case '!': case '?': case ';':
            case ':': case '-': case '(': case ')': case '[':
            case ']': case '{': case '}': case '"': case '/':
            case '\\': case '|': case '@': case '#': case '$':
            case '%': case '^': case '&': case '*': case '+':
            case '=': case '<': case '>': case '`': case '_':
            case '\u2013': case '\u2014': // en/em dash
            case '\u201c': case '\u201d': // curly double quotes
            case '\u2018': case '\u2019': // curly single quotes
            case '\u00ab': case '\u00bb': // guillemets
                return true;
            default:
                return false;
        }
    }

    /** Appends a raw run as one or more escapes, chunked at
     *  99 chars. Length counts UTF-16 chars, the same units
     *  both Java endpoints decode with. */
    static void appendEscaped(StringBuilder out, String raw) {
        int i = 0;
        while (i < raw.length()) {
            int take = Math.min(MAX_ESCAPE_RUN, raw.length() - i);
            out.append(ESCAPE);
            if (take < 10) out.append('0');
            out.append(take);
            out.append(raw, i, i + take);
            i += take;
        }
    }

    /**
     * The receive-side seam every road shares: version 0
     * (no marker) means plain text and passes through
     * untouched; any other version decodes. Never throws.
     */
    public static Result decodeMarked(String raw, int version) {
        return decodeMarked(raw, version, null);
    }

    /**
     * The receive-side seam with a teach block: the block is
     * applied (adopted into the active table) BEFORE the
     * payload decodes, so codes taught by this very message
     * resolve. The adopted indices come back on the result
     * for the caller to record against the sender's scope.
     * Never throws.
     */
    public static Result decodeMarked(String raw, int version, String teachBlock) {
        // Teaching rides even on plain text (version 0):
        // apply it before the version check so a first-use
        // message teaches its words.
        Set<Integer> adopted = applyTeach(teachBlock);
        if (version == 0) return new Result(raw == null ? "" : raw, null, adopted);
        Result r = decode(raw, version);
        if (adopted.isEmpty()) return r;
        return new Result(r.text, r.note, adopted);
    }

    /** Applies a teach block to the active table, returning
     *  the indices this phone now resolves to the taught
     *  words. Oversized blocks are ignored whole. Never
     *  throws. */
    static Set<Integer> applyTeach(String teachBlock) {
        Set<Integer> adopted = new HashSet<>();
        if (teachBlock == null || teachBlock.isEmpty()) return adopted;
        if (teachBlock.getBytes(StandardCharsets.UTF_8).length > MAX_TEACH_BLOCK_BYTES) {
            return adopted;
        }
        WordTable table = ACTIVE;
        try {
            for (TeachBlock.Entry e : TeachBlock.parse(teachBlock)) {
                table.adopt(e.word, e.index);
                String w = table.wordAt(e.index);
                if (e.word.equals(w)) adopted.add(e.index);
            }
        } catch (Exception ignored) {
        }
        return adopted;
    }

    /**
     * The receive-side seam for v1.32 coded payloads: the
     * shape already says "coded", so the payload MUST parse
     * entirely as valid coded form — codes, escapes, and
     * literal punctuation/whitespace, nothing else. Anything
     * else renders undecoded with the honest note, never a
     * silent mis-decode. A teach block is applied BEFORE
     * the payload decodes, so codes taught by this very
     * message resolve; the adopted indices come back on
     * the result for the caller to record against the
     * sender's scope. Never throws.
     */
    public static Result decodeCoded(String wire, String teachBlock) {
        Set<Integer> adopted = applyTeach(teachBlock);
        Result r = decodeImpl(wire, VERSION, true);
        if (adopted.isEmpty()) return r;
        return new Result(r.text, r.note, adopted);
    }

    /**
     * Decodes a wire payload sent with dictionary version
     * {@code version}. Version 0 must never reach here (no
     * marker means plain text; the caller skips decode —
     * see decodeMarked).
     * Never throws: anything unparseable, or any version
     * newer than this build, renders undecoded with an
     * honest note.
     */
    public static Result decode(String wire, int version) {
        return decodeImpl(wire, version, false);
    }

    /**
     * The shared decode loop. Strict mode (v1.32 coded
     * payloads) additionally rejects any character that is
     * neither a code, an escape, nor a literal — a coded
     * payload containing a stray emoji, control byte, or
     * other non-literal character outside an escape is
     * corrupt, not decodable. Lenient mode (legacy
     * "wd"-marked payloads) passes such characters through
     * exactly as v1.28 did.
     */
    private static Result decodeImpl(String wire, int version, boolean strict) {
        if (wire == null) return new Result("", NOTE_CORRUPT);
        if (version > VERSION) {
            return new Result(wire, "Sent with a newer word list (v"
                    + version + ") — shown as sent.");
        }
        if (version < 1) return new Result(wire, NOTE_CORRUPT);
        WordTable table = ACTIVE;
        StringBuilder out = new StringBuilder();
        SentenceState st = new SentenceState();
        int i = 0;
        int n = wire.length();
        try {
            while (i < n) {
                char c = wire.charAt(i);
                if (c == ESCAPE) {
                    if (i + 3 > n) return corrupt(wire);
                    int len = parseEscapeLength(wire.charAt(i + 1),
                            wire.charAt(i + 2));
                    if (len < 1 || i + 3 + len > n) return corrupt(wire);
                    String raw = wire.substring(i + 3, i + 3 + len);
                    out.append(raw);
                    st.advance(raw);
                    i += 3 + len;
                } else if (isSymbol(c)) {
                    int[] parsed = parseCode(wire, i);
                    if (parsed == null) return corrupt(wire);
                    int idx = parsed[0];
                    int len = parsed[1];
                    if (version == 1) {
                        // v1 payloads carry short codes for the
                        // frozen head only; anything else is
                        // not a v1 payload.
                        if (len != 2 || idx >= WORDS.length) return corrupt(wire);
                    }
                    String word = resolve(table, idx);
                    if (word == null) {
                        if (idx >= table.baseCount()
                                && idx < WordTable.LEARNED_BASE) {
                            return new Result(wire, NOTE_NEWER);
                        }
                        if (idx >= WordTable.LEARNED_BASE) {
                            return new Result(wire, NOTE_UNTAUGHT);
                        }
                        return corrupt(wire);
                    }
                    if (st.sentenceStart() && isAllLower(word)) {
                        word = capitalize(word);
                    }
                    out.append(word);
                    st.advance(word);
                    i += len;
                } else {
                    if (strict && !isLiteral(c)) return corrupt(wire);
                    out.append(c);
                    st.advance(c);
                    i++;
                }
            }
        } catch (Exception e) {
            return corrupt(wire);
        }
        return new Result(out.toString(), null);
    }

    /** Resolves a code index against one table: base, learned,
     *  or alias — null when the index means nothing here. */
    private static String resolve(WordTable table, int idx) {
        if (idx < 0) return null;
        if (idx < table.baseCount()) return table.baseWordAt(idx);
        return table.wordAt(idx);
    }

    private static Result corrupt(String wire) {
        return new Result(wire, NOTE_CORRUPT);
    }

    static int parseEscapeLength(char tens, char ones) {
        if (tens < '0' || tens > '9' || ones < '0' || ones > '9') return -1;
        return (tens - '0') * 10 + (ones - '0');
    }

    static boolean isSymbol(char c) {
        return SYMBOLS.indexOf(c) >= 0;
    }

    static String capitalize(String word) {
        if (word.isEmpty()) return word;
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** Sentence-start tracking, shared by encoder and
     *  decoder so both make the same capitalization
     *  decision at every word: the state is computed over
     *  the ORIGINAL text on encode and the RECONSTRUCTED
     *  text on decode, which are identical by
     *  construction. Sentence start = no letter seen yet,
     *  or the last non-whitespace char is sentence-ending
     *  punctuation with whitespace since. */
    static final class SentenceState {
        private boolean seenLetter;
        private char lastNonWs;
        private boolean wsSinceNonWs;

        boolean sentenceStart() {
            if (!seenLetter) return true;
            if (!wsSinceNonWs) return false;
            return lastNonWs == '.' || lastNonWs == '!' || lastNonWs == '?';
        }

        void advance(String s) {
            for (int i = 0; i < s.length(); i++) advance(s.charAt(i));
        }

        void advance(char c) {
            if (Character.isWhitespace(c)) {
                wsSinceNonWs = true;
                return;
            }
            if (Character.isLetter(c)) seenLetter = true;
            lastNonWs = c;
            wsSinceNonWs = false;
        }
    }
}
