package org.jclaude.runtime.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test helper that materialises Python-based MCP fixtures on disk. Mirrors the
 * {@code write_*_script} helpers in the Rust {@code mcp_stdio.rs} and
 * {@code mcp_tool_bridge.rs} test modules.
 */
final class FakeMcpScripts {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private FakeMcpScripts() {}

    static Path tempDir() throws IOException {
        long stamp = System.nanoTime();
        long id = NEXT_ID.getAndIncrement();
        Path dir = Files.createTempDirectory("runtime-mcp-stdio-" + stamp + "-" + id + "-");
        return dir;
    }

    static Path writeEchoScript() throws IOException {
        Path dir = tempDir();
        Path script = dir.resolve("echo-mcp.sh");
        String body = "#!/bin/sh\nprintf 'READY:%s\\n' \"$MCP_TEST_TOKEN\"\nIFS= read -r line\n"
                + "printf 'ECHO:%s\\n' \"$line\"\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        makeExecutable(script);
        return script;
    }

    static Path writeFakeMcpServerScript() throws IOException {
        Path dir = tempDir();
        Path script = dir.resolve("fake-mcp-server.py");
        Files.writeString(script, FAKE_MCP_SERVER_SOURCE, StandardCharsets.UTF_8);
        makeExecutable(script);
        return script;
    }

    static Path writeManagerMcpServerScript() throws IOException {
        Path dir = tempDir();
        Path script = dir.resolve("manager-mcp-server.py");
        Files.writeString(script, MANAGER_MCP_SERVER_SOURCE, StandardCharsets.UTF_8);
        makeExecutable(script);
        return script;
    }

    static Path writeInitializeDisconnectScript() throws IOException {
        Path dir = tempDir();
        Path script = dir.resolve("initialize-disconnect.py");
        Files.writeString(script, INITIALIZE_DISCONNECT_SOURCE, StandardCharsets.UTF_8);
        makeExecutable(script);
        return script;
    }

    static void cleanup(Path script) {
        try {
            Files.deleteIfExists(script);
            Path parent = script.getParent();
            if (parent != null && Files.exists(parent)) {
                try (var walk = Files.walk(parent)) {
                    walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // tests run in a temp dir so leakage is harmless
        }
    }

    private static void makeExecutable(Path script) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows or other non-POSIX filesystems — tests will skip on those platforms.
        }
    }

