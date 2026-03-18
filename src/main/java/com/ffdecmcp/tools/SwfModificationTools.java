package com.ffdecmcp.tools;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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
import com.jpexs.decompiler.flash.abc.avm2.AVM2ConstantPool;
import com.jpexs.decompiler.flash.abc.avm2.parser.pcode.ASM3Parser;
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
import com.jpexs.decompiler.flash.importers.As3ScriptReplacerFactory;
import com.jpexs.decompiler.flash.importers.As3ScriptReplacerInterface;
import com.jpexs.decompiler.flash.importers.FFDecAs3ScriptReplacer;
import com.jpexs.decompiler.flash.tags.ABCContainerTag;
import com.jpexs.decompiler.flash.tags.DoABC2Tag;
import com.jpexs.decompiler.flash.tags.Tag;
import com.jpexs.decompiler.flash.types.RECT;

/**
 * Tools for modifying SWF ActionScript 3 code beyond simple recompilation.
 * E.g. structure changes, constant pool edits, etc.
 */
public class SwfModificationTools {

    private static final Logger LOG = Logger.getLogger(SwfModificationTools.class.getName());
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

    private String getTraitMethodName(ABC abc, Trait trait) {
        Multiname mn = trait.getName(abc);
        if (mn == null) return "unnamed_trait";
        if (mn.name_index > 0 && abc.constants != null && mn.name_index < abc.constants.getStringCount()) {
            return abc.constants.getString(mn.name_index);
        }
        return mn.toString();
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
        for (Tag tag : swf.getTags()) {
            if (tag instanceof ABCContainerTag abcContainer) {
                ABC abc = abcContainer.getABC();
                if (abc == null) continue;
                allAbcs.add(abc);
            }
        }
        return allAbcs;
    }

