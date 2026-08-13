package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Proofs, StartBoostTransaction, TxHelpers}

/** No real, currently-valid PCK CRL fixture is available to vendor (Intel's DCAP PKI has no static "PCK CRL for
  * this specific PCK CA" sample the way a Root CA CRL/TCB Signing CA chain does - even upstream dcap-rs's own test
  * suite fetches this collateral live from Intel PCS at test time rather than vendoring it, which this repo's
  * tests never do, see SOURCE.md). So the deepest deterministically-reachable reject path here is "PCK CRL not
  * set" - the accept path, and the PCK-chain/QE/ISV signature checks beyond it, aren't covered by this suite;
  * IntelPkiTest's synthetic-fixture groups already cover that verification logic in isolation, with an injectable
  * trust anchor.
  *
  * The real TDX quote fixtures (quotev4.hex, quotev5.dat) are only used to get a *structurally valid* quote to
  * mutate report_data on - none of these tests reach a point where the quote's own signatures need to verify.
  */
class StartBoostTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender    = TxHelpers.defaultSigner
  private val validator = sender.toAddress

  private def hexResource(name: String): Array[Byte] =
    Base16.decode(new String(getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()).trim)

  private val baseQuote = hexResource("quotev4.hex")

  // header(48) + TD1.0 report body's own fields up to user_report_data(64B, the body's last field, see DcapQuote).
  private val ReportDataOffset = 48 + 520

  private def quoteWithReportData(reportData: Array[Byte]): ByteStr = {
    require(reportData.length == 64)
    val mutated = baseQuote.clone()
    System.arraycopy(reportData, 0, mutated, ReportDataOffset, 64)
    ByteStr(mutated)
  }

  private def freshReportData(d: Domain, sender: PublicKey = PublicKey(sender.publicKey())): Array[Byte] =
    d.lastBlockId.arr ++ sender.arr

  "StartBoostTxValidator" - {
    "rejects an SGX quote outright" in {
      val sgxQuote = ByteStr(hexResource("quotev3.hex"))
      StartBoostTransaction.create(
        PublicKey(sender.publicKey()),
        validator,
        sgxQuote,
        Height(1),
        TestValues.fee,
        TxHelpers.timestamp,
        Proofs.empty
      ) shouldBe a[Left[?, ?]]
    }
  }

  "StartBoostTransactionDiff" - {
    "rejects a period start that doesn't match the next period" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val wrongPeriodStart = Height(d.blockchain.currentGenerationPeriod.get.next.start.toInt + 1)
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(freshReportData(d)), wrongPeriodStart)
      ) should produce("Expected the next period start height")
    }

    "rejects when the validator is not a committed generator of the next period" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(freshReportData(d)), nextPeriodStart)
      ) should produce("is not a committed generator")
    }

    "rejects a quote whose report data doesn't reference a known block" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val unknownBlockId = Array.fill(32)(1.toByte)
      val reportData     = unknownBlockId ++ PublicKey(sender.publicKey()).arr
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(reportData), nextPeriodStart)
      ) should produce("does not reference a known block")
    }

    "rejects a quote whose report data doesn't commit to the sender" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val someoneElse = PublicKey(TxHelpers.signer(2).publicKey())
      val reportData  = d.lastBlockId.arr ++ someoneElse.arr
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(reportData), nextPeriodStart)
      ) should produce("does not commit to this transaction's sender")
    }

    "rejects when the PCK CRL has not been set on chain yet" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      d.blockchain.dcapPckCrl shouldBe None
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(freshReportData(d)), nextPeriodStart)
      ) should produce("PCK CRL must be set")
    }
  }
}
