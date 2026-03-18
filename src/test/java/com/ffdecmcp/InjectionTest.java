package com.ffdecmcp;

import com.ffdecmcp.tools.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

public class InjectionTest {
    private ToolRegistry registry;
    private final String swfPath = "/home/marko/ffdec-mcp/original_spider.swf";

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void testInjectClass() throws Exception {
        JsonObject openArgs = new JsonObject();
        openArgs.addProperty("file_path", swfPath);
        registry.callTool("open_swf", openArgs);

        JsonObject injectArgs = new JsonObject();
        injectArgs.addProperty("class_name", "com.test.MyInjectedClass");
        injectArgs.addProperty("source_code", "package com.test { public class MyInjectedClass { public function MyInjectedClass() { trace('injected'); } } }");
        
        JsonObject result = registry.callTool("inject_class", injectArgs);
        System.out.println("Inject result: " + result);
        assertFalse(result.get("isError").getAsBoolean(), "Injection failed: " + result);

        // Verify it's in the list immediately
        JsonObject listArgs = new JsonObject();
        JsonObject listImmediateResult = registry.callTool("list_classes", listArgs);
        // Actually list_classes returns:
        // JsonObject result = success("Found " + classesArray.size() + " classes.");
        // result.add("classes", classesArray);
        
        JsonArray classesData = listImmediateResult.get("classes").getAsJsonArray();
        System.out.println("Found " + classesData.size() + " classes.");
        for (int i = 0; i < Math.min(10, classesData.size()); i++) {
            System.out.println("Class " + i + ": " + classesData.get(i).getAsString());
        }
        for (int i = Math.max(0, classesData.size() - 10); i < classesData.size(); i++) {
            System.out.println("Class " + i + ": " + classesData.get(i).getAsString());
        }
        
        boolean found = false;
        for (int i = 0; i < classesData.size(); i++) {
            if (classesData.get(i).getAsString().contains("MyInjectedClass")) {
                found = true;
                System.out.println("FOUND INJECTED CLASS: " + classesData.get(i).getAsString());
                break;
            }
        }
        assertTrue(found, "Class list should contain the injected class immediately.");
        
        // Save to a temporary file to verify it doesn't crash on saving
        Path out = Paths.get("/tmp/injected_test.swf");
        Files.deleteIfExists(out);
        JsonObject saveArgs = new JsonObject();
        saveArgs.addProperty("output_path", out.toString());
        JsonObject saveResult = registry.callTool("save_swf", saveArgs);
        System.out.println("Save result: " + saveResult);
        assertFalse(saveResult.get("isError").getAsBoolean(), "Save failed: " + saveResult);
        
        assertTrue(Files.exists(out), "Output SWF should exist");
        
        // Re-open and verify class list contains the new class
        registry = new ToolRegistry();        // Re-open (force refresh by closing first)
        JsonObject closeArgs = new JsonObject();
        closeArgs.addProperty("file_path", swfPath); // Close original
        // registry.callTool("close_swf", closeArgs); // wait, I don't have close_swf tool yet
        
        // Let's just use a fresh ToolRegistry to be absolutely sure
        registry = new ToolRegistry();
        openArgs.addProperty("file_path", out.toString());
        registry.callTool("open_swf", openArgs);
        
        JsonObject listResult = registry.callTool("list_classes", new JsonObject());
        JsonArray classesData2 = listResult.get("classes").getAsJsonArray();
        boolean found2 = false;
        for (int i = 0; i < classesData2.size(); i++) {
            if (classesData2.get(i).getAsString().contains("MyInjectedClass")) {
                found2 = true;
                System.out.println("FOUND INJECTED CLASS IN SAVED SWF: " + classesData2.get(i).getAsString());
                break;
            }
        }
        assertTrue(found2, "Class list should contain the injected class in the saved SWF. Found " + classesData2.size() + " classes.");
        
        // Final check: decompile the injected class
        JsonObject decompileArgs = new JsonObject();
        decompileArgs.addProperty("class_name", "com.test.MyInjectedClass");
        JsonObject decompileResult = registry.callTool("decompile_class", decompileArgs);
        if (decompileResult.get("isError").getAsBoolean()) {
            System.out.println("DECOMPILE FAILED, trying without package...");
            decompileArgs.addProperty("class_name", "MyInjectedClass");
            decompileResult = registry.callTool("decompile_class", decompileArgs);
        }
        
        assertFalse(decompileResult.get("isError").getAsBoolean(), "Decompile failed: " + decompileResult);
        String decompiledCode = decompileResult.get("source").getAsString();
        System.out.println("Decompiled Code:\n" + decompiledCode);
        assertTrue(decompiledCode.contains("trace(\"injected\")") || decompiledCode.contains("trace('injected')"), "Decompiled code should contain the injected logic.");
        System.out.println("Decompiled successfully!");
        
        Files.deleteIfExists(out);
    }
}
