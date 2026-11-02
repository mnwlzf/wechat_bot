package com.example.wechatbot.tts;

import com.example.wechatbot.config.TtsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用一个手写的最小 WebSocket 服务端跑通「run-task → task-started → continue-task → finish-task
 * → 二进制音频 → task-finished」这条链路，验证客户端的事件顺序和二进制拼包。
 */
class QwenTtsClientTest {
    private static final byte[] AUDIO_HEAD = "PCM-HEAD".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AUDIO_TAIL = "PCM-TAIL".getBytes(StandardCharsets.US_ASCII);

    private FakeServer server;
    private TtsProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeServer();
        server.start();
        properties = new TtsProperties();
        properties.setApiKey("sk-test-key");
        properties.setBaseUrl("ws://127.0.0.1:" + server.port() + "/api-ws/v1/inference");
        properties.setTimeout(Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private QwenTtsClient client() {
        return new QwenTtsClient(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("完整合成：事件顺序对、二进制帧拼起来就是音频")
    void synthesizesAudio() throws Exception {
        QwenTtsClient.Result result = client().synthesize("你好，世界", "longanhuan_v3.1", "pcm", 24000);

        byte[] expected = new byte[AUDIO_HEAD.length + AUDIO_TAIL.length];
        System.arraycopy(AUDIO_HEAD, 0, expected, 0, AUDIO_HEAD.length);
        System.arraycopy(AUDIO_TAIL, 0, expected, AUDIO_HEAD.length, AUDIO_TAIL.length);
        assertThat(result.audio()).isEqualTo(expected);
        assertThat(result.format()).isEqualTo("pcm");
        assertThat(result.sampleRate()).isEqualTo(24000);
        assertThat(result.chunks()).isEqualTo(2);

        assertThat(server.runTask).contains("\"action\":\"run-task\"")
                .contains("\"model\":\"qwen-audio-3.1-tts-flash\"")
                .contains("\"voice\":\"longanhuan_v3.1\"")
                .contains("\"format\":\"pcm\"")
                .contains("\"sample_rate\":24000")
                .contains("\"streaming\":\"duplex\"")
                .contains("\"task_group\":\"audio\"")
                .contains("\"function\":\"SpeechSynthesizer\"");
        assertThat(server.continueTask).contains("你好，世界");
        assertThat(server.finishTask).contains("\"action\":\"finish-task\"");
        // 一次合成的三个事件必须共用一个 task_id
        assertThat(taskIdOf(server.runTask)).isEqualTo(taskIdOf(server.continueTask));
        assertThat(taskIdOf(server.runTask)).isEqualTo(taskIdOf(server.finishTask));
        assertThat(server.authorization).containsIgnoringCase("bearer sk-test-key");
    }

    @Test
    @DisplayName("task-failed 要变成带错误码的异常，而不是空音频")
    void surfacesTaskFailure() {
        server.failTask = true;

        assertThatThrownBy(() -> client().synthesize("hi", null, null, 0))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("InvalidApiKey")
                .hasMessageContaining("bad key");
    }

    @Test
    @DisplayName("没配 API Key / 文本为空 / 文本太长都在本地拦掉，不发请求")
    void validatesInput() {
        properties.setApiKey("");
        assertThatThrownBy(() -> client().synthesize("hi", null, null, 0))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");

        properties.setApiKey("sk-test-key");
        properties.setMaxChars(5);
        assertThatThrownBy(() -> client().synthesize("0123456789", null, null, 0))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("超过");
        assertThatThrownBy(() -> client().synthesize("   ", null, null, 0))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("为空");
    }

    private static String taskIdOf(String json) {
        int index = json.indexOf("\"task_id\":\"");
        assertThat(index).isGreaterThan(-1);
        int start = index + "\"task_id\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    /** 手写的最小 WebSocket 服务端（只够跑这个场景）。 */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String runTask = "";
        private volatile String continueTask = "";
        private volatile String finishTask = "";
        private volatile String authorization = "";
        private volatile boolean failTask;
        private Thread thread;

        FakeServer() throws IOException {
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void start() {
            thread = new Thread(this::serve, "fake-tts");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                handshake(in, out);
                loop(in, out);
            } catch (Exception ex) {
                // 连接被关掉就结束
            } finally {
                done.countDown();
            }
        }

        private void handshake(DataInputStream in, OutputStream out) throws Exception {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("handshake closed");
                }
                head.write(b);
                if (head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                    break;
                }
            }
            String request = head.toString(StandardCharsets.ISO_8859_1);
            for (String line : request.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("authorization")) {
                    authorization = line.substring(colon + 1).trim();
                }
            }
            String key = "";
            for (String line : request.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private void loop(DataInputStream in, OutputStream out) throws Exception {
            while (true) {
                int[] opcode = new int[1];
                byte[] payload = readFrame(in, opcode);
                if (opcode[0] == 0x8) {
                    return;
                }
                if (opcode[0] == 0x9) {
                    writeFrame(out, 0xA, payload);
                    continue;
                }
                if (opcode[0] != 0x1) {
                    continue;
                }
                String text = new String(payload, StandardCharsets.UTF_8);
                if (text.contains("\"run-task\"")) {
                    runTask = text;
                    writeFrame(out, 0x1, ("{\"header\":{\"event\":\"task-started\","
                            + "\"task_id\":\"" + taskIdOf(text) + "\"},\"payload\":{}}")
                            .getBytes(StandardCharsets.UTF_8));
                } else if (text.contains("\"continue-task\"")) {
                    continueTask = text;
                } else if (text.contains("\"finish-task\"")) {
                    finishTask = text;
                    if (failTask) {
                        writeFrame(out, 0x1, ("{\"header\":{\"event\":\"task-failed\",\"error_code\":\"InvalidApiKey\","
                                + "\"error_message\":\"bad key\"}}").getBytes(StandardCharsets.UTF_8));
                    } else {
                        writeFrame(out, 0x2, AUDIO_HEAD);
                        writeFrame(out, 0x2, AUDIO_TAIL);
                        writeFrame(out, 0x1, ("{\"header\":{\"event\":\"task-finished\","
                                + "\"task_id\":\"" + taskIdOf(text) + "\"},\"payload\":{}}")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }

        private static byte[] readFrame(DataInputStream in, int[] opcode) throws IOException {
            int first = in.read();
            int second = in.read();
            if (first < 0 || second < 0) {
                throw new IOException("closed");
            }
            opcode[0] = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                in.readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            return payload;
        }

        private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            int length = payload.length;
            if (length < 126) {
                frame.write(length);
            } else if (length <= 0xFFFF) {
                frame.write(126);
                frame.write((length >> 8) & 0xFF);
                frame.write(length & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) (((long) length >> (8 * i)) & 0xFF));
                }
            }
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            try {
                done.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
