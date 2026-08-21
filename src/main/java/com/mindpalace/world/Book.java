package com.mindpalace.world;

/**
 * A book on a shelf — represents one file in a repo.
 */
public class Book {
    private String filename;
    private String filePath;      // relative path in repo
    private String content;       // cached file content
    private String language;     // detected language
    private long sizeBytes;
    private String lastModified;
    private String lastCommitMessage;
    private String sha;          // GitHub blob SHA
    private boolean placed;      // true once drawn on a shelf (clickable)

    // Visual
    private float spineColor;    // hue for spine color
    private float thickness;     // visual thickness based on file size
    private float worldX, worldY, worldZ; // 3D position on shelf
    private int wallDir;         // 0=back wall (width along X), ±1=side wall (width along Z)
    private int wallIndex = -1;  // which bookcase wall this book belongs to (0,1,2)

    public Book(String filename, String filePath) {
        this.filename = filename;
        this.filePath = filePath;
        this.spineColor = (float) (Math.random() * 0.6f + 0.1f); // random warm hue
        this.thickness = 0.05f;
    }

    public String getFilename() { return filename; }
    public void setFilename(String name) { this.filename = name; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String path) { this.filePath = path; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
    public String getLanguage() { return language; }
    public void setLanguage(String lang) { this.language = lang; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long s) { this.sizeBytes = s; this.thickness = Math.max(0.02f, Math.min(0.15f, s / 100000f)); }
    public String getLastModified() { return lastModified; }
    public void setLastModified(String d) { this.lastModified = d; }
    public String getLastCommitMessage() { return lastCommitMessage; }
    public void setLastCommitMessage(String msg) { this.lastCommitMessage = msg; }
    public String getSha() { return sha; }
    public void setSha(String sha) { this.sha = sha; }
    public boolean isPlaced() { return placed; }
    public void setPlaced(boolean placed) { this.placed = placed; }
    public float getSpineColor() { return spineColor; }
    public float getThickness() { return thickness; }

    public float getWorldX() { return worldX; }
    public float getWorldY() { return worldY; }
    public float getWorldZ() { return worldZ; }
    public void setWorldPosition(float x, float y, float z) { worldX = x; worldY = y; worldZ = z; }
    public int getWallDir() { return wallDir; }
    public void setWallDir(int d) { this.wallDir = d; }
    public int getWallIndex() { return wallIndex; }
    public void setWallIndex(int i) { this.wallIndex = i; }

    /** Texture ID based on language for colored book spines. */
    public int getTextureId() {
        return textureIdForLanguage(language);
    }

    /** Static language → book-color texture map (shared by the poster diagram). */
    public static int textureIdForLanguage(String language) {
        if (language == null) return 5; // TEX_BOOK (green default)
        switch (language) {
            case "Java":       return 5;  // green
            case "C#":         return 5;  // green
            case "Kotlin":     return 10; // yellow
            case "Ruby":       return 12; // red
            case "PHP":        return 11; // orange
            case "Python":     return 9;  // blue
            case "JavaScript": return 10; // yellow
            case "TypeScript": return 10; // yellow
            case "HTML":       return 11; // orange
            case "CSS":        return 11; // orange
            case "C++":        return 12; // red
            case "Rust":       return 12; // red
            case "Go":         return 9;  // blue
            case "Shell":      return 13; // grey
            case "Markdown":   return 14; // white/cream
            case "JSON":       return 10; // yellow
            case "YAML":       return 10; // yellow
            case "SQL":        return 9;  // blue
            case "Text":       return 14; // white
            default:           return 5;  // green
        }
    }

    /**
     * Detect language from file extension.
     */
    public static String detectLanguage(String filename) {
        String name = filename.toLowerCase();
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".js") || name.endsWith(".jsx")) return "JavaScript";
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return "TypeScript";
        if (name.endsWith(".cs")) return "C#";
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "Kotlin";
        if (name.endsWith(".rb")) return "Ruby";
        if (name.endsWith(".php")) return "PHP";
        if (name.endsWith(".vue")) return "HTML";
        if (name.endsWith(".toml") || name.endsWith(".ini")) return "YAML";
        if (name.endsWith(".gradle")) return "Java";
        if (name.endsWith("dockerfile")) return "Shell";
        if (name.endsWith(".html")) return "HTML";
        if (name.endsWith(".css")) return "CSS";
        if (name.endsWith(".cpp") || name.endsWith(".c") || name.endsWith(".h")) return "C++";
        if (name.endsWith(".rs")) return "Rust";
        if (name.endsWith(".go")) return "Go";
        if (name.endsWith(".sh") || name.endsWith(".bash")) return "Shell";
        if (name.endsWith(".md")) return "Markdown";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "YAML";
        if (name.endsWith(".sql")) return "SQL";
        if (name.endsWith(".txt")) return "Text";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".gif")) return "Image";
        return "Unknown";
    }
}
