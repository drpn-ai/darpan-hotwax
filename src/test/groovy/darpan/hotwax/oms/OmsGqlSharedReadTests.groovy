package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-116: checks of one audit run that read the same OMS data for the same days read it ONCE. gorjana's OMS
 * refills 2000 cost/s, and R1/R3 (order lines) and R15 (exchange locations) each read every order item of the month,
 * as R8 (returns reach NetSuite) and R11 (warranty receipts) each read every return.
 */
class OmsGqlSharedReadTests {

    @BeforeEach
    void clean() { OmsGqlSharedRead.reset() }

    @AfterEach
    void after() { OmsGqlSharedRead.reset() }

    private static List<Map> lines(File f) {
        JsonSlurper s = new JsonSlurper()
        return f.readLines().findAll { it }.collect { (Map) s.parseText(it) }
    }

    @Test
    void twoCallersOfOneKeyRunTheReadOnceAndBothGetEveryRecord() {
        int produced = 0
        CountDownLatch bothAsked = new CountDownLatch(2)
        Closure producer = { Closure emit ->
            produced++
            bothAsked.await(5, TimeUnit.SECONDS)
            (1..3).each { emit.call([id: it]) }
            return [pageCount: 1]
        }
        def pool = Executors.newFixedThreadPool(2)
        try {
            List<Future> f = (1..2).collect {
                pool.submit({ ->
                    bothAsked.countDown()
                    OmsGqlSharedRead.read("k", producer)
                } as java.util.concurrent.Callable)
            }
            List<Map> got = f.collect { it.get(10, TimeUnit.SECONDS) as Map }
            assertEquals(1, produced, "the second caller waits on the first read")
            got.each { assertEquals([1, 2, 3], lines((File) it.file)*.id) }
            got.each { assertEquals(1, ((Map) it.stats).pageCount) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    void aFailedReadFailsItsCallerAndIsNotKept() {
        assertThrows(OmsGqlException) {
            OmsGqlSharedRead.read("k", { Closure emit -> throw new OmsGqlException("THROTTLED", "shut") })
        }
        Map again = OmsGqlSharedRead.read("k", { Closure emit -> emit.call([id: 9]); [:] })
        assertEquals([9], lines((File) again.file)*.id, "the next caller reads again")
    }

    @Test
    void aFinishedReadIsNotReusedOnceItsShortLifeIsOver() {
        long now = 1_000_000L
        OmsGqlSharedRead.clock = { -> now }
        int produced = 0
        Closure producer = { Closure emit -> produced++; emit.call([id: produced]); [:] }
        OmsGqlSharedRead.read("k", producer)
        now += OmsGqlSharedRead.REUSE_MILLIS - 1
        OmsGqlSharedRead.read("k", producer)
        assertEquals(1, produced, "a check starting moments later shares the read")
        now += 2
        Map fresh = OmsGqlSharedRead.read("k", producer)
        assertEquals(2, produced, "a later run reads fresh data")
        assertEquals([2], lines((File) fresh.file)*.id)
    }

    /** DAR-BE-129: a follower gets each record while the read is still producing, so its own work overlaps the read. */
    @Test
    void aFollowerGetsEachRecordBeforeTheReadIsDone() {
        CountDownLatch firstSeen = new CountDownLatch(1)
        List<Integer> seen = Collections.synchronizedList([])
        Map read = OmsGqlSharedRead.follow("k", { Closure emit ->
            emit.call([id: 1])
            // The read goes on only once the follower has had the first record.
            assertTrue(firstSeen.await(5, TimeUnit.SECONDS), "the follower never saw record 1 while the read ran")
            emit.call([id: 2])
            [pageCount: 2]
        }) { Map r -> seen.add(r.id as Integer); if (r.id == 1) firstSeen.countDown() }
        assertEquals([1, 2], seen)
        assertEquals(2, ((Map) read.stats).pageCount)
    }

    @Test
    void aReaderJoinsAReadAFollowerStartedAndItRunsOnce() {
        int produced = 0
        CountDownLatch release = new CountDownLatch(1)
        Closure producer = { Closure emit -> produced++; release.await(5, TimeUnit.SECONDS); emit.call([id: 7]); [:] }
        def pool = Executors.newFixedThreadPool(1)
        try {
            Future following = pool.submit({ -> OmsGqlSharedRead.follow("k", producer) { Map r -> } } as java.util.concurrent.Callable)
            Thread.sleep(300L)
            Thread reader = Thread.start { release.countDown() }
            Map read = OmsGqlSharedRead.read("k", producer)
            reader.join()
            following.get(10, TimeUnit.SECONDS)
            assertEquals(1, produced)
            assertEquals([7], lines((File) read.file)*.id)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    void aFailedReadFailsItsFollower() {
        assertThrows(OmsGqlException) {
            OmsGqlSharedRead.follow("k", { Closure emit -> emit.call([id: 1]); throw new OmsGqlException("THROTTLED", "shut") }) { Map r -> }
        }
    }
}
