package tech.hearth.http

import com.typesafe.config.ConfigObject
import tech.hearth.*
import tech.hearth.api.http.{DebugApiRoute, RouteTimeout}
import tech.hearth.block.Block
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.mining.TestMiner
import tech.hearth.network.PeerDatabase
import tech.hearth.settings.HearthSettings
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.SharedSchedulerMixin
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

class DebugApiRouteStateHashGenesisSpec
    extends RouteSpec("/debug")
    with RestAPISettingsHelper
    with TestWallet
    with NTPTime
    with SharedDomain
    with OptionValues
    with SharedSchedulerMixin {

  override def settings: HearthSettings = DomainPresets.DeterministicFinality
    .copy(
      dbSettings = DomainPresets.DeterministicFinality.dbSettings.copy(storeStateHashes = true),
      restAPISettings = restAPISettings
    )

  private val configObject: ConfigObject = settings.config.root()

  private val richAccount = TxHelpers.signer(905)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 50_000.hearth))

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
      "with DeterministicFinality activated on genesis block" in {
        // Append first block to be able to request stateHash
        domain.appendBlock()

        val genesisHeight      = 1
        val genesisBlockHeader = domain.blockchain.blockHeader(genesisHeight).value
        val expectedResponse = Json.obj(
          "stateHash"         -> "d2ba495b9f3ffded5d9f53a518d77713b89b1e9273b603b08c8484712a3a8330",
          "hearthBalanceHash" -> "13efdbee131f5f02c49c7558a185a5933946754bb0f9258d9823660f363bebd3",
          "assetBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          // Note: "nextCommittedGeneratorsHash" and "committedGeneratorBalancesHash" fields are present
          "nextCommittedGeneratorsHash"    -> "be516123bf31843e0b6003004cb2f0e445e5007e04bfd4b2951b4a2d70588bc4",
          "committedGeneratorBalancesHash" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "snapshotHash"                   -> "7692c55cfa0e8ae0d26f53e5e95c3e49094801d737f9e8208af26f0a6027eb94",
          "blockId"                        -> genesisBlockHeader.id().toString,
          "baseTarget"                     -> genesisBlockHeader.header.baseTarget,
          "height"                         -> genesisHeight,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/$genesisHeight")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponse
        }
      }
    }
  }
}
