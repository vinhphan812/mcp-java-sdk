package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import io.github.vinhphan812.mcp.api.config.McpClientCapabilities;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class McpClientCapabilitiesTest {
    @Test
    void serializesElicitationAndExperimentalCapabilities() {
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("vendor.feature", new LinkedHashMap<String, Object>());
        McpClientCapabilities capabilities = McpClientCapabilities.builder()
                .elicitation()
                .experimental(experimental)
                .build();

        Map<?, ?> json = new Gson().fromJson(capabilities.toJson(), Map.class);
        assertTrue(json.containsKey("elicitation"));
        assertEquals(experimental, capabilities.experimental);
        assertTrue(json.containsKey("experimental"));
    }

    @Test
    void doesNotAdvertiseEmptyOptionalCapabilities() {
        assertEquals("{}", McpClientCapabilities.builder().build().toJson());
    }

    @Test
    void capabilityMetadataDoesNotClaimOutboundClientSupport() {
        McpClientCapabilities capabilities = McpClientCapabilities.builder()
                .elicitation()
                .experimental(new LinkedHashMap<>())
                .build();

        assertTrue(capabilities.elicitation.isEmpty());
        assertTrue(capabilities.toMap().containsKey("elicitation"));
        assertFalse(capabilities.toMap().containsKey("request"));
        assertFalse(capabilities.toMap().containsKey("transport"));
        assertFalse(capabilities.toMap().containsKey("responseHandler"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void clientCapabilityMapsAreImmutableSnapshots() {
        Map<String, Object> elicitation = new LinkedHashMap<>();
        elicitation.put("form", true);
        McpClientCapabilities capabilities = McpClientCapabilities.builder()
                .elicitation(elicitation)
                .build();
        elicitation.put("changed", true);

        assertTrue(capabilities.elicitation.containsKey("form"));
        assertFalse(capabilities.elicitation.containsKey("changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.elicitation.put("nope", true));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nestedValuesAreCopiedAndReturnedStructuresAreUnmodifiable() {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        List<Object> nestedList = new ArrayList<>();
        Set<Object> nestedSet = new LinkedHashSet<>();
        nestedSet.add("before");
        nestedList.add(nestedSet);
        nestedMap.put("list", nestedList);
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("nested", nestedMap);

        McpClientCapabilities capabilities = McpClientCapabilities.builder()
                .experimental(experimental)
                .build();
        nestedSet.add("after");
        nestedList.add("changed");
        nestedMap.put("changed", true);

        Map<?, ?> snapshotMap = (Map<?, ?>) capabilities.experimental.get("nested");
        List<?> snapshotList = (List<?>) snapshotMap.get("list");
        Set<?> snapshotSet = (Set<?>) snapshotList.get(0);
        assertFalse(snapshotMap.containsKey("changed"));
        assertEquals(1, snapshotList.size());
        assertFalse(snapshotSet.contains("after"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map) snapshotMap).put("nope", true));
        assertThrows(UnsupportedOperationException.class, () -> ((List) snapshotList).add("nope"));
        assertThrows(UnsupportedOperationException.class, () -> ((Set) snapshotSet).add("nope"));
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void serverExperimentalMetadataIsImmutableAndConfigured() {
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("vendor.feature", true);
        McpServerConfig config = McpServerConfig.builder().experimental(experimental).build();
        experimental.put("changed", true);

        assertEquals(Boolean.TRUE, config.experimental.get("vendor.feature"));
        assertFalse(config.experimental.containsKey("changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.experimental.put("nope", true));
    }
}