    private boolean matchesTraitName(ABC abc, Trait trait, String targetName) {
        Multiname mn = trait.getName(abc);
        if (mn == null) return false;
        
        String name = mn.toString();
        // Replace :: with . for standard class representation
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
                            if (matchesTraitName(abc, t, className)) {
                                return p;
                            }
                        }
                    }
                }
            }
        }
        return sp;
    }

    private int getOrCreateMultiname(ABC abc, String qualifiedName) {
        String nsName = "";
        String localName = qualifiedName;
        if (qualifiedName.contains(".")) {
            int lastDot = qualifiedName.lastIndexOf(".");
            nsName = qualifiedName.substring(0, lastDot);
            localName = qualifiedName.substring(lastDot + 1);
        } else if (qualifiedName.contains("::")) {
            int lastCol = qualifiedName.lastIndexOf("::");
            nsName = qualifiedName.substring(0, lastCol);
            localName = qualifiedName.substring(lastCol + 2);
        }

        // Try to find existing
        for (int i = 1; i < abc.constants.getMultinameCount(); i++) {
            Multiname mn = abc.constants.getMultiname(i);
            if (mn != null && mn.kind == Multiname.QNAME) {
                String mnLocal = abc.constants.getString(mn.name_index);
                if (localName.equals(mnLocal)) {
                    com.jpexs.decompiler.flash.abc.types.Namespace ns = abc.constants.getNamespace(mn.namespace_index);
                    if (ns != null && ns.kind == com.jpexs.decompiler.flash.abc.types.Namespace.KIND_PACKAGE) {
                        String nsRaw = abc.constants.getString(ns.name_index);
                        if (nsName.equals(nsRaw)) {
                            return i;
                        }
                    }
                }
            }
        }

        // Create new
        int nsNameIndex = abc.constants.addString(nsName);
        int nsIndex = abc.constants.addNamespace(com.jpexs.decompiler.flash.abc.types.Namespace.KIND_PACKAGE, nsNameIndex);
        int nameIndex = abc.constants.addString(localName);
        Multiname mn = new Multiname();
        mn.kind = Multiname.QNAME;
        mn.namespace_index = nsIndex;
        mn.name_index = nameIndex;
        return abc.constants.addMultiname(mn);
    }

    private void removeSignatures(SWF swf) {
        java.util.Iterator<Tag> it = swf.getTags().iterator();
        while (it.hasNext()) {
            Tag tag = it.next();
            String name = tag.getClass().getSimpleName();
            if (name.equals("MetadataTag") || name.equals("ProductInfoTag") || name.equals("DebugIDTag")) {
                it.remove();
                swf.setModified(true);
            } else if (name.equals("FileAttributesTag")) {
                try {
                    // Using reflection or specific cast if available, but for simplicity:
                    java.lang.reflect.Field field = tag.getClass().getField("hasMetadata");
                    field.setBoolean(tag, false);
                    swf.setModified(true);
                } catch (Exception ignored) {}
            }
        }
    }

    public JsonObject renameIdentifier(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String oldName = args.get("old_name").getAsString();
            String newName = args.get("new_name").getAsString();

            int changedCount = 0;
            for (ABC abc : getAllAbcs(swf)) {
                for (int i = 1; i < abc.constants.getStringCount(); i++) {
                    String str = abc.constants.getString(i);
                    if (oldName.equals(str)) {
                        abc.constants.setString(i, newName);
                        changedCount++;
                    }
                }
            }

            if (changedCount > 0) {
                swf.setModified(true);
                return success("Successfully renamed identifier '" + oldName + "' to '" + newName + "' in " + changedCount + " instances.");
            } else {
                return error("Identifier '" + oldName + "' not found in any ABC constant pool.");
            }
        } catch (Exception e) {
            return error("Rename identifier error: " + e.getMessage());
        }
    }

    public JsonObject deleteClass(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();

            List<ABC> allAbcs = getAllAbcs(swf);
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (int i = 0; i < si.traits.traits.size(); i++) {
                        Trait t = si.traits.traits.get(i);
                        if (t instanceof TraitClass && matchesTraitName(owningAbc, t, className)) {
                            si.traits.traits.remove(i);
                            // Also remove the class_info and instance_info if possible
                            // This is a more complex operation and might require re-indexing,
                            // but for a simple deletion, removing the trait is a start.
                            // abc.deleteClass(tc.class_info, true); // This method exists but might need careful usage
                            swf.setModified(true);
                            return success("Successfully deleted class: " + className);
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to delete class: " + e.getMessage());
        }
    }

    public JsonObject replaceSuperclass(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String newSuperclass = args.get("new_superclass").getAsString();

            List<ABC> allAbcs = getAllAbcs(swf);
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (Trait t : si.traits.traits) {
                        if (t instanceof TraitClass tc && (t.getName(owningAbc).toString().equals(className) || matchesTraitName(owningAbc, t, className))) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            int multinameIndex = getOrCreateMultiname(owningAbc, newSuperclass);
                            if (multinameIndex <= 0) {
                                return error("Failed to create multiname for superclass: '" + newSuperclass + "'");
                            }
                            ii.super_index = multinameIndex;
                            swf.setModified(true);
                            return success("Replaced superclass for '" + className + "' to '" + newSuperclass + "'.");
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to replace superclass: " + e.getMessage());
        }
    }

    public JsonObject addInterface(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String interfaceName = args.get("interface_name").getAsString();

            List<ABC> allAbcs = getAllAbcs(swf);
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (Trait t : si.traits.traits) {
                        if (t instanceof TraitClass tc && (t.getName(owningAbc).toString().equals(className) || matchesTraitName(owningAbc, t, className))) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            int multinameIndex = getOrCreateMultiname(owningAbc, interfaceName);
                            if (multinameIndex <= 0) {
                                return error("Failed to create multiname for interface: '" + interfaceName + "'");
                            }
                            int[] oldInterfaces = ii.interfaces;
                            int[] newInterfaces = new int[(oldInterfaces == null ? 0 : oldInterfaces.length) + 1];
                            if (oldInterfaces != null) {
                                System.arraycopy(oldInterfaces, 0, newInterfaces, 0, oldInterfaces.length);
                            }
                            newInterfaces[newInterfaces.length - 1] = multinameIndex;
                            ii.interfaces = newInterfaces;
                            swf.setModified(true);
                            return success("Added interface '" + interfaceName + "' to class '" + className + "'.");
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to add interface: " + e.getMessage());
        }
    }

    public JsonObject modifyClassFlags(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();

            List<ABC> allAbcs = getAllAbcs(swf);
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (Trait t : si.traits.traits) {
                        if (t instanceof TraitClass tc && (t.getName(owningAbc).toString().equals(className) || matchesTraitName(owningAbc, t, className))) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            boolean flagChanged = false;
                            if (args.has("flags")) {
                                ii.flags = args.get("flags").getAsInt();
                                flagChanged = true;
                            }
                            if (args.has("is_sealed")) {
                                boolean val = args.get("is_sealed").getAsBoolean();
                                ii.flags = (ii.flags & ~InstanceInfo.CLASS_SEALED) | (val ? InstanceInfo.CLASS_SEALED : 0);
                                flagChanged = true;
                            }
                            if (args.has("is_final")) {
                                boolean val = args.get("is_final").getAsBoolean();
                                ii.flags = (ii.flags & ~InstanceInfo.CLASS_FINAL) | (val ? InstanceInfo.CLASS_FINAL : 0);
                                flagChanged = true;
                            }
                            if (args.has("is_interface")) {
                                boolean val = args.get("is_interface").getAsBoolean();
                                ii.flags = (ii.flags & ~InstanceInfo.CLASS_INTERFACE) | (val ? InstanceInfo.CLASS_INTERFACE : 0);
                                flagChanged = true;
                            }
                            if (args.has("is_protected_namespace")) {
                                boolean val = args.get("is_protected_namespace").getAsBoolean();
                                ii.flags = (ii.flags & ~InstanceInfo.CLASS_PROTECTEDNS) | (val ? InstanceInfo.CLASS_PROTECTEDNS : 0);
                                flagChanged = true;
                            }
                            if (flagChanged) {
                                swf.setModified(true);
                                return success("Class flags updated for: " + className);
                            } else {
                                return success("No flags provided to change for class: " + className);
                            }
                        }
                    }
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to modify class flags: " + e.getMessage());
        }
    }

    public JsonObject addField(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String fieldDeclaration = args.get("field_declaration").getAsString();
            removeSignatures(swf);
            AbcIndexing indexing = new AbcIndexing(swf);
            List<ABC> allAbcs = getAllAbcs(swf);
            DecompilerPool pool = new DecompilerPool();
            try {
                for (ABC abc : allAbcs) {
                    ScriptPack sp = findScriptPack(abc, className, allAbcs);
                    if (sp != null) {
                        HighlightedText decompiled = pool.decompile(indexing, sp);
                        String source = decompiled.text;
                        if (source == null || source.isEmpty()) return error("Decompiler returned empty text for class '" + className + "'.");
                        String patternStr = "(?s)((?:public|private|protected|internal|final|dynamic|\\s)*class\\s+" + className.substring(className.lastIndexOf('.') + 1) + "[^{]*)\\{";
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patternStr);
                        java.util.regex.Matcher matcher = pattern.matcher(source);
                        if (matcher.find()) {
                            int startBrace = matcher.end() - 1;
                            String modifiedSource = source.substring(0, startBrace + 1) + "\n      " + fieldDeclaration + "\n" + source.substring(startBrace + 1);
                            FFDecAs3ScriptReplacer replacer = new FFDecAs3ScriptReplacer(false);
                            replacer.replaceScript(sp, modifiedSource, Collections.singletonList(swf));
                            swf.setModified(true);
                            return success("Successfully added field to class '" + className + "'.");
                        }
                        return error("Class declaration not found in decompiled source.");
                    }
                }
            } finally {
                pool.shutdown();
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Add field error: " + e.getMessage());
        }
    }

    public JsonObject addMethod(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String methodSource = getStr(args, "method_source", getStr(args, "new_source", null));
            if (methodSource == null) return error("Missing 'method_source' or 'new_source' argument.");

            removeSignatures(swf);
            AbcIndexing indexing = new AbcIndexing(swf);
            List<ABC> allAbcs = getAllAbcs(swf);
            DecompilerPool pool = new DecompilerPool();
            try {
                for (ABC abc : allAbcs) {
                    ScriptPack sp = findScriptPack(abc, className, allAbcs);
                    if (sp != null) {
                        HighlightedText decompiled = pool.decompile(indexing, sp);
                        String source = decompiled != null ? decompiled.text : "";
                        if (source == null || source.isEmpty()) return error("Decompiler returned empty text for class '" + className + "'.");
                        int lastBrace = source.lastIndexOf("}");
                        if (lastBrace == -1) return error("Could not find end of class '" + className + "'.");
                        String newSource = source.substring(0, lastBrace) + "\n" + methodSource + "\n" + source.substring(lastBrace);
                        FFDecAs3ScriptReplacer replacer = new FFDecAs3ScriptReplacer(false);
                        replacer.replaceScript(sp, newSource, Collections.singletonList(swf));
                        swf.setModified(true);
                        return success("Successfully added method to class '" + className + "'.");
                    }
                }
            } finally {
                pool.shutdown();
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to add method", e);
            return error("Failed to add method: " + e.getMessage());
        }
    }

    public JsonObject removeTrait(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String traitName = args.get("trait_name").getAsString();
            removeSignatures(swf);
            List<ABC> allAbcs = getAllAbcs(swf);
            boolean removed = false;
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    
                    // 1. Search script-level traits
                    if (si.traits != null && si.traits.traits != null) {
                        for (int i = 0; i < si.traits.traits.size(); i++) {
                            Trait trait = si.traits.traits.get(i);
                            if (matchesTraitName(owningAbc, trait, traitName)) {
                                si.traits.traits.remove(i);
                                removed = true;
                                break;
                            }
                        }
                    }
                    if (removed) {
                        owningAbc.clearPacksCache();
                        break;
                    }

                    // 2. Search class instance and static traits
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            ClassInfo ci = owningAbc.class_info.get(tc.class_info);
                            if (ii.instance_traits != null && ii.instance_traits.traits != null) {
                                for (int i = 0; i < ii.instance_traits.traits.size(); i++) {
                                    if (matchesTraitName(owningAbc, ii.instance_traits.traits.get(i), traitName)) {
                                        ii.instance_traits.traits.remove(i);
                                        removed = true;
                                        break;
                                    }
                                }
                            }
                            if (!removed && ci.static_traits != null && ci.static_traits.traits != null) {
                                for (int i = 0; i < ci.static_traits.traits.size(); i++) {
                                    if (matchesTraitName(owningAbc, ci.static_traits.traits.get(i), traitName)) {
                                        ci.static_traits.traits.remove(i);
                                        removed = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (removed) break;
                    }
                }
                if (removed) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    owningAbc.clearPacksCache();
                    break;
                }
            }
            if (removed) {
                swf.setModified(true);
                return success("Successfully removed trait '" + traitName + "' from '" + className + "'.");
            }
            return error("Trait '" + traitName + "' not found in '" + className + "'.");
        } catch (Exception e) {
            return error("Failed to remove trait: " + e.getMessage());
        }
    }

    public JsonObject editConstants(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String pattern = args.get("search_pattern").getAsString();
            String replacement = args.get("replacement_text").getAsString();
            removeSignatures(swf);
            List<ABC> allAbcs = getAllAbcs(swf);
            int count = 0;
            String lowerPattern = pattern.toLowerCase();
            for (ABC abc : allAbcs) {
                AVM2ConstantPool pool = abc.constants;
                if (pool != null) {
                    int stringCount = pool.getStringCount();
                    for (int i = 0; i < stringCount; i++) {
                        String s = pool.getString(i);
                        if (s != null && s.toLowerCase().contains(lowerPattern)) {
                            if (s.contains(pattern)) {
                                pool.setString(i, s.replace(pattern, replacement));
                            } else {
                                pool.setString(i, s.replaceAll("(?i)" + pattern, replacement));
                            }
                            count++;
                        }
                    }
                }
            }
            if (count > 0) {
                swf.setModified(true);
                return success("Successfully replaced " + count + " string constants.");
            }
            return success("No constants found matching '" + pattern + "'. Search was case-insensitive.");
        } catch (Exception e) {
            return error("Failed to edit constants: " + e.getMessage());
        }
    }

    public JsonObject getTagDetails(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            Tag foundTag = null;
            int index = -1;

            if (args.has("tag_index")) {
                index = args.get("tag_index").getAsInt();
                if (index >= 0 && index < swf.getTags().size()) {
                    foundTag = swf.getTags().get(index);
                }
            } else if (args.has("tag_id")) {
                int tagId = args.get("tag_id").getAsInt();
                for (int i = 0; i < swf.getTags().size(); i++) {
                    if (swf.getTags().get(i).getId() == tagId) {
                        foundTag = swf.getTags().get(i);
                        index = i;
                        break;
                    }
                }
            }

            if (foundTag != null) {
                JsonObject details = new JsonObject();
                details.addProperty("index", index);
                details.addProperty("id", foundTag.getId());
                details.addProperty("name", foundTag.getName());
                details.addProperty("type", foundTag.getClass().getSimpleName());
                byte[] data = foundTag.getData();
                details.addProperty("size", data != null ? data.length : 0);
                
                StringBuilder sb = new StringBuilder();
                sb.append("Tag Details (Index ").append(index).append("):\n");
                sb.append("- Name: ").append(foundTag.getName()).append("\n");
                sb.append("- ID: ").append(foundTag.getId()).append("\n");
                sb.append("- Type: ").append(foundTag.getClass().getSimpleName()).append("\n");
                sb.append("- Size: ").append(data != null ? data.length : 0).append(" bytes\n");
                
                if (data != null && data.length > 0) {
                    sb.append("\nHex Sample (up to 32 bytes):\n");
                    int len = Math.min(data.length, 32);
                    for (int i = 0; i < len; i++) {
                        sb.append(String.format("%02X ", data[i]));
                        if ((i + 1) % 16 == 0) sb.append("\n");
                    }
                    if (data.length > 32) sb.append("...");
                    sb.append("\n");
                }

                JsonObject result = success(sb.toString());
                result.add("details", details); // Keeping raw details for programmatic use
                return result;
            }
            return error("Tag " + (args.has("tag_index") ? "at index " + args.get("tag_index").getAsInt() : "ID " + args.get("tag_id").getAsInt()) + " not found.");
        } catch (Exception e) {
            return error("Failed to get tag details: " + e.getMessage());
        }
    }

    // Chaining fix for above:

    public JsonObject removeTag(JsonObject args) {
        try {
            if (!args.has("tag_id") && !args.has("tag_index")) return error("Missing required argument: tag_id or tag_index");
            SWF swf = getSwf(args);
            
            if (args.has("tag_index")) {
                int index = args.get("tag_index").getAsInt();
                int current = 0;
                java.util.Iterator<Tag> it = swf.getTags().iterator();
                while (it.hasNext()) {
                    it.next();
                    if (current == index) {
                        it.remove();
                        swf.setModified(true);
                        return success("Successfully removed tag at index " + index);
                    }
                    current++;
                }
                return error("Tag index " + index + " out of bounds (0-" + (current-1) + ")");
            }

            int tagId = args.get("tag_id").getAsInt();
            java.util.Iterator<Tag> it = swf.getTags().iterator();
            while (it.hasNext()) {
                if (it.next().getId() == tagId) {
                    it.remove();
                    swf.setModified(true);
                    return success("Successfully removed tag ID " + tagId);
                }
            }
            return error("Tag ID " + tagId + " not found.");
        } catch (Exception e) {
            return error("Failed to remove tag: " + e.getMessage());
        }
    }

    public JsonObject listSymbols(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            JsonArray symbolsArray = new JsonArray();
            StringBuilder sb = new StringBuilder();
            sb.append("Exported Symbols:\n");
            
            for (Tag tag : swf.getTags()) {
                if (tag instanceof com.jpexs.decompiler.flash.tags.SymbolClassTag st) {
                    java.util.Map<Integer, String> symbolsMap = st.getTagToNameMap();
                    for (int id : symbolsMap.keySet()) {
                        String name = symbolsMap.get(id);
                        JsonObject s = new JsonObject();
                        s.addProperty("id", id);
                        s.addProperty("name", name);
                        symbolsArray.add(s);
                        sb.append("- ").append(name).append(" (ID: ").append(id).append(")\n");
                    }
                }
            }
            
            if (symbolsArray.size() == 0) {
                return success("No symbols found in this SWF.");
            }
            
            JsonObject result = success(sb.toString());
            result.add("symbols", symbolsArray);
            return result;
        } catch (Exception e) {
            return error("Failed to list symbols: " + e.getMessage());
        }
    }

    public JsonObject modifySymbol(JsonObject args) {
        try {
            if (!args.has("symbol_id") || !args.has("new_name")) {
                return error("Missing required arguments: symbol_id, new_name");
            }
            SWF swf = getSwf(args);
            int symbolId = args.get("symbol_id").getAsInt();
            String newName = args.get("new_name").getAsString();
            boolean found = false;
            for (Tag tag : swf.getTags()) {
                if (tag instanceof com.jpexs.decompiler.flash.tags.SymbolClassTag st) {
                    java.util.Map<Integer, String> symbolsMap = st.getTagToNameMap();
                    if (symbolsMap.containsKey(symbolId)) {
                        symbolsMap.put(symbolId, newName);
                        found = true;
                    }
                }
            }
            if (found) {
                swf.setModified(true);
                return success("Successfully updated symbol " + symbolId + " to '" + newName + "'.");
            }
            return error("Symbol ID " + symbolId + " not found.");
        } catch (Exception e) {
            return error("Failed to modify symbol: " + e.getMessage());
        }
    }

    public JsonObject modifySwfHeader(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            if (args.has("version")) swf.version = args.get("version").getAsInt();
            if (args.has("width") || args.has("height")) {
                RECT r = swf.getRect();
                int w = args.has("width") ? args.get("width").getAsInt() : r.getWidth();
                int h = args.has("height") ? args.get("height").getAsInt() : r.getHeight();
                swf.displayRect = new RECT(0, 0, w * 20, h * 20); // Twips
            }
            swf.setModified(true);
            return success("SWF header updated.");
        } catch (Exception e) {
            return error("Failed to modify SWF header: " + e.getMessage());
        }
    }

    public JsonObject patchClass(JsonObject args) {
        // Simple wrapper for addField/addMethod or similar if needed, 
        // but let's implement a 'source replacement' one.
        try {
            SWF swf = getSwf(args);
            String className = args.get("class_name").getAsString();
            String sourceCode = args.get("source_code").getAsString();
            removeSignatures(swf);
            List<ABC> allAbcs = getAllAbcs(swf);
            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    FFDecAs3ScriptReplacer replacer = new FFDecAs3ScriptReplacer(false);
                    replacer.replaceScript(sp, sourceCode, Collections.singletonList(swf));
                    swf.setModified(true);
                    return success("Successfully patched class: " + className);
                }
            }
            return error("Class not found: " + className);
        } catch (Exception e) {
            return error("Failed to patch class: " + e.getMessage());
        }
    }

    public JsonObject patchMethod(JsonObject args) {
        JsonObject result = addMethod(args);
        if (result.has("success") && result.get("success").getAsBoolean()) {
            String className = args.get("class_name").getAsString();
            String methodSource = getStr(args, "method_source", getStr(args, "new_source", "unknown source"));
            return success("Successfully patched method in class '" + className + "' by appending/replacing source. Method length: " + methodSource.length() + " chars.");
        }
        return result;
    }

    public JsonObject patchBytecode(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null);
            String methodName = getStr(args, "method_name", null);
            String searchHex = getStr(args, "search_hex", null);
            String replaceHex = getStr(args, "replace_hex", null);

            String pcode = getStr(args, "pcode", null);

            if (className == null || methodName == null) {
                return error("Missing required arguments (class_name, method_name).");
            }

            if (pcode == null && (searchHex == null || replaceHex == null)) {
                return error("Either 'pcode' or both 'search_hex' and 'replace_hex' must be provided.");
            }

            byte[] searchBytes = searchHex != null ? hexToBytes(searchHex) : null;
            byte[] replaceBytes = replaceHex != null ? hexToBytes(replaceHex) : null;

            List<ABC> allAbcs = getAllAbcs(swf);
            boolean found = false;

            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    // Important: use the ABC that actually owns the script pack
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            if (ii != null) {
                                if (pcode != null) {
                                    found |= patchTraitsPcode(owningAbc, ii.instance_traits, methodName, pcode);
                                } else {
                                    found |= patchTraits(owningAbc, ii.instance_traits, methodName, searchBytes, replaceBytes);
                                }
                            }
                            ClassInfo ci = owningAbc.class_info.get(tc.class_info);
                            if (ci != null) {
                                if (pcode != null) {
                                    found |= patchTraitsPcode(owningAbc, ci.static_traits, methodName, pcode);
                                } else {
                                    found |= patchTraits(owningAbc, ci.static_traits, methodName, searchBytes, replaceBytes);
                                }
                            }
                        } else if (t instanceof TraitMethodGetterSetter tmgs) {
                            String tName = getTraitMethodName(owningAbc, t);
                            if (tName.equals(methodName) || tName.contains(methodName)) {
                                if (pcode != null) {
                                    found |= patchMethodBodyPcode(owningAbc, tmgs.method_info, pcode);
                                } else {
                                    found |= patchMethodBody(owningAbc, tmgs.method_info, searchBytes, replaceBytes);
                                }
                            }
                        }
                    }
                }
            }

            if (found) {
                swf.setModified(true);
                return success("Bytecode patched successfully.");
            } else {
                return error("Method or search sequence not found.");
            }
        } catch (Exception e) {
            return error("Failed to patch bytecode: " + e.getMessage());
        }
    }

    private boolean patchTraits(ABC abc, Traits traits, String methodName, byte[] searchBytes, byte[] replaceBytes) {
        if (traits == null || traits.traits == null) return false;
        boolean found = false;
        for (Trait t : traits.traits) {
            if (t instanceof TraitMethodGetterSetter tm) {
                String tName = getTraitMethodName(abc, t);
                if (tName.equals(methodName) || tName.contains(methodName)) {
                    found |= patchMethodBody(abc, tm.method_info, searchBytes, replaceBytes);
                }
            }
        }
        return found;
    }

    private boolean patchTraitsPcode(ABC abc, Traits traits, String methodName, String pcode) {
        if (traits == null || traits.traits == null) return false;
        boolean found = false;
        for (Trait t : traits.traits) {
            if (t instanceof TraitMethodGetterSetter tm) {
                String tName = getTraitMethodName(abc, t);
                if (tName.equals(methodName) || tName.contains(methodName)) {
                    found |= patchMethodBodyPcode(abc, tm.method_info, pcode);
                }
            }
        }
        return found;
    }

    private boolean patchMethodBody(ABC abc, int methodInfoIndex, byte[] searchBytes, byte[] replaceBytes) {
        if (abc.bodies == null) return false;
        for (MethodBody body : abc.bodies) {
            if (body.method_info == methodInfoIndex) {
                try {
                    java.lang.reflect.Field field = MethodBody.class.getDeclaredField("code");
                    field.setAccessible(true);
                    byte[] rawCode = (byte[]) field.get(body);
                    if (rawCode != null) {
                        int index = indexOf(rawCode, searchBytes);
                        if (index != -1) {
                            byte[] newCode = new byte[rawCode.length - searchBytes.length + replaceBytes.length];
                            System.arraycopy(rawCode, 0, newCode, 0, index);
                            System.arraycopy(replaceBytes, 0, newCode, index, replaceBytes.length);
                            System.arraycopy(rawCode, index + searchBytes.length, newCode, index + replaceBytes.length, rawCode.length - index - searchBytes.length);
                            field.set(body, newCode);
                            // Important: force re-parsing of instructions if needed
                            try {
                                java.lang.reflect.Method setCodeMethod = MethodBody.class.getDeclaredMethod("setCode", AVM2Code.class);
                                setCodeMethod.setAccessible(true);
                                setCodeMethod.invoke(body, (AVM2Code)null);
                            } catch (Exception se) {
                                // Ignore if setCode doesn't exist or fails
                            }
                            return true;
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Reflection failed to patch bytecode", e);
                }
            }
        }
        return false;
    }

    public JsonObject addNopPadding(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String className = getStr(args, "class_name", null);
            String methodName = getStr(args, "method_name", null);
            int count = args.has("count") ? args.get("count").getAsInt() : 1;

            if (className == null || methodName == null) {
                return error("Missing required arguments (class_name, method_name).");
            }

            List<ABC> allAbcs = getAllAbcs(swf);
            boolean found = false;

            for (ABC abc : allAbcs) {
                ScriptPack sp = findScriptPack(abc, className, allAbcs);
                if (sp != null) {
                    ABC owningAbc = sp.abc != null ? sp.abc : abc;
                    ScriptInfo si = owningAbc.script_info.get(sp.scriptIndex);
                    for (int traitIdx : sp.traitIndices) {
                        Trait t = si.traits.traits.get(traitIdx);
                        if (t instanceof TraitClass tc) {
                            InstanceInfo ii = owningAbc.instance_info.get(tc.class_info);
                            if (ii != null) {
                                found |= appendNopsToTraits(owningAbc, ii.instance_traits, methodName, count);
                            }
                            ClassInfo ci = owningAbc.class_info.get(tc.class_info);
                            if (ci != null) {
                                found |= appendNopsToTraits(owningAbc, ci.static_traits, methodName, count);
                            }
                        } else if (t instanceof TraitMethodGetterSetter tmgs) {
                            String tName = getTraitMethodName(owningAbc, t);
                            if (tName.equals(methodName) || tName.contains(methodName)) {
                                found |= appendNopsToMethodBody(owningAbc, tmgs.method_info, count);
                            }
                        }
                    }
                }
            }

            if (found) {
                swf.setModified(true);
                return success("Successfully added " + count + " NOPs to method '" + methodName + "'.");
            } else {
                return error("Method '" + methodName + "' not found in class '" + className + "'.");
            }
        } catch (Exception e) {
            return error("Failed to add NOP padding: " + e.getMessage());
        }
    }

    private boolean appendNopsToTraits(ABC abc, Traits traits, String methodName, int count) {
        if (traits == null || traits.traits == null) return false;
        boolean found = false;
        for (Trait t : traits.traits) {
            if (t instanceof TraitMethodGetterSetter tm) {
                String tName = getTraitMethodName(abc, t);
                if (tName.equals(methodName) || tName.contains(methodName)) {
                    found |= appendNopsToMethodBody(abc, tm.method_info, count);
                }
            }
        }
        return found;
    }

    private boolean appendNopsToMethodBody(ABC abc, int methodInfoIndex, int count) {
        if (abc.bodies == null) return false;
        for (MethodBody body : abc.bodies) {
            if (body.method_info == methodInfoIndex) {
                try {
                    java.lang.reflect.Field codeField = MethodBody.class.getDeclaredField("code");
                    codeField.setAccessible(true);
                    byte[] rawCode = (byte[]) codeField.get(body);
                    if (rawCode == null) rawCode = new byte[0];
                    byte[] nopBytes = new byte[count];
                    java.util.Arrays.fill(nopBytes, (byte)0x02); // 0x02 is nop
                    byte[] newCode = new byte[rawCode.length + nopBytes.length];
                    System.arraycopy(nopBytes, 0, newCode, 0, nopBytes.length);
                    System.arraycopy(rawCode, 0, newCode, nopBytes.length, rawCode.length);
                    
                    java.lang.reflect.Field field = MethodBody.class.getDeclaredField("code");
                    field.setAccessible(true);
                    field.set(body, newCode);
                    
                    try {
                        java.lang.reflect.Method setCodeMethod = MethodBody.class.getDeclaredMethod("setCode", AVM2Code.class);
                        setCodeMethod.setAccessible(true);
                        setCodeMethod.invoke(body, (AVM2Code)null);
                    } catch (Exception se) { /* Ignore */ }
                    
                    body.setModified();
                    ((Tag) abc.parentTag).setModified(true);
                    return true;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to add NOP padding", e);
                }
            }
        }
        return false;
    }

    private boolean patchMethodBodyPcode(ABC abc, int methodInfoIndex, String pcode) {
        if (abc.bodies == null) return false;
        for (MethodBody body : abc.bodies) {
            if (body.method_info == methodInfoIndex) {
                try {
                    AVM2Code code = ASM3Parser.parse(abc, new StringReader(pcode), null, body, abc.method_info.get(body.method_info));
                    body.setCode(code);
                    body.setModified();
                    ((Tag) abc.parentTag).setModified(true);
                    return true;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to patch method P-code", e);
                }
            }
        }
        return false;
    }

    private int indexOf(byte[] data, byte[] pattern) {
        if (pattern.length == 0) return 0;
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public JsonObject injectClass(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String fullClassName = args.get("class_name").getAsString();
            String sourceCode = args.get("source_code").getAsString();
            LOG.info("Injecting class '" + fullClassName + "' into SWF. Source length: " + sourceCode.length());
            removeSignatures(swf);

            // Refactoring current injectClass to at least be "cleaner" by appending to the last ABC tag
            // but in a more controlled manner if possible.
            List<ABC> allAbcs = getAllAbcs(swf);
            if (allAbcs.isEmpty()) {
                return error("No ABC tags found in SWF.");
            }

            ABC targetAbc = allAbcs.get(allAbcs.size() - 1);
            List<ScriptPack> packs = targetAbc.getScriptPacks("", allAbcs);
            
            if (packs.isEmpty()) {
                return error("No scripts found in target ABC to append to.");
            }

            ScriptPack sp = packs.get(packs.size() - 1);
            String originalSource = "";
            try {
                HighlightedText ht = new DecompilerPool().decompile(new AbcIndexing(swf), sp);
                if (ht != null) originalSource = ht.text;
            } catch (Exception e) {
                LOG.warning("Failed to decompile existing script for injection: " + e.getMessage());
            }

            String newSource = originalSource + "\n\n" + sourceCode;
            
            try {
                As3ScriptReplacerInterface replacer = As3ScriptReplacerFactory.createFFDec();
                sp.abc.replaceScriptPack(replacer, sp, newSource, Collections.singletonList(swf));
            } catch (Exception e) {
                return error("Failed to inject class: " + e.getMessage());
            }

            swf.setModified(true);
            return success("Class injected into script: " + sp.getPathScriptName());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to inject class", e);
            return error("Inject class error: " + e.getMessage());
        }
    }

    public JsonObject injectAbcFromFile(JsonObject args) {
        try {
            SWF swf = getSwf(args);
            String abcFilePath = args.get("abc_file_path").getAsString();
            removeSignatures(swf);

            File abcFile = new File(abcFilePath);
            if (!abcFile.exists()) {
                return error("ABC/SWF file not found: " + abcFilePath);
            }

            byte[] data = Files.readAllBytes(Paths.get(abcFilePath));
            
            int injectedCount = 0;
            // If it's a SWF, we need to extract ABC tags from it
            if (abcFilePath.toLowerCase().endsWith(".swf")) {
                SWF otherSwf = new SWF(new java.io.ByteArrayInputStream(data), abcFilePath, null, false);
                for (Tag tag : otherSwf.getTags()) {
                    if (tag instanceof ABCContainerTag abcContainer) {
                        // Check for existing ABC tag with the same name and replace it
                        boolean replaced = false;
                        if (abcContainer instanceof DoABC2Tag doAbc2Tag) {
                            for (Tag t : swf.getTags()) {
                                if (t instanceof DoABC2Tag existingAbcTag && doAbc2Tag.name.equals(existingAbcTag.name)) {
                                    existingAbcTag.setABC(doAbc2Tag.getABC());
                                    replaced = true;
                                    injectedCount++;
                                    break;
                                }
                            }
                        }
                        if (!replaced) {
                            swf.addTag(tag);
                            injectedCount++;
                        }
                    }
                }
                if (injectedCount > 0) {
                    swf.setModified(true);
                    return success("Injected " + injectedCount + " ABC tags from SWF " + abcFilePath);
                } else {
                    return error("No ABC tags found in SWF: " + abcFilePath);
                }
            } else {
                // Assume it's raw ABC data
                com.jpexs.helpers.MemoryInputStream mis = new com.jpexs.helpers.MemoryInputStream(data);
                com.jpexs.decompiler.flash.abc.ABCInputStream ais = new com.jpexs.decompiler.flash.abc.ABCInputStream(mis);
                ABC abcFromData = new ABC(ais, swf, null);
                
                String abcName = abcFile.getName();
                boolean replaced = false;
                for (Tag t : swf.getTags()) {
                    if (t instanceof DoABC2Tag abcTag && abcName.equals(abcTag.name)) {
                        abcTag.setABC(abcFromData);
                        replaced = true;
                        injectedCount++;
                        break;
                    }
                }

                if (!replaced) {
                    DoABC2Tag tag = new DoABC2Tag(swf);
                    tag.setABC(abcFromData);
                    tag.name = abcName;
                    tag.flags = 1; // Lazy initialize by default
                    swf.addTag(tag);
                    injectedCount++;
                }
                swf.setModified(true);
                return success("Successfully injected raw ABC from '" + abcFile.getName() + "'.");
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to inject ABC from file", e);
            return error("Inject ABC from file error: " + e.getMessage());
        }
    }
}
