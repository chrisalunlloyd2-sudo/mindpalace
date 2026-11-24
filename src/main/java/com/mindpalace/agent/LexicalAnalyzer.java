package com.mindpalace.agent;

import java.util.*;

/**
 * LexicalAnalyzer — deterministic term-frequency vectors over chat-log text.
 *
 * "Lexical" (not neural): tokenize → lowercase → strip stopwords → count term
 * frequencies → normalize to a unit vector. Cosine similarity between two
 * vectors measures topical overlap. This is the cheap, deterministic bridge
 * between the chat logs and the quorum voting schema — no embedding model, no
 * Ollama call, runs in microseconds.
 *
 * The user's spec: "extract lexical vectors to quorum voting" — i.e. turn the
 * words the agents actually said into a vector space, then let the quorum vote
 * on the dominant topics those vectors surface.
 */
public final class LexicalAnalyzer {

    private LexicalAnalyzer() {}

    /** English stopwords — the glue words that carry no topical signal. */
    private static final Set<String> STOPWORDS = Set.of(
        "the","a","an","and","or","but","if","then","else","for","while",
        "to","of","in","on","at","by","with","from","as","is","are","was","were",
        "be","been","being","have","has","had","does","did","will","would",
        "shall","should","can","could","may","might","must","it","its","this",
        "that","these","those","i","you","he","she","we","they","them","his",
        "her","their","our","your","my","me","us","not","no","yes","so","very",
        "too","just","about","into","over","under","again","once","here","there",
        "what","which","who","whom","when","where","why","how","all","any","both",
        "each","few","more","most","other","some","such","only","own","same",
        "than","also","because","until","during","before","after",
        "above","below","between","through","against","out","up","down","off",
        "am","im","youre","hes","shes","theyre","ive","youve",
        "dont","cant","wont","couldnt","shouldnt","wouldnt","isnt","arent",
        "wasnt","werent","hasnt","havent","hadnt","doesnt","didnt"
    );

    /** A normalized term-frequency vector: term → weight (0..1). */
    public static final class Vector {
        public final Map<String, Float> terms;   // term → normalized weight
        public final int rawTokenCount;

        Vector(Map<String, Float> terms, int rawTokenCount) {
            this.terms = terms;
            this.rawTokenCount = rawTokenCount;
        }

        public int size() { return terms.size(); }
        public boolean isEmpty() { return terms.isEmpty(); }

        /** Top-k terms by weight, descending. */
        public List<Map.Entry<String, Float>> top(int k) {
            List<Map.Entry<String, Float>> e = new ArrayList<>(terms.entrySet());
            e.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
            return e.subList(0, Math.min(k, e.size()));
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder("Vector[");
            for (Map.Entry<String, Float> e : top(5)) {
                sb.append(e.getKey()).append(':').append(String.format("%.2f", e.getValue())).append(' ');
            }
            return sb.append(']').toString();
        }
    }

    /** Tokenize a string into lowercase, stopword-stripped terms. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        // Split on non-alphanumeric (keep apostrophes inside words)
        for (String raw : text.toLowerCase().split("[^a-z0-9']+")) {
            String t = raw.replaceAll("'", "").trim();
            if (t.isEmpty() || t.length() < 2) continue;      // drop 1-char noise
            if (STOPWORDS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    /** Build a normalized term-frequency vector from text. */
    public static Vector vectorize(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new Vector(Map.of(), 0);

        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokens) counts.merge(t, 1, Integer::sum);

