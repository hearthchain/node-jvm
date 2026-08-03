package tech.hearth.state

import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.lang.ValidationError
import tech.hearth.settings.{GenesisGeneratorSettings, PredefinedSnapshotSettings}
import tech.hearth.state.diffs.BalanceDiffValidation
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.{Asset, CommitToGenerationTransaction, TxDecimals}

import scala.collection.immutable.VectorMap

/** A predefined chunk of state, applied outside of transaction processing before the block at
  * [[PredefinedSnapshotSettings.height]] applies its own transactions. Since there is no issue transaction, this is
  * the only way to mint a new asset; it can also credit asset balances and commit generators (Waves balances only
  * at genesis, see [[balances]]). The height-1 entry is the genesis snapshot - unlike every other block, genesis
  * carries no transactions at all, so its whole effect is this predefined snapshot.
  */
object PredefinedSnapshot {
  def build(settings: PredefinedSnapshotSettings, blockchain: Blockchain): Either[ValidationError, StateSnapshot] =
    for {
      assets     <- issuedAssets(settings, blockchain)
      balances   <- this.balances(settings, assets)
      _          <- checkAssetsAreFullyDistributed(assets, balances)
      generators <- committedGenerators(settings.generators)
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = toPortfolios(balances),
        issuedAssets = assets,
        nextCommittedGenerators = generators
      )
      _ <- BalanceDiffValidation(blockchain)(snapshot)
      // snapshot.balances only holds entries for addresses this snapshot's own balances touched - a generator
      // funded earlier (e.g. at genesis) and simply committed here, with no balances entry of its own, would look
      // like it holds 0. Resolve against the resulting blockchain view instead, which falls back to the real
      // cumulative balance for an address this snapshot didn't touch.
      resultingBlockchain = SnapshotBlockchain(blockchain, snapshot)
      _ <- generators
        .collectFirst {
          case c if resultingBlockchain.balance(c.sender.toAddress) < CommitToGenerationTransaction.DepositInWavelets =>
            GenericError(
              s"Generator ${c.sender.toAddress} balance ${resultingBlockchain.balance(c.sender.toAddress)} is less than required for generation"
            )
        }
        .toLeft(())
    } yield snapshot

  private def issuedAssets(
      settings: PredefinedSnapshotSettings,
      blockchain: Blockchain
  ): Either[ValidationError, Seq[(IssuedAsset, NewAssetInfo)]] =
    for {
      _ <- checkNoDuplicates(settings.assets.map(_.id.toString), "asset id")
      assets <- settings.assets.toList.traverse { a =>
        for {
          issuer <- PublicKey.fromBase58String(a.issuer).leftMap(e => GenericError(s"Predefined snapshot asset ${a.id}: invalid issuer: $e"))
          _ <- Either.cond(a.quantity > 0, (), GenericError(s"Predefined snapshot asset ${a.id}: quantity must be greater than 0, got ${a.quantity}"))
          decimalsError = GenericError(s"Predefined snapshot asset ${a.id}: ${TxDecimals.errMsg}, got ${a.decimals}")
          _ <- Either.cond(a.decimals.isValidByte, (), decimalsError)
          _ <- TxDecimals.from(a.decimals.toByte).leftMap(_ => decimalsError)
          _ <- Either.cond(
            blockchain.assetDescription(IssuedAsset(a.id)).isEmpty,
            (),
            GenericError(s"Predefined snapshot asset ${a.id}: an asset with this id already exists")
          )
          isNft = a.quantity == 1 && a.decimals == 0 && !a.reissuable
        } yield IssuedAsset(a.id) -> NewAssetInfo(
          AssetStaticInfo(a.id, TransactionId(a.id), issuer, a.decimals, nft = isNft),
          AssetInfo(a.name, a.description, Height(settings.height)),
          AssetVolumeInfo(a.reissuable, BigInt(a.quantity))
        )
      }
    } yield assets

  private def balances(
      settings: PredefinedSnapshotSettings,
      assets: Seq[(IssuedAsset, NewAssetInfo)]
  ): Either[ValidationError, VectorMap[(Address, Asset), Long]] = {
    val knownAssets = assets.map(_._1).toSet
    for {
      _ <- checkNoDuplicates(settings.balances.map(_.recipient), "predefined snapshot balance recipient")
      // Waves supply growth is tracked as reward + genesis balance only (see Blockchain.wavesAmount); a predefined
      // snapshot beyond genesis is for minting new assets, not new Waves, so it must not credit any.
      _ <- Either.cond(
        settings.height == GenesisBlockHeight.toInt || settings.balances.forall(_.waves == 0),
        (),
        GenericError(s"Predefined snapshot at height ${settings.height}: crediting Waves is only supported at genesis (height $GenesisBlockHeight)")
      )
      _ <- Either
        .catchNonFatal(settings.balances.map(_.waves).foldLeft(0L)(Math.addExact))
        .leftMap(_ => GenericError("Total Waves balance in the predefined snapshot overflows"))
      entries <- settings.balances.toList.flatTraverse { b =>
        for {
          address <- Address.fromString(b.recipient).leftMap(e => GenericError(s"Predefined snapshot balance ${b.recipient}: invalid recipient: $e"))
          _ <- Either.cond(b.waves >= 0, (), GenericError(s"Predefined snapshot balance $address: Waves amount must not be negative, got ${b.waves}"))
          _ <- checkNoDuplicates(b.assets.keys.toSeq, s"asset id in the balance of $address")
          assetEntries <- b.assets.toList.traverse { case (id, amount) =>
            for {
              assetId <- ByteStr
                .decodeBase58(id)
                .toEither
                .leftMap(e => GenericError(s"Predefined snapshot balance $address: invalid asset id $id: $e"))
              asset = IssuedAsset(assetId)
              _ <- Either.cond(knownAssets(asset), (), GenericError(s"Predefined snapshot balance $address: unknown asset $id"))
              _ <- Either.cond(
                amount > 0,
                (),
                GenericError(s"Predefined snapshot balance $address: amount of $id must be greater than 0, got $amount")
              )
            } yield (address, asset: Asset) -> amount
          }
        } yield (if (b.waves > 0) List((address, Waves: Asset) -> b.waves) else Nil) ++ assetEntries
      }
    } yield VectorMap.from(entries)
  }

  // each (address, asset) key is unique (recipients and per-recipient asset ids are checked above), so grouping by
  // address never needs to combine two entries for the same asset
  private def toPortfolios(balances: VectorMap[(Address, Asset), Long]): Map[Address, Portfolio] =
    balances.groupBy(_._1._1).map { case (address, entries) =>
      val waves        = entries.collectFirst { case ((_, Waves), amount) => amount }.getOrElse(0L)
      val assetEntries = entries.collect { case ((_, a: IssuedAsset), amount) => a -> amount }
      address -> Portfolio(waves, assets = VectorMap.from(assetEntries))
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
          GenericError(s"Predefined snapshot asset ${asset.id}: quantity ${info.volume.volume} does not match the distributed amount $issued")
        )
    }
  }

  private def committedGenerators(
      settings: Seq[GenesisGeneratorSettings]
  ): Either[ValidationError, Seq[GenerationCommitment]] =
    if (settings.isEmpty) Right(Seq.empty)
    else
      for {
        _ <- checkNoDuplicates(settings.map(_.publicKey), "predefined snapshot generator public key")
        _ <- checkNoDuplicates(settings.map(_.endorserPublicKey), "predefined snapshot generator endorser public key")
        _ <- checkNoDuplicates(settings.map(_.vrfPublicKey), "predefined snapshot generator VRF public key")
        generators <- settings.toList.traverse { g =>
          for {
            publicKey <- PublicKey.fromBase58String(g.publicKey).leftMap(e => GenericError(s"Predefined snapshot generator ${g.publicKey}: $e"))
            rawEndorserKey <- ByteStr
              .decodeBase58(g.endorserPublicKey)
              .toEither
              .leftMap(e => GenericError(s"Predefined snapshot generator ${g.publicKey}: invalid endorser public key: $e"))
            endorserKey <- BlsPublicKey(rawEndorserKey)
              .leftMap(e => GenericError(s"Predefined snapshot generator ${g.publicKey}: invalid endorser key: $e"))
            _ <- endorserKey.validated.leftMap(e => GenericError(s"Predefined snapshot generator ${g.publicKey}: invalid endorser public key: $e"))
            vrfKey <- ByteStr
              .decodeBase58(g.vrfPublicKey)
              .toEither
              .leftMap(e => GenericError(s"Predefined snapshot generator ${g.publicKey}: invalid VRF public key: $e"))
            _ <- Either.cond(
              vrfKey.size == crypto.KeyLength,
              (),
              GenericError(s"Predefined snapshot generator ${g.publicKey}: VRF public key must be ${crypto.KeyLength} bytes, got ${vrfKey.size}")
            )
          } yield GenerationCommitment(publicKey, endorserKey, vrfKey)
        }
      } yield generators

  private def checkNoDuplicates(xs: Seq[String], what: String): Either[ValidationError, Unit] = {
    val duplicates = xs.groupBy(identity).collect { case (x, ys) if ys.size > 1 => x }
    Either.cond(duplicates.isEmpty, (), GenericError(s"Duplicate $what: ${duplicates.mkString(", ")}"))
  }
}
