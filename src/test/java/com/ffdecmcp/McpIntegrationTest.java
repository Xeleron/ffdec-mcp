package com.ffdecmcp;

import com.ffdecmcp.tools.ToolRegistry;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class McpIntegrationTest {

    private ToolRegistry registry;
    private Path swfPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        registry = new ToolRegistry();
        
        // Copy a sample SWF from resources to temp dir
        swfPath = tempDir.resolve("sample.swf");
        try (InputStream is = getClass().getResourceAsStream("/sample.swf")) {
            if (is == null) {
                // Point to a real SWF on disk if resource not found
                Path realSwf = java.nio.file.Paths.get("/home/marko/ffdec-mcp/original_spider.swf");
                if (java.nio.file.Files.exists(realSwf)) {
                    java.nio.file.Files.copy(realSwf, swfPath);
                } else {
                    fail("Sample SWF not found at " + realSwf);
                }
            } else {
                Files.copy(is, swfPath);
            }
        }
    }

    @Test
    void testSessionPersistence() throws Exception {
        // 1. Open SWF explicitly
        JsonObject openArgs = new JsonObject();
        openArgs.addProperty("file_path", swfPath.toString());
        JsonObject openResult = registry.callTool("open_swf", openArgs);
        assertFalse(openResult.get("isError").getAsBoolean(), "open_swf failed: " + openResult);

        // 2. Call list_classes WITHOUT file_path - should use active session
        JsonObject listArgs = new JsonObject();
        JsonObject listResult = registry.callTool("list_classes", listArgs);
        assertFalse(listResult.get("isError").getAsBoolean(), "list_classes failed without file_path: " + listResult);
        
        // 3. Call get_swf_info WITHOUT file_path
        JsonObject infoArgs = new JsonObject();
        JsonObject infoResult = registry.callTool("get_swf_info", infoArgs);
        assertFalse(infoResult.get("isError").getAsBoolean(), "get_swf_info failed without file_path: " + infoResult);
    }
}
