package com.mindpalace.audio;

/**
 * Audio engine stub — OpenAL via LWJGL.
 * Full implementation: footstep sounds, door creaks, ambient hum, music.
 */
public class AudioEngine {
    private boolean enabled = true;
    private float masterVolume = 0.7f;
    private float musicVolume = 0.5f;
    private float sfxVolume = 0.8f;

    public AudioEngine() {
        // TODO: Initialize OpenAL context
        // For now, audio is a stub — sounds will be added in Phase 2
        System.out.println("[Audio] Engine initialized (stub — sounds coming in Phase 2)");
    }

    public void playFootstep(String material) {
        if (!enabled) return;
        // TODO: Play footstep sound based on floor material
    }

    public void playDoorOpen() {
        if (!enabled) return;
        // TODO: Play door creak sound
    }

    public void playBookOpen() {
        if (!enabled) return;
        // TODO: Play page flip sound
    }

    public void playAmbient() {
        if (!enabled) return;
        // TODO: Loop ambient hallway hum
    }

    public void setMasterVolume(float v) { this.masterVolume = Math.max(0, Math.min(1, v)); }
    public void setMusicVolume(float v) { this.musicVolume = Math.max(0, Math.min(1, v)); }
    public void setSfxVolume(float v) { this.sfxVolume = Math.max(0, Math.min(1, v)); }
    public void setEnabled(boolean e) { this.enabled = e; }

    public void cleanup() {
        // TODO: Clean up OpenAL
    }
}
