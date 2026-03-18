package com.ffdecmcp;

import com.ffdecmcp.tools.ToolRegistry;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.*;

/**
 * MCP Server using STDIO transport (JSON-RPC 2.0).
 * Communicates with VS Code / GitHub Copilot over stdin/stdout.
 * All logging goes to stderr so it doesn't corrupt the protocol stream.
 */
public class FFDecMcpServer {

    private static final Logger LOG = Logger.getLogger(FFDecMcpServer.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ToolRegistry toolRegistry;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private boolean running = true;

    public FFDecMcpServer(InputStream in, OutputStream out) {
        this.inputStream = in;
        this.outputStream = out;
        this.toolRegistry = new ToolRegistry();
    }

    static void main(String[] args) {
        // Configure logging to stderr
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        StreamHandler stderrHandler = new StreamHandler(System.err, new SimpleFormatter()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        stderrHandler.setLevel(Level.ALL);
        rootLogger.addHandler(stderrHandler);
        rootLogger.setLevel(Level.INFO);

        LOG.info("FFDec MCP Server starting...");

        FFDecMcpServer server = new FFDecMcpServer(System.in, System.out);
        server.run();
    }

    public void run() {
        LOG.info("Server running, waiting for JSON-RPC messages on stdin...");
        while (running) {
            try {
                String message = readMessage();
                if (message == null) {
                    LOG.info("EOF on stdin, shutting down.");
                    break;
                }
                handleMessage(message);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Error processing message", e);
            }
        }
        LOG.info("Server stopped.");
    }

    /**
     * Reads a JSON-RPC message. Supports both:
     * 1. HTTP-style headers (Content-Length + blank line + body)
     * 2. Raw line-delimited JSON (one JSON object per line)
     */
    private String readMessage() throws IOException {
        String line = readAsciiLine();
        if (line == null) return null;
        line = line.trim();
        while (line.isEmpty()) {
            line = readAsciiLine();
            if (line == null) return null;
            line = line.trim();
        }

        if (line.startsWith("{")) {
            // Raw JSON line
            return line;
        }

        // Potential header-based message (LSP style)
        int contentLength = -1;
        while (!line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    LOG.log(Level.WARNING, "Invalid Content-Length: {0}", line);
                }
            }
            line = readAsciiLine();
            if (line == null) return null;
            line = line.trim();
        }

        if (contentLength > 0) {
            byte[] buffer = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = inputStream.read(buffer, read, contentLength - read);
                if (n == -1) break;
                read += n;
            }
            return new String(buffer, StandardCharsets.UTF_8);
        }

        return null;
    }

    private String readAsciiLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = inputStream.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') {
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void sendMessage(JsonObject msg) throws IOException {
        String json = GSON.toJson(msg);
        synchronized (outputStream) {
            outputStream.write(json.getBytes(StandardCharsets.UTF_8));
            outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    private void handleMessage(String rawJson) throws IOException {
        LOG.log(Level.FINE, "Received: {0}", rawJson);
        JsonObject request;
        try {
            request = JsonParser.parseString(rawJson).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            LOG.log(Level.WARNING, "Invalid JSON: {0}", rawJson);
            return;
        }

        String method = request.has("method") ? request.get("method").getAsString() : null;
        JsonElement idElem = request.get("id");

        if (method == null) {
            LOG.warning("No method in request");
            return;
        }

        LOG.log(Level.INFO, "Handling method: {0}", method);

        switch (method) {
            case "initialize" -> handleInitialize(request, idElem);
            case "initialized" -> { /* notification, no response needed */ }
            case "tools/list" -> handleToolsList(request, idElem);
            case "tools/call" -> handleToolsCall(request, idElem);
            case "shutdown" -> {
                sendResult(idElem, JsonNull.INSTANCE);
                running = false;
            }
            case "exit" -> running = false;
            case "notifications/initialized" -> { /* ignore */ }
            default -> {
                if (idElem != null) {
                    sendError(idElem, -32601, "Method not found: " + method);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private void handleInitialize(JsonObject request, JsonElement id) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "ffdec-mcp-server");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);

        sendResult(id, result);
    }

    @SuppressWarnings("unused")
    private void handleToolsList(JsonObject request, JsonElement id) throws IOException {
        JsonObject result = new JsonObject();
        result.add("tools", toolRegistry.getToolDefinitions());
        sendResult(id, result);
    }

    private void handleToolsCall(JsonObject request, JsonElement id) throws IOException {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        LOG.log(Level.INFO, "Calling tool: {0} with args: {1}", new Object[]{toolName, arguments});

        try {
            JsonObject toolResultRaw = toolRegistry.callTool(toolName, arguments);
            
            // Format result according to MCP spec (CallToolResult)
            JsonObject mcpResult = new JsonObject();
            if (toolResultRaw.has("content")) {
                mcpResult = toolResultRaw;
            } else {
                JsonArray content = new JsonArray();
                JsonObject textContent = new JsonObject();
                textContent.addProperty("type", "text");
                // Pretty print the JSON result if it's not already content
                textContent.addProperty("text", GSON.toJson(toolResultRaw));
                content.add(textContent);
                mcpResult.add("content", content);
                mcpResult.addProperty("isError", false);
            }
            
            sendResult(id, mcpResult);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Tool execution error", e);
            // Return error as content per MCP spec
            JsonObject errorResult = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", "Error: " + e.getMessage());
            content.add(textContent);
            errorResult.add("content", content);
            errorResult.addProperty("isError", true);
            sendResult(id, errorResult);
        }
    }

    private void sendResult(JsonElement id, JsonElement result) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        sendMessage(response);
    }

    private void sendError(JsonElement id, int code, String message) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        sendMessage(response);
    }
}
