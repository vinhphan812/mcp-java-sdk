package io.github.vinhphan812.mcp.api.dto;

import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension.DirectoryEntry;
import java.util.List;

/**
 * Atomic page of directory entries returned by {@code resources/directory/read}.
 * Combines the page data and the next-cursor in one return value, eliminating
 * the need for mutable state in pagination.
 *
 * <p>Implementation note: the handler calls {@code listDirectoryPage()} once per
 * request. The extension returns the page and the cursor together, so concurrent
 * requests on the same extension instance do not interfere.
 */
public final class DirectoryPage {
    private final List<? extends DirectoryEntry> entries;
    private final String nextCursor;

    public DirectoryPage(List<? extends DirectoryEntry> entries, String nextCursor) {
        this.entries = entries != null ? entries : List.of();
        this.nextCursor = nextCursor;
    }

    public List<? extends DirectoryEntry> getEntries() {
        return entries;
    }

    public String getNextCursor() {
        return nextCursor;
    }
}
