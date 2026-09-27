package com.dsh.phoneact.mcp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/**
 * 长连接 SSE 响应。
 *
 * NanoHTTPD 2.3.1 的 Response#sendBody 是 private、chunkedTransfer 也是 private，
 * 因此这里直接覆写 protected 的 send(OutputStream)，自行写出 HTTP 头 + chunked 正文，
 * 并持续向同一条连接推送事件，直到客户端断开或显式 closeStream()。
 */
public class SseStreamResponse extends NanoHTTPD.Response {

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final String initialPayload;
    private volatile boolean closed = false;
    private volatile Runnable onClose;

    public SseStreamResponse(String initialPayload) {
        super(Status.OK, "text/event-stream; charset=utf-8", null, 0);
        this.initialPayload = initialPayload;
    }

    public void push(String event, String data) {
        if (closed) return;
        StringBuilder sb = new StringBuilder();
        if (event != null && !event.isEmpty()) {
            sb.append("event: ").append(event).append('\n');
        }
        String payload = data == null ? "" : data;
        for (String line : payload.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        queue.offer(sb.toString());
    }

    public void setOnClose(Runnable r) {
        this.onClose = r;
    }

    public void closeStream() {
        closed = true;
        queue.offer("");
    }

    @Override
    protected void send(OutputStream outputStream) {
        try {
            PrintWriter pw = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)), false);
            pw.append("HTTP/1.1 200 OK\r\n");
            pw.append("Content-Type: text/event-stream; charset=utf-8\r\n");
            pw.append("Cache-Control: no-cache, no-transform\r\n");
            pw.append("Connection: keep-alive\r\n");
            pw.append("Access-Control-Allow-Origin: *\r\n");
            pw.append("X-Accel-Buffering: no\r\n");
            pw.append("Transfer-Encoding: chunked\r\n");
            pw.append("\r\n");
            pw.flush();

            if (initialPayload != null && !initialPayload.isEmpty()) {
                writeChunk(outputStream, initialPayload.getBytes(StandardCharsets.UTF_8));
            }
            while (!closed) {
                String s = queue.poll(15, TimeUnit.SECONDS);
                if (s == null) {
                    writeChunk(outputStream, ": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (s.isEmpty()) break;
                writeChunk(outputStream, s.getBytes(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 客户端断开
        } finally {
            closed = true;
            try {
                outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();
            } catch (IOException ignored) {
                // 连接已关闭
            }
            Runnable r = onClose;
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // 清理回调异常不影响服务
                }
            }
        }
    }

    private static void writeChunk(OutputStream out, byte[] data) throws IOException {
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
