package com.mindpalace.avatar;

/**
 * AvatarImporter — the OCR->JSON->TODO->cron pipeline.
 * 1. OCR  (injectable: image/text -> text; default identity = text in -> text out)
 * 2. JSON (parse a "key: value" text spec into an AvatarDescriptor)
 * 3. TODO (a deterministic contract object)
 * 4. cron (map a workflow/action name -> cron expression)
 * Deterministic, zero-LLM.
 */
public class AvatarImporter {

    /** OCR stage — injectable. Default: identity (input is already text).
     *  Swap in Tesseract or a local model to OCR a screenshot/image to text. */
    public interface Ocr { String read(String input); }

    private final Ocr ocr;

    public AvatarImporter() { this(s -> s); }
    public AvatarImporter(Ocr ocr) { this.ocr = ocr; }

    /** A deterministic TODO contract (feeds the game's TodoCrystal / fleet contracts). */
    public static class Todo {
        public String id;       // sha1 of (name+action)
        public String name;
        public String action;   // the workflow/command
        public String cron;     // cron expression
        public boolean done;
        public Todo(String name, String action, String cron) {
            this.name = name; this.action = action; this.cron = cron;
            this.id = sha1(name + "|" + action);
        }
    }

    /** Full pipeline: OCR -> JSON(AvatarDescriptor) -> TODO -> cron. */
    public Todo importSpec(String rawText) {
        String text = ocr.read(rawText);        // 1. OCR
        AvatarDescriptor a = parseSpec(text);   // 2. -> JSON (descriptor)
        return todoFor(a);                       // 3+4. -> TODO -> cron
    }

    /** Parse a "key: value; key: value" text spec into a descriptor. */
    public AvatarDescriptor parseSpec(String text) {
        AvatarDescriptor a = new AvatarDescriptor();
        for (String tok : text.split("[;\\n]")) {
            int colon = tok.indexOf(':');
            if (colon < 0) continue;
            String k = tok.substring(0, colon).trim().toLowerCase();
            String v = tok.substring(colon + 1).trim();
            switch (k) {
                case "name":       a.name = v; break;
                case "sex":        a.sex = v.toLowerCase().startsWith("m") ? AvatarDescriptor.Sex.MALE : AvatarDescriptor.Sex.FEMALE; break;
                case "skin":       setRgb(v, a, 's'); break;
                case "hair":       a.hairStyle = hairStyleOf(v); break;
                case "haircolor":  setRgb(v, a, 'h'); break;
                case "eyes":       setRgb(v, a, 'e'); break;
                case "top":        a.top = v; break;
                case "bottom":     a.bottom = v; break;
                case "proportion": parseProportion(v, a); break;   // proportion=shoulders:1.4
                case "mod":        parseMod(v, a); break;          // mod=tail
                default: break;
            }
        }
        return a;
    }

    /** Build the TODO + cron for a descriptor (the workflow the avatar should learn/run). */
    public Todo todoFor(AvatarDescriptor a) {
        String action = "dress " + a.name + " (" + a.sex + ", " + a.hairStyle + ", top=" + a.top + ")";
        return new Todo("avatar:" + a.name, action, cronFor("avatar"));
    }

    /** Map a workflow/action name -> cron expression (deterministic). */
    public String cronFor(String workflow) {
        switch (workflow.toLowerCase()) {
            case "avatar":  return "0 0 * * *";       // nightly
            case "harvest": return "0 * * * *";       // hourly
            case "dream":   return "0 3 * * *";       // 3am
            case "gossip":  return "*/15 * * * *";    // 15 min
            default:        return "0 0 * * *";
        }
    }

    // ── helpers ──
    private static AvatarDescriptor.HairStyle hairStyleOf(String v) {
        try { return AvatarDescriptor.HairStyle.valueOf(v.trim().toUpperCase()); }
        catch (Exception e) { return AvatarDescriptor.HairStyle.LONG; }
    }

    private static void setRgb(String v, AvatarDescriptor a, char which) {
        String[] p = v.split(",");
        if (p.length != 3) return;
        try {
            float r = Float.parseFloat(p[0].trim());
            float g = Float.parseFloat(p[1].trim());
            float b = Float.parseFloat(p[2].trim());
            if (which == 's') { a.skinR = r; a.skinG = g; a.skinB = b; }
            else if (which == 'h') { a.hairR = r; a.hairG = g; a.hairB = b; }
            else { a.eyeR = r; a.eyeG = g; a.eyeB = b; }
        } catch (NumberFormatException ignored) {}
    }

    private static void parseProportion(String v, AvatarDescriptor a) {
        String[] p = v.split(":");
        if (p.length != 2) return;
        try {
            AvatarDescriptor.BodyPart part = AvatarDescriptor.BodyPart.valueOf(p[0].trim().toUpperCase());
            a.proportions.put(part, Float.parseFloat(p[1].trim()));
        } catch (Exception ignored) {}
    }

    private static void parseMod(String v, AvatarDescriptor a) {
        AvatarDescriptor.BodyMod m = new AvatarDescriptor.BodyMod();
        m.type = v.trim();
        a.bodyMods.add(m);
    }

    private static String sha1(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
