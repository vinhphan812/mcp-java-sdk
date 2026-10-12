package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.spi.ExtensionRegistry;
import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension;
import io.github.vinhphan812.mcp.core.DefaultExtensionRegistry;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillsExtensionTest {

    private McpProtocolHandler handler;
    private McpRegistry registry;
    private MockSkillsExtension skillsExtension;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
        skillsExtension = new MockSkillsExtension();
        
        // Need to configure the handler with the extension via registry
        // Actually, McpProtocolHandler takes config in constructor which has extensionRegistry or similar.
        // Wait, McpProtocolHandler uses config.extensionRegistry?
        // Let's check McpServerConfig construction.
    }

    private static class MockSkillsExtension implements McpSkillsExtension {
        @Override
        public List<SkillDTO> listSkills(int offset, int limit) { return Collections.emptyList(); }
        @Override
        public SkillDTO getSkill(String uri) { return null; }
        @Override
        public String readManifestFile(String uri, String fileName) { return ""; }
    }

    @Test
    void testRegistry() {
        // Just verify basic setup
        assertNotNull(skillsExtension.namespace());
    }
}
