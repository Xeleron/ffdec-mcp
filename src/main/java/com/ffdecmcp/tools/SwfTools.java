package com.ffdecmcp.tools;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ffdecmcp.SwfSessionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jpexs.decompiler.flash.DecompilerPool;
import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.abc.ABC;
import com.jpexs.decompiler.flash.abc.ScriptPack;
import com.jpexs.decompiler.flash.abc.avm2.AVM2Code;
import com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instruction;
import com.jpexs.decompiler.flash.abc.avm2.parser.script.AbcIndexing;
import com.jpexs.decompiler.flash.abc.types.ClassInfo;
import com.jpexs.decompiler.flash.abc.types.InstanceInfo;
import com.jpexs.decompiler.flash.abc.types.MethodBody;
import com.jpexs.decompiler.flash.abc.types.Multiname;
import com.jpexs.decompiler.flash.abc.types.ScriptInfo;
import com.jpexs.decompiler.flash.abc.types.traits.Trait;
import com.jpexs.decompiler.flash.abc.types.traits.TraitClass;
import com.jpexs.decompiler.flash.abc.types.traits.TraitMethodGetterSetter;
import com.jpexs.decompiler.flash.abc.types.traits.Traits;
import com.jpexs.decompiler.flash.helpers.HighlightedText;
import com.jpexs.decompiler.flash.tags.ABCContainerTag;
import com.jpexs.decompiler.flash.tags.DebugIDTag;
import com.jpexs.decompiler.flash.tags.FileAttributesTag;
import com.jpexs.decompiler.flash.tags.MetadataTag;
import com.jpexs.decompiler.flash.tags.ProductInfoTag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.types.RECT;

/**
 * High-level SWF and ActionScript 3 inspection and session management tools.
 */
public class SwfTools {

    private static final Logger LOG = Logger.getLogger(SwfTools.class.getName());
    private final SwfSessionManager sessionManager = SwfSessionManager.getInstance();