        // Normalize: weight = count / maxCount (relative, not absolute — so a
        // short message and a long one with the same topic align).
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        Map<String, Float> norm = new HashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            norm.put(e.getKey(), e.getValue() / (float) max);
        }
        return new Vector(norm, tokens.size());
    }

    /** Cosine similarity between two lexical vectors (0..1). */
    public static float cosine(Vector a, Vector b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        float dot = 0f;
        // Iterate the smaller map for speed
        Map<String, Float> small = a.terms.size() <= b.terms.size() ? a.terms : b.terms;
        Map<String, Float> large = small == a.terms ? b.terms : a.terms;
        for (Map.Entry<String, Float> e : small.entrySet()) {
            Float w = large.get(e.getKey());
            if (w != null) dot += e.getValue() * w;
        }
        float na = norm(a), nb = norm(b);
        if (na == 0f || nb == 0f) return 0f;
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static float norm(Vector v) {
        float s = 0f;
        for (float w : v.terms.values()) s += w * w;
        return s;
    }

    /**
     * Extract the dominant topic from a batch of messages: merge all tokens,
     * return the top-k terms as a single "topic signature" string. This is the
     * input to a quorum proposal — the words the agents are actually circling.
     */
    public static String dominantTopic(List<String> messages, int k) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder all = new StringBuilder();
        for (String m : messages) all.append(m).append(' ');
        Vector v = vectorize(all.toString());
        if (v.isEmpty()) return "";
        StringBuilder topic = new StringBuilder();
        for (Map.Entry<String, Float> e : v.top(k)) {
            topic.append(e.getKey()).append(' ');
        }
        return topic.toString().trim();
    }

    // ── P1-B: lexical topic gate — reject keyboard-mash "topics" ────────────
    // game_console.log 2026-11-17: the quorum APPROVED topics like
    // "q67 u36my7 rtmjuyujnb" and "hy ee ug usyuvnyhmjtc sh" — chat noise that
    // made it through vectorize() into proposals filed as GitHub issues. The
    // gate is deterministic, offline and LLM-free: a topic must look like the
    // language agents actually use (dictionary words or code identifiers).

    /** Bundled function/word list — the small everyday dictionary plus code
     *  identifiers agents mention. Grep-sized on purpose: no network, no LLM. */
    private static final Set<String> KNOWN_WORDS = Set.of(
        // English core (non-stopword content words)
        "agent","agents","app","api","apples","arg","args","ascii","audio","auth",
        "backup","bag","ball","band","bank","bar","base","bat","bath","beam","bean",
        "bear","beat","bed","bee","been","before","begin","bell","below","bench",
        "big","bill","bit","black","blade","block","blocks","blue","board","boat",
        "body","book","books","boot","border","boss","both","box","boy","brain",
        "branch","break","bridge","bright","bring","broad","brown","brush","build",
        "built","burn","burst","bus","business","busy","but","butter","button","buy",
        "c","cache","cake","call","camera","camp","can","cap","car","card","care",
        "cargo","case","cash","cast","cat","catch","cause","cell","center","cent",
        "chain","chair","chalk","chance","change","char","chat","check","cheese",
        "chest","chicken","chief","child","chip","chocolate","choice","choose",
        "circle","city","claim","class","clean","clear","clever","climb","clock",
        "close","cloud","club","clue","coal","code","codes","coffee","coin","cold",
        "collar","color","column","come","comet","commit","common","compare",
        "complete","computer","concept","concern","cone","connect","consider",
        "constitution","contact","container","content","contrast","control",
        "convert","cook","cookie","cool","copy","corn","corner","correct","cost",
        "could","count","counter","country","couple","course","cover","crack",
        "crash","crate","cream","create","crew","crisis","critic","crop","cross",
        "crowd","crown","crystal","cube","cup","current","curve","custom","cut",
        "cycle","d","dance","dark","data","database","date","daughter","day","dead",
        "deal","dear","death","debt","decade","decide","deck","deep","deer","def",
        "degree","demand","dentist","depot","desc","desk","detail","develop","dev",
        "diamond","dict","dictionary","die","diet","difference","dig","digital",
        "dinner","direct","dirt","disk","display","distance","divide","diver","do",
        "doctor","document","dog","doll","dollar","door","dot","double","down",
        "dozen","draft","dragon","draw","dream","dress","drill","drink","drive",
        "driver","drop","dry","duck","dude","dust","duty","e","each","eagle","ear",
        "early","earth","ease","east","easy","eat","edge","edition","egg","eight",
        "elbow","elder","electric","element","elephant","eleven","else","email",
        "empty","end","enemy","energy","engine","english","ensure","enter","entry",
        "env","equal","equip","error","errors","escape","essay","estate","estimate",
        "even","evening","event","exact","example","exec","exercise","exit","expect",
        "expensive","experience","export","express","extend","extra","eye","f",
        "face","fact","factory","fail","fair","fall","false","family","famous",
        "fan","farm","fat","father","fault","favor","feature","feed","female","fence",
        "fern","few","fiber","field","figure","file","files","film","final","find",
        "fine","finger","finish","fire","fish","fit","five","fix","flag","flake",
        "flame","flash","flat","float","floor","flower","fly","focus","fold","font",
        "food","foot","for","force","forest","fork","form","format","fort","forty",
        "forward","fossil","found","four","fourth","frame","frameless","free",
        "fresh","friend","fridge","frog","from","front","frost","fruit","fuel","full",
        "fun","function","functions","funny","fur","furniture","future","g","gain",
        "game","games","garden","gas","gate","gather","gave","gear","gem","general",
        "generator","gentle","get","girl","give","glass","glove","goal","goat","gold",
        "golf","gone","good","governor","grab","grace","grade","grain","grand",
        "grant","graph","grass","grave","gray","grease","great","green","greet",
        "grey","grid","grill","grocery","ground","group","grow","guard","guess",
        "guest","guide","gun","gym","h","habit","hair","half","hall","ham","hammer",
        "hand","handle","hang","happy","harbor","hard","hat","hate","have","hawk",
        "head","health","hear","heart","heat","heavy","hedge","height","hello","help",
        "hen","here","hero","hex","hidden","high","hill","hint","hire","hit","hive",
        "hold","hole","holiday","hollow","home","honest","honey","hood","hook",
        "hope","horn","horse","hospital","host","hot","hotel","hour","house","how",
        "hub","huge","human","humor","hundred","hungry","hunt","hurry","husband",
        "i","ice","idea","ideal","if","ignore","image","images","impact","import",
        "impossible","impress","improve","in","inch","include","income","index",
        "indoor","info","input","inside","install","instance","int","integer",
        "interest","internet","intro","invalid","investigate","invite","iron",
        "island","issue","items","iterate","j","jam","jar","jaw","jazz","jeans",
        "jelly","jewel","job","jobs","join","joke","journey","joy","judge","juice",
        "jump","junior","just","k","kayak","keep","kernel","kettle","key","keys",
        "kick","kid","kill","kind","king","kiss","kit","kitchen","kite","knee",
        "knife","knight","knock","know","known","l","lab","label","labor","lace",
        "lack","ladder","lake","lamp","land","lane","language","large","last","late",
        "laugh","launch","laundry","law","lawn","lawyer","layer","lazy","lead",
        "leader","leaf","learn","least","leather","leave","led","left","leg","legal",
        "lemon","lend","length","lens","less","lesson","let","letter","level",
        "library","license","lid","life","lift","light","like","lime","line","lint",
        "list","listen","liter","little","live","load","loan","lobster","local",
        "lock","log","logic","long","look","loop","loss","lost","lot","loud","love",
        "low","lower","loyal","luck","lunch","lung","m","machine","mad","made","magic",
        "magnet","mail","main","major","make","male","mall","man","manage","map",
        "margin","mark","market","marry","mars","mask","mass","master","match",
        "math","matter","mature","max","maximum","may","maze","meal","mean","meat",
        "medal","media","medical","meet","melon","melt","member","membrane","memo",
        "memory","mend","mental","menu","merchant","message","metal","method","mid",
        "middle","midnight","mild","mile","milk","mill","million","mind","mine",
        "mineral","minimap","minor","minstrel","minute","mirror","miss","mission",
        "mist","mitchell","mix","mixture","mobile","model","models","modern","mode",
        "modify","mold","moment","money","monitor","monkey","month","mood","moon",
        "moral","more","morning","mosquito","most","mother","motor","mount",
        "mountain","mouse","mouth","move","movie","much","mud","muffin","mug",
        "multiply","museum","music","must","n","nail","name","narrow","nation",
        "native","nature","near","neat","neck","need","needle","negative","neighbor",
        "neither","nerve","nest","net","network","never","new","news","next","nice",
        "night","nine","no","noise","none","normal","north","nose","not","note",
        "nothing","notice","noun","now","nowhere","null","number","nurse","nut",
        "o","oak","object","observe","obtain","obvious","occur","ocean","octagon",
        "october","odd","odds","of","off","offer","office","officer","often","oil",
        "okay","old","olive","olympic","one","onion","online","only","open","opera",
        "opinion","opposite","opt","orange","orbit","orchard","order","organ",
        "origin","ornament","other","ought","ounce","our","out","outcome","outside",
        "oven","over","owl","own","owner","oxford","oxygen","oyster","p","pace",
        "pack","package","pad","page","paint","pair","palace","pale","palm","pan",
        "panel","panic","paper","paragraph","parcel","pardon","parent","park",
        "parrot","part","partner","party","pass","passenger","past","patch","path",
        "patient","patio","pattern","pause","pave","paw","pay","peace","peak",
        "pen","pencil","penny","people","pepper","percent","perfect","perform",
        "perfume","period","permit","person","pet","phase","phone","photo","phrase",
        "physical","piano","pick","picture","pie","piece","pier","pig","pigeon",
        "pile","pilot","pin","pine","pink","pipe","pistol","pit","pizza","place",
        "plain","plan","plane","planet","plank","planning","plant","plaster",
        "plastic","plate","platform","play","player","please","pleasure","plenty",
        "plot","plug","plum","plunge","plus","pocket","pod","poem","poet","point",
        "poison","polar","police","policy","polish","polite","pond","pool","poor",
        "pop","popular","porch","port","portion","position","positive","possible",
        "post","pot","potato","potent","pouch","powder","power","practice","praise",
        "pray","preach","precision","prefer","prefix","pregnant","prepare",
        "presence","present","president","press","pressure","pretend","pretty",
        "prevent","previous","price","pride","primary","print","prior","prison",
        "private","prize","probable","problem","process","produce","product",
        "profile","profit","program","progress","project","promise","prompt",
        "proof","proper","property","proportion","propose","protect","proud",
        "prove","provide","public","pull","pulp","pulse","pump","punch","pupil",
        "purchase","pure","purple","purpose","purse","push","put","puzzle","pyramid",
        "q","quality","quantity","quarrel","quarter","queen","quest","question",
        "queue","quick","quiet","quilt","quilted","quorum","quote","r","rabbit",
        "race","rack","radar","radio","rafter","rage","rail","rain","raise","rally",
        "ranch","random","range","rank","rapid","rare","rat","rate","rather","ratio",
        "raven","raw","ray","razor","reach","read","reader","ready","real","reason",
        "recall","receipt","receive","recipe","record","recover","red","reduce",
        "reef","refer","reflect","refresh","refund","refuse","regard","region",
        "register","regret","regular","reject","relate","relative","release",
        "relevant","reliable","relief","remain","remember","remind","remote","render",
        "repair","repeat","replace","reply","report","represent","request","research",
        "reserve","resident","resist","resolve","resource","respect","respond",
        "rest","restaurant","result","resume","retail","retain","retire","return",
        "reveal","review","reward","rhythm","rib","ribbon","rice","rich","ride",
        "ridge","rifle","right","ring","rinse","rip","ripe","rise","risk","river",
        "road","roam","roar","roast","robe","robot","rock","rod","role","roll",
        "roof","room","rooms","root","rope","rose","rotor","rough","round","route",
        "routine","row","royal","rub","rubber","rug","rule","ruler","run","runner",
        "rural","rush","s","saddle","safe","sail","salad","sale","salmon","salt",
        "same","sand","satisfy","sauce","saucer","save","saw","say","scale","scare",
        "scarf","scene","scent","schedule","scheme","school","science","scissors",
        "scold","score","scout","scrap","screen","screw","script","scroll","sculpt",
        "sea","seal","search","season","seat","second","secret","section","sector",
        "secure","seed","see","seek","seem","segment","select","self","sell","senate",
        "send","senior","sense","sentence","separate","series","serve","service",
        "session","set","settle","seven","several","severe","shade","shadow","shaft",
        "shake","shall","shape","share","shark","sharp","shave","sheep","sheet",
        "shelf","shell","shelter","shine","ship","shirt","shock","shoe","shop","shore",
        "short","shot","should","shoulder","shout","show","shower","shrimp","shrine",
        "shut","shy","sick","side","siege","sieve","sifte","signal","silent","silk",
        "silver","similar","simple","since","sing","single","sink","sip","sir","siren",
        "site","situation","size","skate","sketch","ski","skill","skin","skip","skirt",
        "sky","slab","slate","sleep","sleeve","slender","slice","slide","slight",
        "slim","slip","slogan","slope","small","smart","smell","smile","smoke",
        "smooth","snake","snap","sneak","snow","so","soap","soccer","social",
        "society","sock","soda","sofa","soft","soil","soldier","solution","solve",
        "some","son","song","soon","sorry","sort","sound","soup","source","south",
        "space","spade","spare","spark","speak","special","speed","spell","spend",
        "sphere","spice","spider","spin","spirit","split","spoil","spoke","sponge",
        "sport","spot","spray","spread","spring","sprout","square","squeeze",
        "stable","stack","stadium","staff","stage","stairs","stamp","stand","star",
        "stare","start","state","station","statue","status","stay","steady","steak",
        "steal","steam","steel","steep","steer","step","steps","stereo","stew",
        "stick","still","stock","stomach","stone","stood","stool","stop","store",
        "storm","stove","straight","strategy","straw","stream","street","strength",
        "stress","stretch","strike","string","strip","stripe","strong","structure",
        "studio","study","stuff","stumble","style","subject","submit","substance",
        "subtle","subway","success","such","sudden","suffer","sugar","suggest",
        "suit","suitcase","sum","summer","summit","sun","super","supply","support",
        "suppose","sure","surf","surface","surge","surprise","surround","survey",
        "survive","swallow","swamp","sweet","swept","swift","swim","switch","sword",
        "symbol","sympathy","system","t","table","tablet","tail","take","tale",
        "talent","talk","tall","tank","tap","tape","target","task","taste","tax",
        "taxi","tea","teach","team","tear","tears","technical","technology","teen",
        "telephone","telescope","television","tell","temperature","temp","ten",
        "tenant","tend","tent","term","terrible","terrific","test","text","than",
        "thank","that","the","theater","theme","then","theory","there","thermal",
        "these","thick","thin","thing","think","third","thirst","thirteen","this",
        "thorough","though","thought","thousand","thread","three","throat","through",
        "throw","thumb","thunder","ticket","tide","tie","tight","tiles","till",
        "time","timetable","tin","tiny","tip","tire","title","to","toast","tobacco",
        "today","toe","together","toilet","token","tomato","tomorrow","tone",
        "tongue","tonight","tool","tools","tooth","top","topic","total","touch",
        "tourist","toward","tower","town","toy","trace","track","trade","traffic",
        "trail","train","transfer","transform","translate","transparent","trap",
        "travel","tray","treasure","treat","tree","tremble","trend","trial",
        "triangle","tribe","trick","trip","troop","trophy","tropic","trouble",
        "trousers","truck","true","truly","trumpet","trust","truth","try","tube",
        "tuesday","tug","tulip","tune","tunnel","turkey","turn","turtle","tv",
        "twelve","twentieth","twenty","twice","twin","two","type","typical","u",
        "ugly","ull","ultimate","umbrella","uncle","under","uncover","undo","unfold",
        "uniform","union","unique","unit","unite","university","unless","until",
        "unusual","up","update","upgrade","upon","upper","upset","urban","urge",
        "usage","use","useful","user","users","usual","v","vacuum","valid","valley",
        "value","valve","van","vapor","various","vault","vast","vegetable","vehicle",
        "veil","velvet","vendor","venture","verb","verify","version","vertical",
        "very","vessel","veteran","veto","via","vibrate","vice","victim","video",
        "view","vigorous","village","vine","violate","violation","violence",
        "violin","virtual","virus","visa","vision","visit","visits","visual",
        "vital","vitamin","voice","void","volcano","volume","voluntary","vote",
        "w","wade","wagon","waist","wait","wake","walk","wall","wallet","walnut",
        "want","war","wardrobe","warehouse","warm","warn","warrant","warrior",
        "wash","wasp","waste","watch","water","wave","wax","way","ways","weak",
        "wealth","weapon","wear","weather","web","wedge","weed","week","weigh",
        "weight","weird","welcome","weld","well","west","wet","whale","what","wheat",
        "wheel","when","where","whether","which","while","whip","whisper","whistle",
        "white","who","whole","whose","why","wide","width","wife","wild","will",
        "willing","win","wind","window","wine","wing","winter","wire","wise","wish",
        "with","within","without","witness","wolf","woman","wonder","wood","wool",
        "word","work","worker","world","worm","worn","worth","wrap","wrist","write",
        "writer","wrong","x","y","yard","yarn","yaw","year","yell","yellow","yes",
        "yesterday","yet","yield","you","young","your","youth","z","zero","zone",
        "zoo","zoom",
        // code identifiers the bots really use (English core above is
        // deduped-checked; Set.of forbids repeats)
        "android","blender","boolean","branchless","buffer","bugfix","bytecode",
        "bytes","callable","camelcase","checkout","classloader","cli","clone",
        "cmd","codec","compile","compiler","config","console","context","cpu",
        "css","curl","debug","decode","default","deploy","docs","docker",
        "domain","encode","enum","examples","executor","fifo",
        "floats","frontend","gc","gemini","gist","git","github",
        "glm","gradle","grep","gui","hash","hashtag","heap","http","https","id",
        "impl","imports","ingest","jdk","jvm","lambda","latex","lora",
        "md","merge","mesh","midi","mixin","npm","nullpointer","ollama","opcode",
        "parser","persistence","pip","plugin","png","poc","pojo",
        "pr","prime","profiler","py","python","pythagoras","qa","quic",
        "readme","refactor","regex","repo","repos","repl","retry",
        "revert","rotator","rtfm","runnable","runtime","schema","sdk",
        "serialization","setter","snippet","spec","spike",
        "sql","ssl","stdin","stdout","strings","stub","sudo",
        "swagger","swap","sync","syntax","tcp","template","throttle","tls",
        "todo","todos","tokenizer",
        "tutorial","txt","udp","url","urls","utils","uuid","validator",
        "vector","verbose","vg","vs","wasm","wiki","wikipedia","worktree",
        "wrapper","yaml","zip"
    );

    /** Supplemental code/domain words agents actually use (kept OUT of the
     *  big Set.of literal above: Set.of throws on any duplicate literal, so
     *  growth goes here where dupes are visible in a short list). */
    private static final Set<String> EXTRA_WORDS = Set.of(
        "bot","bots","npc","npcs","bug","bugs","buggy","pointer","string",
        "array","desktop","opengl","glfw","lwjgl","shader","vertex","texture",
        "renderer","rendering","frustum","hud","java","maven","mvn","pom",
        "push","rebase","selftest","e2e","perf","fps","gpu","gcd",
        "crystals","teleporter","portal","portals","hallway","hallways",
        "votes","proposal","proposals","issues","tests","tokens",
        "adapter","adapters","lora",
        "provides","performs","specific","evaluate","handles","performed",
        "proposes","proposed","repository","repos","explorer","idle","interface",
        "information","handling","usability",
        "fills","fill","filled","rolling","roll","fast","faster","speeds","sped"
    );

    private static boolean isKnownWord(String w) {
        return KNOWN_WORDS.contains(w) || EXTRA_WORDS.contains(w)
            || STOPWORDS.contains(w);
    }

    /** Consonant runs longer than this are keyboard mash, not English. */
    private static final int MAX_CONSONANT_RUN = 4;

    /**
     * P1-B topic gate: TRUE when the topic looks like real language or code.
     * Cheap deterministic heuristics (no network, no LLM), per the card spec:
     *   1. ratio — at least 60% of ALL tokens (lowercased, punctuation split)
     *      are dictionary words, stopwords, or bundled code identifiers
     *   2. consonant-run entropy — the letters of any unknown token may not
     *      contain a consonant run longer than 4 (catches "usyuvnyhmjtc",
     *      "78er04vtbts2wwww…", "rtmjuyujnb" whether or not digits are mixed
     *      in — digit-bearing mash like "q67 u36my7" additionally fails the
     *      60% ratio, because digits never count as dictionary words)
     * Rejects the mash the live game approved on 2026-11-17; passes
     * "code gcd python int function" and "room agent user rooms users".
     */
    public static boolean looksLikeLanguage(String topic) {
        if (topic == null) return false;
        String t = topic.toLowerCase();
        String[] toks = t.split("[^a-z0-9]+");
        int total = 0, known = 0;
        for (String raw : toks) {
            if (raw.isEmpty()) continue;
            total++;
            if (isKnownWord(raw)) { known++; continue; }
            if (hasLongConsonantRun(raw.replaceAll("[^a-z]", ""))) return false;
        }
        if (total == 0) return false; // empty/blank topic is not language
        return known * 100 >= 60 * total;
    }

    /** TRUE if the lowercase word contains a consonant run > MAX_CONSONANT_RUN. */
    private static boolean hasLongConsonantRun(String word) {
        int run = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            boolean isConsonant = (c >= 'a' && c <= 'z') && c != 'a' && c != 'e'
                && c != 'i' && c != 'o' && c != 'u' && c != 'y';
            if (isConsonant) { run++; if (run > MAX_CONSONANT_RUN) return true; }
            else run = 0;
        }
        return false;
    }

    /**
     * Cluster messages into topical groups by cosine similarity (greedy, single
     * pass). Returns a list of clusters, each a list of message indices. Used to
     * find the distinct threads in a chat log so each can become its own
     * quorum proposal instead of one giant blob.
     */
    public static List<List<Integer>> cluster(List<String> messages, float threshold) {
        List<List<Integer>> clusters = new ArrayList<>();
        List<Vector> vecs = new ArrayList<>();
        for (String m : messages) vecs.add(vectorize(m));

        for (int i = 0; i < vecs.size(); i++) {
            if (vecs.get(i).isEmpty()) continue;
            int best = -1;
            float bestSim = 0f;
            for (int c = 0; c < clusters.size(); c++) {
                // Compare against the cluster's centroid (first member's vector)
                float sim = cosine(vecs.get(i), vecs.get(clusters.get(c).get(0)));
                if (sim > bestSim) { bestSim = sim; best = c; }
            }
            if (best >= 0 && bestSim >= threshold) {
                clusters.get(best).add(i);
            } else {
                List<Integer> nc = new ArrayList<>();
                nc.add(i);
                clusters.add(nc);
            }
        }
        return clusters;
    }
}
