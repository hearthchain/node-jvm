package tech.hearth.consensus.nxt

import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.TransactionsOrdering
import tech.hearth.state.{AssetDescription, Height, MinAssetFee}
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.{Asset, TxHelpers}
import tech.hearth.crypto.SigningKey
import tech.hearth.utils.EmptyBlockchain

import scala.util.Random

class TransactionsOrderingSpecification extends PropSpec {

  private val kp: SigningKey = TxHelpers.defaultSigner
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        125L,
        Hearth,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Hearth,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Hearth,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      )
    )

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Hearth,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        123L,
        Hearth,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        123L,
        Hearth,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      )
    )

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty, EmptyBlockchain))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool sorts an asset-fee transaction by its hearth-equivalent fee density") {
    val asset  = IssuedAsset(ByteStr.fill(32)(1))
    val minFee = 1000L
    val blockchainWithAsset = new EmptyBlockchain {
      override def assetDescription(id: IssuedAsset): Option[AssetDescription] =
        if (id == asset) Some(AssetDescription("", "", 0, BigInt(1), 0, Height(1), MinAssetFee.unsafeFrom(minFee)))
        else None
    }

    // fee = 10 * minFee in `asset` converts to (fee * FeeUnit / minFee) = 10 * FeeUnit hearth-equivalent, dwarfing the
    // 124-ember Hearth fee below - the asset-fee tx must sort first, not last as a zero-fee tx would.
    val assetFeeTx  = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Hearth, minFee * 10, asset, ByteStr.empty, 1)
    val hearthFeeTx = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Hearth, 124L, Hearth, ByteStr.empty, 2)
    val correctSeq  = Seq(assetFeeTx, hearthFeeTx)

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty, blockchainWithAsset))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool does not throw when the hearth-equivalent fee overflows Long") {
    val asset  = IssuedAsset(ByteStr.fill(32)(2))
    val minFee = 1L
    val blockchainWithAsset = new EmptyBlockchain {
      override def assetDescription(id: IssuedAsset): Option[AssetDescription] =
        if (id == asset) Some(AssetDescription("", "", 0, BigInt(1), 0, Height(1), MinAssetFee.unsafeFrom(minFee)))
        else None
    }

    // fee (Long.MaxValue) * FeeUnit (100000) / minFee (1) vastly exceeds Long range - must saturate, not throw.
    // amount is 0 (not e.g. 100000) because TransferTxValidator's own noOverflow check sums fee + every transfer
    // amount and would otherwise reject construction of this transaction outright, before ordering ever sees it.
    val overflowingFeeTx = TxHelpers.transfer(kp, TxHelpers.address(20), 0, Hearth, Long.MaxValue, asset, ByteStr.empty, 1)
    val hearthFeeTx      = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Hearth, 124L, Hearth, ByteStr.empty, 2)
    val ordering         = TransactionsOrdering.InUTXPool(Set.empty, blockchainWithAsset)

    noException should be thrownBy ordering.compare(overflowingFeeTx, hearthFeeTx)
    Seq(hearthFeeTx, overflowingFeeTx).sorted(using ordering) shouldBe Seq(overflowingFeeTx, hearthFeeTx)
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        1,
        Hearth,
        ByteStr.empty,
        124L
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Hearth,
        1,
        Hearth,
        ByteStr.empty,
        123L
      )
    )

    Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock) shouldBe correctSeq
  }

}
