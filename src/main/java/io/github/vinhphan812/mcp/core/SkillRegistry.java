package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for skills.
 */
public final class SkillRegistry {
    private final Map<String, SkillDTO> skills = new ConcurrentHashMap<>();

    public void registerSkill(SkillDTO skill) {
        skills.put(skill.uri, skill);
    }

    public SkillDTO getSkill(String uri) {
        return skills.get(uri);
    }

    public Map<String, SkillDTO> getAllSkills() {
        return skills;
    }

    /**
     * Returns a paginated slice of registered skills.
     * @param offset starting index (0-based)
     * @param limit maximum number to return
     */
    public List<SkillDTO> listSkills(int offset, int limit) {
        List<SkillDTO> all = new ArrayList<>(skills.values());
        if (offset >= all.size()) return new ArrayList<>();
        int end = Math.min(offset + limit, all.size());
        return all.subList(offset, end);
    }
}
