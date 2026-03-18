package com.ffdecmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class SwfToolsTest {

    private ToolRegistry registry;
    private Path swfPath;
    private static final String ORIGINAL_SWF = "/home/marko/ffdec-mcp/original_spider.swf";

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        registry = new ToolRegistry();
        
        swfPath = tempDir.resolve("test.swf");
        Path realSwf = Paths.get(ORIGINAL_SWF);
        if (Files.exists(realSwf)) {
            Files.copy(realSwf, swfPath);
        } else {
            // Try to load from resources if disk path fails
            try (InputStream is = getClass().getResourceAsStream("/sample.swf")) {
                if (is != null) {
                    Files.copy(is, swfPath);
                } else {
                    fail("Could not find a sample SWF file for testing.");
                }
            }
        }

        // Open the SWF
        JsonObject openArgs = new JsonObject();
        openArgs.addProperty("file_path", swfPath.toString());
        JsonObject result = registry.callTool("open_swf", openArgs);
        assertFalse(result.get("isError").getAsBoolean(), "Failed to open SWF: " + result);
    }

    @Test
    void testListClasses() {
        JsonObject result = registry.callTool("list_classes", new JsonObject());
        assertFalse(result.get("isError").getAsBoolean(), "list_classes failed: " + result);
        assertTrue(result.has("classes"), "Result should have 'classes' field");
        JsonArray classes = result.getAsJsonArray("classes");
        assertTrue(classes.size() > 0, "SWF should have at least one class");
    }

    @Test
    void testGetSwfInfo() {
        JsonObject result = registry.callTool("get_swf_info", new JsonObject());
        assertFalse(result.get("isError").getAsBoolean(), "get_swf_info failed: " + result);
        assertTrue(result.has("info"), "Result should have 'info'");
        JsonObject info = result.getAsJsonObject("info");
        assertTrue(info.has("version"), "Info should have 'version'");
        assertTrue(info.has("frameCount"), "Info should have 'frameCount'");
    }

    @Test
    void testListTags() {
        JsonObject result = registry.callTool("list_tags", new JsonObject());
        assertFalse(result.get("isError").getAsBoolean(), "list_tags failed: " + result);
        assertTrue(result.has("tags"), "Result should have 'tags'");
        JsonArray tags = result.getAsJsonArray("tags");
        assertTrue(tags.size() > 0, "SWF should have tags");
    }

    @Test
    void testDecompileClass() {
        // First get a class name
        JsonObject listResult = registry.callTool("list_classes", new JsonObject());
        String className = listResult.getAsJsonArray("classes").get(0).getAsString();

        JsonObject decompileArgs = new JsonObject();
        decompileArgs.addProperty("class_name", className);
        JsonObject result = registry.callTool("decompile_class", decompileArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "decompile_class failed for " + className + ": " + result);
        assertTrue(result.has("source"), "Result should have 'source'");
        assertFalse(result.get("source").getAsString().isEmpty(), "Source should not be empty");
    }

    @Test
    void testGetBytecode() {
        // First get a class name
        JsonObject listResult = registry.callTool("list_classes", new JsonObject());
        String className = listResult.getAsJsonArray("classes").get(0).getAsString();

        JsonObject bytecodeArgs = new JsonObject();
        bytecodeArgs.addProperty("class_name", className);
        JsonObject result = registry.callTool("get_bytecode", bytecodeArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "get_bytecode failed for " + className + ": " + result);
        assertTrue(result.has("methods"), "Result should have 'methods'");
        JsonArray methods = result.getAsJsonArray("methods");
        assertTrue(methods.size() > 0, "Should find at least one method (the class itself or constructor)");
    }

    @Test
    void testSearchPcode() {
        JsonObject searchArgs = new JsonObject();
        searchArgs.addProperty("pattern", "pushscope"); // Common AVM2 instruction
        JsonObject result = registry.callTool("search_pcode", searchArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "search_pcode failed: " + result);
        assertTrue(result.has("matches"), "Result should have 'matches'");
    }

    @Test
    void testSaveSwf(@TempDir Path tempDir) {
        Path outPath = tempDir.resolve("saved.swf");
        JsonObject saveArgs = new JsonObject();
        saveArgs.addProperty("output_path", outPath.toString());
        JsonObject result = registry.callTool("save_swf", saveArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "save_swf failed: " + result);
        assertTrue(Files.exists(outPath), "Saved file should exist");
    }

    @Test
    void testInjectClass() {
        JsonObject injectArgs = new JsonObject();
        injectArgs.addProperty("class_name", "com.test.InjectTest");
        injectArgs.addProperty("source_code", "package com.test { public class InjectTest { public function hello():void { trace(\"hello\"); } } }");
        JsonObject result = registry.callTool("inject_class", injectArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "inject_class failed: " + result);
    }

    @Test
    void testPatchClass() {
        // Find a class to patch
        JsonObject listResult = registry.callTool("list_classes", new JsonObject());
        String className = listResult.getAsJsonArray("classes").get(0).getAsString();

        JsonObject decompileArgs = new JsonObject();
        decompileArgs.addProperty("class_name", className);
        JsonObject decompileResult = registry.callTool("decompile_class", decompileArgs);
        String source = decompileResult.get("source").getAsString();

        JsonObject patchArgs = new JsonObject();
        patchArgs.addProperty("class_name", className);
        patchArgs.addProperty("source_code", source.replace("public class", "/* patched */ public class"));
        JsonObject result = registry.callTool("patch_class", patchArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "patch_class failed for " + className + ": " + result);
    }

    @Test
    void testRemoveTrait() {
        // First inject a class with a trait
        testInjectClass();

        JsonObject removeArgs = new JsonObject();
        removeArgs.addProperty("class_name", "com.test.InjectTest");
        removeArgs.addProperty("trait_name", "hello");
        JsonObject result = registry.callTool("remove_trait", removeArgs);
        
        assertFalse(result.get("isError").getAsBoolean(), "remove_trait failed: " + result);
    }

    @Test
    void testPatchMethod() {
        // Inject a class, then patch one of its methods
        testInjectClass();

        JsonObject patchArgs = new JsonObject();
        patchArgs.addProperty("class_name", "com.test.InjectTest");
        patchArgs.addProperty("method_name", "hello");
        patchArgs.addProperty("new_source", "trace(\"patched hello\");");
        JsonObject result = registry.callTool("patch_method", patchArgs);

        assertFalse(result.get("isError").getAsBoolean(), "patch_method failed: " + result);
    }

    @Test
    void testAddMethod() {
        // Inject a class, then add a new method to it
        testInjectClass();

        JsonObject addArgs = new JsonObject();
        addArgs.addProperty("class_name", "com.test.InjectTest");
        addArgs.addProperty("method_source", "public function newMethod():String { return \"added\"; }");
        JsonObject result = registry.callTool("add_method", addArgs);

        assertFalse(result.get("isError").getAsBoolean(), "add_method failed: " + result);
    }

    @Test
    void testEditConstants() {
        // Inject a class with a known string, then edit it
        JsonObject injectArgs = new JsonObject();
        injectArgs.addProperty("class_name", "com.test.ConstTest");
        injectArgs.addProperty("source_code", "package com.test { public class ConstTest { public function greet():String { return \"MAGIC_STRING_42\"; } } }");
        JsonObject injectResult = registry.callTool("inject_class", injectArgs);
        assertFalse(injectResult.get("isError").getAsBoolean(), "inject_class for constant test failed");

        JsonObject editArgs = new JsonObject();
        editArgs.addProperty("search_pattern", "MAGIC_STRING_42");
        editArgs.addProperty("replacement_text", "REPLACED_VALUE");
        JsonObject result = registry.callTool("edit_constants", editArgs);

        assertFalse(result.get("isError").getAsBoolean(), "edit_constants failed: " + result);
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("replaced") || text.contains("Successfully"), "Should report successful replacement");
    }

    @Test
    void testPatchBytecode() {
        // Inject a class with a known method so we can target it reliably
        testInjectClass();

        // Get current bytecode for the 'hello' method
        JsonObject bcArgs = new JsonObject();
        bcArgs.addProperty("class_name", "com.test.InjectTest");
        bcArgs.addProperty("method_name", "hello");
        JsonObject bcResult = registry.callTool("get_bytecode", bcArgs);
        assertFalse(bcResult.get("isError").getAsBoolean(), "get_bytecode failed: " + bcResult);
        JsonArray methods = bcResult.getAsJsonArray("methods");
        assertTrue(methods.size() > 0, "Should find the 'hello' method");

        // Join the pcode instructions into a single string
        JsonObject firstMethod = methods.get(0).getAsJsonObject();
        JsonArray codeArr = firstMethod.getAsJsonArray("pcode");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codeArr.size(); i++) {
            sb.append(codeArr.get(i).getAsString()).append("\n");
        }
        System.out.println("DEBUG PCODE:\n" + sb);

        JsonObject patchArgs = new JsonObject();
        patchArgs.addProperty("class_name", "com.test.InjectTest");
        patchArgs.addProperty("method_name", "hello");
        patchArgs.addProperty("pcode", sb.toString());
        JsonObject result = registry.callTool("patch_bytecode", patchArgs);

        assertFalse(result.get("isError").getAsBoolean(), "patch_bytecode (identity) failed: " + result);
    }

}
