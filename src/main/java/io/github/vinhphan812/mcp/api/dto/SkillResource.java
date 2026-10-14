package io.github.vinhphan812.mcp.api.dto;

/**
 * Represents a skill file resource returned by {@code resources/read} for skill:// URIs.
 *
 * <p>Two representations are supported:
 * <ul>
 *   <li><b>Text</b>: UTF-8 text content (SKILL.md, scripts, config files, source code).
 *       Returned via {@link #text(String, String)}.</li>
 *   <li><b>Blob</b>: binary content encoded as Base64. Used for binary files
 *       (images, PDFs, archives). Returned via {@link #blob(String, byte[])}.</li>
 * </ul>
 *
 * <p>The MCP {@code resources/read} response serialises this as:
 * <pre>{@code
 * {
 *   "uri": "skill://acme/scripts/deploy.sh",
 *   "mimeType": "text/x-shellscript",
 *   "contents": [{ "type": "text", "text": "..." }]
 * }
 * }</pre>
 * or for blobs:
 * <pre>{@code
 * {
 *   "uri": "skill://acme/logo.png",
 *   "mimeType": "image/png",
 *   "contents": [{ "type": "blob", "data": "...base64...", "mimeType": "image/png" }]
 * }
 * }</pre>
 */
public final class SkillResource {
    private final String mimeType;
    private final String text;       // non-null for text resources
    private final byte[] blob;       // non-null for binary resources

    private SkillResource(String mimeType, String text, byte[] blob) {
        this.mimeType = mimeType;
        this.text = text;
        this.blob = blob;
    }

    /** Creates a text resource. */
    public static SkillResource text(String mimeType, String text) {
        return new SkillResource(mimeType, text, null);
    }

    /** Creates a binary resource. Content is Base64-encoded in the MCP response. */
    public static SkillResource blob(String mimeType, byte[] content) {
        return new SkillResource(mimeType, null, content);
    }

    public String mimeType() {
        return mimeType;
    }

    /** Returns the text content, or {@code null} if this is a binary resource. */
    public String text() {
        return text;
    }

    /** Returns the binary content as bytes, or {@code null} if this is a text resource. */
    public byte[] blob() {
        return blob;
    }

    /** Returns {@code true} if this is a binary (blob) resource. */
    public boolean isBlob() {
        return blob != null;
    }

    /** Returns {@code true} if this is a text resource. */
    public boolean isText() {
        return text != null;
    }
}
