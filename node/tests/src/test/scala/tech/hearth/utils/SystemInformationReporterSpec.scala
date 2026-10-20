package tech.hearth.utils

import com.typesafe.config.ConfigFactory
import tech.hearth.test.FreeSpec

class SystemInformationReporterSpec extends FreeSpec {
  private val config = ConfigFactory.parseString(
    """hearth.miner {
      |  quorum = 3
      |  accounts = [
      |    { mnemonic = "secret words", signing-account = 0, vrf-account = 0, bls-account = 0 },
      |    { signing-key-scalar = "00ff", vrf-key = "01ff", bls-key = "02ff" }
      |  ]
      |}
      |""".stripMargin
  )

  "the logged configuration" - {
    "leaves out the miner's account keys" in {
      SystemInformationReporter.configForLogs(config).hasPath("hearth.miner.accounts") shouldBe false
    }

    "keeps the rest of the miner settings" in {
      SystemInformationReporter.configForLogs(config).getInt("hearth.miner.quorum") shouldBe 3
    }
  }
}
