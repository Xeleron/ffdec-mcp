package com.ffdecmcp.tools;

import com.jpexs.decompiler.flash.tags.FileAttributesTag;
import java.lang.reflect.Field;
import java.util.Arrays;

public class TagProbe {
    static void main(String[] args) {
        System.out.println("Fields in com.jpexs.decompiler.flash.tags.FileAttributesTag:");
        Field[] fields = FileAttributesTag.class.getFields();
        Arrays.stream(fields).forEach(f -> System.out.println(f.getName()));
        
        System.out.println("\nDeclared Fields in com.jpexs.decompiler.flash.tags.FileAttributesTag:");
        fields = FileAttributesTag.class.getDeclaredFields();
        Arrays.stream(fields).forEach(f -> System.out.println(f.getName()));
    }
}
