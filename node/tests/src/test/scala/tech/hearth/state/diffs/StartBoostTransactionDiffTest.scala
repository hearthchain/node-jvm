package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.dcap.DcapQuote
import tech.hearth.crypto.dcap.IntelPki.MaxCollateralFieldSize
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
  * mutate report_data and measurements on - none of these tests reach a point where the quote's own signatures need to verify.
  */
class StartBoostTransactionDiffTest extends FreeSpec with WithDomain {
  private val sender    = TxHelpers.defaultSigner
  private val validator = sender.toAddress

  private def hexResource(name: String): Array[Byte] =
    Base16.decode(new String(getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()).trim)

  private val baseQuote = hexResource("quotev4.hex")

  // Offsets into a v4 quote: header(48) + the TD1.0 report body's fields before each one (see DcapQuote).
  private val TdAttributesOffset = 48 + 120
  private val MrTdOffset         = 48 + 136
  private val Rtmr0Offset        = 48 + 328
  private val ReportDataOffset   = 48 + 520

  private val pinned = StartBoostTransactionDiff.PinnedMeasurements.head

  private def quoteWithReportData(
      reportData: Array[Byte],
      measurement: StartBoostTransactionDiff.TdMeasurement = pinned,
      tdAttributes: Array[Byte] = Base16.decode("0000001000000000")
  ): ByteStr = {
    require(reportData.length == 64)
    val mutated = baseQuote.clone()
    System.arraycopy(tdAttributes, 0, mutated, TdAttributesOffset, 8)
    System.arraycopy(measurement.mrTd.arr, 0, mutated, MrTdOffset, 48)
    measurement.rtmrs.zipWithIndex.foreach { case (rtmr, i) => System.arraycopy(rtmr.arr, 0, mutated, Rtmr0Offset + 48 * i, 48) }
    System.arraycopy(reportData, 0, mutated, ReportDataOffset, 64)
    ByteStr(mutated)
  }

  private def flipFirstByte(b: ByteStr): ByteStr = ByteStr(b.arr.updated(0, (b.arr(0) ^ 1).toByte))

  // Any 32 non-zero bytes work as the enclave key: the diff only extracts and registers it, never verifies it
  // cryptographically (the quote's hardware signature is what vouches for report_data).
  private val enclaveKey = PublicKey(TxHelpers.signer(5).publicKey())

  private def freshReportData(d: Domain, key: PublicKey = enclaveKey): Array[Byte] =
    d.lastBlockId.arr ++ key.arr

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

    "rejects a tdxQuote larger than MaxCollateralFieldSize, independent of the REST layer's own limit" in {
      val oversized = ByteStr(new Array[Byte](MaxCollateralFieldSize + 1))
      StartBoostTransaction.create(
        PublicKey(sender.publicKey()),
        validator,
        oversized,
        Height(1),
        TestValues.fee,
        TxHelpers.timestamp,
        Proofs.empty
      ) should produce(s"exceeds maximum of $MaxCollateralFieldSize bytes")
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
      val reportData     = unknownBlockId ++ enclaveKey.arr
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(reportData), nextPeriodStart)
      ) should produce("does not reference a known block")
    }

    "rejects a quote of the current block, whose id changes with every microblock" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))
      d.appendKeyBlock()

      d.appendMicroBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(freshReportData(d)), nextPeriodStart)
      ) should produce("outside the freshness window")
    }

    "below strictTxValidationHeight, lets through a quote of the current block or of an unknown one" in withDomain(
      DeterministicFinality.configure(_.copy(strictTxValidationHeight = Int.MaxValue)),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))
      d.appendKeyBlock()

      // Past the freshness check, the next one to fail is the missing PCK CRL.
      d.appendMicroBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(freshReportData(d)), nextPeriodStart)
      ) should produce("PCK CRL must be set")
      d.appendMicroBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(Array.fill(32)(1.toByte) ++ enclaveKey.arr), nextPeriodStart)
      ) should produce("PCK CRL must be set")
    }

    "pins the measurement of testnet's TEE miner image" in {
      val quote                           = DcapQuote.parse(hexResource("testnet-startboost-51018.hex")).explicitGet()
      val DcapQuote.Td10QuoteBody(report) = quote.body: @unchecked
      StartBoostTransactionDiff.PinnedMeasurements should contain(StartBoostTransactionDiff.TdMeasurement(report))
    }

    "rejects a quote whose MRTD is not pinned" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val quote = quoteWithReportData(freshReportData(d), pinned.copy(mrTd = flipFirstByte(pinned.mrTd)))
      d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("is not pinned")
    }

    // RTMR3 alone proves nothing: an unpinned kernel (RTMR1) can extend RTMR3 with the expected event log.
    "rejects a quote whose RTMR0-2 are not pinned, even with a pinned MRTD and RTMR3" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      (0 to 2).foreach { i =>
        val quote = quoteWithReportData(freshReportData(d), pinned.copy(rtmrs = pinned.rtmrs.updated(i, flipFirstByte(pinned.rtmrs(i)))))
        d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("is not pinned")
      }
    }

    "rejects a debug TD, whose memory the host can read, even with pinned measurements" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val quote = quoteWithReportData(freshReportData(d), tdAttributes = Base16.decode("0100001000000000"))
      d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("Debug TDs are not accepted")
    }

    "rejects a migratable TD, whose memory a migration TD can move, even with pinned measurements" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val quote = quoteWithReportData(freshReportData(d), tdAttributes = Base16.decode("0000003000000000"))
      d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("Migratable TDs are not accepted")
    }

    "rejects a TD with reserved attribute bits set" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      // Bit 1 (low reserved range) and bit 40 (high reserved range), each on top of the fixture's SEPT_VE_DISABLE.
      Seq("0200001000000000", "0000001000010000").foreach { attributes =>
        val quote = quoteWithReportData(freshReportData(d), tdAttributes = Base16.decode(attributes))
        d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("set reserved bits")
      }
    }

    "accepts every attribute bit a hardened TD may set: SEPT_VE_DISABLE, PKS, KL, PERFMON" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val quote = quoteWithReportData(freshReportData(d), tdAttributes = Base16.decode("000000d000000080"))
      d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("PCK CRL must be set")
    }

    "below strictTxValidationHeight, lets through a quote with unpinned measurements" in withDomain(
      DeterministicFinality.configure(_.copy(strictTxValidationHeight = Int.MaxValue)),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val quote = quoteWithReportData(freshReportData(d), pinned.copy(mrTd = flipFirstByte(pinned.mrTd)))
      d.appendBlockE(TxHelpers.startBoost(sender, validator, quote, nextPeriodStart)) should produce("PCK CRL must be set")
    }

    "rejects a quote whose report data carries an all-zero enclave key" in withDomain(
      DeterministicFinality,
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val nextPeriodStart = d.blockchain.currentGenerationPeriod.get.next.start
      d.appendBlock(TxHelpers.commitToGeneration(nextPeriodStart, sender))

      val reportData = d.lastBlockId.arr ++ new Array[Byte](32)
      d.appendBlockE(
        TxHelpers.startBoost(sender, validator, quoteWithReportData(reportData), nextPeriodStart)
      ) should produce("all-zero enclave key")
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
