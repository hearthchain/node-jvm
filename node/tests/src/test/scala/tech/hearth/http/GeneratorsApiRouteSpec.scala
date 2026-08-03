package tech.hearth.http

import cats.syntax.option.*
import tech.hearth.TestValues.commitToGenerationFee
import tech.hearth.api.http.{GeneratorsApiRoute, RouteTimeout}
import tech.hearth.block.{BlockEndorsement, FinalizationVoting}
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.db.{WithDomain, WithState}
import tech.hearth.history.Domain
import tech.hearth.settings.WavesSettings
import tech.hearth.state.{GenerationPeriod, GeneratorIndex, Height, diffs}
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}
import monix.execution.Scheduler.global
import org.apache.pekko.http.scaladsl.model.StatusCodes.{NotFound, OK}
import org.scalactic.source.Position
import play.api.libs.json.{JsArray, JsNull, JsObject, Json}
import tech.hearth.crypto.SigningKey

import scala.annotation.targetName
import scala.concurrent.duration.*

/** Finalization is active from genesis now, so periods are anchored at height 1: with a length of 3 they are [1, 3],
  * [4, 6], [7, 9], ... The genesis snapshot commits the miner for the first of them, and the scenario is:
  *   1. Commit on 2, which lands on period1 = [4, 6]
  *   2. Append blocks up to 5 - the last block of a period is too late to commit for the next one, see `commit`
  *   3. Issue a conflicting endorsement there
  *   4. Commit for period2 = [7, 9]
  *   5. Append into period2
  */
class GeneratorsApiRouteSpec extends RouteSpec("/generators") with WithDomain {
  private val generationPeriodLength = 3
  private val defaultSettings: WavesSettings = {
    val orig = DomainPresets.DeterministicFinality
    orig.copy(
      blockchainSettings = orig.blockchainSettings.copy(
        functionalitySettings = orig.blockchainSettings.functionalitySettings.copy(generationPeriodLength = generationPeriodLength)
      )
    )
  }

  private val generators = Seq(TxHelpers.signer(1000), TxHelpers.signer(1001), TxHelpers.defaultSigner)

  private val Seq(validGenerator, conflictingGenerator, miner)             = generators
  private val Seq(validGeneratorAddr, conflictingGeneratorAddr, minerAddr) = generators.map(_.toAddress.toString)

  private val depositAndFee = CommitToGenerationTransaction.DepositInWavelets + commitToGenerationFee
  private val initBalance   = diffs.ENOUGH_AMT + depositAndFee

  private val defaultInitBalances: Seq[WithState.AddrWithBalance] = generators.map(x => AddrWithBalance(x.toAddress, initBalance))

  // The period the genesis snapshot commits the miner for, and the three that follow
  private val genesisPeriod = GenerationPeriod(Height(1), generationPeriodLength)
  private val period1       = genesisPeriod.next
  private val period2       = period1.next
  private val period3       = period2.next

  "/generators/at/{height}" - {
    "committed to the period 1" in test { d =>
      d.appendBlock()
      val txIds = d.commit(generators*)

      // Every height has a generation period now - `Blockchain.generationPeriodOf` no longer returns None below the
      // activation height - so height 1 answers with what the genesis snapshot committed: the miner, with no
      // transaction behind it
      d.checkAt(1) {
        jsonBodyIs(Json.obj("address" -> minerAddr, "transactionId" -> JsNull, "balance" -> 0))
      }
      // Height 2 is in the same period as height 1, so it answers with the same generator - with a balance this time,
      // since the height is the chain's own and the generator set for it is known. The deposit is locked out of it.
      val genesisCommitted = Json.obj(
        "address"       -> minerAddr,
        "transactionId" -> JsNull,
        "balance"       -> (initBalance - CommitToGenerationTransaction.DepositInWavelets)
      )
      d.checkAt(2) { jsonBodyIs(genesisCommitted) }
      d.checkAt() { jsonBodyIs(genesisCommitted) }

      d.checkAt(period1.start) {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds(0).toString
          ),
          Json.obj(
            "address"       -> conflictingGeneratorAddr,
            "transactionId" -> txIds(1).toString
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds(2).toString
          )
        )
      }

