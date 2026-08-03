package com.wavesplatform.state

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.crypto.bls.BlsPublicKey
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.settings.{GenesisAssetSettings, GenesisGeneratorSettings, GenesisSettings}
import com.wavesplatform.state.diffs.BalanceDiffValidation
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.{Asset, CommitToGenerationTransaction, TxDecimals}

import scala.collection.immutable.VectorMap

/** The predefined state of the genesis block.
  *
  * Unlike a regular block, the genesis block carries no transactions: everything it puts into the state - issued assets, committed generators and
  * account balances - comes from [[GenesisSettings]]. Balances are absolute, because there is nothing in the state to add them to.
  */
object GenesisSnapshot {
  def build(genesisSettings: GenesisSettings): Either[ValidationError, StateSnapshot] =
    for {
      assets     <- issuedAssets(genesisSettings.assets)
      balances   <- this.balances(genesisSettings, assets)
      _          <- checkAssetsAreFullyDistributed(assets, balances)
      generators <- committedGenerators(genesisSettings.generators)
      snapshot = StateSnapshot(
        balances = balances,
        assetStatics = StateSnapshot.assetStatics(assets),
        assetVolumes = assets.map { case (asset, info) => asset -> info.volume }.toMap,
        assetNamesAndDescriptions = assets.map { case (asset, info) => asset -> info.dynamic }.toMap,
        nextCommittedGenerators = generators
      )
      _ <- BalanceDiffValidation(snapshot)
      _ <- generators
        .collectFirst {
          case c if snapshot.balances.getOrElse(c.sender.toAddress -> Asset.Waves, 0L) < CommitToGenerationTransaction.DepositInWavelets =>
            GenericError(
              s"Generator ${c.sender.toAddress} balance ${snapshot.balances.getOrElse(c.sender.toAddress -> Asset.Waves, 0L)} is less than required for generation"
            )
        }
        .toLeft(())
    } yield snapshot
  private def issuedAssets(settings: Seq[GenesisAssetSettings]): Either[ValidationError, Seq[(IssuedAsset, NewAssetInfo)]] =
    for {
      _ <- checkNoDuplicates(settings.map(_.id.toString), "asset id")
      assets <- settings.toList.traverse { a =>
        for {
          issuer <- PublicKey.fromBase58String(a.issuer).leftMap(e => GenericError(s"Genesis asset ${a.id}: invalid issuer: $e"))
          _      <- Either.cond(a.quantity > 0, (), GenericError(s"Genesis asset ${a.id}: quantity must be greater than 0, got ${a.quantity}"))
          decimalsError = GenericError(s"Genesis asset ${a.id}: ${TxDecimals.errMsg}, got ${a.decimals}")
          _ <- Either.cond(a.decimals.isValidByte, (), decimalsError)
          _ <- TxDecimals.from(a.decimals.toByte).leftMap(_ => decimalsError)
          isNft = a.quantity == 1 && a.decimals == 0 && !a.reissuable
        } yield IssuedAsset(a.id) -> NewAssetInfo(
          AssetStaticInfo(a.id, TransactionId(a.id), issuer, a.decimals, nft = isNft),
          AssetInfo(a.name, a.description, GenesisBlockHeight),
          AssetVolumeInfo(a.reissuable, BigInt(a.quantity))
        )
      }
    } yield assets

