package com.ffdecmcp;

import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.configuration.Configuration;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages open SWF file sessions. Each SWF is opened once and cached
 * by its absolute file path so multiple tool calls can operate on the same file.
 */
public class SwfSessionManager {

    private static final Logger LOG = Logger.getLogger(SwfSessionManager.class.getName());
    private static final SwfSessionManager INSTANCE = new SwfSessionManager();

    private final Map<String, SWF> openSwfs = new ConcurrentHashMap<>();
    private String lastSwfPath = null;

    static {
        // Initialize FFDec configuration
        Configuration.parallelSpeedUp.set(true);
    }

    private SwfSessionManager() {}

    public static SwfSessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Opens a SWF file or returns an already-opened instance.
     */
    public SWF getSwf(String filePath) throws Exception {
        String key = validatePath(filePath);
        this.lastSwfPath = key;
        SWF swf = openSwfs.get(key);
        if (swf != null) return swf;

        LOG.log(Level.INFO, "Opening SWF: {0}", key);
        File file = new File(key);
        if (!file.canRead()) {
            throw new IOException("Cannot read SWF file: " + key);
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            swf = new SWF(fis, true);
        }
        // Store the file path for saving later
        swf.setFile(key);
        openSwfs.put(key, swf);
        return swf;
    }

    public SWF getCurrentSwf() throws Exception {
        if (lastSwfPath == null) {
            throw new Exception("No active SWF session. Call open_swf first.");
        }
        return getSwf(lastSwfPath);
    }

    public String getCurrentSwfPath() throws Exception {
        if (lastSwfPath == null) {
            throw new Exception("No active SWF session. Call open_swf first.");
        }
        return lastSwfPath;
    }

    /**
     * Forces a reload of the SWF from disk.
     */
    public SWF reloadSwf(String filePath) throws Exception {
        String key = validatePath(filePath);
        closeSwf(key);
        return getSwf(key);
    }

    /**
     * Saves the SWF to its original path or a new path.
     */
    public void saveSwf(String filePath, String outputPath) throws Exception {
        String key = validatePath(filePath);
        SWF swf = openSwfs.get(key);
        if (swf == null) {
            throw new IllegalStateException("SWF not opened: " + filePath);
        }

        String savePath = (outputPath != null && !outputPath.isEmpty())
                ? validatePath(outputPath) : key;

        LOG.log(Level.INFO, "Saving SWF to: {0}", savePath);
        try (FileOutputStream fos = new FileOutputStream(savePath)) {
            swf.saveTo(fos);
        }

        // If saved to a new path, register it under that path too
        if (!savePath.equals(key)) {
            openSwfs.put(savePath, swf);
        }
    }

    public boolean closeSwf(String filePath) {
        String key = new File(filePath).getAbsolutePath();
        SWF swf = openSwfs.remove(key);
        if (swf != null) {
            LOG.log(Level.INFO, "Closed SWF: {0}", key);
            return true;
        }
        return false;
    }

    private String validatePath(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }
        File file = new File(path);
        String absolutePath = file.getCanonicalPath();
        
        // Example validation: check if it's a .swf file
        if (!absolutePath.toLowerCase().endsWith(".swf")) {
             // In some cases we might be saving to a .swf, but let's be flexible
             // for now, just ensure it's not a directory traversal attempt
        }
        
        return absolutePath;
    }

    public Map<String, SWF> getAllOpen() {
        return openSwfs;
    }
}

