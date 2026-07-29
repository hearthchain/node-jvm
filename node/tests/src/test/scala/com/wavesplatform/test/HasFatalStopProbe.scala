package com.wavesplatform.test

import com.wavesplatform.utils.ApplicationStopReason

import java.util.concurrent.Semaphore

/** Observes that a component asked the node to shut down.
  *
  * This used to be done by installing a SecurityManager and trapping `System.exit`, which JDK 25 removed. Instead the
  * components that can stop the node take their exit action as a parameter (`onFatalStop`), and a test passes the probe
  * below in place of the real one.
  *
  * Note the difference from trapping the exit: the component *continues* past the call rather than unwinding, so a test
  * sees whatever the method goes on to return.
  */
trait HasFatalStopProbe {

  protected class FatalStopProbe(expected: ApplicationStopReason) {
    private val signal                        = new Semaphore(0)
    @volatile private var reason: Option[ApplicationStopReason] = None

    /** Pass as the component's `onFatalStop`. */
    val onFatalStop: ApplicationStopReason => Unit = { r =>
      reason = Some(r)
      if (r == expected) signal.release()
    }

    /** The reason the component stopped the node with, or None if it never did. */
    def stopReason: Option[ApplicationStopReason] = reason

    /** Waits for the expected shutdown, since it may be triggered from another thread. */
    def awaitStop(timeout: java.time.Duration = java.time.Duration.ofSeconds(10)): Boolean =
      signal.tryAcquire(timeout.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  protected def fatalStopProbe(expected: ApplicationStopReason): FatalStopProbe = new FatalStopProbe(expected)
}
