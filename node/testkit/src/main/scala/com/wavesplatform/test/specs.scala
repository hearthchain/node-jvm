package com.wavesplatform.test

import com.wavesplatform.utils.ScorexLogging
import tech.hearth.crypto.Address
import com.wavesplatform.{EitherMatchers, TransactionGen}
import org.scalacheck.ShrinkLowPriority
import org.scalatest.*
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

trait BaseSuite
    extends matchers.should.Matchers
    with ScalaCheckPropertyChecks
    with ShrinkLowPriority
    with TransactionGen
    with EitherMatchers
    with EitherValues
    with OptionValues
    with ScorexLogging {
  this: Suite =>
  BaseSuite.configureDefaultNetwork()
}

object BaseSuite {

  /** Addresses are bech32m, so rendering or parsing one needs a process-wide default network. Tests run on the 'T'
    * scheme, see TestRocksDB.createTestBlockchainSettings. A node has to do the same at startup.
    */
  def configureDefaultNetwork(): Unit = Address.setDefaultHrp("thrth")
}

abstract class FunSuite extends funsuite.AnyFunSuite with BaseSuite

abstract class FlatSpec extends flatspec.AnyFlatSpec with BaseSuite

abstract class FeatureSpec extends featurespec.AnyFeatureSpec with BaseSuite

abstract class FreeSpec extends freespec.AnyFreeSpec with BaseSuite

abstract class PropSpec extends propspec.AnyPropSpec with BaseSuite
