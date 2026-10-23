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
        volatile long finishedAt = 0L
    }

    /**
     * [file: File (NDJSON, one raw record a line), stats: Map the producer returned]. `producer(emit)` is run by the
     * first caller only; it calls emit(Map) once per raw record and returns its stats (pagination and counts).
     */
    static Map read(String key, Closure producer) {
        sweep()
        Entry mine = new Entry()
        Entry entry = READS.compute(key, { String k, Entry existing ->
            boolean reusable = existing != null &&
                    (!existing.result.isDone() || (!existing.result.isCompletedExceptionally() &&
                            clock.call() - existing.finishedAt < REUSE_MILLIS))
            return reusable ? existing : mine
        })
        if (entry.is(mine)) produce(key, mine, producer)
        else logger.info("oms shared read joined key={}", key)
        try {
            return entry.result.get()
        } catch (ExecutionException e) {
            throw (e.cause instanceof RuntimeException) ? (RuntimeException) e.cause : new RuntimeException(e.cause)
        }
    }

    private static void produce(String key, Entry entry, Closure producer) {
        File file = Files.createTempFile("darpan-oms-shared-", ".ndjson").toFile()
        long started = clock.call()
        try {
            Map stats
            file.withWriter(StandardCharsets.UTF_8.name()) { Writer w ->
                Closure emit = { Map record -> synchronized (w) { w.write(JsonOutput.toJson(record)); w.write("\n") } }
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
