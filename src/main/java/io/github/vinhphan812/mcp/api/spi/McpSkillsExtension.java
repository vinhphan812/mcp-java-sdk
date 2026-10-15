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
 *   <li>{@link #readManifestFile(String, String)} — raw file content for digest/size computation</li>
 * </ul>
 *
 * <p>For correct cursor pagination via {@link #listSkills(String, int)} without
 * requiring a {@link #totalSkills()} override, see the note on {@link #listSkills(String, int)}.
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
     * without a {@link #totalSkills()} override, return at most {@code limit} items.
     * If your data source can return more than {@code limit} items, override
     * {@link #totalSkills()} with an accurate total count.
     *
     * @param offset skip the first {@code offset} skills
     * @param limit  maximum number of skills to return
     * @return list of skills (up to {@code limit})
     */
    List<SkillDTO> listSkills(int offset, int limit);

    /**
     * Returns the total number of skills known to this extension.
     * Used by the default {@link #listSkills(String, int)} implementation to compute
     * cursor pagination correctly. Override to provide an accurate count.
     *
     * @return total skill count, or {@link Integer#MAX_VALUE} if unknown
     */
    default int totalSkills() {
        return Integer.MAX_VALUE;
    }

    /**
     * Lists available skills with cursor-based pagination.
     *
     * <p>Default implementation converts the cursor to an offset and delegates
     * to {@link #listSkills(int, int)}. Override this method directly if you
     * want native cursor semantics (opaque cursor string, not integer offset).
     *
     * <p>Cursor pagination without {@link #totalSkills()}: the default implementation
     * requests {@code limit+1} items from {@link #listSkills(int, int)} and detects
     * the last page by comparing the returned size to {@code limit}. If your
     * {@link #listSkills(int, int)} already caps at exactly {@code limit} items,
     * the {@code limit+1} request means you receive {@code limit+1} items and
     * would need to handle that. In that case, override {@link #totalSkills()}
     * with an accurate count so the cursor can be computed correctly.
     *
     * @param cursor opaque pagination cursor (null/empty for first page).
     *     If the cursor is a numeric string, it is decoded as the offset.
     * @param limit  maximum number of skills to return
     * @return paginated result with optional nextCursor
     */
    default ListSkillsResult listSkills(String cursor, int limit) {
        int offset = 0;
        if (cursor != null && !cursor.isEmpty()) {
            try {
                offset = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                throw McpException.invalidParams("invalid skills cursor");
            }
            if (offset < 0) throw McpException.invalidParams("invalid skills cursor");
            // Validate cursor doesn't cause integer overflow
            if (offset > Integer.MAX_VALUE - limit) {
                throw McpException.invalidParams("invalid skills cursor");
            }
        }
        // Request limit+1 to detect if there's a next page
        int requestLimit = Math.addExact(limit, 1);
        List<SkillDTO> skills = listSkills(offset, requestLimit);
        boolean hasExtra = skills.size() > limit;
        if (hasExtra) {
            skills = new java.util.ArrayList<>(skills.subList(0, limit));
        }
        // B6: Proper limit+1 pagination. Only expose nextCursor when we know there's more
        // (hasExtra is true). When totalSkills() returns MAX_VALUE (unknown), we cannot
        // compute hasNextPage from total, so we only rely on hasExtra.
        // This ensures no nextCursor is exposed on an exact final full page.
        String nextCursor = hasExtra ? String.valueOf((long) offset + limit) : null;
        return new ListSkillsResult(skills, nextCursor);
    }

    SkillDTO getSkill(String uri);

    default boolean supportsDirectoryRead() {
        return false;
    }

    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        return java.util.Collections.emptyList();
    }

    String readManifestFile(String uri, String fileName);

    /**
     * Reads a skill file resource, supporting both text and binary content.
     *
     * <p>Default implementation wraps {@link #readManifestFile} as a text resource.
     * Override to serve binary files (images, archives) as blobs.
     *
     * @param uri      the skill URI being requested
     * @param fileName the file name / relative path within the skill (e.g. {@code "scripts/check.py"})
     * @param mimeType the MIME type of the file (from {@link DirectoryEntry#mimeType()})
     * @return the file content as a {@link SkillResource}, or {@code null} if not found
     */
    default SkillResource readSkillResource(String uri, String fileName, String mimeType) {
        String text = readManifestFile(uri, fileName);
        if (text == null) return null;
        return SkillResource.text(mimeType, text);
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
     * <p>Each entry's digest and size are computed from the actual file content
     * returned by {@link #readManifestFile(String, String)}. Files are sorted
     * by URI and duplicate URIs are removed (first occurrence wins).
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
        String manifest = extension.readManifestFile(skillRootUri, "SKILL.md");
        if (manifest != null) {
            byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
            seen.put("SKILL.md", new SkillResourceEntry(
                    skillRootUri + "/SKILL.md",
                    "SKILL.md",
                    sha256Hex(manifest),
                    manifestBytes.length));
        }

        if (fileNames != null) {
            for (String name : fileNames) {
                if (name == null || name.isEmpty() || name.equals("SKILL.md")) continue;
                if (seen.containsKey(name)) continue; // deduplicate

                String content = extension.readManifestFile(skillRootUri, name);
                if (content != null) {
                    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                    seen.put(name, new SkillResourceEntry(
                            skillRootUri + "/" + name,
                            name,
                            sha256Hex(content),
                            contentBytes.length));
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
