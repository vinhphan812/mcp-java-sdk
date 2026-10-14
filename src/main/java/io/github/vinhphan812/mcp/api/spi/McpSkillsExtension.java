package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.DirectoryPage;
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.dto.SkillResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension point for MCP Skills.
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
                offset = 0;
            }
        }
        List<SkillDTO> skills = listSkills(offset, limit);
        int total = totalSkills();
        boolean hasNextPage = offset + limit < total;
        String nextCursor = hasNextPage ? String.valueOf(offset + limit) : null;
        return new ListSkillsResult(skills, nextCursor);
    }

    SkillDTO getSkill(String uri);

    default boolean supportsDirectoryRead() {
        return false;
    }

    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        return List.of();
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
                case "html": return "text/html";
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
