package com.mindpalace.avatar;

import java.util.*;

/**
 * AvatarLibrary — presets + procedural generation (the "automation library").
 * 90s-style: seeded RNG, procedural hue -> color, parametric proportions. Deterministic.
 */
public final class AvatarLibrary {

    private AvatarLibrary() {}

    /** A named starting point (Cortana, Alien, Athlete, ...). */
    public static AvatarDescriptor preset(String name) {
        switch (name.toLowerCase()) {
            case "cortana": {
                AvatarDescriptor a = new AvatarDescriptor();
                a.name = "Cortana"; a.sex = AvatarDescriptor.Sex.FEMALE;
                a.skinR = 0.55f; a.skinG = 0.75f; a.skinB = 0.95f;  // blue skin
                a.skinTexture = 3;                                   // circuit
                a.hairStyle = AvatarDescriptor.HairStyle.SHORT;
                a.hairR = 0.2f; a.hairG = 0.4f; a.hairB = 0.9f;
                a.eyeR = 0.3f; a.eyeG = 0.9f; a.eyeB = 1.0f;
                a.top = "bra"; a.topR = 0.1f; a.topG = 0.4f; a.topB = 0.9f;
                a.bottom = "yogapants"; a.bottomR = 0.05f; a.bottomG = 0.1f; a.bottomB = 0.25f;
                return a;
            }
            case "alien": {
                AvatarDescriptor a = new AvatarDescriptor();
                a.name = "Alien"; a.sex = AvatarDescriptor.Sex.FEMALE;
                a.skinR = 0.3f; a.skinG = 0.9f; a.skinB = 0.4f;
                a.skinTexture = 4;
                a.hairStyle = AvatarDescriptor.HairStyle.NONE;
                a.eyeR = 1.0f; a.eyeG = 0.2f; a.eyeB = 0.2f;
                AvatarDescriptor.BodyMod tail = new AvatarDescriptor.BodyMod();
                tail.type = "tail"; tail.attach = "hips"; tail.scale = 1.4f;
                tail.r = 0.3f; tail.g = 0.9f; tail.b = 0.4f;
                a.bodyMods.add(tail);
                a.push(AvatarDescriptor.BodyPart.HEAD, 0.3f);   // bigger head
                return a;
            }
            case "athlete": {
                AvatarDescriptor a = new AvatarDescriptor();
                a.name = "Athlete"; a.sex = AvatarDescriptor.Sex.MALE;
                a.push(AvatarDescriptor.BodyPart.SHOULDERS, 0.4f);
                a.push(AvatarDescriptor.BodyPart.CHEST, 0.3f);
                a.push(AvatarDescriptor.BodyPart.HIPS, -0.2f);
                a.top = "none";
                return a;
            }
            default:
                return new AvatarDescriptor();
        }
    }

    /** Procedural random avatar (seeded = deterministic, the "90s math").
     *  @param seed any long
     */
    public static AvatarDescriptor random(long seed) {
        Random r = new Random(seed);
        AvatarDescriptor a = new AvatarDescriptor();
        a.name = "Gen-" + Long.toHexString(seed & 0xffffL);
        a.sex = r.nextBoolean() ? AvatarDescriptor.Sex.FEMALE : AvatarDescriptor.Sex.MALE;

        float[] skin = hsvToRgb(r.nextFloat() * 360f, 0.4f + 0.3f * r.nextFloat(), 0.75f + 0.25f * r.nextFloat());
        a.skinR = skin[0]; a.skinG = skin[1]; a.skinB = skin[2];
        a.skinTexture = r.nextInt(5);

        a.hairStyle = AvatarDescriptor.HairStyle.values()[r.nextInt(AvatarDescriptor.HairStyle.values().length)];
        float[] hair = hsvToRgb(r.nextFloat() * 360f, 0.3f, 0.4f);
        a.hairR = hair[0]; a.hairG = hair[1]; a.hairB = hair[2];

        float[] eye = hsvToRgb(r.nextFloat() * 360f, 0.7f, 0.9f);
        a.eyeR = eye[0]; a.eyeG = eye[1]; a.eyeB = eye[2];

        for (AvatarDescriptor.BodyPart p : AvatarDescriptor.BodyPart.values()) {
            a.push(p, (r.nextFloat() - 0.5f) * 0.4f);   // subtle proportion drift
        }

        float[] top = hsvToRgb(r.nextFloat() * 360f, 0.6f, 0.7f);
        a.topR = top[0]; a.topG = top[1]; a.topB = top[2];
        return a;
    }

    /** Small random mutation ("pull/push" automation) — strength 0..1. */
    public static AvatarDescriptor mutate(AvatarDescriptor a, long seed, float strength) {
        Random r = new Random(seed);
        AvatarDescriptor copy = AvatarDescriptor.fromJson(a.toJson());
        for (AvatarDescriptor.BodyPart p : AvatarDescriptor.BodyPart.values()) {
            copy.push(p, (r.nextFloat() - 0.5f) * strength);
        }
        copy.skinR = clamp(copy.skinR + (r.nextFloat() - 0.5f) * strength);
        copy.skinG = clamp(copy.skinG + (r.nextFloat() - 0.5f) * strength);
        copy.skinB = clamp(copy.skinB + (r.nextFloat() - 0.5f) * strength);
        return copy;
    }

    /** HSV [0,360]/[0,1]/[0,1] -> RGB [0,1]. */
    public static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;
        float[] p = h < 60 ? new float[]{c, x, 0f} : h < 120 ? new float[]{x, c, 0f}
                  : h < 180 ? new float[]{0f, c, x} : h < 240 ? new float[]{0f, x, c}
                  : h < 300 ? new float[]{x, 0f, c} : new float[]{c, 0f, x};
        return new float[]{ p[0] + m, p[1] + m, p[2] + m };
    }

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }
}
