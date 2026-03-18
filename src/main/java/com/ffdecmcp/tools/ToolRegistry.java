package com.ffdecmcp.tools;

import com.google.gson.*;

import java.util.*;
import java.util.function.Function;

/**
 * Registry of all MCP tools. Each tool has a name, description,
 * JSON Schema for its parameters, and an implementation function.
 */
public class ToolRegistry {

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();
    private final SwfTools swfTools = new SwfTools();
    private final SwfAnalysisTools analysisTools = new SwfAnalysisTools();
    private final SwfModificationTools modificationTools = new SwfModificationTools();

    public ToolRegistry() {
        registerBaseTools();
        registerAnalysisTools();
        registerModificationTools();
        registerTagSymbolTools();
    }

    private void registerBaseTools() {
        // ── open_swf ──
        register("open_swf",
                "Open a SWF file for analysis and manipulation. Must be called before other operations.",
                schema(
                        required("file_path", "string", "Absolute path to the .swf file")
                ),
                swfTools::openSwf
        );

        // ── close_swf ──
        register("close_swf",
                "Close an open SWF session and free its resources.",
                schema(
                        required("file_path", "string", "Absolute path to the open .swf file")
                ),
                swfTools::closeSwf
        );

        // ── reload_swf ──
        register("reload_swf",
                "Force-reload a SWF from disk, resetting any unsaved memory patches.",
                schema(
                        required("file_path", "string", "Absolute path to the .swf file")
                ),
                swfTools::reloadSwf
        );

        // ── list_sessions ──
        register("list_sessions",
                "List all currently open SWF sessions by file path.",
                schema(),
                swfTools::listSessions
        );

        // ── list_classes ──
        register("list_classes",
                "List all ActionScript 3 classes/scripts in the SWF. Returns fully qualified class names.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        optional("filter", "string", "Optional substring filter for class names")
                ),
                swfTools::listClasses
        );

