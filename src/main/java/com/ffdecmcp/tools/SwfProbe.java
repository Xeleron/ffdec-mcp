package com.ffdecmcp.tools;

import com.jpexs.decompiler.flash.SWF;
import java.lang.reflect.Method;
import java.util.Arrays;

public class SwfProbe {
    static void main(String[] args) {
        System.out.println("Methods in com.jpexs.decompiler.flash.SWF:");
        Method[] methods = SWF.class.getMethods();
        Arrays.stream(methods)
            .filter(m -> m.getName().toLowerCase().contains("sign") || 
                         m.getName().toLowerCase().contains("crypt") ||
                         m.getName().toLowerCase().contains("auth") ||
                         m.getName().toLowerCase().contains("metadat"))
            .forEach(m -> System.out.println(m.getName()));
            
        System.out.println("\nMethods in com.jpexs.decompiler.flash.tags.DoABC2Tag:");
        try {
            Class<?> c = Class.forName("com.jpexs.decompiler.flash.tags.DoABC2Tag");
            Arrays.stream(c.getMethods())
                .filter(m -> m.getName().toLowerCase().contains("sign"))
                .forEach(m -> System.out.println(m.getName()));
        } catch (Exception e) {
            System.out.println("Could not find DoABC2Tag");
        }
    }
}
