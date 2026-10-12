package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.dto.SkillDTO;
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
}