      d.checkAt(period2.start) { status shouldBe NotFound }
    }

    "on start height of period1" in test { d =>
      d.appendBlock()
      val txIds                = d.commit(generators*)
      val minerBalanceAtCommit = d.effBalance(miner.toAddress)

      d.appendUpTo(period1.end - 1)
      val minerBalanceInPeriod1 = d.effBalance(miner.toAddress)

      d.checkAt(period1.start) {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> conflictingGeneratorAddr,
            "transactionId" -> txIds(1).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds(2).toString,
            "balance"       -> minerBalanceAtCommit
          )
        )
      }

      d.checkAt() {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> conflictingGeneratorAddr,
            "transactionId" -> txIds(1).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds(2).toString,
            "balance"       -> minerBalanceInPeriod1
          )
        )
      }

      d.checkAt(period2.start) { jsonBodyIsEmpty() }
      d.checkAt(period3.start) { status shouldBe NotFound }
    }

    "one generator is conflicting" in test { d =>
      d.appendBlock()
      val txIds                = d.commit(generators*)
      val minerBalanceAtCommit = d.effBalance(miner.toAddress)

      d.appendUpTo(period1.end - 1)
      val minerBalanceInPeriod1 = d.effBalance(miner.toAddress)
      d.appendConflicting()

      d.checkAt(period1.start) {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> conflictingGeneratorAddr,
            "transactionId" -> txIds(1).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds(2).toString,
            "balance"       -> minerBalanceAtCommit
          )
        )
      }

      d.checkAt() {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"        -> conflictingGeneratorAddr,
            "transactionId"  -> txIds(1).toString,
            "balance"        -> 0,
            "conflictHeight" -> d.blockchain.height
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds(2).toString,
            "balance"       -> minerBalanceInPeriod1
          )
        )
      }

      d.checkAt(period2.start) { jsonBodyIsEmpty() }
      d.checkAt(period3.start) { status shouldBe NotFound }
    }

    "on commit to period2" in test { d =>
      d.appendBlock()
      val txIds1 = d.commit(generators*)

      d.appendUpTo(period1.end - 1)
      val minerBalanceInPeriod1 = d.effBalance(miner.toAddress)
      d.appendConflicting()
      val txIds2 = d.commit(validGenerator, miner)

      d.checkAt() {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds1(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"        -> conflictingGeneratorAddr,
            "transactionId"  -> txIds1(1).toString,
            "balance"        -> 0,
            "conflictHeight" -> d.blockchain.height
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds1(2).toString,
            "balance"       -> minerBalanceInPeriod1
          )
        )
      }

      d.checkAt(period2.start) {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds2(0).toString
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds2(1).toString
          )
        )
      }

      d.checkAt(period3.start) { status shouldBe NotFound }
    }

    "on the period 2" in test { d =>
      d.appendBlock()
      val txIds1               = d.commit(generators*)
      val minerBalanceAtCommit = d.effBalance(miner.toAddress)

      d.appendUpTo(period1.end - 1)
      d.appendConflicting()
      val txIds2 = d.commit(validGenerator, miner)

      d.appendUpTo(period2.start + 1)
      val minerBalanceInPeriod2 = d.effBalance(miner.toAddress)

      d.checkAt(period1.start) {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds1(0).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> conflictingGeneratorAddr,
            "transactionId" -> txIds1(1).toString,
            "balance"       -> (initBalance - depositAndFee)
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds1(2).toString,
            "balance"       -> minerBalanceAtCommit
          )
        )
      }

      d.checkAt() {
        jsonBodyIs(
          Json.obj(
            "address"       -> validGeneratorAddr,
            "transactionId" -> txIds2(0).toString,
            "balance"       -> (initBalance - 2 * depositAndFee)
          ),
          Json.obj(
            "address"       -> minerAddr,
            "transactionId" -> txIds2(1).toString,
            "balance"       -> minerBalanceInPeriod2
          )
        )
      }
    }
  }

  private def test(f: Domain => Unit): Unit = withDomain(defaultSettings, balances = defaultInitBalances)(f)

  private def jsonBodyIsEmpty()(using position: Position): Unit = jsonBodyIs()

  private def jsonBodyIs(expectedItems: JsObject*)(using position: Position): Unit = {
    status shouldBe OK
    responseAs[JsArray] should matchJson(JsArray(expectedItems))
  }

  extension (d: Domain) {
    def commit(generators: SigningKey*): Seq[ByteStr] = {
      val generationPeriod = {
        val p = d.blockchain.currentGenerationPeriod.value.next
        if (p.start == Height(d.blockchain.height + 1)) p.next
        else p
      }

      val txs = generators.map(x => TxHelpers.commitToGeneration(generationPeriod.start, sender = x))
      d.appendMicroBlock(d.createMicroBlock(signer = miner.some)(txs*))
      txs.map(_.id())
    }

    /** Blocks generated by the miner up to and including `height`. */
    def appendUpTo(height: Height): Unit =
      (d.blockchain.height + 1 to height.toInt).foreach(_ => d.appender.appendBlock(d.createBlock(strictTime = true, generator = miner)))

    def appendConflicting(): Unit = d.appendMicroBlock(
      d.createMicroBlock(
        signer = miner.some,
        finalizationVoting = FinalizationVoting(
          valid = Nil,
          finalizedHeight = Height(1),
          aggregatedEndorsement = None,
          conflict = Vector(
            BlockEndorsement.signed(
              // Index 1 of the committed set is the conflicting generator, and an endorsement only counts as its own
              // if it is signed by the BLS key that generator committed
              TxHelpers.blsKeyOf(conflictingGenerator),
              GeneratorIndex(1),
              finalizedId = TxHelpers.randomBlockId,
              finalizedHeight = Height(1),
              endorsedId = d.blockchain.lastBlockId.value
            )
          )
        ).some
      )(TxHelpers.transfer())
    )

    @targetName("checkAtHeight") def checkAt(height: Height)(f: => Unit): Unit = checkAt(height.toInt)(f)
    def checkAt(height: Int = d.blockchain.height)(f: => Unit): Unit           = Get(s"/generators/at/$height") ~> route ~> check(f)

    def route = seal(
      GeneratorsApiRoute(
        d.settings.restAPISettings,
        d.blockchain,
        d.generatorsApi,
        d.testTime,
        new RouteTimeout(60.seconds)(using global)
      ).route
    )
  }
}
