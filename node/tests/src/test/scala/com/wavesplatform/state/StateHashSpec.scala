package com.wavesplatform.state

import com.google.common.primitives.Longs
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.StateHash.Section
import com.wavesplatform.test.FreeSpec
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxHelpers

import scala.util.Random

class StateHashSpec extends FreeSpec {
  private def mkAddress() = {
    val bs = new Array[Byte](32)
    Random.nextBytes(bs)
    Address.fromPublicKey(PublicKey(bs))
  }

  "state hash" - {
    val stateHash    = new StateHashBuilder
    val address      = mkAddress()
    val address1     = mkAddress()
    val assetId      = IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get)
    val wavesAccount = TxHelpers.defaultSigner
    val blsAccount   = TxHelpers.defaultBlsKey
    val vrfKey       = TxHelpers.defaultVrfKey

    stateHash.addLeaseBalance(address, 10000L, 10000L)
    stateHash.addLeaseStatus(assetId.id, isActive = true)
    stateHash.addAssetBalance(address, assetId, 2000)
    stateHash.addAssetBalance(address1, assetId, 2000)
    stateHash.addWavesBalance(address, 1000)
    stateHash.addNextCommittedGenerator(GenerationCommitment(PublicKey(wavesAccount.publicKey), blsAccount.publicKey, ByteStr(vrfKey.publicKey())))
    stateHash.addCommittedGeneratorBalances(Seq(3000))
    val result = stateHash.result()

    def hash(bs: Array[Byte]*): ByteStr = ByteStr(com.wavesplatform.crypto.fastHash(bs.reduce(_ ++ _)))
    def sect(id: Section): ByteStr      = result.hashes.getOrElse(id, StateHashBuilder.EmptySectionHash)
    import Section.*

    "sections" - {
      "lease balance" in {
        sect(LeaseBalance) shouldBe hash(
          address.toBytes,
          Longs.toByteArray(10000L),
          Longs.toByteArray(10000L)
        )
      }

      "asset balance" in {
        sect(AssetBalance) shouldBe hash(
          address.toBytes,
          assetId.id.arr,
          Longs.toByteArray(2000),
          address1.toBytes,
          assetId.id.arr,
          Longs.toByteArray(2000)
        )
      }

      "waves balance" in {
        sect(WavesBalance) shouldBe hash(
          address.toBytes,
          Longs.toByteArray(1000)
        )
      }

      "lease status" in {
        sect(LeaseStatus) shouldBe hash(
          assetId.id.arr,
          Array(1.toByte)
        )
      }

      "next generator" in {
        sect(NextCommittedGenerators) shouldBe hash(
          wavesAccount.publicKey,
          blsAccount.publicKey.byteStr.arr,
          vrfKey.publicKey()
        )
      }

      "committed generator balance" in {
        sect(CommittedGeneratorBalances) shouldBe hash(
          Longs.toByteArray(3000)
        )
      }
    }

    "total" in {
      val allHashes = StateHash.Section.values.map(id => result.hashes.getOrElse(id, StateHashBuilder.EmptySectionHash))
      allHashes shouldBe Seq(
        WavesBalance,
        AssetBalance,
        LeaseBalance,
        LeaseStatus,
        NextCommittedGenerators,
        CommittedGeneratorBalances
      ).map(sect)

      val testPrevHash = sect(Section.WavesBalance)
      result.createStateHash(testPrevHash).totalHash shouldBe hash((testPrevHash.arr +: allHashes.map(_.arr))*)
      result.copy(hashes = result.hashes - Section.WavesBalance).createStateHash(ByteStr.empty).totalHash shouldBe hash(
        (StateHashBuilder.EmptySectionHash.arr +: allHashes.tail.map(_.arr))*
      )
    }
  }
}