    private JsonObject success(String text) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("type", "text");
        textObj.addProperty("text", text);
        content.add(textObj);
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    private JsonObject error(String text) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("type", "text");
        textObj.addProperty("text", text);
        content.add(textObj);
        result.add("content", content);
        result.addProperty("isError", true);
        return result;
    }

    private String getStr(JsonObject args, String key, String defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : defaultValue;
    }

    private int getInt(JsonObject args, String key, int defaultValue) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : defaultValue;
    }

    private SWF getSwf(JsonObject args) throws Exception {
        String path = getStr(args, "file_path", null);
        if (path == null) {
            return sessionManager.getCurrentSwf();
        }
        return sessionManager.getSwf(path);
    }

    private String getFilePath(JsonObject args) throws Exception {
        return getStr(args, "file_path", sessionManager.getCurrentSwfPath());
    }

    private List<ABC> getAllAbcs(SWF swf) {
        List<ABC> allAbcs = new ArrayList<>();
        if (swf == null || swf.getTags() == null) return allAbcs;
        for (Tag t : swf.getTags()) {
            if (t instanceof ABCContainerTag abcContainer) {
                try {
                    ABC abc = abcContainer.getABC();
                    if (abc != null) allAbcs.add(abc);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to get ABC from tag", e);
                }
            }
        }
        return allAbcs;
    }

    public JsonObject saveSwf(JsonObject args) {
        try {
            String filePath = getFilePath(args);
            String outputPath = getStr(args, "output_path", null);
            
            if (filePath == null) {
                return error("file_path is required (or open a SWF first).");
            }

            sessionManager.saveSwf(filePath, outputPath);
            return success("Successfully saved SWF to: " + (outputPath != null ? outputPath : filePath));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to save SWF", e);
            return error("Failed to save SWF: " + e.getMessage());
        }
    }

    public JsonObject openSwf(JsonObject args) {
        try {
            String path = args.get("file_path").getAsString();
            sessionManager.getSwf(path);
            return success("SWF opened successfully: " + path);
        } catch (Exception e) {
            return error("Failed to open SWF: " + e.getMessage());
        }
    }

    public JsonObject closeSwf(JsonObject args) {
        try {
            String path = args.get("file_path").getAsString();
            boolean closed = sessionManager.closeSwf(path);
            if (closed) {
                return success("SWF closed successfully: " + path);
            } else {
                return error("SWF not found or not open: " + path);
            }
        } catch (Exception e) {
            return error("Failed to close SWF: " + e.getMessage());
        }
    }

    public JsonObject reloadSwf(JsonObject args) {
        try {
            String path = args.get("file_path").getAsString();
            sessionManager.reloadSwf(path);
            return success("SWF reloaded successfully: " + path);
        } catch (Exception e) {
            return error("Failed to reload SWF: " + e.getMessage());
        }
    }

    public JsonObject getSwfInfo(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            RECT rect = swf.getRect();
            
            JsonObject info = new JsonObject();
            info.addProperty("version", swf.version);
            info.addProperty("width", rect.getWidth());
            info.addProperty("height", rect.getHeight());
            info.addProperty("frameCount", swf.getFrameCount());

            JsonObject result = success("SWF information retrieved.");
            result.add("info", info);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(info) + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Failed to get SWF info: " + e.getMessage());
        }
    }

    public JsonObject listSessions(JsonObject args) {
        try {
            JsonArray sessions = new JsonArray();
            for (String path : sessionManager.getAllOpen().keySet()) {
                sessions.add(path);
            }
            JsonObject result = success("Active sessions retrieved.");
            result.add("sessions", sessions);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(sessions) + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Failed to list sessions: " + e.getMessage());
        }
    }

    public JsonObject listClasses(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            JsonArray classesArray = new JsonArray();
            String filter = getStr(args, "filter", null);
            if (filter != null) filter = filter.toLowerCase();

            List<ABC> allAbcs = getAllAbcs(swf);

            for (ABC abc : allAbcs) {
                for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                    String name = sp.getPathScriptName().replace("/", ".");
                    if (filter == null || name.toLowerCase().contains(filter)) {
                        classesArray.add(name);
                    }
                }
            }
            
            JsonObject result = success("Found " + classesArray.size() + " classes.");
            result.add("classes", classesArray);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            
            if (classesArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(classesArray.size(), 50); i++) {
                    sb.append("- ").append(classesArray.get(i).getAsString()).append("\n");
                }
                if (classesArray.size() > 50) {
                    sb.append("... and ").append(classesArray.size() - 50).append(" more.");
                }
                jsonText.addProperty("text", sb.toString().trim());
            } else {
                jsonText.addProperty("text", "No classes found.");
            }
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Failed to list classes: " + e.getMessage());
        }
    }

    public JsonObject decompileClass(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            AbcIndexing indexing = new AbcIndexing(swf);
            
            List<ABC> allAbcs = getAllAbcs(swf);

            DecompilerPool pool = new DecompilerPool();
            try {
                boolean classEncountered = false;
                for (ABC abc : allAbcs) {
                    ScriptPack sp = findScriptPack(abc, className, allAbcs);
                    if (sp != null) {
                        classEncountered = true;
                        HighlightedText text = pool.decompile(indexing, sp);
                        if (text != null && text.text != null && !text.text.trim().isEmpty()) {
                            String sourceText = text.text;
                            JsonObject result = success("Decompiled " + className);
                            result.addProperty("source", sourceText);
                            return result;
                        }
                    }
                }
                if (classEncountered) {
                    return error("Failed to decompile class '" + className + "'. It was found but the decompiler returned no text.");
                }
            } finally {
                pool.shutdown();
            }
            return error("Class not found: " + className + ". Make sure to use the name as it appears in list_classes.");
        } catch (Exception e) {
            return error("Decompilation error: " + e.getMessage());
        }
    }

    public JsonObject getBytecode(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String methodName = getStr(args, "method_name", null);

            JsonArray methodsArray = new JsonArray();
            boolean found = false;
            List<ABC> allAbcs = getAllAbcs(swf);

            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ScriptInfo si = abc.script_info.get(sp.scriptIndex);
                    
                    if (methodName == null || methodName.equalsIgnoreCase("script_init") || methodName.equalsIgnoreCase("<script_init>")) {
                        addMethodToResult(methodsArray, abc, si.init_index, "script_init");
                    }
                    
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = abc.instance_info.get(tc.class_info);
                            ClassInfo ci = abc.class_info.get(tc.class_info);
                            
                            if (methodName == null || methodName.equalsIgnoreCase("constructor") || methodName.equalsIgnoreCase("<init>") || methodName.equalsIgnoreCase("init")) {
                                if (ii.iinit_index != -1) {
                                    addMethodToResult(methodsArray, abc, ii.iinit_index, "constructor");
                                }
                            }
                            collectTraits(methodsArray, abc, ii.instance_traits, methodName);
                            
                            if (methodName == null || methodName.equalsIgnoreCase("cinit") || methodName.equalsIgnoreCase("<cinit>") || methodName.equalsIgnoreCase("static_init")) {
                                if (ci.cinit_index != -1) {
                                    addMethodToResult(methodsArray, abc, ci.cinit_index, "cinit");
                                }
                            }
                            collectTraits(methodsArray, abc, ci.static_traits, methodName);
                        } else if (t instanceof TraitMethodGetterSetter tmgs) {
                            String tName = t.getName(abc).toString();
                            if (methodName == null || methodName.equalsIgnoreCase(tName)) {
                                addMethodToResult(methodsArray, abc, tmgs.method_info, tName);
                            }
                        }
                    }
                    found = true;
                }
            }
            if (!found) {
                return error("Class " + className + " not found in any ABC tag.");
            }
            JsonObject result = success("Extracted bytecode for " + methodsArray.size() + " methods.");
            result.add("methods", methodsArray);
            return result;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to get bytecode", e);
            return error("Failed to get bytecode: " + e.getMessage());
        }
    }

    private void collectTraits(JsonArray methodsArray, ABC abc, Traits traits, String methodNameFilter) {
        if (traits == null || traits.traits == null) return;
        for (Trait trait : traits.traits) {
            int methodIndex = -1;
            if (trait instanceof TraitMethodGetterSetter tmgs) {
                methodIndex = tmgs.method_info;
            }

            if (methodIndex != -1) {
                Multiname mn = trait.getName(abc);
                String name = (mn != null) ? mn.toString() : "unnamed_trait";
                if (mn != null && mn.name_index > 0 && abc.constants != null && mn.name_index < abc.constants.getStringCount()) {
                    name = abc.constants.getString(mn.name_index);
                }
                if (methodNameFilter == null || (name != null && name.contains(methodNameFilter))) {
                    addMethodToResult(methodsArray, abc, methodIndex, name);
                }
            }
        }
    }

    private void addMethodToResult(JsonArray methodsArray, ABC abc, int methodIndex, String name) {
        JsonObject m = new JsonObject();
        m.addProperty("name", name);
        JsonArray codeArr = new JsonArray();
        MethodBody body = null;

        if (abc.bodies != null) {
            for (MethodBody b : abc.bodies) {
                if (b.method_info == methodIndex) {
                    body = b;
                    break;
                }
            }
        }

        if (body != null && body.getCode() instanceof AVM2Code avm2Code) {
            if (avm2Code.code != null) {
                for (AVM2Instruction ins : avm2Code.code) {
                    if (ins != null) {
                        codeArr.add(ins.toStringNoAddress(abc.constants, null));
                    }
                }
            }
        }
        m.add("pcode", codeArr);
        methodsArray.add(m);
    }

    private ScriptPack findScriptPack(ABC abc, String className, List<ABC> allAbcs) {
        String colonName = className.replace(".", ":");
        ScriptPack sp = abc.findScriptPackByPath(colonName, allAbcs);
        if (sp == null) sp = abc.findScriptPackByPath(className, allAbcs);

        if (sp == null) {
            for (ScriptPack p : abc.getScriptPacks("", allAbcs)) {
                String scriptName = p.getPathScriptName();
                if (className.equals(scriptName) || colonName.equals(scriptName)) {
                    return p;
                }
            }
        }
        
        if (sp == null) {
            for (ScriptPack p : abc.getScriptPacks("", allAbcs)) {
                if (p.abc != null && p.abc.script_info != null && p.scriptIndex >= 0 && p.scriptIndex < p.abc.script_info.size()) {
                    ScriptInfo si = p.abc.script_info.get(p.scriptIndex);
                    if (si.traits != null && si.traits.traits != null) {
                        for (Trait t : si.traits.traits) {
                            if (matchesTraitName(abc, t, className)) return p;
                        }
                    }
                }
            }
        }
        return sp;
    }

    private boolean matchesTraitName(ABC abc, Trait trait, String targetName) {
        Multiname mn = trait.getName(abc);
        String name = mn.toString();
        if (name.equalsIgnoreCase(targetName) || name.endsWith("::" + targetName)) return true;
        if (mn.name_index > 0 && mn.name_index < abc.constants.getStringCount()) {
            String rawName = abc.constants.getString(mn.name_index);
            return rawName != null && rawName.equalsIgnoreCase(targetName);
        }
        return false;
    }

    public JsonObject searchPcode(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String pattern = args.get("pattern").getAsString().toLowerCase();
            int maxResults = getInt(args, "max_results", 50);

            JsonArray resultsArray = new JsonArray();
            for (Tag tag : swf.getTags()) {
                if (tag instanceof ABCContainerTag abcContainer) {
                    ABC abc;
                    try { abc = abcContainer.getABC(); } catch (Exception e) { continue; }
                    if (abc == null || abc.bodies == null) continue;

                    for (MethodBody body : abc.bodies) {
                        if (body.getCode() instanceof AVM2Code avm2Code && avm2Code.code != null) {
                            for (AVM2Instruction ins : avm2Code.code) {
                                if (ins == null) continue;
                                if (ins.toString().toLowerCase().contains(pattern)) {
                                    JsonObject match = new JsonObject();
                                    match.addProperty("abc_tag", tag.getName());
                                    match.addProperty("type", "instruction");
                                    match.addProperty("method_index", body.method_info);
                                    match.addProperty("instruction", ins.toString());
                                    resultsArray.add(match);
                                    if (resultsArray.size() >= maxResults) break;
                                }
                            }
                        }
                        if (resultsArray.size() >= maxResults) break;
                    }

                    if (resultsArray.size() < maxResults) {
                        for (int i = 1; i < abc.constants.getStringCount(); i++) {
                            String s = abc.constants.getString(i);
                            if (s != null && s.toLowerCase().contains(pattern)) {
                                JsonObject match = new JsonObject();
                                match.addProperty("abc_tag", tag.getName());
                                match.addProperty("type", "constant_pool_string");
                                match.addProperty("value", s);
                                resultsArray.add(match);
                                if (resultsArray.size() >= maxResults) break;
                            }
                        }
                    }
                }
                if (resultsArray.size() >= maxResults) break;
            }
            JsonObject result = success("Found " + resultsArray.size() + " matches.");
            result.add("matches", resultsArray);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(resultsArray) + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Search error: " + e.getMessage());
        }
    }

    public JsonObject listTags(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            JsonArray tagsArray = new JsonArray();
            int index = 0;
            for (Tag tag : swf.getTags()) {
                JsonObject t = new JsonObject();
                t.addProperty("index", index++);
                t.addProperty("id", tag.getId());
                t.addProperty("name", tag.getName());
                t.addProperty("type", tag.getClass().getSimpleName());
                tagsArray.add(t);
            }
            
            JsonObject result = success("Found " + tagsArray.size() + " tags.");
            result.add("tags", tagsArray);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            
            if (tagsArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(tagsArray.size(), 100); i++) {
                    JsonObject t = tagsArray.get(i).getAsJsonObject();
                    sb.append(String.format("[%d] %s (ID: %d, Type: %s)\n", 
                        t.get("index").getAsInt(), 
                        t.get("name").getAsString(), 
                        t.get("id").getAsInt(),
                        t.get("type").getAsString()));
                }
                if (tagsArray.size() > 100) {
                    sb.append("... and ").append(tagsArray.size() - 100).append(" more.");
                }
                jsonText.addProperty("text", sb.toString().trim());
            } else {
                jsonText.addProperty("text", "No tags found.");
            }
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Failed to list tags: " + e.getMessage());
        }
    }
}