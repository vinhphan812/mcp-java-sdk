package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
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

    /** Returns the capabilities advertisement for the server/info response. */
    default Map<String, Object> advertiseCapabilities(String protocolVersion) {
        if (!supports(protocolVersion)) return null;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("namespace", NAMESPACE);
        return caps;
    }

    /**
     * Lists available skills with cursor-based pagination.
     *
     * @param cursor opaque pagination cursor (null/empty for first page)
     * @param limit  maximum number of skills to return
     * @return paginated result with optional nextCursor
     */
    ListSkillsResult listSkills(String cursor, int limit);

    /** Retrieves a skill by URI. */
    SkillDTO getSkill(String uri);

    /**
     * Returns whether this extension supports the {@code resources/directory/read} method.
     * When {@code false} (the default), the server will not advertise the
     * {@code directoryRead} capability and will respond with method-not-found
     * to directory-read requests.
     *
     * @return {@code true} if directory listing is supported
     */
    default boolean supportsDirectoryRead() {
        return false;
    }

    /**
     * Lists directory entries for a skill URI.
     * Called only when {@link #supportsDirectoryRead()} returns {@code true}.
     *
     * <p>For nested directory support (e.g. {@code skill://refunds/templates}), implement
     * {@link PaginatedSkillsExtension} and use {@link PaginatedSkillsExtension#listDirectoryPaginated}.
     *
     * @param skillRootUri the skill root URI (e.g. {@code skill://refunds})
     * @return list of directory entries, or empty list if the directory is empty
     */
    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        // Default: no nested support, no pagination
        return List.of();
    }

    /** Serves manifest files. */
    String readManifestFile(String uri, String fileName);

    /**
     * Capabilities for resource directory listing.
     * SEP-2640: directory entries use {@code mimeType} to indicate type:
     * {@code inode/directory} for directories, inferred from file extension for files.
     */
    interface DirectoryEntry {
        String uri();
        String name();
        boolean isDirectory();

        /**
         * MIME type of this entry.
         * For directories: {@code inode/directory}.
         * For files: inferred from file name extension.
         * Default implementation returns {@code inode/directory} when {@code isDirectory()}.
         */
        default String mimeType() {
            return isDirectory() ? "inode/directory" : null;
        }
    }

    /**
     * Optional extension interface for cursor-based pagination and nested directory support.
     * Implement this alongside {@link McpSkillsExtension} to enable:
     * - Cursor-based pagination for {@code skills/list} and {@code resources/directory/read}
     * - Nested directory listing (e.g. {@code skill://acme/billing/refunds/templates})
     *
     * <p>If your extension does not implement this interface, the default
     * {@link #listDirectory(String)} is used without pagination.
     */
    interface PaginatedSkillsExtension {
        /**
         * Lists directory entries for a skill URI with optional cursor-based pagination.
         *
         * @param skillRootUri the skill root or nested path (e.g. {@code skill://refunds}
         *     or {@code skill://refunds/templates})
         * @param cursor opaque cursor for pagination (null/empty for first page)
         * @param limit maximum entries to return
         * @return page of directory entries; null/empty if cursor exhausted
         */
        List<DirectoryEntry> listDirectoryPaginated(String skillRootUri, String cursor, int limit);

        /**
         * Returns the pagination cursor for the last {@link #listDirectoryPaginated} call.
         * Called after the handler serialises the response to embed the cursor.
         *
         * @return opaque next-cursor string, or null if no more pages
         */
        String getLastDirectoryCursor();
    }
}
