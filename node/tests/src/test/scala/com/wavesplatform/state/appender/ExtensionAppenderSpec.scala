package com.wavesplatform.state.appender

import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.network.{ExtensionBlocks, InvalidBlockStorage, PeerDatabase}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.SystemTime
import com.wavesplatform.utx.UtxPoolImpl
import io.netty.channel.embedded.EmbeddedChannel
import monix.execution.Scheduler.Implicits.global

class ExtensionAppenderSpec extends FlatSpec with WithDomain {
  private val sender = TxHelpers.signer(1)

  "Extension appender" should "drop duplicate transactions from UTX" in
    withDomain(balances = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, sender)) { d =>
      val utx  = new UtxPoolImpl(SystemTime, d.blockchain, d.settings.utxSettings, d.settings.maxTxErrorLogSize, d.settings.minerSettings.enable)
      val time = TestTime()
      val extensionAppender =
        ExtensionAppender(d.blockchain, utx, d.posSelector, time, InvalidBlockStorage.NoOp, PeerDatabase.NoOp, global)(new EmbeddedChannel(), _)

      // The transfer is sent by someone other than the miner: a block's minimum valid timestamp depends on the
      // generating balance, so spending from the miner between building this block and validating it would push the
      // required delay up and get the block rejected as too early
      val tx     = TxHelpers.transfer(sender)
      val block1 = d.createBlock(Seq(tx), strictTime = true)
      utx.putIfNew(tx).resultE.explicitGet()
      d.appendBlock(tx)
      utx.all shouldBe Seq(tx)

      time.setTime(block1.header.timestamp)
      extensionAppender(ExtensionBlocks(d.blockchain.score + block1.blockScore(), Seq(block1), Map.empty, new EmbeddedChannel()))
        .runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
        .explicitGet()
      d.blockchain.height shouldBe 2
      utx.all shouldBe Nil
      utx.close()
    }

}
