package com.mars.harness.kernel.adapters.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.ports.event.ExecutionEventStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The execution event log, {@code events/events.jsonl}: one canonical JSON event per line.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li><b>append-only</b>: the file is only ever extended, never rewritten</li>
 *   <li><b>strictly increasing sequence</b>: the next sequence is read from the file under an
 *       exclusive lock, so appends from several threads, several store instances or several
 *       processes (the CLI and the Control Center) interleave into one gap-free sequence</li>
 *   <li><b>torn-write tolerant</b>: a crash mid-append leaves at most one unterminated fragment.
 *       The next append terminates it first, and readers report it as malformed instead of
 *       mis-parsing it. Only newline-terminated lines are ever parsed.</li>
 * </ul>
 *
 * <p>This is a run-area writer: it never touches tracked source.
 */
public final class FilesystemExecutionEventStore implements ExecutionEventStore {

    /** In-process serialization per file: {@link FileChannel#lock()} is per JVM, not per thread. */
    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();

    /** A batch of events read from a byte offset, for tailing readers. */
    public record Chunk(List<ExecutionEvent> events, long nextOffset, int malformedLines) {
        public Chunk {
            events = List.copyOf(events);
        }
    }

    private final Path file;
    private final Object monitor;
    private long cachedSize;
    private long cachedLastSequence;

    public FilesystemExecutionEventStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.monitor = MONITORS.computeIfAbsent(this.file, k -> new Object());
    }

    public Path file() {
        return file;
    }

    @Override
    public ExecutionEvent append(ExecutionEvent draft) {
        synchronized (monitor) {
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                    long size = channel.size();
                    long last = lastSequenceLocked(channel, size);
                    ExecutionEvent sealed = draft.sealed(HarnessIds.allocate(HarnessIds.Kind.EVENT), last + 1);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (size > 0 && lastByte(channel, size) != '\n') {
                        out.write('\n'); // terminate a torn fragment left by a crash; it stays reported as malformed
                    }
                    out.write((KernelJson.canonical(sealed) + "\n").getBytes(StandardCharsets.UTF_8));
                    ByteBuffer buffer = ByteBuffer.wrap(out.toByteArray());
                    long position = size;
                    while (buffer.hasRemaining()) {
                        position += channel.write(buffer, position);
                    }
                    cachedSize = position;
                    cachedLastSequence = sealed.sequence();
                    return sealed;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot append execution event to " + file, e);
            }
        }
    }

    @Override
    public List<ExecutionEvent> readAfter(long afterSequence, int limit) {
        List<ExecutionEvent> result = new ArrayList<>();
        for (ExecutionEvent event : readFrom(0).events()) {
            if (event.sequence() > afterSequence) {
                result.add(event);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public long lastSequence() {
        synchronized (monitor) {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                return lastSequenceLocked(channel, channel.size());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read execution events " + file, e);
            }
        }
    }

    /**
     * Every complete event line at or after {@code byteOffset} (which must be a line boundary
     * previously returned as {@link Chunk#nextOffset()}, or 0). The returned offset is the end of
     * the last complete line, so a partially written line is read again, whole, next time.
     */
    public Chunk readFrom(long byteOffset) {
        if (!Files.isRegularFile(file)) {
            return new Chunk(List.of(), 0, 0);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = byteOffset > size ? 0 : byteOffset;
            Parsed parsed = parse(channel, start, size);
            return new Chunk(parsed.events, parsed.endOfLastLine, parsed.malformed);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read execution events " + file, e);
        }
    }

    // ------------------------------------------------------------------ internals

    private long lastSequenceLocked(FileChannel channel, long size) throws IOException {
        if (size == 0) {
            cachedSize = 0;
            cachedLastSequence = 0;
            return 0;
        }
        if (size == cachedSize) {
            return cachedLastSequence;
        }
        // the file grew (another instance or process appended) or is unknown: scan only what is new
        long from = size > cachedSize ? cachedSize : 0;
        long last = size > cachedSize ? cachedLastSequence : 0;
        Parsed parsed = parse(channel, from, size);
        for (ExecutionEvent event : parsed.events) {
            last = Math.max(last, event.sequence());
        }
        cachedSize = size;
        cachedLastSequence = last;
        return last;
    }

    private static int lastByte(FileChannel channel, long size) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        channel.read(one, size - 1);
        return one.get(0);
    }

    private record Parsed(List<ExecutionEvent> events, long endOfLastLine, int malformed) {
    }

    private static Parsed parse(FileChannel channel, long from, long to) throws IOException {
        List<ExecutionEvent> events = new ArrayList<>();
        int malformed = 0;
        long lineStart = from;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long position = from;
        while (position < to) {
            buffer.clear();
            int read = channel.read(buffer, position);
            if (read <= 0) {
                break;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                position++;
                if (b == '\n') {
                    if (line.size() > 0) {
                        ExecutionEvent event = decode(line.toString(StandardCharsets.UTF_8));
                        if (event == null) {
                            malformed++;
                        } else {
                            events.add(event);
                        }
                    }
                    line.reset();
                    lineStart = position;
                } else {
                    line.write(b);
                }
            }
        }
        return new Parsed(events, lineStart, malformed);
    }

    private static ExecutionEvent decode(String text) {
        try {
            JsonNode node = KernelJson.parse(text);
            ExecutionEvent event = KernelJson.convert(node, ExecutionEvent.class);
            return event.sequence() > 0 && event.type() != null ? event : null;
        } catch (UncheckedIOException | IllegalArgumentException e) {
            return null;
        }
    }
}
