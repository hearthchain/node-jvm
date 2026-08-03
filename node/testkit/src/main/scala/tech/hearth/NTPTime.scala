package tech.hearth

import tech.hearth.utils.{SystemTime, Time}
import org.scalatest.Suite

trait NTPTime { suite: Suite =>
  protected val ntpTime: Time = SystemTime

  protected def ntpNow: Long = ntpTime.correctedTime()
}
