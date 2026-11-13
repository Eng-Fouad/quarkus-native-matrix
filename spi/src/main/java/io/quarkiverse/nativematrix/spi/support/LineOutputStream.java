package io.quarkiverse.nativematrix.spi.support;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * An {@link OutputStream} that decodes UTF-8 bytes and hands complete lines to a consumer. Handy to forward the output
 * of remote commands (SSH channels, container logs...) to a {@link io.quarkiverse.nativematrix.spi.BuildLog}.
 */
public final class LineOutputStream extends OutputStream {

    private final Consumer<String> consumer;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

    public LineOutputStream(Consumer<String> consumer) {
        this.consumer = consumer;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            flushLine();
        } else {
            buffer.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    private void flushLine() {
        String line = buffer.toString(StandardCharsets.UTF_8);
        buffer.reset();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        consumer.accept(line);
    }

    @Override
    public synchronized void close() {
        if (buffer.size() > 0) {
            flushLine();
        }
    }
}
