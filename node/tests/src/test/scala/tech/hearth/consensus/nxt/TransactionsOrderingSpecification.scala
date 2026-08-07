package tech.hearth.consensus.nxt

import com.google.protobuf.ByteString
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.TransactionsOrdering
import tech.hearth.state.{AssetDescription, Height, MinAssetFee}
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
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
        Waves,
        125L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
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
        Waves,
        124L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        123L,
        Waves,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        123L,
        Waves,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      )
    )

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty, EmptyBlockchain))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool sorts an asset-fee transaction by its waves-equivalent fee density") {
    val asset  = IssuedAsset(ByteStr.fill(32)(1))
    val minFee = 1000L
    val blockchainWithAsset = new EmptyBlockchain {
      override def assetDescription(id: IssuedAsset): Option[AssetDescription] =
        if (id == asset) Some(AssetDescription(ByteString.EMPTY, ByteString.EMPTY, 0, BigInt(1), 0, Height(1), MinAssetFee.unsafeFrom(minFee)))
        else None
    }

    // fee = 10 * minFee in `asset` converts to (fee * FeeUnit / minFee) = 10 * FeeUnit waves-equivalent, dwarfing the
    // 124-wavelet Waves fee below - the asset-fee tx must sort first, not last as a zero-fee tx would.
    val assetFeeTx = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Waves, minFee * 10, asset, ByteStr.empty, 1)
    val wavesFeeTx = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Waves, 124L, Waves, ByteStr.empty, 2)
    val correctSeq = Seq(assetFeeTx, wavesFeeTx)

    val sorted = Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty, blockchainWithAsset))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool does not throw when the waves-equivalent fee overflows Long") {
    val asset  = IssuedAsset(ByteStr.fill(32)(2))
    val minFee = 1L
    val blockchainWithAsset = new EmptyBlockchain {
      override def assetDescription(id: IssuedAsset): Option[AssetDescription] =
        if (id == asset) Some(AssetDescription(ByteString.EMPTY, ByteString.EMPTY, 0, BigInt(1), 0, Height(1), MinAssetFee.unsafeFrom(minFee)))
        else None
    }

    // fee (Long.MaxValue) * FeeUnit (100000) / minFee (1) vastly exceeds Long range - must saturate, not throw
    val overflowingFeeTx = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Waves, Long.MaxValue, asset, ByteStr.empty, 1)
    val wavesFeeTx       = TxHelpers.transfer(kp, TxHelpers.address(20), 100000, Waves, 124L, Waves, ByteStr.empty, 2)
    val ordering         = TransactionsOrdering.InUTXPool(Set.empty, blockchainWithAsset)

    noException should be thrownBy ordering.compare(overflowingFeeTx, wavesFeeTx)
    Seq(wavesFeeTx, overflowingFeeTx).sorted(using ordering) shouldBe Seq(overflowingFeeTx, wavesFeeTx)
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        1,
        Waves,
        ByteStr.empty,
        124L
      ),
      TxHelpers.transfer(
        kp,
        TxHelpers.address(20),
        100000,
        Waves,
        1,
        Waves,
        ByteStr.empty,
        123L
      )
    )

    Random.shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock) shouldBe correctSeq
  }

}
