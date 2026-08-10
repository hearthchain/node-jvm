package tech.hearth.state.snapshot

import com.google.common.primitives.Ints
import com.google.protobuf.ByteString
import com.google.protobuf.ByteString.copyFrom as bs
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base64
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.crypto.fastHash
import tech.hearth.protobuf.snapshot.{TransactionStatus, TransactionStateSnapshot as TSS}
import tech.hearth.protobuf.{Amount, PBSnapshots}
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers
import org.bouncycastle.util.encoders.Hex

class TxStateSnapshotHashSpec extends PropSpec {
  private def hashInt(i: Int) = bs(fastHash(Ints.toByteArray(i)))

  val stateHash         = new StateHashBuilder
  private val signer101 = TxHelpers.signer(101)
  private val signer102 = TxHelpers.signer(102)
  private val signer103 = TxHelpers.signer(103)

  private val address1 = signer101.toAddress
  private val address2 = signer102.toAddress
  private val address3 = signer103.toAddress

  private val assetId1 = hashInt(0xaa22aa44)
  private val assetId2 = hashInt(0xbb22aa44)

  private val leaseId  = hashInt(0x11aaef22)
  private val orderId1 = hashInt(0xee23ef22)
  private val orderId2 = hashInt(0xbb77ef29)

  private val wavesBalances = TSS(balances =
    Seq(
      TSS.Balance(bs(address1.toBytes), Some(Amount(amount = 10.waves))),
      TSS.Balance(bs(address2.toBytes), Some(Amount(amount = 20.waves)))
    )
  )

  private val assetBalances = TSS(balances =
    Seq(
      TSS.Balance(bs(address1.toBytes), Some(Amount(assetId1, 10_000))),
      TSS.Balance(bs(address2.toBytes), Some(Amount(assetId2, 20_000)))
    )
  )

  private val newLease = TSS(
    leaseBalances = Seq(
      TSS.LeaseBalance(bs(address1.toBytes), out = 45.waves),
      TSS.LeaseBalance(bs(address2.toBytes), in = 55.waves)
    ),
    newLeases = Seq(
      TSS.NewLease(leaseId, bs(signer101.publicKey()), bs(address2.toBytes), 25.waves)
    )
  )

  private val cancelledLease = TSS(
    leaseBalances = Seq(TSS.LeaseBalance(bs(address3.toBytes), out = 20.waves), TSS.LeaseBalance(bs(TxHelpers.address(104).toBytes), in = 0.waves)),
    cancelledLeases = Seq(
      TSS.CancelledLease(leaseId)
    )
  )

  private val volumeAndFee = TSS(
    orderFills = Seq(
      TSS.OrderFill(orderId1, 10.waves, 2000),
      TSS.OrderFill(orderId2, 10.waves, 2000)
    )
  )

  // AssetVolume.reissuable and NewAsset.issuer_public_key/nft are wire-compat only (nothing reissues an asset,
  // checks who issued it, or classifies it as an NFT any more, see PBSnapshots.toProtobuf) - always
  // empty/false on the wire, and every asset now carries a name/description pair from the moment it's issued
  // (an asset can no longer exist with no name at all, unlike under the old separate-rename-transaction model).
  private val newAsset = TSS(
    assetStatics = Seq(
      TSS.NewAsset(assetId1, ByteString.EMPTY, nft = false),
      TSS.NewAsset(assetId2, ByteString.EMPTY, decimals = 8)
    ),
    assetVolumes = Seq(
      TSS.AssetVolume(assetId2, false, bs((BigInt(Long.MaxValue) * 10).toByteArray)),
      TSS.AssetVolume(assetId1, false, bs(BigInt(1).toByteArray))
    ),
    assetNamesAndDescriptions = Seq(
      TSS.AssetNameAndDescription(assetId1, "", ""),
      TSS.AssetNameAndDescription(assetId2, "", "")
    )
  )

