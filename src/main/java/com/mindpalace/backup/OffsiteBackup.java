package com.mindpalace.backup;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * MP-041: USB/off-site backup. Packs source trees (AIGEN_SYS, repos, databases,
 * chat logs) into ONE passphrase-encrypted archive (AES-256-GCM, PBKDF2 key) so the
 * telemetry ledger and any keys are never stored in the clear on removable media.
 * Archive = "MPBK1" | salt(16) | iv(12) | GCM(zip). The zip carries MANIFEST.tsv
 * (sha256 per file) which {@link #restoreTest} replays after decrypting.
 */
public final class OffsiteBackup {
    private static final byte[] MAGIC = "MPBK1".getBytes(StandardCharsets.US_ASCII);
    private static final String MANIFEST = "MANIFEST.tsv";
    private static final int ITER = 120_000;

    private OffsiteBackup() {}

    public record Result(boolean ok, int files, String detail) {}

    /** Pass a null/blank passphrase to refuse (never write plaintext). */
    public static Result backup(Map<String, Path> sources, Path archive, char[] pass) {
        if (pass == null || pass.length == 0) return new Result(false, 0, "passphrase required");
        try {
            if (archive.getParent() != null) Files.createDirectories(archive.getParent());
            byte[] salt = new byte[16], iv = new byte[12];
            SecureRandom rnd = new SecureRandom();
            rnd.nextBytes(salt); rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(pass, salt), new GCMParameterSpec(128, iv));
            StringBuilder manifest = new StringBuilder();
            int n = 0;
            try (OutputStream raw = Files.newOutputStream(archive)) {
                raw.write(MAGIC); raw.write(salt); raw.write(iv);
                try (ZipOutputStream zip = new ZipOutputStream(new CipherOutputStream(raw, c))) {
                    for (Map.Entry<String, Path> src : new TreeMap<>(sources).entrySet()) {
                        if (!Files.isDirectory(src.getValue())) continue;
                        List<Path> files;
                        try (Stream<Path> w = Files.walk(src.getValue())) {
                            files = w.filter(Files::isRegularFile).filter(OffsiteBackup::wanted).sorted().toList();
                        }
                        for (Path f : files) {
                            String name = src.getKey() + "/" + src.getValue().relativize(f).toString().replace('\\', '/');
                            zip.putNextEntry(new ZipEntry(name));
                            Files.copy(f, zip);
                            zip.closeEntry();
                            manifest.append(sha256(f)).append('\t').append(name).append('\n');
                            n++;
                        }
                    }
                    zip.putNextEntry(new ZipEntry(MANIFEST));
                    zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return new Result(true, n, "wrote " + archive);
        } catch (Exception e) {
            return new Result(false, 0, e.toString());
        }
    }

    /** Decrypt into workDir, then verify every manifest hash. */
    public static Result restoreTest(Path archive, char[] pass, Path workDir) {
        try {
            Files.createDirectories(workDir);
            Path root = workDir.toAbsolutePath().normalize();
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
                byte[] magic = raw.readNBytes(MAGIC.length);
                if (!Arrays.equals(magic, MAGIC)) return new Result(false, 0, "bad magic");
                byte[] salt = raw.readNBytes(16), iv = raw.readNBytes(12);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key(pass, salt), new GCMParameterSpec(128, iv));
                try (ZipInputStream zip = new ZipInputStream(new CipherInputStream(raw, c))) {
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null) {
                        Path out = root.resolve(e.getName()).normalize();
                        if (!out.startsWith(root)) return new Result(false, 0, "unsafe entry " + e.getName());
                        if (out.getParent() != null) Files.createDirectories(out.getParent());
                        Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path mf = root.resolve(MANIFEST);
            if (!Files.exists(mf)) return new Result(false, 0, "no manifest (wrong passphrase or tampered)");
            int ok = 0;
            for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t < 0) continue;
                Path f = root.resolve(line.substring(t + 1)).normalize();
                if (!f.startsWith(root) || !Files.exists(f) || !sha256(f).equals(line.substring(0, t)))
                    return new Result(false, ok, "hash mismatch: " + line.substring(t + 1));
                ok++;
            }
            return new Result(true, ok, "verified " + ok + " files");
        } catch (Exception e) {
            return new Result(false, 0, e.toString());
        }
    }

    private static boolean wanted(Path p) {
        for (Path part : p) {
            String s = part.toString();
            if (s.equals(".git") || s.equals("node_modules") || s.equals("target")) return false;
        }
        return true;
    }

    private static SecretKeySpec key(char[] pass, byte[] salt) throws Exception {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(pass, salt, ITER, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
