package io.github.vinhphan812.mcp.api.dto;

import java.util.List;

/**
 * Result of a paginated skills/list call.
 */
public final class ListSkillsResult {
    public final List<SkillDTO> skills;
    public final String nextCursor;

    public ListSkillsResult(List<SkillDTO> skills, String nextCursor) {
        this.skills = skills;
        this.nextCursor = nextCursor;
    }
}