  private def balances(
      genesisSettings: GenesisSettings,
      assets: Seq[(IssuedAsset, NewAssetInfo)]
  ): Either[ValidationError, VectorMap[(Address, Asset), Long]] = {
    val knownAssets = assets.map(_._1).toSet
    for {
      _ <- checkNoDuplicates(genesisSettings.balances.map(_.recipient), "genesis balance recipient")
      _ <- Either.catchNonFatal(genesisSettings.initialBalance).leftMap(_ => GenericError("Total genesis Waves balance overflows"))
      entries <- genesisSettings.balances.toList.flatTraverse { b =>
        for {
          address <- Address.fromString(b.recipient).leftMap(e => GenericError(s"Genesis balance ${b.recipient}: invalid recipient: $e"))
          _       <- Either.cond(b.waves >= 0, (), GenericError(s"Genesis balance $address: Waves amount must not be negative, got ${b.waves}"))
          _       <- checkNoDuplicates(b.assets.keys.toSeq, s"asset id in the balance of $address")
          assetEntries <- b.assets.toList.traverse { case (id, amount) =>
            for {
              assetId <- ByteStr.decodeBase58(id).toEither.leftMap(e => GenericError(s"Genesis balance $address: invalid asset id $id: $e"))
              asset = IssuedAsset(assetId)
              _ <- Either.cond(knownAssets(asset), (), GenericError(s"Genesis balance $address: unknown asset $id"))
              _ <- Either.cond(amount > 0, (), GenericError(s"Genesis balance $address: amount of $id must be greater than 0, got $amount"))
            } yield (address, asset: Asset) -> amount
          }
        } yield (if (b.waves > 0) List((address, Waves: Asset) -> b.waves) else Nil) ++ assetEntries
      }
    } yield VectorMap.from(entries)
  }

  private def checkAssetsAreFullyDistributed(
      assets: Seq[(IssuedAsset, NewAssetInfo)],
      balances: VectorMap[(Address, Asset), Long]
  ): Either[ValidationError, Unit] = {
    val distributed = balances.foldLeft(Map.empty[IssuedAsset, BigInt]) {
      case (r, ((_, asset: IssuedAsset), amount)) => r.updated(asset, r.getOrElse(asset, BigInt(0)) + amount)
      case (r, _)                                 => r
    }
    assets.foldLeft(Either.unit[ValidationError]) {
      case (r @ Left(_), _) => r
      case (_, (asset, info)) =>
        val issued = distributed.getOrElse(asset, BigInt(0))
        Either.cond(
          issued == info.volume.volume,
          (),
          GenericError(s"Genesis asset ${asset.id}: quantity ${info.volume.volume} does not match the distributed amount $issued")
        )
    }
  }

  private def committedGenerators(
      settings: Seq[GenesisGeneratorSettings]
  ): Either[ValidationError, Seq[GenerationCommitment]] =
    if (settings.isEmpty) Right(Seq.empty)
    else
      for {
        _ <- checkNoDuplicates(settings.map(_.publicKey), "genesis generator public key")
        _ <- checkNoDuplicates(settings.map(_.endorserPublicKey), "genesis generator endorser public key")
        _ <- checkNoDuplicates(settings.map(_.vrfPublicKey), "genesis generator VRF public key")
        generators <- settings.toList.traverse { g =>
          for {
            publicKey <- PublicKey.fromBase58String(g.publicKey).leftMap(e => GenericError(s"Genesis generator ${g.publicKey}: $e"))
            rawEndorserKey <- ByteStr
              .decodeBase58(g.endorserPublicKey)
              .toEither
              .leftMap(e => GenericError(s"Genesis generator ${g.publicKey}: invalid endorser public key: $e"))
            endorserKey <- BlsPublicKey(rawEndorserKey).leftMap(e => GenericError(s"Genesis generator ${g.publicKey}: invalid endorser key: $e"))
            _           <- endorserKey.validated.leftMap(e => GenericError(s"Genesis generator ${g.publicKey}: invalid endorser public key: $e"))
            vrfKey <- ByteStr
              .decodeBase58(g.vrfPublicKey)
              .toEither
              .leftMap(e => GenericError(s"Genesis generator ${g.publicKey}: invalid VRF public key: $e"))
            _ <- Either.cond(
              vrfKey.size == crypto.KeyLength,
              (),
              GenericError(s"Genesis generator ${g.publicKey}: VRF public key must be ${crypto.KeyLength} bytes, got ${vrfKey.size}")
            )
          } yield GenerationCommitment(publicKey, endorserKey, vrfKey)
        }
      } yield generators

  private def checkNoDuplicates(xs: Seq[String], what: String): Either[ValidationError, Unit] = {
    val duplicates = xs.groupBy(identity).collect { case (x, ys) if ys.size > 1 => x }
    Either.cond(duplicates.isEmpty, (), GenericError(s"Duplicate $what: ${duplicates.mkString(", ")}"))
  }
}
