package org.jclaude.runtime.mcp.jsonrpc.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StdioTransportTest {

    @Test
    void encode_frame_prefixes_content_length_header() {
        byte[] payload = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        byte[] framed = StdioTransport.encodeFrame(payload);
        String text = new String(framed, StandardCharsets.UTF_8);
        assertThat(text).startsWith("Content-Length: " + payload.length + "\r\n\r\n");
        assertThat(text).endsWith("{\"hello\":\"world\"}");
    }
}