        // ── decompile_class ──
        register("decompile_class",
                "Decompile an AS3 class to ActionScript source code.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name (e.g. 'com.example.MyClass')")
                ),
                analysisTools::decompileClass
        );

        // ── get_bytecode ──
        register("get_bytecode",
                "Get the P-code (AVM2 bytecode disassembly) for a specific method in a class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        optional("method_name", "string", "Method name. If omitted, returns all methods' bytecode.")
                ),
                swfTools::getBytecode
        );

        // ── search_pcode ──
        register("search_pcode",
                "Search all P-code (bytecode) in the SWF for a pattern. Useful for finding specific instructions or constants.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("pattern", "string", "Text pattern to search for in P-code (case-insensitive)"),
                        optional("max_results", "integer", "Maximum number of results to return (default 50)")
                ),
                swfTools::searchPcode
        );

        // ── save_swf ──
        register("save_swf",
                "Save the modified SWF to disk. If no output_path is given, overwrites the original file.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        optional("output_path", "string", "Output path. If omitted, overwrites original.")
                ),
                swfTools::saveSwf
        );

        // ── list_tags ──
        register("list_tags",
                "List all tags in the SWF with their types, showing the internal structure.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file")
                ),
                swfTools::listTags
        );

        // ── get_swf_info ──
        register("get_swf_info",
                "Get metadata and header info about the SWF (version, dimensions, frame rate, etc.).",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file")
                ),
                swfTools::getSwfInfo
        );
    }

    private void registerModificationTools() {
        // ── patch_class ──
        register("patch_class",
                "Replace the whole ActionScript source code of a class. The SWF will recompile the class from the provided source.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("source_code", "string", "New ActionScript source code for the class")
                ),
                modificationTools::patchClass
        );

        // ── patch_method ──
        register("patch_method",
                "Replace the ActionScript source code of a specific method. This tool targets a single method within a class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("method_name", "string", "Name of the method to patch"),
                        required("new_source", "string", "New ActionScript source code for the method body (inside the braces)")
                ),
                modificationTools::patchMethod
        );

        // ── patch_bytecode ──
        register("patch_bytecode",
                "Replace the P-code (raw AVM2 bytecode) of a method directly. Use get_bytecode first to see current P-code format.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("method_name", "string", "Name of the method to patch"),
                        required("pcode", "string", "New P-code assembly to replace the method body")
                ),
                modificationTools::patchBytecode
        );

        // ── inject_class ──
        register("inject_class",
                "Inject a new AS3 class into the SWF by providing full ActionScript source. Creates a new DoABC tag with the class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name for the new class"),
                        required("source_code", "string", "Full ActionScript 3 source code for the class")
                ),
                modificationTools::injectClass
        );

        // ── inject_abc_from_file ──
        register("inject_abc_from_file",
                "Inject one or more ABC tags from another SWF or .abc file directly into this SWF.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("abc_file_path", "string", "Absolute path to the .abc or .swf file to import from")
                ),
                modificationTools::injectAbcFromFile
        );

        // ── add_method ──
        register("add_method",
                "Append a new method to an existing class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("method_source", "string", "ActionScript source for the new method")
                ),
                modificationTools::addMethod
        );

        // ── remove_trait ──
        register("remove_trait",
                "Remove a method or property trait from a class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("trait_name", "string", "Name of the trait to remove")
                ),
                modificationTools::removeTrait
        );

        // ── edit_constants ──
        register("edit_constants",
                "Edit string constants in the ABC constant pool.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("search_pattern", "string", "Substring to search for in constants"),
                        required("replacement_text", "string", "Replacement text")
                ),
                modificationTools::editConstants
        );

        // ── rename_identifier ──
        register("rename_identifier",
                "Rename a class, method, or field consistently across the entire SWF.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("old_name", "string", "Current name of the identifier"),
                        required("new_name", "string", "New name for the identifier")
                ),
                modificationTools::renameIdentifier
        );

        // ── add_field ──
        register("add_field",
                "Add a new field (variable) to an existing class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("field_declaration", "string", "ActionScript declaration, e.g. 'public var myVar:int = 0;'")
                ),
                modificationTools::addField
        );

        // ── delete_class ──
        register("delete_class",
                "Remove entire class from ABC.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name")
                ),
                modificationTools::deleteClass
        );

        // ── replace_superclass ──
        register("replace_superclass",
                "Change the superclass of an existing class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("new_superclass", "string", "Fully qualified name of the new superclass")
                ),
                modificationTools::replaceSuperclass
        );

        // ── add_interface ──
        register("add_interface",
                "Add an interface implementation to an existing class.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("interface_name", "string", "Fully qualified name of the interface")
                ),
                modificationTools::addInterface
        );

        // ── modify_class_flags ──
        register("modify_class_flags",
                "Toggle class flags like sealed, final, and interface.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("class_name", "string", "Fully qualified class name"),
                        required("flags", "integer", "New bitmask for class flags (InstanceInfo flags)")
                ),
                modificationTools::modifyClassFlags
        );
    }

    private void registerTagSymbolTools() {
        // ── get_tag_details ──
        register("get_tag_details",
                "Get detailed metadata for a specific tag by its index in the SWF.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("tag_index", "integer", "Index of the tag in the SWF (0-based)")
                ),
                modificationTools::getTagDetails
        );

        // ── remove_tag ──
        register("remove_tag",
                "Remove a tag from the SWF by its index.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("tag_index", "integer", "Index of the tag to remove")
                ),
                modificationTools::removeTag
        );

        // ── list_symbols ──
        register("list_symbols",
                "List all SymbolClass associations (Character ID ↔ Class Name).",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file")
                ),
                modificationTools::listSymbols
        );

        // ── modify_symbol ──
        register("modify_symbol",
                "Add, remove, or change SymbolClass entries (association between character ID and class name).",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("character_id", "integer", "Character ID (0 for global script context)"),
                        optional("class_name", "string", "Fully qualified class name. If omitted or empty, removes the association.")
                ),
                modificationTools::modifySymbol
        );

        // ── modify_swf_header ──
        register("modify_swf_header",
                "Modify basic SWF header settings (version, frame rate, dimensions).",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        optional("version", "integer", "SWF version"),
                        optional("frame_rate", "number", "Frame rate (fps)"),
                        optional("width", "integer", "Width in pixels"),
                        optional("height", "integer", "Height in pixels")
                ),
                modificationTools::modifySwfHeader
        );
    }

    private void registerAnalysisTools() {
        register("search_source",
                "Search across all AS3 decompiled source code in the SWF.",
                schema(
                        required("query", "string", "The string to search for"),
                        optional("file_path", "string", "Path to the SWF file"),
                        optional("limit", "integer", "Max results to return (default 50)")
                ),
                analysisTools::searchSource
        );

        register("get_class_hierarchy",
                "Get the superclass and interfaces for a specific class.",
                schema(
                        required("class_name", "string", "The class name (e.g., flash.display.Sprite)"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::getClassHierarchy
        );

        register("list_methods",
                "List all methods (instance and static) of a specific class. (Use get_traits)",
                schema(
                        required("class_name", "string", "The class name (e.g., flash.display.Sprite)"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::listMethods
        );

        register("get_traits",
                "List all traits (methods, fields, getters/setters) of a specific class.",
                schema(
                        required("class_name", "string", "The class name (e.g., flash.display.Sprite)"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::listMethods
        );

        register("get_dependencies",
                "List classes that a specific class depends on.",
                schema(
                        required("class_name", "string", "The class name (e.g., com.example.Main)"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::getDependencies
        );

        register("get_constant_pool",
                "Get a snippet of the constant pool (strings, ints) for a class.",
                schema(
                        required("class_name", "string", "The class name (e.g., com.example.Main)"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::getConstantPool
        );

        register("list_scripts",
                "List all AS3 scripts in the SWF.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file")
                ),
                analysisTools::listScripts
        );

        register("decompile_script",
                "Decompile a specific AS3 script to ActionScript source code.",
                schema(
                        optional("file_path", "string", "Path to the opened .swf file"),
                        required("script_index", "integer", "Index of the script (from list_scripts)")
                ),
                analysisTools::decompileScript
        );

        register("find_class",
                "Search for classes matching a query.",
                schema(
                        required("query", "string", "Search query"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::findClass
        );

        register("find_methods",
                "Find methods matching a query.",
                schema(
                        required("query", "string", "Search query"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::findMethods
        );

        register("diff_classes",
                "Compare two classes for differences.",
                schema(
                        required("class1", "string", "First class name"),
                        required("class2", "string", "Second class name"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::diffClasses
        );

        register("resolve_identifier",
                "Resolve a name (class, method, symbol, tag) to its internal indices and IDs.",
                schema(
                        required("name", "string", "The name or ID to resolve"),
                        optional("file_path", "string", "Path to the SWF file")
                ),
                analysisTools::resolveIdentifier
        );
    }

    // ── Schema builder helpers ──

    private record PropDef(String name, String type, String description, boolean required) {}

    private PropDef required(String name, String type, String description) {
        return new PropDef(name, type, description, true);
    }

    private PropDef optional(String name, String type, String description) {
        return new PropDef(name, type, description, false);
    }

    private JsonObject schema(PropDef... props) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonArray requiredArr = new JsonArray();

        for (PropDef p : props) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p.type);
            prop.addProperty("description", p.description);
            properties.add(p.name, prop);
            if (p.required) {
                requiredArr.add(p.name);
            }
        }

        schema.add("properties", properties);
        if (requiredArr.size() > 0) {
            schema.add("required", requiredArr);
        }
        return schema;
    }

    // ── Registration ──

    private record ToolDef(String name, String description, JsonObject inputSchema,
                           Function<JsonObject, JsonObject> handler) {}

    private void register(String name, String description, JsonObject inputSchema,
                          Function<JsonObject, JsonObject> handler) {
        tools.put(name, new ToolDef(name, description, inputSchema, handler));
    }

    /**
     * Returns the tool definitions array for tools/list response.
     */
    public JsonArray getToolDefinitions() {
        JsonArray arr = new JsonArray();
        for (ToolDef tool : tools.values()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name);
            t.addProperty("description", tool.description);
            t.add("inputSchema", tool.inputSchema);
            arr.add(t);
        }
        return arr;
    }

    /**
     * Calls a tool by name with the given arguments.
     */
    public JsonObject callTool(String name, JsonObject arguments) {
        ToolDef tool = tools.get(name);
        if (tool == null) {
            JsonObject err = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "Unknown tool: " + name);
            content.add(text);
            err.add("content", content);
            err.addProperty("isError", true);
            return err;
        }
        return tool.handler.apply(arguments);
    }
}