  // A second asset's volume, with no corresponding assetStatics entry - the hash builder hashes whatever
  // assetVolumes it's given regardless of whether the asset was also issued in this same snapshot.
  private val reissuedAsset = TSS(
    assetVolumes = Seq(
      TSS.AssetVolume(hashInt(0x23aadd55), false, bs((BigInt(10000000_00L)).toByteArray))
    )
  )
  private val failedTransaction = TSS(
    balances = Seq(
      TSS.Balance(bs(address2.toBytes), Some(Amount(amount = 25.995.waves)))
    ),
    transactionStatus = TransactionStatus.FAILED
  )
  private val elidedTransaction = TSS(
    transactionStatus = TransactionStatus.ELIDED
  )

  private val withCommitment = TSS(
    generationCommitment = Some(
      TSS.GenerationCommitment(
        bs(signer101.publicKey()),
        bs(BlsKeyPair.fromSeed(Ints.toByteArray(101)).publicKey.byteStr.arr),
        bs(signer101.publicKey)
      )
    )
  )

  private val all = TSS(
    balances = assetBalances.balances ++ wavesBalances.balances,
    leaseBalances = newLease.leaseBalances ++ cancelledLease.leaseBalances,
    newLeases = newLease.newLeases,
    cancelledLeases = cancelledLease.cancelledLeases,
    assetStatics = newAsset.assetStatics,
    assetVolumes = newAsset.assetVolumes ++ reissuedAsset.assetVolumes,
    assetNamesAndDescriptions = newAsset.assetNamesAndDescriptions,
    orderFills = volumeAndFee.orderFills,
    transactionStatus = failedTransaction.transactionStatus,
    generationCommitment = withCommitment.generationCommitment
  )

