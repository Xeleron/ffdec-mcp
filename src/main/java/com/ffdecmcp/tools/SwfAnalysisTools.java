package com.ffdecmcp.tools;

import java.util.ArrayList;
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
import com.jpexs.decompiler.flash.abc.avm2.AVM2ConstantPool;
import com.jpexs.decompiler.flash.abc.avm2.parser.script.AbcIndexing;
import com.jpexs.decompiler.flash.abc.types.ClassInfo;
import com.jpexs.decompiler.flash.abc.types.InstanceInfo;
import com.jpexs.decompiler.flash.abc.types.MethodInfo;
import com.jpexs.decompiler.flash.abc.types.Multiname;
import com.jpexs.decompiler.flash.abc.types.ScriptInfo;
import com.jpexs.decompiler.flash.abc.types.traits.Trait;
import com.jpexs.decompiler.flash.abc.types.traits.TraitClass;
import com.jpexs.decompiler.flash.abc.types.traits.TraitMethodGetterSetter;
import com.jpexs.decompiler.flash.helpers.HighlightedText;
import com.jpexs.decompiler.flash.tags.ABCContainerTag;
import com.jpexs.decompiler.flash.tags.Tag;

public class SwfAnalysisTools {

    private static final Logger LOG = Logger.getLogger(SwfAnalysisTools.class.getName());
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

    private SWF getSwf(JsonObject args) throws Exception {
        String path = getStr(args, "file_path", null);
        if (path == null) {
            return sessionManager.getCurrentSwf();
        }
        return sessionManager.getSwf(path);
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

    private ScriptPack findScriptPack(ABC abc, String className, List<ABC> allAbcs) {
        String colonName = className.replace(".", ":");
        String slashName = className.replace(".", "/");
        ScriptPack sp = abc.findScriptPackByPath(colonName, allAbcs);
        if (sp == null) sp = abc.findScriptPackByPath(slashName, allAbcs);
        if (sp == null) sp = abc.findScriptPackByPath(className, allAbcs);

        if (sp == null) {
            for (ScriptPack p : abc.getScriptPacks("", allAbcs)) {
                String scriptName = p.getPathScriptName();
                if (className.equals(scriptName) || colonName.equals(scriptName) || slashName.equals(scriptName) || scriptName.replace("/", ".").equals(className)) {
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
        if (mn == null) return false;
        
        String name = mn.toString();
        // Replace :: with . for standard representation
        String normalizedName = name.replace("::", ".");
        
        if (normalizedName.equalsIgnoreCase(targetName) || name.equalsIgnoreCase(targetName) || name.endsWith("::" + targetName)) {
            return true;
        }
        
        if (mn.name_index > 0 && abc.constants != null && mn.name_index < abc.constants.getStringCount()) {
            String rawName = abc.constants.getString(mn.name_index);
            return rawName != null && rawName.equalsIgnoreCase(targetName);
        }
        return false;
    }

    public JsonObject searchSource(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String query = getStr(args, "query", null);
            if (query == null) return error("Missing 'query' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);
            
            JsonArray results = new JsonArray();
            int limit = 50;
            if (args.has("limit") && !args.get("limit").isJsonNull()) {
                limit = args.get("limit").getAsInt();
            }
            int count = 0;

            for (ABC abc : allAbcs) {
                DecompilerPool pool = new DecompilerPool();
                AbcIndexing indexing = new AbcIndexing(swf);
                for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                    if (count >= limit) break;
                    String className = sp.getPathScriptName().replace("/", ".");
                    try {
                        HighlightedText decompiled = pool.decompile(indexing, sp);
                        String source = decompiled != null ? decompiled.text : null;
                        if (source != null && source.contains(query)) {
                            results.add(className);
                            count++;
                        }
                    } catch (Exception e) {
                        // Ignore decomposition errors
                    }
                }
            }
            JsonObject result = success("Search complete. Found " + results.size() + " matches.");
            result.add("matches", results);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + results + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Search failed: " + e.getMessage());
        }
    }

    public JsonObject getClassHierarchy(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null);
            if (className == null) return error("Missing 'class_name' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);
            
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ScriptInfo si = abc.script_info.get(sp.scriptIndex);
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = abc.instance_info.get(tc.class_info);
                            JsonObject hierarchy = new JsonObject();
                            hierarchy.addProperty("class_name", className);
                            
                            String superNameStr = abc.constants.multinameToString(ii.super_index);
                            hierarchy.addProperty("superclass", superNameStr);
                            
                            JsonArray interfaces = new JsonArray();
                            if (ii.interfaces != null) {
                                for (int n : ii.interfaces) {
                                    String intfStr = abc.constants.multinameToString(n);
                                    if (intfStr != null) interfaces.add(intfStr);
                                }
                            }
                            hierarchy.add("interfaces", interfaces);
                            
                            JsonObject result = success("Class hierarchy retrieved.");
                            result.add("hierarchy", hierarchy);
                            
                            JsonArray content = result.getAsJsonArray("content");
                            JsonObject jsonText = new JsonObject();
                            jsonText.addProperty("type", "text");
                            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hierarchy) + "\n```");
                            content.add(jsonText);
                            
                            return result;
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to get class hierarchy: " + e.getMessage());
        }
    }

