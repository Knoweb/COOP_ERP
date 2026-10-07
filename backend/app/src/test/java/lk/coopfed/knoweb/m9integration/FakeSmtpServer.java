package lk.coopfed.knoweb.m9integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A relay that speaks just enough SMTP for JavaMail (RFC 5321: greeting, EHLO, MAIL, RCPT, DATA,
 * QUIT) and keeps every message it takes, as the raw text of the DATA section. {@link #failNext}
 * makes it refuse the next messages with a temporary error (451) at MAIL FROM, which is what a
 * relay that is down for a moment answers: the adapter throws and the kernel retries. The stand-in
 * for Mailpit in the tests (29A section 9: "SMTP against mailpit"), with no container to start.
 */
public final class FakeSmtpServer implements AutoCloseable {

    public final List<String> messages = new CopyOnWriteArrayList<>();
    public final AtomicInteger failNext = new AtomicInteger();

    private final ServerSocket socket;
    private final Thread acceptor;

    public FakeSmtpServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                serve(client);
            } catch (IOException e) {
                // A closed server, or a client that hung up: wait for the next one.
            }
        }
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
        reply(out, "220 fake-smtp ready");
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
            switch (command) {
                case "EHLO", "HELO" -> reply(out, "250 fake-smtp");
                case "MAIL" -> {
                    if (failNext.get() > 0) {
                        failNext.decrementAndGet();
                        reply(out, "451 try again later");
                    } else {
                        reply(out, "250 ok");
                    }
                }
                case "RCPT" -> reply(out, "250 ok");
                case "DATA" -> {
                    reply(out, "354 go on");
                    StringBuilder data = new StringBuilder();
                    String dataLine;
                    while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                        data.append(dataLine).append('\n');
                    }
                    messages.add(data.toString());
                    reply(out, "250 queued");
                }
                case "RSET", "NOOP" -> reply(out, "250 ok");
                case "QUIT" -> {
                    reply(out, "221 bye");
                    return;
                }
                default -> reply(out, "250 ok");
            }
        }
    }

    private static void reply(Writer out, String text) throws IOException {
        out.write(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
