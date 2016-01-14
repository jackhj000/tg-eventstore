package com.timgroup.eventsubscription.healthcheck

import com.timgroup.eventstore.api.Clock
import com.timgroup.tucker.info.Status
import com.timgroup.tucker.info.Status.{CRITICAL, WARNING, OK}
import org.joda.time.DateTime
import org.mockito.Mockito.{mock, when}
import org.scalatest.{FunSpec, MustMatchers}

class ChaserHealthTest extends FunSpec with MustMatchers {
  it("reports OK if chaser polled evenstore within last 5s") {
    val clock = mock(classOf[Clock])
    val now = new DateTime()
    val health = new ChaserHealth("", clock)

    when(clock.now()).thenReturn(now)
    health.chaserUpToDate(1)

    when(clock.now()).thenReturn(now.plusSeconds(4))

    health.getReport.getStatus must be(OK)
  }

  it("reports WARNING if chaser did not poll eventstore in over 5s") {
    val clock = mock(classOf[Clock])
    val now = new DateTime()
    val health = new ChaserHealth("", clock)

    when(clock.now()).thenReturn(now)
    health.chaserUpToDate(1)

    when(clock.now()).thenReturn(now.plusSeconds(6))

    health.getReport.getStatus must be(WARNING)
  }

  it("reports CRITICAL if chaser did not poll eventstore in over 30s") {
    val clock = mock(classOf[Clock])
    val now = new DateTime()
    val health = new ChaserHealth("", clock)

    when(clock.now()).thenReturn(now)
    health.chaserUpToDate(1)

    when(clock.now()).thenReturn(now.plusSeconds(31))

    health.getReport.getStatus must be(CRITICAL)
  }

  it("does not report CRITICAL if initial replay takes longer than 30s (still WARNING)") {
    val clock = mock(classOf[Clock])
    val now = new DateTime()
    val health = new ChaserHealth("", clock)

    when(clock.now()).thenReturn(now)
    health.chaserReceived(1)

    when(clock.now()).thenReturn(now.plusSeconds(31))

    health.getReport.getStatus must be(WARNING)
  }
}