  private val testData = Table(
    ("clue", "state snapshot", "base64 bytes", "tx id", "previous state hash", "expected result"),
    (
      "waves balances",
      wavesBalances,
      "Ch4KFPDjERx1UoettFtdN7y5oQgF6T5uEgYQgJTr3AMKHgoUBBIwcyZ6gOgX+RqInVgBe4bf9wESBhCAqNa5Bw==",
      ByteStr.empty,
      Hex.toHexString(TxStateSnapshotHashBuilder.InitStateHash.arr),
      "06fd242c1ba1417d15f3b123f9f67a9b8dd40c7350aa294a3bc18c2dada0dc3c"
    ),
    (
      "asset balances",
      assetBalances,
      "Cj0KFPDjERx1UoettFtdN7y5oQgF6T5uEiUKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbEJBOCj4KFAQSMHMmeoDoF/kaiJ1YAXuG3/cBEiYKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEKCcAQ==",
      ByteStr.empty,
      "06fd242c1ba1417d15f3b123f9f67a9b8dd40c7350aa294a3bc18c2dada0dc3c",
      "c8a6184bb152feafb8a76a3a0b094c0e2368aa3643e1c4c3d7186f15812fbcbc"
    ),
    (
      "new lease",
      newLease,
      "EhwKFPDjERx1UoettFtdN7y5oQgF6T5uGICa4uEQEhwKFAQSMHMmeoDoF/kaiJ1YAXuG3/cBEICuzb4UGmAKILiCMyyFggW8Zd2LGt/AtMr7WWp+kfWbzlN93pXZqzqNEiCMSZz6RORPZ7kUf0a79isEvdRsoCS6DFzKGcvWL14jyBoUBBIwcyZ6gOgX+RqInVgBe4bf9wEggPKLqAk=",
      ByteStr.empty,
      "c8a6184bb152feafb8a76a3a0b094c0e2368aa3643e1c4c3d7186f15812fbcbc",
      "405c3e52047647b024b7a25358784bc4e6fb1a2594440d722b06e50299ccf79e"
    ),
    (
      "cancelled lease",
      cancelledLease,
      "EhwKFHutVCUNFNB+ZP+4Rk1pFws2xX+oGICo1rkHEhYKFDYQIRFQuTQphCjri0+rTlgfsu7pIiIKILiCMyyFggW8Zd2LGt/AtMr7WWp+kfWbzlN93pXZqzqN",
      ByteStr.empty,
      "405c3e52047647b024b7a25358784bc4e6fb1a2594440d722b06e50299ccf79e",
      "b2210a20dbfaa01248e6b5a882a3595d35f5aade4327f45f38680b72a9e3c389"
    ),
    (
      "order fill",
      volumeAndFee,
      "UisKIMkknO8yHpMUT/XKkkdlrbYCG0Dt+qvVgphfgtRbyRDMEICU69wDGNAPUisKIJZ9YwvJObbWItHAD2zhbaFOTFx2zQ4p0Xbo81GXHKeEEICU69wDGNAP",
      ByteStr.empty,
      "b2210a20dbfaa01248e6b5a882a3595d35f5aade4327f45f38680b72a9e3c389",
      "ad08b2cdcf276172198ac60568a1e207ab9e6247c6eedd353b12f9bcd7ef6426"
    ),
    (
      "new asset",
      newAsset,
      "KiIKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbKiQKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOGAgyLQogeJ3AESPVNg9wgq/UtGq4v+i1Fgu/tSbAQ+X8eDpPiU4aCQT/////////9jIlCiBeZp+CCmfQiG2HR4zwQ6KseFzHr1XcMc4QdTlL01CiWxoBAToiCiBeZp+CCmfQiG2HR4zwQ6KseFzHr1XcMc4QdTlL01CiWzoiCiB4ncARI9U2D3CCr9S0ari/6LUWC7+1JsBD5fx4Ok+JTg==",
      ByteStr.empty,
      "ad08b2cdcf276172198ac60568a1e207ab9e6247c6eedd353b12f9bcd7ef6426",
      "46aff309589a8ca05a593adb61a65ca1b093360445f9411595d0239c8b6c9a41"
    ),
    (
      "elided transaction",
      elidedTransaction,
      "cAI=",
      ByteStr(fastHash(Ints.toByteArray(0xaabbef40))),
      "46aff309589a8ca05a593adb61a65ca1b093360445f9411595d0239c8b6c9a41",
      "dbbd7dd99509c00d1494b17387be2f989cefea1503322044cecf9eb4a35056a9"
    ),
    (
      "with generation commitment",
      withCommitment,
      "enYKIIxJnPpE5E9nuRR/Rrv2KwS91GygJLoMXMoZy9YvXiPIEjDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAaIIxJnPpE5E9nuRR/Rrv2KwS91GygJLoMXMoZy9YvXiPI",
      ByteStr.empty,
      "dbbd7dd99509c00d1494b17387be2f989cefea1503322044cecf9eb4a35056a9",
      "9117eac44911daf6f916441b560ccfa8d4b0649159a3754b6785f20cf49382e3"
    ),
    (
      "all together",
      all,
      "Cj0KFPDjERx1UoettFtdN7y5oQgF6T5uEiUKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbEJBOCj4KFAQSMHMmeoDoF/kaiJ1YAXuG3/cBEiYKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEKCcAQoeChTw4xEcdVKHrbRbXTe8uaEIBek+bhIGEICU69wDCh4KFAQSMHMmeoDoF/kaiJ1YAXuG3/cBEgYQgKjWuQcSHAoU8OMRHHVSh620W103vLmhCAXpPm4YgJri4RASHAoUBBIwcyZ6gOgX+RqInVgBe4bf9wEQgK7NvhQSHAoUe61UJQ0U0H5k/7hGTWkXCzbFf6gYgKjWuQcSFgoUNhAhEVC5NCmEKOuLT6tOWB+y7ukaYAoguIIzLIWCBbxl3Ysa38C0yvtZan6R9ZvOU33eldmrOo0SIIxJnPpE5E9nuRR/Rrv2KwS91GygJLoMXMoZy9YvXiPIGhQEEjBzJnqA6Bf5GoidWAF7ht/3ASCA8ouoCSIiCiC4gjMshYIFvGXdixrfwLTK+1lqfpH1m85Tfd6V2as6jSoiCiBeZp+CCmfQiG2HR4zwQ6KseFzHr1XcMc4QdTlL01CiWyokCiB4ncARI9U2D3CCr9S0ari/6LUWC7+1JsBD5fx4Ok+JThgIMi0KIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOGgkE//////////YyJQogXmafggpn0Ihth0eM8EOirHhcx69V3DHOEHU5S9NQolsaAQEyKAogOG+NPdNOUn6/g2LbTm9xhzWb1ZaCdA8Wi+OYkjUfrbIaBDuaygA6IgogXmafggpn0Ihth0eM8EOirHhcx69V3DHOEHU5S9NQols6IgogeJ3AESPVNg9wgq/UtGq4v+i1Fgu/tSbAQ+X8eDpPiU5SKwogySSc7zIekxRP9cqSR2WttgIbQO36q9WCmF+C1FvJEMwQgJTr3AMY0A9SKwogln1jC8k5ttYi0cAPbOFtoU5MXHbNDinRdujzUZccp4QQgJTr3AMY0A9wAXp2CiCMSZz6RORPZ7kUf0a79isEvdRsoCS6DFzKGcvWL14jyBIwwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGiCMSZz6RORPZ7kUf0a79isEvdRsoCS6DFzKGcvWL14jyA==",
      ByteStr(fastHash(Ints.toByteArray(0xaabbef50))),
      "9117eac44911daf6f916441b560ccfa8d4b0649159a3754b6785f20cf49382e3",
      "ca30dbcbc2d6c8b93aa17ec9e8053176595014da98a716dfc6fc9c6241b0d2ad"
    )
  )

