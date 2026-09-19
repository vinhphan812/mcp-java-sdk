package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpResource;
import io.github.vinhphan812.mcp.annotations.Resources;
import java.util.Base64;

@Resources
public final class BlobResourceExample {
    @McpResource(uri = "demo://blob-image", name = "Demo Image Blob",
            description = "Binary resource example", mimeType = "image/png")
    public String image(String ignoredUri) {
        byte[] dummyImage = new byte[]{1, 2, 3, 4}; // Dummy binary data
        return Base64.getEncoder().encodeToString(dummyImage);
    }
}
