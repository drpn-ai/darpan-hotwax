package darpan.hotwax.oms

import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * DAR-BE-116. One OMS read for the checks that want the same data: the first caller of a key runs the read and spills
 * its raw records to an NDJSON file; callers arriving while it runs, or within REUSE_MILLIS after, wait for and share
 * that file. Each caller then applies its OWN filters, projection and counts, so the key must name only what decides
 * the raw records (endpoint, kind, window, query shape), never a check's filters.
 *
 * Why: gorjana's OMS refills 2000 cost/s, and one audit run's checks read the same month twice over (order lines for R1/R3
 * and R15; returns for R8 and R11). Fails closed: a read that throws fails every caller waiting on it and is dropped,
 * so the next attempt reads again. The short reuse window keeps a re-run from ever seeing a previous run's data.
 */
class OmsGqlSharedRead {

    private static final Logger logger = LoggerFactory.getLogger(OmsGqlSharedRead.class)

    /** How long a FINISHED read is still handed to a caller of the same key: the checks of one run start together. */
    static final long REUSE_MILLIS = 120_000L

    private static final Map<String, Entry> READS = new ConcurrentHashMap<>()
    static Closure<Long> clock = { -> System.currentTimeMillis() }

    private static class Entry {
        final CompletableFuture<Map> result = new CompletableFuture<>()
        final File file = Files.createTempFile("darpan-oms-shared-", ".ndjson").toFile()
        volatile long finishedAt = 0L
        boolean claimed = false
    }

    /**
     * [file: File (NDJSON, one raw record a line), stats: Map the producer returned]. `producer(emit)` is run by the
     * first caller only; it calls emit(Map) once per raw record and returns its stats (pagination and counts).
     */
    static Map read(String key, Closure producer) {
        Entry entry = entryFor(key)
        if (entry.result.isDone() || !claim(entry)) logger.info("oms shared read joined key={}", key)
        else produce(key, entry, producer)
        return await(entry)
    }

    /**
     * DAR-BE-129. As read, but each record is handed to `onRecord` as the read writes it, not after the whole read: a
     * caller with work of its own per record (the order-items read, per batch of orders) overlaps it with the read.
     * A read this call starts runs on a thread of its own. Answers the read's [file, stats] once it is complete.
     */
    static Map follow(String key, Closure producer, Closure onRecord) {
        Entry entry = entryFor(key)
        Thread producing = null
        if (!entry.result.isDone() && claim(entry)) {
            producing = Thread.start("oms-shared-read") { produce(key, entry, producer) }
        } else {
            logger.info("oms shared read followed key={}", key)
        }
        groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()
        RandomAccessFile tail
        try {
            tail = new RandomAccessFile(entry.file, "r")
        } catch (FileNotFoundException ignored) {
            return await(entry)   // a read that failed already removed its file: its failure is the answer
        }
        ByteArrayOutputStream partial = new ByteArrayOutputStream()
        byte[] buffer = new byte[65536]
        try {
            while (true) {
                // Done is read BEFORE the bytes: once a finished read's bytes are taken, nothing more is coming.
                boolean done = entry.result.isDone()
                int taken = 0
                int n
                while ((n = tail.read(buffer)) > 0) {
                    taken += n
                    int start = 0
                    for (int i = 0; i < n; i++) {
                        if (buffer[i] != (byte) 10) continue
                        partial.write(buffer, start, i - start)
                        String line = partial.toString(StandardCharsets.UTF_8.name())
                        partial.reset()
                        start = i + 1
                        if (line) onRecord.call((Map) slurper.parseText(line))
                    }
                    partial.write(buffer, start, n - start)
                }
                if (done && taken == 0) break
                if (taken == 0) Thread.sleep(200L)
            }
        } finally {
            tail.close()
        }
        producing?.join()
        return await(entry)
    }

    /** The key's live or reusable read, or a new entry for a caller to claim and produce. */
    private static Entry entryFor(String key) {
        sweep()
        Entry mine = new Entry()
        Entry entry = READS.compute(key, { String k, Entry existing ->
            boolean reusable = existing != null &&
                    (!existing.result.isDone() || (!existing.result.isCompletedExceptionally() &&
                            clock.call() - existing.finishedAt < REUSE_MILLIS))
            return reusable ? existing : mine
        })
        if (!entry.is(mine)) mine.file.delete()
        return entry
    }

    /** True for the one caller that is to produce the entry's read. */
    private static boolean claim(Entry entry) {
        synchronized (entry) {
            if (entry.claimed) return false
            entry.claimed = true
            return true
        }
    }

    private static Map await(Entry entry) {
        try {
            return entry.result.get()
        } catch (ExecutionException e) {
            throw (e.cause instanceof RuntimeException) ? (RuntimeException) e.cause : new RuntimeException(e.cause)
        }
    }

    private static void produce(String key, Entry entry, Closure producer) {
        File file = entry.file
        long started = clock.call()
        try {
            Map stats
            file.withWriter(StandardCharsets.UTF_8.name()) { Writer w ->
                // Flushed per record, so a follower reads each record as it is written.
                Closure emit = { Map record -> synchronized (w) { w.write(JsonOutput.toJson(record)); w.write("\n"); w.flush() } }
                stats = (Map) producer.call(emit) ?: [:]
            }
            entry.finishedAt = clock.call()
            logger.info("oms shared read produced key={} ms={} bytes={}", key, entry.finishedAt - started, file.length())
            entry.result.complete([file: file, stats: stats])
        } catch (Throwable t) {
            READS.remove(key, entry)
            file.delete()
            entry.result.completeExceptionally(t)
        }
    }

    /** Drops finished reads past their reuse window, and their files. */
    private static void sweep() {
        long now = clock.call()
        READS.each { String key, Entry e ->
            if (e.result.isDone() && (e.result.isCompletedExceptionally() || now - e.finishedAt >= REUSE_MILLIS * 5)) {
                if (READS.remove(key, e) && !e.result.isCompletedExceptionally()) ((File) e.result.get().file)?.delete()
            }
        }
    }

    static void reset() {
        READS.values().each { Entry e -> if (e.result.isDone() && !e.result.isCompletedExceptionally()) ((File) e.result.get().file)?.delete() }
        READS.clear()
        clock = { -> System.currentTimeMillis() }
    }
}
