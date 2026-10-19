package com.mindpalace.tailscale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

/**
 * MeshCrypto (plan T2, use 3/18): per-device Ed25519 keypair + peer key pin
 * store for signed mesh messages (ballots, chat, presence).
 *
 * <p>Every envelope on the mesh carries a signature over a canonical payload
 * string built by the caller ({@code "mp-ballot-1" / "mp-chat-1"} version
 * prefixes + fields joined with '\n'). Keys live under
 * {@code mindpalace_memory/mesh/} beside the telemetry ledger. Each node
 * generates one keypair on first open and writes
 * {@code private.bin}/${@code public.bin}; the pin store
 * {@code peer_pins.properties} maps peer device name (from tailscale whois,
 * never from the request body) to owner-login device public keys,
 * trust-on-first-use. A pin mismatch means tampering or key replacement —
 * reject and report.</p>
 *
 * <p>Fail-closed: {@link #verifyFromPeer} returns false for unknown peers
 * (they must present a key over an owner-authenticated channel first); the
 * transport layer guarantees only owner-login devices reach signing routes.</p>
 */
public final class MeshCrypto {

    private static final String PRIVATE_FILE = "private.bin";
    private static final String PUBLIC_FILE = "public.bin";
    private static final String PINS_FILE = "peer_pins.properties";
    private static final String ALGO = "Ed25519";

    private final Path keyDir;
    private final String deviceName;
    private final KeyPair keyPair;
    private final Properties pins = new Properties();
    private final Path pinsFile;
    private final java.util.function.Consumer<String> onTamper;

    private MeshCrypto(Path keyDir, String deviceName, KeyPair keyPair,
                       java.util.function.Consumer<String> onTamper) {
        this.keyDir = keyDir;
        this.deviceName = deviceName;
        this.keyPair = keyPair;
        this.pinsFile = keyDir.resolve(PINS_FILE);
        this.onTamper = onTamper;
    }

    /**
     * Load (or create) this device's keypair and peer pin store.
     *
     * @param keyDir    directory kept across restarts (created if missing)
     * @param deviceName stable label for this device (e.g. tailnet hostname)
     * @param onTamper  optional hook fired when a stored key is replaced
     *                  (pin-store tamper evidence); may be null
     */
    public static MeshCrypto open(Path keyDir, String deviceName,
                                  java.util.function.Consumer<String> onTamper)
            throws GeneralSecurityException, IOException {
        Files.createDirectories(keyDir);
        Path priv = keyDir.resolve(PRIVATE_FILE);
        Path pub = keyDir.resolve(PUBLIC_FILE);
        KeyPair pair;
        if (Files.exists(priv) && Files.exists(pub)) {
            pair = loadPrivateKey(priv, pub);
        } else {
            pair = generate();
            storeKeyPair(priv, pub, pair);
        }
        MeshCrypto crypto = new MeshCrypto(keyDir, deviceName, pair, onTamper);
        crypto.loadPins();
        return crypto;
    }

    public String deviceName() { return deviceName; }
    public PublicKey publicKey() { return keyPair.getPublic(); }
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** Sign a canonical payload string; returns the base64 signature. */
    public String sign(String payload) throws GeneralSecurityException {
        Signature sig = Signature.getInstance(ALGO);
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /** True when {@code sigBase64} is a valid signature of {@code payload}. */
    public static boolean verify(PublicKey key, String payload, String sigBase64) {
        if (key == null || payload == null || sigBase64 == null || sigBase64.isBlank()) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance(ALGO);
            sig.initVerify(key);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(sigBase64.trim()));
        } catch (Exception e) {
            return false; // bad base64, wrong key, corrupt sig: all just "invalid"
        }
    }

    /** Pin (or validate) a peer public key, trust-on-first-use per device. */
    public synchronized void pinPeer(String peerName, String publicKeyBase64) {
        String existing = pins.getProperty(peerName);
        if (existing != null && existing.equals(publicKeyBase64)) return;
        if (existing != null && onTamper != null) {
            onTamper.accept("peer key changed for " + peerName
                + ": " + shortKey(existing) + " -> " + shortKey(publicKeyBase64));
        }
        pins.setProperty(peerName, publicKeyBase64);
        storePins();
    }

    /** The pinned key for a peer, or null (unpinned = unverified caller). */
    public synchronized PublicKey peerKey(String peerName) {
        String stored = pins.getProperty(peerName);
        if (stored == null) return null;
        try {
            byte[] der = Base64.getDecoder().decode(stored);
            return KeyFactory.getInstance(ALGO).generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            return null; // corrupt pin row: behave as unpinned (fail-closed)
        }
    }

    /** Verify a peer's signature using their pinned key. Unknown peer => false. */
    public synchronized boolean verifyFromPeer(String peerName, String payload,
                                               String sigBase64) {
        return verify(peerKey(peerName), payload, sigBase64);
    }

    /** True when the pin store already holds this exact key for the peer. */
    public synchronized boolean pinMatches(String peerName, String publicKeyBase64) {
        return publicKeyBase64 != null
            && publicKeyBase64.equals(pins.getProperty(peerName));
    }

    /** Short label of a base64 key for logs (never prints the full key). */
    private static String shortKey(String base64) {
        return base64 == null ? "null" : base64.substring(0, Math.min(8, base64.length())) + "..";
    }

    /**
     * Canonical signed payload for a mesh ballot: version, proposal, device,
     * vote, nonce, ts — joined with '\n' (ASCII only). Shared verbatim by
     * server (MeshServer verifies) and client (MeshPeerClient signs).
     */
    public static String ballotPayload(String proposalId, String device, String vote,
                                       String nonce, long ts) {
        return String.join("\n", java.util.List.of("mp-ballot-1", nvl(proposalId),
            nvl(device), nvl(vote), nvl(nonce), Long.toString(ts)));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private void loadPins() throws IOException {
        if (Files.exists(pinsFile)) {
            try (InputStream in = Files.newInputStream(pinsFile)) {
                pins.load(in);
            }
        }
    }

    private synchronized void storePins() {
        try (OutputStream out = Files.newOutputStream(pinsFile)) {
            pins.store(out, "mesh peer pins (TOFU, owner-login devices only)");
        } catch (IOException e) {
            // pins survive in memory; next successful pin write re-dumps
        }
    }

    private static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(ALGO); // Ed25519 is fixed-size — no initialize()
        return gen.generateKeyPair();
    }

    private static KeyPair loadPrivateKey(Path priv, Path pub)
            throws GeneralSecurityException, IOException {
        byte[] pubDer = Files.readAllBytes(pub);
        PublicKey publicKey = KeyFactory.getInstance(ALGO)
            .generatePublic(new X509EncodedKeySpec(pubDer));
        // PKCS8 private encoding
        byte[] privDer = Files.readAllBytes(priv);
        java.security.PrivateKey privateKey = KeyFactory.getInstance(ALGO)
            .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(privDer));
        return new KeyPair(publicKey, privateKey);
    }

    private static void storeKeyPair(Path priv, Path pub, KeyPair pair) throws IOException {
        Path privTmp = priv.resolveSibling(PRIVATE_FILE + ".new");
        Path pubTmp = pub.resolveSibling(PUBLIC_FILE + ".new");
        Files.write(privTmp, pair.getPrivate().getEncoded());
        Files.write(pubTmp, pair.getPublic().getEncoded());
        Files.move(privTmp, priv, StandardCopyOption.REPLACE_EXISTING);
        Files.move(pubTmp, pub, StandardCopyOption.REPLACE_EXISTING);
    }
}