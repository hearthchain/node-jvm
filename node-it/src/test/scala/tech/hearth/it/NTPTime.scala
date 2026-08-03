package tech.hearth.it

import tech.hearth.utils.NTP
import org.scalatest.{BeforeAndAfterAll, Suite}

trait NTPTime extends BeforeAndAfterAll { suite: Suite =>
  protected val ntpTime = new NTP("pool.ntp.org")

  override protected def afterAll(): Unit = {
    super.afterAll()
    ntpTime.close()
  }
}
