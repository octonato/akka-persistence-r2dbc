/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.r2dbc

import java.time.Instant
import java.time.{ Duration => JDuration }

import scala.concurrent.ExecutionContext

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.persistence.r2dbc.query.TimestampOffset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.internal.ManagementState
import akka.projection.r2dbc.internal.R2dbcOffsetStore
import akka.projection.r2dbc.internal.R2dbcOffsetStore.Pid
import akka.projection.r2dbc.internal.R2dbcOffsetStore.Record
import akka.projection.r2dbc.internal.R2dbcOffsetStore.SeqNr
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

class R2dbcTimestampOffsetStoreSpec
    extends ScalaTestWithActorTestKit(TestConfig.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  override def typedSystem: ActorSystem[_] = system

  private val clock = new TestClock
  def tick(): Unit = clock.tick(JDuration.ofMillis(1))

  private val log = LoggerFactory.getLogger(getClass)

  private val settings = R2dbcProjectionSettings(testKit.system)

  private def createOffsetStore(projectionId: ProjectionId, customSettings: R2dbcProjectionSettings = settings) =
    new R2dbcOffsetStore(projectionId, system, customSettings, r2dbcExecutor)

  def createEnvelope(
      pid: Pid,
      seqNr: SeqNr,
      timestamp: Instant,
      readAfterMillis: Int,
      event: String): EventEnvelope[String] =
    EventEnvelope(
      TimestampOffset(timestamp, timestamp.plusMillis(readAfterMillis), Map(pid -> seqNr)),
      pid,
      seqNr,
      event,
      timestamp.toEpochMilli)

  private implicit val ec: ExecutionContext = system.executionContext

  "The R2dbcOffsetStore for TimestampOffset" must {

    "update TimestampOffset with one entry" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      readOffset2.futureValue shouldBe Some(offset2) // yep, saveOffset overwrites previous
    }

    "update TimestampOffset with several entries" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L, "p3" -> 6L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      // p2 is not included in read offset because it wasn't updated and has earlier timestamp
      readOffset2.futureValue shouldBe Some(offset2)
    }

    "update TimestampOffset when same timestamp" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      // not tick, same timestamp
      val offset2 = TimestampOffset(clock.instant(), Map("p2" -> 2L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      // all should be included since same timestamp
      val expectedOffset2 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 2L, "p3" -> 5L, "p4" -> 9L))
      readOffset2.futureValue shouldBe Some(expectedOffset2)

      // saving new with later timestamp
      tick()
      val offset3 = TimestampOffset(clock.instant(), Map("p1" -> 4L))
      offsetStore.saveOffset(offset3).futureValue
      val readOffset3 = offsetStore.readOffset[TimestampOffset]()
      // then it should only contain that entry
      readOffset3.futureValue shouldBe Some(offset3)
    }

    "not update when earlier seqNr" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L))
      offsetStore.saveOffset(offset1).futureValue
      val readOffset1 = offsetStore.readOffset[TimestampOffset]()
      readOffset1.futureValue shouldBe Some(offset1)

      clock.setInstant(clock.instant().minusMillis(1))
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 2L))
      offsetStore.saveOffset(offset2).futureValue
      val readOffset2 = offsetStore.readOffset[TimestampOffset]()
      readOffset2.futureValue shouldBe Some(offset1) // keeping offset1
    }

    "filter duplicates" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      tick()
      val offset1 = TimestampOffset(clock.instant(), Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue
      tick()
      val offset2 = TimestampOffset(clock.instant(), Map("p1" -> 4L, "p3" -> 6L, "p4" -> 9L))
      offsetStore.saveOffset(offset2).futureValue
      tick()
      val offset3 = TimestampOffset(clock.instant(), Map("p5" -> 10L))
      offsetStore.saveOffset(offset3).futureValue

      offsetStore.isDuplicate(Record("p5", 10, offset3.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p1", 4, offset2.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p3", 6, offset2.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p4", 9, offset2.timestamp)) shouldBe true

      offsetStore.isDuplicate(Record("p1", 3, offset1.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p2", 1, offset1.timestamp)) shouldBe true
      offsetStore.isDuplicate(Record("p3", 5, offset1.timestamp)) shouldBe true

      offsetStore.isDuplicate(Record("p1", 2, offset1.timestamp.minusMillis(1))) shouldBe true
      offsetStore.isDuplicate(Record("p5", 9, offset3.timestamp.minusMillis(1))) shouldBe true

      offsetStore.isDuplicate(Record("p5", 11, offset3.timestamp)) shouldBe false
      offsetStore.isDuplicate(Record("p5", 12, offset3.timestamp.plusMillis(1))) shouldBe false

      offsetStore.isDuplicate(Record("p6", 1, offset3.timestamp.plusMillis(2))) shouldBe false
      offsetStore.isDuplicate(Record("p7", 1, offset3.timestamp.minusMillis(1))) shouldBe false
    }

    "accept known sequence numbers and reject unknown" in {
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      val startTime = Instant.now()
      val offset1 = TimestampOffset(startTime, Map("p1" -> 3L, "p2" -> 1L, "p3" -> 5L))
      offsetStore.saveOffset(offset1).futureValue

      // seqNr 1 is always accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")) shouldBe true
      // but not if already seen, seqNr 1 was accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")) shouldBe false
      // subsequent seqNr is accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p4", 2L, startTime.plusMillis(2), 10, "e4-2")) shouldBe true
      // but not when gap
      offsetStore.isSequenceNumberAccepted(createEnvelope("p4", 4L, startTime.plusMillis(3), 10, "e4-4")) shouldBe false
      // and not if later already seen, seqNr 2 was accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p4", 1L, startTime.plusMillis(1), 10, "e4-1")) shouldBe false

      // +1 to known is accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p1", 4L, startTime.plusMillis(4), 10, "e1-4")) shouldBe true
      // but not same
      offsetStore.isSequenceNumberAccepted(createEnvelope("p3", 5L, startTime, 10, "e3-5")) shouldBe false
      // but not same, even if it's 1
      offsetStore.isSequenceNumberAccepted(createEnvelope("p2", 1L, startTime, 10, "e2-1")) shouldBe false
      // and not less
      offsetStore.isSequenceNumberAccepted(createEnvelope("p3", 4L, startTime, 10, "e3-4")) shouldBe false

      // +1 to known, and then also subsequent are accepted (needed for grouped)
      offsetStore.isSequenceNumberAccepted(createEnvelope("p3", 6L, startTime.plusMillis(5), 10, "e3-6")) shouldBe true
      offsetStore.isSequenceNumberAccepted(createEnvelope("p3", 7L, startTime.plusMillis(6), 10, "e3-7")) shouldBe true
      offsetStore.isSequenceNumberAccepted(createEnvelope("p3", 8L, startTime.plusMillis(7), 10, "e3-8")) shouldBe true

      // reject unknown
      offsetStore.isSequenceNumberAccepted(createEnvelope("p5", 7L, startTime.plusMillis(8), 10, "e5-7")) shouldBe false
      // but ok when read later
      offsetStore.isSequenceNumberAccepted(
        createEnvelope("p5", 7L, startTime.plusMillis(5), 10000, "e5-7")) shouldBe true
      // and subsequent seqNr is accepted
      offsetStore.isSequenceNumberAccepted(createEnvelope("p5", 8L, startTime.plusMillis(9), 10, "e5-8")) shouldBe true

      // it's keeping the seen that are not in the "stored" state
      offsetStore.getSeen() shouldBe Map("p1" -> 4L, "p3" -> 8L, "p4" -> 2L, "p5" -> 8L)
      // and they are removed from seen once they have been stored
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(2), Map("p4" -> 2L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plusMillis(9), Map("p5" -> 8L))).futureValue
      offsetStore.getSeen() shouldBe Map("p1" -> 4L, "p3" -> 8L)
    }

    "evict old records" in {
      val projectionId = genRandomProjectionId()
      val evictSettings = settings.copy(timeWindow = JDuration.ofSeconds(100), evictInterval = JDuration.ofSeconds(10))
      import evictSettings._
      val offsetStore = createOffsetStore(projectionId, evictSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(1)), Map("p2" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(2)), Map("p3" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(evictInterval), Map("p4" -> 1L))).futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(1)), Map("p4" -> 1L)))
        .futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(2)), Map("p5" -> 1L)))
        .futureValue
      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(evictInterval).plus(JDuration.ofSeconds(3)), Map("p6" -> 1L)))
        .futureValue
      offsetStore.getState().size shouldBe 6

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(10)), Map("p7" -> 1L))).futureValue
      offsetStore.getState().size shouldBe 7 // nothing evicted yet

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).minusSeconds(3)), Map("p8" -> 1L)))
        .futureValue
      offsetStore.getState().size shouldBe 8 // still nothing evicted yet

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).plusSeconds(1)), Map("p8" -> 2L)))
        .futureValue
      offsetStore.getState().byPid.keySet shouldBe Set("p5", "p6", "p7", "p8")

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.plus(evictInterval).plusSeconds(20)), Map("p8" -> 3L)))
        .futureValue
      offsetStore.getState().byPid.keySet shouldBe Set("p7", "p8")
    }

    "delete old records" in {
      val projectionId = genRandomProjectionId()
      val deleteSettings = settings.copy(timeWindow = JDuration.ofSeconds(100))
      import deleteSettings._
      val offsetStore = createOffsetStore(projectionId, deleteSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(1)), Map("p2" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(2)), Map("p3" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(JDuration.ofSeconds(3)), Map("p4" -> 1L))).futureValue
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 0
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().size shouldBe 4

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(2)), Map("p5" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.minusSeconds(1)), Map("p6" -> 1L))).futureValue
      // nothing deleted yet
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 0
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().size shouldBe 6

      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(1)), Map("p7" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(2)), Map("p8" -> 1L))).futureValue
      offsetStore.deleteOldTimestampOffsets().futureValue shouldBe 2
      offsetStore.readOffset().futureValue // this will load from database
      offsetStore.getState().byPid.keySet shouldBe Set("p3", "p4", "p5", "p6", "p7", "p8")
    }

    "periodically delete old records" in {
      val projectionId = genRandomProjectionId()
      val deleteSettings =
        settings.copy(timeWindow = JDuration.ofSeconds(100), deleteInterval = JDuration.ofMillis(500))
      import deleteSettings._
      val offsetStore = createOffsetStore(projectionId, deleteSettings)

      val startTime = Instant.now()
      log.debug("Start time [{}]", startTime)

      offsetStore.saveOffset(TimestampOffset(startTime, Map("p1" -> 1L))).futureValue
      offsetStore.saveOffset(TimestampOffset(startTime.plus(timeWindow.plusSeconds(1)), Map("p2" -> 1L))).futureValue
      eventually {
        offsetStore.readOffset().futureValue // this will load from database
        offsetStore.getState().byPid.keySet shouldBe Set("p2")
      }

      offsetStore
        .saveOffset(TimestampOffset(startTime.plus(timeWindow.multipliedBy(2).plusSeconds(2)), Map("p3" -> 1L)))
        .futureValue
      eventually {
        offsetStore.readOffset().futureValue // this will load from database
        offsetStore.getState().byPid.keySet shouldBe Set("p3")
      }
    }

    "clear offset" in {
      pending // FIXME not implemented yet
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      offsetStore.saveOffset(3L).futureValue
      offsetStore.readOffset[Long]().futureValue shouldBe Some(3L)

      offsetStore.clearOffset().futureValue
      offsetStore.readOffset[Long]().futureValue shouldBe None
    }

    "read and save paused" in {
      pending // FIXME not implemented yet
      val projectionId = genRandomProjectionId()
      val offsetStore = createOffsetStore(projectionId)

      offsetStore.readManagementState().futureValue shouldBe None

      offsetStore.savePaused(paused = true).futureValue
      offsetStore.readManagementState().futureValue shouldBe Some(ManagementState(paused = true))

      offsetStore.savePaused(paused = false).futureValue
      offsetStore.readManagementState().futureValue shouldBe Some(ManagementState(paused = false))
    }
  }
}