    private static final String FAKE_MCP_SERVER_SOURCE = "#!/usr/bin/env python3\n" + "import json, os, sys, time\n"
            + "TOOL_CALL_DELAY_MS = int(os.environ.get('MCP_TOOL_CALL_DELAY_MS', '0'))\n"
            + "INVALID_TOOL_CALL_RESPONSE = os.environ.get('MCP_INVALID_TOOL_CALL_RESPONSE') == '1'\n"
            + "\n"
            + "def read_message():\n"
            + "    header = b''\n"
            + "    while not header.endswith(b'\\r\\n\\r\\n'):\n"
            + "        chunk = sys.stdin.buffer.read(1)\n"
            + "        if not chunk:\n"
            + "            return None\n"
            + "        header += chunk\n"
            + "    length = 0\n"
            + "    for line in header.decode().split('\\r\\n'):\n"
            + "        if line.lower().startswith('content-length:'):\n"
            + "            length = int(line.split(':', 1)[1].strip())\n"
            + "    payload = sys.stdin.buffer.read(length)\n"
            + "    return json.loads(payload.decode())\n"
            + "\n"
            + "def send_message(message):\n"
            + "    payload = json.dumps(message).encode()\n"
            + "    sys.stdout.buffer.write(f'Content-Length: {len(payload)}\\r\\n\\r\\n'.encode() + payload)\n"
            + "    sys.stdout.buffer.flush()\n"
            + "\n"
            + "while True:\n"
            + "    request = read_message()\n"
            + "    if request is None:\n"
            + "        break\n"
            + "    method = request['method']\n"
            + "    if method == 'initialize':\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'protocolVersion': request['params']['protocolVersion'],\n"
            + "                       'capabilities': {'tools': {}, 'resources': {}},\n"
            + "                       'serverInfo': {'name': 'fake-mcp', 'version': '0.2.0'}}})\n"
            + "    elif method == 'tools/list':\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'tools': [{'name': 'echo', 'description': 'Echoes text',\n"
            + "                'inputSchema': {'type': 'object', 'properties': {'text': {'type': 'string'}}, 'required': ['text']}}]}})\n"
            + "    elif method == 'tools/call':\n"
            + "        if INVALID_TOOL_CALL_RESPONSE:\n"
            + "            sys.stdout.buffer.write(b'Content-Length: 5\\r\\n\\r\\nnope!')\n"
            + "            sys.stdout.buffer.flush()\n"
            + "            continue\n"
            + "        if TOOL_CALL_DELAY_MS:\n"
            + "            time.sleep(TOOL_CALL_DELAY_MS / 1000)\n"
            + "        args = request['params'].get('arguments') or {}\n"
            + "        if request['params']['name'] == 'fail':\n"
            + "            send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "                'error': {'code': -32001, 'message': 'tool failed'}})\n"
            + "        else:\n"
            + "            text = args.get('text', '')\n"
            + "            send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "                'result': {'content': [{'type': 'text', 'text': f'echo:{text}'}],\n"
            + "                           'structuredContent': {'echoed': text}, 'isError': False}})\n"
            + "    elif method == 'resources/list':\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'resources': [{'uri': 'file://guide.txt', 'name': 'guide',\n"
            + "                'description': 'Guide text', 'mimeType': 'text/plain'}]}})\n"
            + "    elif method == 'resources/read':\n"
            + "        uri = request['params']['uri']\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'contents': [{'uri': uri, 'mimeType': 'text/plain',\n"
            + "                'text': f'contents for {uri}'}]}})\n"
            + "    else:\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'error': {'code': -32601, 'message': f'unknown method: {method}'}})\n";

    private static final String MANAGER_MCP_SERVER_SOURCE = "#!/usr/bin/env python3\n" + "import json, os, sys\n"
            + "LABEL = os.environ.get('MCP_SERVER_LABEL', 'server')\n"
            + "LOG_PATH = os.environ.get('MCP_LOG_PATH')\n"
            + "EXIT_AFTER_TOOLS_LIST = os.environ.get('MCP_EXIT_AFTER_TOOLS_LIST') == '1'\n"
            + "FAIL_ONCE_MODE = os.environ.get('MCP_FAIL_ONCE_MODE')\n"
            + "FAIL_ONCE_MARKER = os.environ.get('MCP_FAIL_ONCE_MARKER')\n"
            + "initialize_count = 0\n"
            + "\n"
            + "def log(method):\n"
            + "    if LOG_PATH:\n"
            + "        with open(LOG_PATH, 'a', encoding='utf-8') as h:\n"
            + "            h.write(f'{method}\\n')\n"
            + "\n"
            + "def should_fail_once():\n"
            + "    if not FAIL_ONCE_MODE or not FAIL_ONCE_MARKER:\n"
            + "        return False\n"
            + "    if os.path.exists(FAIL_ONCE_MARKER):\n"
            + "        return False\n"
            + "    with open(FAIL_ONCE_MARKER, 'w', encoding='utf-8') as h:\n"
            + "        h.write(FAIL_ONCE_MODE)\n"
            + "    return True\n"
            + "\n"
            + "def read_message():\n"
            + "    header = b''\n"
            + "    while not header.endswith(b'\\r\\n\\r\\n'):\n"
            + "        chunk = sys.stdin.buffer.read(1)\n"
            + "        if not chunk:\n"
            + "            return None\n"
            + "        header += chunk\n"
            + "    length = 0\n"
            + "    for line in header.decode().split('\\r\\n'):\n"
            + "        if line.lower().startswith('content-length:'):\n"
            + "            length = int(line.split(':', 1)[1].strip())\n"
            + "    return json.loads(sys.stdin.buffer.read(length).decode())\n"
            + "\n"
            + "def send_message(message):\n"
            + "    payload = json.dumps(message).encode()\n"
            + "    sys.stdout.buffer.write(f'Content-Length: {len(payload)}\\r\\n\\r\\n'.encode() + payload)\n"
            + "    sys.stdout.buffer.flush()\n"
            + "\n"
            + "while True:\n"
            + "    request = read_message()\n"
            + "    if request is None:\n"
            + "        break\n"
            + "    method = request['method']\n"
            + "    log(method)\n"
            + "    if method == 'initialize':\n"
            + "        initialize_count += 1\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'protocolVersion': request['params']['protocolVersion'],\n"
            + "                       'capabilities': {'tools': {}},\n"
            + "                       'serverInfo': {'name': LABEL, 'version': '1.0.0'}}})\n"
            + "    elif method == 'tools/list':\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'tools': [{'name': 'echo', 'description': f'Echo tool for {LABEL}',\n"
            + "                'inputSchema': {'type': 'object', 'properties': {'text': {'type': 'string'}}, 'required': ['text']}}]}})\n"
            + "        if EXIT_AFTER_TOOLS_LIST:\n"
            + "            raise SystemExit(0)\n"
            + "    elif method == 'tools/call':\n"
            + "        if FAIL_ONCE_MODE == 'tool_call_disconnect' and should_fail_once():\n"
            + "            log('tools/call-disconnect')\n"
            + "            raise SystemExit(0)\n"
            + "        args = request['params'].get('arguments') or {}\n"
            + "        text = args.get('text', '')\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'content': [{'type': 'text', 'text': f'{LABEL}:{text}'}],\n"
            + "                       'structuredContent': {'server': LABEL, 'echoed': text,\n"
            + "                                              'initializeCount': initialize_count},\n"
            + "                       'isError': False}})\n"
            + "    elif method == 'resources/list':\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'resources': [{'uri': 'file://guide.txt', 'name': 'guide',\n"
            + "                'description': 'Guide text', 'mimeType': 'text/plain'}]}})\n"
            + "    elif method == 'resources/read':\n"
            + "        uri = request['params']['uri']\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'result': {'contents': [{'uri': uri, 'mimeType': 'text/plain',\n"
            + "                'text': f'contents for {uri}'}]}})\n"
            + "    else:\n"
            + "        send_message({'jsonrpc': '2.0', 'id': request['id'],\n"
            + "            'error': {'code': -32601, 'message': f'unknown method: {method}'}})\n";

    private static final String INITIALIZE_DISCONNECT_SOURCE = "#!/usr/bin/env python3\n" + "import sys\n"
            + "header = b''\n"
            + "while not header.endswith(b'\\r\\n\\r\\n'):\n"
            + "    chunk = sys.stdin.buffer.read(1)\n"
            + "    if not chunk:\n"
            + "        raise SystemExit(1)\n"
            + "    header += chunk\n"
            + "length = 0\n"
            + "for line in header.decode().split('\\r\\n'):\n"
            + "    if line.lower().startswith('content-length:'):\n"
            + "        length = int(line.split(':', 1)[1].strip())\n"
            + "if length:\n"
            + "    sys.stdin.buffer.read(length)\n"
            + "raise SystemExit(0)\n";
}
