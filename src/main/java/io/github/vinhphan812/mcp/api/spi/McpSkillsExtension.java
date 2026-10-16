package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.DirectoryPage;
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.dto.SkillResource;
import io.github.vinhphan812.mcp.api.dto.SkillResourceEntry;
import io.github.vinhphan812.mcp.api.utils.McpException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension point for MCP Skills (SEP-2640).
 *
 * <p>Implementations must provide:
 * <ul>
 *   <li>{@link #listSkills(int, int)} — paginated skill listing</li>
 *   <li>{@link #getSkill(String)} — single skill by URI</li>
 *   <li>{@link #readManifestFileBytes(String, String)} — raw file bytes for digest/size</li>
 * </ul>
 *
 * @see SkillResourceEntry
 */
public interface McpSkillsExtension extends McpExtension {
    String NAMESPACE = "io.modelcontextprotocol/skills";

    @Override
    default String namespace() {
        return NAMESPACE;
    }

    @Override
    default boolean supports(String protocolVersion) {
        return "2026-07-28".equals(protocolVersion);
    }

    default Map<String, Object> advertiseCapabilities(String protocolVersion) {
        if (!supports(protocolVersion)) return null;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("namespace", NAMESPACE);
        return caps;
    }

    /**
     * Lists available skills with offset-based pagination (primary method for v1.1.2 compat).
     *
     * <p>Implement this method for full backward compatibility with extensions
     * written against the v1.1.2 API.
     *
     * <p>Note: for cursor-based pagination via {@link #listSkills(String, int)} to work
     * correctly, return at most {@code limit} items. If your data source can return more
     * than {@code limit} items, override {@link #totalSkills()} with an accurate count.
     *
     * @param offset skip the first {@code offset} skills
     * @param limit  maximum number of skills to return
     * @return list of skills (up to {@code limit})
     */
    List<SkillDTO> listSkills(int offset, int limit);

    /**
     * Returns the total number of skills known to this extension.
     * Used by the default {@link #listSkills(String, int)} implementation to compute
     * cursor pagination. The default {@link Integer#MAX_VALUE} is incorrect — it
     * causes the last-page cursor to be returned even when there is no next page,
     * producing an endless next cursor and violating the limit+1 semantics contract.
     * Override with an accurate count (e.g. the size of the backing collection).
     *
     * @return total skill count; must be accurate for correct pagination
     */
    default int totalSkills() {
        return Integer.MAX_VALUE; // DEPRECATED default — override with accurate count
    }

    /**
     * Lists available skills with cursor-based pagination.
     *
     * <p>Default implementation converts the cursor to an offset and delegates
     * to {@link #listSkills(int, int)}. Override this method directly if you
     * want native cursor semantics (opaque cursor string, not integer offset).
     *
     * <p>Pagination semantics (limit+1 pattern):
     * <ul>
     *   <li>Requests {@code limit+1} items from {@link #listSkills(int, int)}</li>
     *   <li>If {@code limit+1} items are returned, there IS a next page</li>
     *   <li>If only {@code limit} or fewer items are returned, there is NO next page
     *       (cursor becomes null) even if {@link #totalSkills()} says otherwise</li>
     *   <li>Overflow guard: if {@code offset+limit} exceeds {@link Integer#MAX_VALUE},
     *       the cursor is treated as invalid and rejected</li>
     * </ul>
     *
     * @param cursor opaque pagination cursor (null/empty for first page).
     *     If the cursor is a numeric string, it is decoded as the offset.
     * @param limit  maximum number of skills to return
     * @return paginated result with optional nextCursor; nextCursor is null on the
     *     terminal page (when {@code limit+1} detects no more items) regardless of
     *     what {@link #totalSkills()} returns
     */
    default ListSkillsResult listSkills(String cursor, int limit) {
        int offset = 0;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                // Reject non-numeric cursors (malformed)
                offset = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                throw McpException.invalidParams("invalid skills cursor");
            }
            // Reject negative offsets
            if (offset < 0) throw McpException.invalidParams("invalid skills cursor");
            // Reject overflow: offset near MAX_VALUE means next page would overflow
            if (offset > Integer.MAX_VALUE - limit) {
                throw McpException.invalidParams("invalid skills cursor");
            }
        }
        // limit+1 pattern: request one extra to detect whether a next page exists.
        // This is robust even when totalSkills() is not overridden.
        int requestLimit;
        try {
            requestLimit = Math.addExact(limit, 1);
        } catch (ArithmeticException e) {
            requestLimit = Integer.MAX_VALUE; // overflow guard for extreme limit values
        }
        List<SkillDTO> skills = listSkills(offset, requestLimit);
        boolean hasExtra = skills.size() > limit;
        if (hasExtra) {
            skills = new java.util.ArrayList<>(skills.subList(0, limit));
        }
        // Terminal page: when limit+1 returns <= limit items, there is no next page.
        // This is the authoritative signal — ignore totalSkills() for terminal detection.
        boolean hasNextPage = hasExtra;
        String nextCursor = hasNextPage ? String.valueOf((long) offset + limit) : null;
        return new ListSkillsResult(skills, nextCursor);
    }

    SkillDTO getSkill(String uri);

    default boolean supportsDirectoryRead() {
        return false;
    }

    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        return java.util.Collections.emptyList();
    }

    /**
     * Reads a skill file as a UTF-8 string.
     *
     * <p>This is the primary method for reading file content for source compatibility.
     * Implementations that handle text-only content may implement only this method.
     *
     * <p>For binary files (images, PDFs, archives), also override
     * {@link #readManifestFileBytes(String, String)} so digest/size computation
     * uses the raw bytes directly.
     *
     * @param uri      the skill root URI (e.g. {@code "skill://acme"})
     * @param fileName the file name / relative path within the skill
     * @return file content as a string, or {@code null} if not found
     */
    String readManifestFile(String uri, String fileName);

    /**
     * Reads a skill file as raw bytes.
     *
     * <p>Default implementation delegates to {@link #readManifestFile(String, String)}
     * and encodes the result as UTF-8. Override this method when the underlying
     * storage provides binary content (e.g. binary file formats, compressed files)
     * where the byte-level representation matters for digest/size computation.
     *
     * @param uri      the skill root URI
     * @param fileName the file name / relative path within the skill
     * @return raw file bytes, or {@code null} if not found
     */
    default byte[] readManifestFileBytes(String uri, String fileName) {
        String text = readManifestFile(uri, fileName);
        if (text == null) return null;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a skill file resource, supporting both text and binary content.
     *
     * <p>Default implementation calls {@link #readManifestFileBytes} and wraps
     * the result as a {@link SkillResource} (text or blob depending on MIME type).
     * Override to serve binary files (images, archives) as blobs.
     *
     * @param uri      the skill URI being requested
     * @param fileName the file name / relative path within the skill
     * @param mimeType the MIME type of the file (from {@link DirectoryEntry#mimeType()})
     * @return the file content as a {@link SkillResource}, or {@code null} if not found
     */
    default SkillResource readSkillResource(String uri, String fileName, String mimeType) {
        byte[] bytes = readManifestFileBytes(uri, fileName);
        if (bytes == null) return null;
        // Use the declared mimeType; default to text/plain if null.
        String resolvedMime = mimeType != null ? mimeType : "text/plain";
        // Text types are served as UTF-8 strings; everything else as binary.
        if (resolvedMime.startsWith("text/")) {
            try {
                return SkillResource.text(resolvedMime,
                        new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                // UTF-8 decode failed — treat as binary
            }
        }
        return SkillResource.blob(resolvedMime, bytes);
    }

    // ── Digest and size utilities ────────────────────────────────────────────────

    /**
     * Computes the SHA-256 digest of a UTF-8 text string and returns it in
     * the format {@code "sha256:<hex>"}.
     *
     * @param text the text content (never null)
     * @return the formatted digest string
     */
    static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the SHA-256 digest of raw bytes and returns it in the format
     * {@code "sha256:<hex>"}.
     *
     * @param bytes the raw content bytes (never null)
     * @return the formatted digest string
     */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Builds an ordered, deduplicated list of {@link SkillResourceEntry} for
     * the given skill root URI and file names.
     *
     * <p>Each entry's digest and size are computed from the raw bytes returned
     * by {@link #readManifestFileBytes(String, String)} — not from a UTF-8
     * re-encoded string — so that binary files have correct SHA-256 digests
     * and byte-accurate sizes.
     *
     * <p>Files are ordered by insertion (LinkedHashMap) with SKILL.md forced
     * first, then deduplicated by name (first occurrence wins).
     *
     * <p>SKILL.md is always included as the first entry.
     *
     * <p>Note: this utility is intended for <b>static</b> skills where file
     * content is known at registration time. For <b>dynamic</b> skills, return
     * {@link SkillResourceEntry#dynamic()} instead.
     *
     * @param skillRootUri the skill root URI (e.g. {@code "skill://acme"})
     * @param fileNames    ordered collection of file names (e.g. from {@code File.list()})
     * @param extension    the skills extension instance (used to read file content)
     * @return ordered, deduplicated list of resource entries (never null)
     */
    static List<SkillResourceEntry> buildResourceEntries(
            String skillRootUri,
            Iterable<String> fileNames,
            McpSkillsExtension extension) {

        Map<String, SkillResourceEntry> seen = new LinkedHashMap<>();

        // Always include SKILL.md first
        byte[] manifestBytes = extension.readManifestFileBytes(skillRootUri, "SKILL.md");
        if (manifestBytes != null) {
            seen.put("SKILL.md", new SkillResourceEntry(
                    skillRootUri + "/SKILL.md",
                    "SKILL.md",
                    sha256Hex(manifestBytes),
                    manifestBytes.length));
        }

        if (fileNames != null) {
            for (String name : fileNames) {
                if (name == null || name.isEmpty() || name.equals("SKILL.md")) continue;
                if (seen.containsKey(name)) continue; // deduplicate

                byte[] fileBytes = extension.readManifestFileBytes(skillRootUri, name);
                if (fileBytes != null) {
                    seen.put(name, new SkillResourceEntry(
                            skillRootUri + "/" + name,
                            name,
                            sha256Hex(fileBytes),
                            fileBytes.length));
                }
            }
        }

        return new java.util.ArrayList<>(seen.values());
    }

    // ── DirectoryEntry ───────────────────────────────────────────────────────────

    interface DirectoryEntry {
        String uri();
        String name();
        boolean isDirectory();

        default String mimeType() {
            if (isDirectory()) return "inode/directory";
            String name = name();
            if (name == null) return "text/plain";
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return "text/plain";
            String ext = name.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "md":  return "text/markdown";
                case "txt": return "text/plain";
                case "json": return "application/json";
                case "yaml": case "yml": return "text/yaml";
                case "xml": return "application/xml";
                case "sh": return "text/x-shellscript";
                case "py": return "text/x-python";
                case "java": return "text/x-java";
                case "js": return "application/javascript";
                case "css": return "text/css";
                case "html": case "htm": return "text/html";
                case "png": return "image/png";
                case "jpg": case "jpeg": return "image/jpeg";
                case "gif": return "image/gif";
                case "svg": return "image/svg+xml";
                case "pdf": return "application/pdf";
                case "zip": return "application/zip";
                case "tar": return "application/x-tar";
                case "gz": case "gzip": return "application/gzip";
                default: return "application/octet-stream";
            }
        }
    }

    interface PaginatedSkillsExtension {
        DirectoryPage listDirectoryPage(String directoryUri, String cursor, int limit);
    }
}
