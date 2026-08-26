package com.mindpalace.avatar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/**
 * AvatarDescriptor — the parametric source of truth for a player avatar.
 *
 * The dressing room edits this; the renderer reads it; JSON save/load persists it;
 * the OCR->JSON->TODO->cron pipeline imports it. Nothing is baked — every attribute
 * is a number or an enum, so any attribute can be "pulled/pushed" live (ragdoll style).
 */
public class AvatarDescriptor {

    public enum Sex { MALE, FEMALE }
    public enum HairStyle { NONE, BUZZ, SHORT, BOB, ANGLED_BOB, LONG, PONYTAIL, MOHAWK, CURLY, AFRO, TWIN_TAILS }
    public enum EyeShape { ROUND, ALMOND, NARROW, UPTURNED }
    public enum BodyPart { HEAD, NECK, SHOULDERS, CHEST, WAIST, HIPS, ARMS, LEGS, FEET, HANDS }

    /** A body mod — add/remove anything (tail, boots, horns, wings, eyelashes...). */
    public static class BodyMod {
        public String type = "tail";      // tail, horns, wings, boots, eyelashes, antennae...
        public String attach = "hips";    // body region it attaches to
        public float scale = 1.0f;
        public float r = 0.5f, g = 0.5f, b = 0.5f;
        public float x = 0f, y = 0f, z = 0f;  // local offset
    }

    /** A tattoo — drawn (or AI-drawn) region + design + color. */
    public static class Tattoo {
        public String region = "arm";     // arm, chest, back, face, leg...
        public String design = "tribal";  // a name/ref to a drawn or AI-generated glyph
        public float r = 0.1f, g = 0.1f, b = 0.1f;
        public float opacity = 1.0f;
    }

    /** Jewelry — a slot + type + a cron action (mods to game actions). */
    public static class Jewelry {
        public String slot = "neck";      // neck, ring, wrist, ear, ankle...
        public String type = "amulet";    // amulet, ring, bracelet, earring...
        public float r = 0.9f, g = 0.7f, b = 0.2f;
        public String cronAction = "";    // e.g. "0 * * * *: harvest_self_emails"
    }

    // ── identity ──
    public String name = "Player";
    public Sex sex = Sex.FEMALE;

    // ── skin / hair / eyes ──
    public float skinR = 0.9f, skinG = 0.75f, skinB = 0.65f;
    public int skinTexture = 0;          // 0 smooth, 1 freckled, 2 scaled, 3 circuit, 4 alien
    public HairStyle hairStyle = HairStyle.LONG;
    public float hairR = 0.1f, hairG = 0.1f, hairB = 0.12f;
    public float streakR = -1f, streakG = -1f, streakB = -1f;  // -1 = no dye streaks
    public EyeShape eyeShape = EyeShape.ALMOND;
    public float eyeR = 0.2f, eyeG = 0.8f, eyeB = 1.0f;

    // ── proportions ("pull/push" — multiplicative scale around 1.0 neutral) ──
    public Map<BodyPart, Float> proportions = new HashMap<>();

    // ── clothing ──
    public String top = "bra";
    public String bottom = "yogapants";
    public String footwear = "none";
    public float topR = 0.9f, topG = 0.15f, topB = 0.45f;
    public float bottomR = 0.07f, bottomG = 0.07f, bottomB = 0.11f;

    // ── makeup ──
    public float eyeliner = 0f;
    public float eyeshadowR = 0.4f, eyeshadowG = 0.2f, eyeshadowB = 0.8f;
    public float lipstick = 0f, lipstickR = 0.8f, lipstickG = 0.1f, lipstickB = 0.2f;
    public float blush = 0f;

    // ── mods / tattoos / jewelry / learned workflows ──
    public List<BodyMod> bodyMods = new ArrayList<>();
    public List<Tattoo> tattoos = new ArrayList<>();
    public List<Jewelry> jewelry = new ArrayList<>();
    public Map<String, String> learnedWorkflows = new LinkedHashMap<>();  // action -> cron

    public AvatarDescriptor() { resetProportions(); }

    /** Every body part starts at 1.0 (neutral). */
    public void resetProportions() {
        for (BodyPart p : BodyPart.values()) proportions.put(p, 1.0f);
    }

    /** "Pull/push" a body part — scale it by +delta (the dressing-room pointer). */
    public void push(BodyPart part, float delta) {
        proportions.put(part, Math.max(0.2f, Math.min(3.0f, proportions.getOrDefault(part, 1.0f) + delta)));
    }

    public float getProportion(BodyPart part) { return proportions.getOrDefault(part, 1.0f); }

    /** Serialize to JSON (save to disk / git / OCR pipeline). */
    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }

    /** Deserialize from JSON. */
    public static AvatarDescriptor fromJson(String json) {
        return new Gson().fromJson(json, AvatarDescriptor.class);
    }
}