    public JsonObject getTraits(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null);
            if (className == null) return error("Missing 'class_name' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);

            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ScriptInfo si = abc.script_info.get(sp.scriptIndex);
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = abc.instance_info.get(tc.class_info);
                            ClassInfo ci = abc.class_info.get(tc.class_info);
                            JsonArray traitsJson = new JsonArray();

                            // Instance Traits
                            for (int i = 0; i < ii.instance_traits.traits.size(); i++) {
                                Trait trait = ii.instance_traits.traits.get(i);
                                JsonObject traitObj = formatTrait(abc, trait, "instance", i);
                                traitsJson.add(traitObj);
                            }

                            // Static Traits
                            if (ci.static_traits != null && ci.static_traits.traits != null) {
                                for (int i = 0; i < ci.static_traits.traits.size(); i++) {
                                    Trait trait = ci.static_traits.traits.get(i);
                                    JsonObject traitObj = formatTrait(abc, trait, "static", i);
                                    traitsJson.add(traitObj);
                                }
                            }

                            JsonObject result = success("Traits retrieved.");
                            result.add("traits", traitsJson);

                            JsonArray content = result.getAsJsonArray("content");
                            JsonObject jsonText = new JsonObject();
                            jsonText.addProperty("type", "text");
                            StringBuilder sb = new StringBuilder();
                            sb.append("Traits for class ").append(className).append(":\n\n");
                            for (int i = 0; i < traitsJson.size(); i++) {
                                JsonObject to = traitsJson.get(i).getAsJsonObject();
                                sb.append(String.format("[%s #%d] %s : %s\n",
                                        to.get("scope").getAsString(),
                                        to.get("index").getAsInt(),
                                        to.get("name").getAsString(),
                                        to.get("kind").getAsString()));
                                if (to.has("details")) {
                                    sb.append("  Details: ").append(to.get("details").getAsString()).append("\n");
                                }
                            }
                            jsonText.addProperty("text", sb.toString().trim());
                            content.add(jsonText);

                            return result;
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to get traits", e);
            return error("Failed to get traits: " + e.getMessage());
        }
    }

    private JsonObject formatTrait(ABC abc, Trait trait, String scope, int index) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", trait.getName(abc).toString());
        obj.addProperty("scope", scope);
        obj.addProperty("index", index);
        
        String kind = "unknown";
        String details = "";
        
        if (trait instanceof TraitMethodGetterSetter tm) {
            int traitKind = -1;
            try {
                java.lang.reflect.Field kindField = trait.getClass().getField("kind");
                traitKind = kindField.getInt(trait);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Method getKindMethod = trait.getClass().getMethod("getKind");
                    traitKind = (Integer) getKindMethod.invoke(trait);
                } catch (Exception e2) { /* ignore */ }
            }
            
            if (traitKind == Trait.TRAIT_GETTER) kind = "getter";
            else if (traitKind == Trait.TRAIT_SETTER) kind = "setter";
            else kind = "method";
            
            try {
                MethodInfo mi = abc.method_info.get(tm.method_info);
                details = "params: " + (mi.param_types != null ? mi.param_types.length : 0);
            } catch (Exception e) {
                details = "method_info: " + tm.method_info;
            }
        } else if (trait.getClass().getSimpleName().contains("Slot")) {
            kind = "slot";
            try {
                java.lang.reflect.Field typeIndexField = trait.getClass().getField("type_index");
                int typeIndex = typeIndexField.getInt(trait);
                details = "type: " + abc.constants.multinameToString(typeIndex);
            } catch (Exception e) {
                details = "slot_data";
            }
        } else if (trait instanceof TraitClass) {
            kind = "class";
        } else if (trait.getClass().getSimpleName().contains("Function")) {
            kind = "function";
        }
        
        obj.addProperty("kind", kind);
        if (!details.isEmpty()) {
            obj.addProperty("details", details);
        }
        return obj;
    }

    public JsonObject getDependencies(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null); if (className == null) return error("Missing 'class_name' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);
            
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    JsonArray deps = new JsonArray();
                    AVM2ConstantPool pool = abc.constants;
                    if (pool != null) {
                        int limit = Math.min(pool.getMultinameCount(), 100);
                        for (int i = 0; i < limit; i++) {
                            Multiname m = pool.getMultiname(i);
                            if (m != null) {
                                try {
                                    String mName = pool.multinameToString(i);
                                    if (mName != null && mName.contains(":")) deps.add(mName);
                                } catch(Exception e) {
                                    // ignore unresolvable MN
                                }
                            }
                        }
                    }
                    JsonObject result = success("Dependencies retrieved heuristically.");
                    result.add("dependencies", deps);
                    
                    JsonArray content = result.getAsJsonArray("content");
                    JsonObject jsonText = new JsonObject();
                    jsonText.addProperty("type", "text");
                    jsonText.addProperty("text", "\n```json\n" + deps + "\n```");
                    content.add(jsonText);
                    
                    return result;
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to get dependencies: " + e.getMessage());
        }
    }

    public JsonObject getConstantPool(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null); if (className == null) return error("Missing 'class_name' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);
            
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    AVM2ConstantPool pool = abc.constants;
                    JsonObject poolObj = new JsonObject();
                    
                    JsonArray strings = new JsonArray();
                    int strLimit = Math.min(pool.getStringCount(), 100);
                    for (int i = 0; i < strLimit; i++) {
                        String s = pool.getString(i);
                        if (s != null) strings.add(s);
                    }
                    
                    JsonArray ints = new JsonArray();
                    int intLimit = Math.min(pool.getIntCount(), 50);
                    for (int i = 0; i < intLimit; i++) {
                        ints.add(pool.getInt(i));
                    }
                    
                    poolObj.add("strings", strings);
                    poolObj.add("ints", ints);
                    
                    JsonObject result = success("Constant pool snippet retrieved.");
                    result.add("constant_pool", poolObj);
                    
                    JsonArray content = result.getAsJsonArray("content");
                    JsonObject jsonText = new JsonObject();
                    jsonText.addProperty("type", "text");
                    jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(poolObj) + "\n```");
                    content.add(jsonText);
                    
                    return result;
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to get constant pool: " + e.getMessage());
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
                for (ABC abc : allAbcs) {
                    ScriptPack sp = findScriptPack(abc, className, allAbcs);
                    if (sp != null) {
                        HighlightedText text = pool.decompile(indexing, sp);
                        if (text != null && text.text != null && !text.text.trim().isEmpty()) {
                            String sourceText = text.text;
                            JsonObject result = success("Decompiled " + className);
                            result.addProperty("source", sourceText);
                            
                            JsonArray content = result.getAsJsonArray("content");
                            JsonObject codeObj = new JsonObject();
                            codeObj.addProperty("type", "text");
                            codeObj.addProperty("text", "\n```actionscript\n" + sourceText + "\n```");
                            content.add(codeObj);
                            
                            return result;
                        }
                    }
                }
            } finally {
                pool.shutdown();
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Decompilation error: " + e.getMessage());
        }
    }

    public JsonObject listScripts(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            List<ABC> allAbcs = getAllAbcs(swf);
            JsonArray scripts = new JsonArray();
            int index = 0;
            for (ABC abc : allAbcs) {
                for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                    JsonObject script = new JsonObject();
                    script.addProperty("index", index++);
                    script.addProperty("name", sp.getPathScriptName());
                    scripts.add(script);
                }
            }
            JsonObject result = success("Found " + scripts.size() + " scripts.");
            result.add("scripts", scripts);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(scripts) + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Failed to list scripts: " + e.getMessage());
        }
    }

    public JsonObject decompileScript(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            int scriptIndex = args.get("script_index").getAsInt();
            List<ABC> allAbcs = getAllAbcs(swf);
            AbcIndexing indexing = new AbcIndexing(swf);
            DecompilerPool pool = new DecompilerPool();
            
            int currentIndex = 0;
            try {
                for (ABC abc : allAbcs) {
                    for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                        if (currentIndex == scriptIndex) {
                            HighlightedText text = pool.decompile(indexing, sp);
                            if (text != null && text.text != null) {
                                JsonObject result = success("Decompiled script index " + scriptIndex);
                                result.addProperty("source", text.text);
                                
                                JsonArray content = result.getAsJsonArray("content");
                                JsonObject codeObj = new JsonObject();
                                codeObj.addProperty("type", "text");
                                codeObj.addProperty("text", "\n```actionscript\n" + text.text + "\n```");
                                content.add(codeObj);
                                
                                return result;
                            }
                        }
                        currentIndex++;
                    }
                }
            } finally {
                pool.shutdown();
            }
            return error("Script index " + scriptIndex + " not found.");
        } catch (Exception e) {
            return error("Decompilation error: " + e.getMessage());
        }
    }

    public JsonObject findClass(JsonObject args) {
        return searchSource(args); // Reusing searchSource as a proxy for now
    }

    public JsonObject findMethods(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String query = args.get("query").getAsString().toLowerCase();
            List<ABC> allAbcs = getAllAbcs(swf);
            JsonArray results = new JsonArray();
            
            for (ABC abc : allAbcs) {
                for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                    String className = sp.getPathScriptName().replace("/", ".");
                    ScriptInfo si = abc.script_info.get(sp.scriptIndex);
                    // Search in traits
                    for (int traitIdx : sp.traitIndices) {
                         Trait t = si.traits.traits.get(traitIdx);
                         if (t instanceof TraitClass tc) {
                             InstanceInfo ii = abc.instance_info.get(tc.class_info);
                             for (Trait trait : ii.instance_traits.traits) {
                                 if (trait instanceof TraitMethodGetterSetter) {
                                     String mName = trait.getName(abc).toString();
                                     if (mName.toLowerCase().contains(query)) {
                                         results.add(className + "::" + mName);
                                     }
                                 }
                             }
                         }
                    }
                }
            }
            JsonObject result = success("Found " + results.size() + " method matches.");
            result.add("matches", results);
            return result;
        } catch (Exception e) {
            return error("Find methods failed: " + e.getMessage());
        }
    }

    public JsonObject resolveIdentifier(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String name = getStr(args, "name", null);
            if (name == null) return error("Missing 'name' argument.");
            List<ABC> allAbcs = getAllAbcs(swf);
            JsonArray matches = new JsonArray();

            // 1. Search for classes/scripts
            for (int abcIdx = 0; abcIdx < allAbcs.size(); abcIdx++) {
                ABC abc = allAbcs.get(abcIdx);
                for (ScriptPack sp : abc.getScriptPacks("", allAbcs)) {
                    String scriptName = sp.getPathScriptName().replace("/", ".");
                    if (scriptName.equalsIgnoreCase(name) || scriptName.endsWith("." + name) || scriptName.endsWith(":" + name)) {
                        JsonObject m = new JsonObject();
                        m.addProperty("type", "class");
                        m.addProperty("name", scriptName);
                        m.addProperty("abc_index", abcIdx);
                        m.addProperty("script_index", sp.scriptIndex);
                        matches.add(m);
                    }
                    
                    // 2. Search for traits within this script
                    ScriptInfo si = abc.script_info.get(sp.scriptIndex);
                    // Class traits (if it is a class)
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (matchesTraitName(abc, t, name)) {
                            JsonObject m = new JsonObject();
                            m.addProperty("type", "trait");
                            m.addProperty("name", t.getName(abc).toString());
                            m.addProperty("abc_index", abcIdx);
                            m.addProperty("script_index", sp.scriptIndex);
                            m.addProperty("trait_index", traitIdx);
                            m.addProperty("scope", "script");
                            matches.add(m);
                        }
                        
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = abc.instance_info.get(tc.class_info);
                            ClassInfo ci = abc.class_info.get(tc.class_info);
                            
                            // Instance traits
                            for (int i = 0; i < ii.instance_traits.traits.size(); i++) {
                                Trait it = ii.instance_traits.traits.get(i);
                                if (matchesTraitName(abc, it, name)) {
                                    JsonObject m = new JsonObject();
                                    m.addProperty("type", "trait");
                                    m.addProperty("name", it.getName(abc).toString());
                                    m.addProperty("class_name", scriptName);
                                    m.addProperty("abc_index", abcIdx);
                                    m.addProperty("instance_index", tc.class_info);
                                    m.addProperty("trait_index", i);
                                    m.addProperty("scope", "instance");
                                    matches.add(m);
                                }
                            }
                            
                            // Static traits
                            if (ci.static_traits != null) {
                                for (int i = 0; i < ci.static_traits.traits.size(); i++) {
                                    Trait st = ci.static_traits.traits.get(i);
                                    if (matchesTraitName(abc, st, name)) {
                                        JsonObject m = new JsonObject();
                                        m.addProperty("type", "trait");
                                        m.addProperty("name", st.getName(abc).toString());
                                        m.addProperty("class_name", scriptName);
                                        m.addProperty("abc_index", abcIdx);
                                        m.addProperty("class_index", tc.class_info);
                                        m.addProperty("trait_index", i);
                                        m.addProperty("scope", "static");
                                        matches.add(m);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Search for tags
            if (swf.getTags() != null) {
                for (int i = 0; i < swf.getTags().size(); i++) {
                    Tag tag = swf.getTags().get(i);
                    String tagName = tag.getTagName();
                    if (tagName.equalsIgnoreCase(name)) {
                        JsonObject m = new JsonObject();
                        m.addProperty("type", "tag");
                        m.addProperty("name", tagName);
                        m.addProperty("tag_index", i);
                        matches.add(m);
                    }
                    if (tag instanceof com.jpexs.decompiler.flash.tags.base.CharacterTag idt) {
                        if (String.valueOf(idt.getCharacterId()).equals(name)) {
                            JsonObject m = new JsonObject();
                            m.addProperty("type", "tag");
                            m.addProperty("name", tagName);
                            m.addProperty("tag_index", i);
                            m.addProperty("character_id", idt.getCharacterId());
                            matches.add(m);
                        }
                    }
                }
            }

            JsonObject result = success("Resolution complete. Found " + matches.size() + " matches.");
            result.add("matches", matches);
            
            JsonArray content = result.getAsJsonArray("content");
            JsonObject jsonText = new JsonObject();
            jsonText.addProperty("type", "text");
            jsonText.addProperty("text", "\n```json\n" + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(matches) + "\n```");
            content.add(jsonText);
            
            return result;
        } catch (Exception e) {
            return error("Resolution failed: " + e.getMessage());
        }
    }

    public JsonObject listMethods(JsonObject args) {
        return getTraits(args);
    }

    public JsonObject diffClasses(JsonObject args) {
        return error("diff_classes is not yet implemented.");
    }
}