  property("correctly create transaction state snapshot hash from snapshot") {
    forAll(testData) { case (clue, pbSnapshot, b64str, txId, prev, expectedResult) =>
      withClue(clue) {
        TSS.parseFrom(Base64.decode(b64str)) shouldEqual pbSnapshot

        val (snapshot, meta) = PBSnapshots.fromProtobuf(pbSnapshot, txId, Height(10))
        val raw = Hex.toHexString(
          TxStateSnapshotHashBuilder
            .createHashFromSnapshot(snapshot, Some(TxStateSnapshotHashBuilder.TxStatusInfo(txId, meta)))
            .createHash(ByteStr(Hex.decodeStrict(prev)))
            .arr
        )
        PBSnapshots.toProtobuf(snapshot, meta) shouldEqual pbSnapshot
        raw shouldEqual expectedResult
      }
    }
  }

  // minAssetFee has no representation in TransactionStateSnapshot (only PredefinedSnapshot ever sets it, never a
  // transaction diff - see PBSnapshots.fromProtobuf), so it can't be exercised through the table above; it must
  // still change the hash, or two configs/nodes that disagree only on an asset's minAssetFee would be undetectable.
  property("minAssetFee changes the resulting hash") {
    val asset        = IssuedAsset(ByteStr(fastHash(Ints.toByteArray(0xaa22aa44))))
    val withoutFee   = StateSnapshot()
    val withFee      = StateSnapshot(minAssetFees = Map(asset -> MinAssetFee.unsafeFrom(100000L)))
    val withOtherFee = StateSnapshot(minAssetFees = Map(asset -> MinAssetFee.unsafeFrom(200000L)))

    def hashOf(s: StateSnapshot): ByteStr =
      TxStateSnapshotHashBuilder.createHashFromSnapshot(s, None).createHash(TxStateSnapshotHashBuilder.InitStateHash)

    hashOf(withFee) should not equal hashOf(withoutFee)
    hashOf(withFee) should not equal hashOf(withOtherFee)
  }
}
