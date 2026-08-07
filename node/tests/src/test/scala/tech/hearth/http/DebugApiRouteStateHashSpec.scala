package tech.hearth.http

import com.typesafe.config.ConfigObject
import tech.hearth.*
import tech.hearth.api.http.{DebugApiRoute, RouteTimeout}
import tech.hearth.block.Block
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.mining.TestMiner
import tech.hearth.network.PeerDatabase
import tech.hearth.settings.WavesSettings
import tech.hearth.state.{Blockchain, Height}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.SharedSchedulerMixin
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

class DebugApiRouteStateHashSpec
    extends RouteSpec("/debug"),
      RestAPISettingsHelper,
      TestWallet,
      NTPTime,
      SharedDomain,
      OptionValues,
      SharedSchedulerMixin {

  override def settings: WavesSettings = DomainPresets.TransactionStateSnapshot
    .copy(
      dbSettings = DomainPresets.TransactionStateSnapshot.dbSettings.copy(storeStateHashes = true),
      restAPISettings = restAPISettings
    )
    .configure(_.copy(generationPeriodLength = 5))

  private val configObject: ConfigObject = settings.config.root()

  private val secondGenerator = TxHelpers.signer(906)
  private val thirdGenerator  = TxHelpers.signer(907)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(TxHelpers.defaultSigner.toAddress, 10_000.waves),
    AddrWithBalance(secondGenerator.toAddress, 11_000.waves),
    AddrWithBalance(thirdGenerator.toAddress, 12_000.waves)
  )

  val block: Block = TestBlock.create(Nil).block

  val debugApiRoute: DebugApiRoute =
    DebugApiRoute(
      settings,
      ntpTime,
      domain.blockchain,
      domain.accountsApi,
      domain.transactionsApi,
      domain.assetsApi,
      PeerDatabase.NoOp,
      new ConcurrentHashMap(),
      (blockId, _) => Task(domain.blockchain.removeAfter(blockId).map(_ => ())),
      domain.utxPool,
      TestMiner.SafelyDisabled,
      null,
      null,
      null,
      null,
      configObject,
      domain.rocksDBWriter,
      new RouteTimeout(60.seconds)(using sharedScheduler),
      sharedScheduler
    )

  private val route = seal(debugApiRoute.route)

  routePath("/stateHash") - {
    "works" - {
      "before and after DeterministicFinality activation" in {
        // Append first block to be able to request stateHash
        domain.appendBlock()

        val commitTxDefault = TxHelpers.commitToGeneration(generationPeriodStart = Height(6), sender = TxHelpers.defaultSigner)
        val commitTxSecond  = TxHelpers.commitToGeneration(generationPeriodStart = Height(6), sender = secondGenerator)
        val commitTxThird   = TxHelpers.commitToGeneration(generationPeriodStart = Height(6), sender = thirdGenerator)
        domain.appendBlock(commitTxDefault, commitTxSecond, commitTxThird)
        domain.appendBlock()

        // Assert after commitment, before generation period
        val afterGeneratingBalanceUpdateHeight = domain.blockchain.height - 1
        val afterGeneratingBalanceUpdateHeader = domain.blockchain.blockHeader(afterGeneratingBalanceUpdateHeight).value
        val expectedResponseAfter = Json.obj(
          "stateHash"                      -> "88d74439c56a50252a40af206954021451ae6999598cdbb5e4dc3a92bedac6b5",
          "wavesBalanceHash"               -> "877c92d02612bbef786e6e69d5c213047607c083ea641f46ab99943d1d5c2d22",
          "assetBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "nextCommittedGeneratorsHash"    -> "525627f76ff443e594f531dbba23e65a90cdd4ad42d2e26c471a191da0047b51", // Note: non-empty
          "committedGeneratorBalancesHash" -> "46bcf0aad5ea9fed3548ca5c582823ad540a8ebbeec2de5e889c5d042b2f19ca",
          "snapshotHash"                   -> "f2c6c78fb25303d3d3f7aee4ea08ff989904cb8f24cc3451f555e6cde9754f80",
          "blockId"                        -> afterGeneratingBalanceUpdateHeader.id().toString,
          "baseTarget"                     -> afterGeneratingBalanceUpdateHeader.header.baseTarget,
          "height"                         -> afterGeneratingBalanceUpdateHeight,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/$afterGeneratingBalanceUpdateHeight")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseAfter
        }

        // Note: the generating balances are used on this height (parent block for heightOnGenerationPeriod)
        domain.blockchain.generatingBalance(TxHelpers.defaultSigner.toAddress) shouldBe 980802000000L
        domain.blockchain.generatingBalance(secondGenerator.toAddress) shouldBe 1089990000000L
        domain.blockchain.generatingBalance(thirdGenerator.toAddress) shouldBe 1189990000000L

        // Fast-forward to generation period change
        domain.appendBlock() // heightOnGenerationPeriod
        domain.appendBlock() // add 1 more block for API requests

        // Assert after commitment, on generation period
        val heightOnGenerationPeriod = domain.blockchain.height - 1
        val headerOnGenerationPeriod = domain.blockchain.blockHeader(heightOnGenerationPeriod).value
        val expectedResponseAfter2 = Json.obj(
          "stateHash"                      -> "f0fe639be392fc4e6ed1ba411da7172d9f93775df0dc2c1cc98f073591821a22",
          "wavesBalanceHash"               -> "571af75a738868f8a2316a48943e6fccc714a55d1e429537a307d1533fa6f796",
          "assetBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "nextCommittedGeneratorsHash"    -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "committedGeneratorBalancesHash" -> "246896d0f7e188baff9c31b80c56a55a2b58e7105b24cd12bf941fe27c8e7fb5", // Note: non-empty
          "snapshotHash"                   -> "87277ac4a8262ba5de28d0555742778e23e146ce84a869b8a03eea27e4fc44dc",
          "blockId"                        -> headerOnGenerationPeriod.id().toString,
          "baseTarget"                     -> headerOnGenerationPeriod.header.baseTarget,
          "height"                         -> heightOnGenerationPeriod,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/last")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseAfter2
        }
      }
    }
  }
}
