# FFDec MCP Server

[![Release](https://img.shields.io/github/v/release/Xeleron/ffdec-mcp?style=flat-square)](https://github.com/Xeleron/ffdec-mcp/releases)
[![Build Status](https://github.com/Xeleron/ffdec-mcp/workflows/Release/badge.svg)](https://github.com/Xeleron/ffdec-mcp/actions)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for analyzing and manipulating Shockwave Flash (SWF) files using [JPEXS Free Flash Decompiler (FFDec)](https://github.com/jindrapetrik/jpexs-decompiler).

> **TL;DR**: Let AI assistants (Claude, GitHub Copilot, etc.) read, modify, and patch SWF files through natural language commands.

---

## Quick Start

### Using Podman/Docker (Recommended)

```bash
# 1. Build the image
podman build -t ffdec-mcp-server:latest .

# 2. Add to your MCP client config (see Installation for details)
```

### Using Smithery (Claude Desktop only)

```bash
npx -y @smithery/cli install @Xeleron/ffdec-mcp --client claude
```

---

## Features

| Category | Capabilities |
| :--- | :--- |
| **Analysis** | Read SWF headers, list tags/classes/symbols, inspect bytecode |
| **Decompilation** | Extract ActionScript 3 source from classes and scripts |
| **Modification** | Patch classes/methods, edit constants, rename identifiers |
| **Injection** | Add new classes, methods, fields, or import ABC from files |
| **Export** | Save modified SWFs, export specific components |

---

## Prerequisites

| Requirement | Version | Notes |
| :--- | :--- | :--- |
| **Java** | 21+ | Required for local builds (JDK, not JRE) |
| **Maven** | 3.6+ | For building from source |
| **Podman/Docker** | Any | Recommended alternative to local Java setup |

---

## Installation

### Option 1: Podman/Docker (Best for stability)

The Dockerfile handles all FFDec library dependencies automatically.

```bash
podman build -t ffdec-mcp-server:latest .
```

Add this to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "ffdec": {
      "command": "podman",
      "args": [
        "run",
        "-i",
        "--rm",
        "-v",
        "/absolute/path/to/your/swfs:/swfs:rw",
        "ffdec-mcp-server:latest"
      ]
    }
  }
}
```

> **Note**: Replace `/absolute/path/to/your/swfs` with your actual local directory.

### Option 2: Local Build (Advanced)

1. **Clone the repository:**

    ```bash
    git clone https://github.com/Xeleron/ffdec-mcp.git
    cd ffdec-mcp
    ```

2. **Install FFDec library:**

    ```bash
    mvn install:install-file \
      -Dfile=/path/to/ffdec_lib.jar \
      -DgroupId=com.jpexs \
      -DartifactId=ffdec_lib \
      -Dversion=1.0.0 \
      -Dpackaging=jar
    ```

3. **Build and Configure:**

    ```bash
    mvn clean package
    ```

    Point your MCP client to the resulting `run.sh` (ensure it is executable via `chmod +x run.sh`).

---

## Available Tools

The server exposes 40+ tools. Use `tools/list` at runtime for full technical definitions.

<details>
<summary><strong> Session Management</strong></summary>

| Tool | Description |
| :--- | :--- |
| `open_swf` | Open a SWF file and create a session |
| `close_swf` | Close a session and free resources |
| `reload_swf` | Reload from disk, discarding unsaved changes |
| `list_sessions` | List all open SWF sessions |
| `save_swf` | Save modifications to disk |

</details>

<details>
<summary><strong> Analysis & Inspection</strong></summary>

| Tool | Description |
| :--- | :--- |
| `get_swf_info` | SWF header and metadata (version, size, dimensions) |
| `list_classes` | Enumerate AS3 classes and scripts |
| `list_tags` | List all tags and their types |
| `list_symbols` | SymbolClass associations (ID ↔ class name) |
| `list_scripts` | AS3 script packs and indices |
| `list_methods` | Instance and static methods for a class |
| `get_traits` | All traits (methods, fields, accessors) for a class |
| `get_class_hierarchy` | Superclass and interfaces |
| `get_dependencies` | Classes referenced by a given class |
| `get_constant_pool` | Constant pool snippets (strings, ints) |
| `get_tag_details` | Detailed metadata for a tag by index |

</details>

<details>
<summary><strong> Decompilation & Search</strong></summary>

| Tool | Description |
| :--- | :--- |
| `decompile_class` | Decompile AS3 class to source |
| `decompile_script` | Decompile script by index |
| `get_bytecode` | AVM2 bytecode (P-code) for method/class |
| `search_source` | Search decompiled AS3 source |
| `search_pcode` | Search bytecode for patterns |
| `find_class` | Find classes matching a query |
| `find_methods` | Find methods across scripts/classes |
| `resolve_identifier` | Resolve name to internal indices |

</details>

<details>
<summary><strong> Modification</strong></summary>

| Tool | Description |
| :--- | :--- |
| `patch_class` | Replace entire class source and recompile |
| `patch_method` | Replace a single method's source |
| `patch_bytecode` | Replace method P-code directly |
| `edit_constants` | Find-and-replace constants in ABC pool |
| `rename_identifier` | Rename class/method/field consistently |
| `modify_symbol` | Add/remove/change SymbolClass associations |
| `modify_swf_header` | Change version, frame rate, dimensions |
| `modify_class_flags` | Toggle sealed/final/interface flags |
| `replace_superclass` | Change a class's superclass |

</details>

<details>
<summary><strong> Injection & Removal</strong></summary>

| Tool | Description |
| :--- | :--- |
| `inject_class` | Add new AS3 class (creates DoABC tag) |
| `inject_abc_from_file` | Import ABC tags from SWF/.abc file |
| `add_method` | Append method to existing class |
| `add_field` | Add field to existing class |
| `add_interface` | Add interface implementation |
| `remove_trait` | Remove method/property trait |
| `remove_tag` | Remove tag by index |
| `delete_class` | Remove class from ABC data |

</details>

---

## Example Workflows

### Analyzing a SWF

> **User**: "Open game.swf and show me what classes it contains"
> **AI**: Uses `open_swf`, then `list_classes`.

### Modifying a SWF

> **User**: "Change the player's max health from 100 to 200 in the Player class"
> **AI**: Searches for the constant, uses `edit_constants` or `patch_method`, then `save_swf`.

---

## Troubleshooting

<details>
<summary><strong>Build fails with "ffdec_lib not found"</strong></summary>

The FFDec library is not in Maven Central. Use the **Docker build** to handle this automatically, or manually install the JAR using `mvn install:install-file`.

</details>

<details>
<summary><strong>Server won't start: "Java version X required"</strong></summary>

Run `java -version`. This project requires **Java 21+**.

</details>

---

## License

Licensed under the **Apache License 2.0**.

### Third-Party Licenses

| Component | License | Notes |
| :--- | :--- | :--- |
| `ffdec_lib` | [LGPLv3](https://www.gnu.org/licenses/lgpl-3.0.html) | Core SWF analysis library |
