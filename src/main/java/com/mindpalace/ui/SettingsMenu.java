package com.mindpalace.ui;

import com.mindpalace.github.GitHubClient;

/**
 * Settings menu — graphics, GitHub, audio, controls.
 * Rendered as overlay when ESC is pressed.
 */
public class SettingsMenu {
    private boolean open = false;

    // Graphics
    private int resolutionWidth = 1920;
    private int resolutionHeight = 1080;
    private boolean fullscreen = false;
    private boolean vsync = true;
    private int fov = 70;
    private int textureQuality = 2; // 0=low, 1=med, 2=high

    // GitHub
    private String pat = "";
    private boolean patValid = false;
    private int syncInterval = 300; // seconds

    // Audio
    private float masterVolume = 0.7f;
    private float musicVolume = 0.5f;
    private float sfxVolume = 0.8f;
    private boolean audioEnabled = true;

    // Controls
    private float mouseSensitivity = 0.1f;
    private boolean invertY = false;

    // Theme
    private String hallwayTheme = "stone"; // stone, wood, marble, scifi

    public void toggle() {
        open = !open;
        if (open) {
            printSettings();
        }
    }

    public void printSettings() {
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║          MIND PALACE SETTINGS            ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ GRAPHICS                                ║");
        System.out.println("║  1. Resolution: " + resolutionWidth + "x" + resolutionHeight);
        System.out.println("║  2. Fullscreen: " + (fullscreen ? "ON" : "OFF"));
        System.out.println("║  3. VSync: " + (vsync ? "ON" : "OFF"));
        System.out.println("║  4. FOV: " + fov);
        System.out.println("║  5. Texture Quality: " + (textureQuality == 2 ? "HIGH" : textureQuality == 1 ? "MED" : "LOW"));
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ GITHUB                                  ║");
        System.out.println("║  6. PAT: " + (patValid ? "✓ Valid" : "✗ Not set"));
        System.out.println("║  7. Sync Interval: " + syncInterval + "s");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ AUDIO                                   ║");
        System.out.println("║  8. Master Volume: " + (int)(masterVolume * 100) + "%");
        System.out.println("║  9. Music: " + (int)(musicVolume * 100) + "%");
        System.out.println("║ 10. SFX: " + (int)(sfxVolume * 100) + "%");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ CONTROLS                                ║");
        System.out.println("║ 11. Mouse Sensitivity: " + String.format("%.1f", mouseSensitivity));
        System.out.println("║ 12. Invert Y: " + (invertY ? "ON" : "OFF"));
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ THEME                                   ║");
        System.out.println("║ 13. Hallway: " + hallwayTheme.toUpperCase());
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ Press number to change, ESC to close    ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    public void validatePat(GitHubClient github) {
        if (pat == null || pat.length() < 40) {
            patValid = false;
            return;
        }
        github.setToken(pat);
        patValid = github.isAuthenticated();
    }

    // Getters
    public boolean isOpen() { return open; }
    public void setOpen(boolean o) { this.open = o; }
    public int getResolutionWidth() { return resolutionWidth; }
    public int getResolutionHeight() { return resolutionHeight; }
    public boolean isFullscreen() { return fullscreen; }
    public boolean isVsync() { return vsync; }
    public int getFov() { return fov; }
    public String getPat() { return pat; }
    public void setPat(String p) { this.pat = p; }
    public float getMasterVolume() { return masterVolume; }
    public float getMusicVolume() { return musicVolume; }
    public float getSfxVolume() { return sfxVolume; }
    public float getMouseSensitivity() { return mouseSensitivity; }
    public String getHallwayTheme() { return hallwayTheme; }
}
