package tech.hearth.api.http

import tech.hearth.account.Address
import tech.hearth.api.common.CommonAccountsApi
import tech.hearth.api.http.ApiError.*
import tech.hearth.common.state.ByteStr
import tech.hearth.network.TransactionPublisher
import tech.hearth.settings.RestAPISettings
import tech.hearth.state.Blockchain
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.utils.Time
import tech.hearth.mining.GeneratorKeys
import tech.hearth.wallet.Wallet
import monix.execution.Scheduler
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.server.{Directive0, Route}
import play.api.libs.json.*

case class AddressApiRoute(
    settings: RestAPISettings,
    wallet: Wallet,
    generatorKeys: GeneratorKeys,
    blockchain: Blockchain,
    transactionPublisher: TransactionPublisher,
    time: Time,
    limitedScheduler: Scheduler,
    routeTimeout: RouteTimeout,
    commonAccountsApi: CommonAccountsApi,
    maxBalanceDepth: Int
) extends ApiRoute
    with AuthRoute
    with TimeLimitedRoute {

  import AddressApiRoute.*

  val MaxAddressesPerRequest = 1000

  override lazy val route: Route =
    pathPrefix("addresses") {
      balanceDetails ~ validate ~ balance ~ balances ~ balancesPost ~ balanceWithConfirmations ~ deleteAddress ~
        seq ~ publicKey ~ effectiveBalance ~ effectiveBalanceWithConfirmations ~ blsPublicKey
    } ~ root ~ create

  def deleteAddress: Route = (delete & withAuth & path(AddrSegment)) { address =>
    val deleted = wallet.signingKey(address).exists(account => wallet.deleteAccount(account))
    complete(Json.obj("deleted" -> deleted))
  }

  def balance: Route = (path("balance" / AddrSegment) & get) { address =>
    complete(balanceJson(address))
  }

  def balances: Route = (path("balance") & get & parameters("height".as[Int].?, "address".as[String].*, "asset".?)) {
    (maybeHeight, addresses, assetId) =>
      val height = maybeHeight.getOrElse(blockchain.height)
      validateBalanceDepth(height)(
        complete(
          balancesJson(height, addresses.toSeq, assetId.fold(Waves: Asset)(a => IssuedAsset(ByteStr.decodeBase58(a).get)))
        )
      )
  }

  def balancesPost: Route = (path("balance") & (post & entity(as[JsObject]))) { request =>
    val height    = (request \ "height").asOpt[Int].getOrElse(blockchain.height)
    val addresses = (request \ "addresses").as[Seq[String]]
    val assetId   = (request \ "asset").asOpt[String]
    validateBalanceDepth(height)(complete(balancesJson(height, addresses, assetId.fold(Waves: Asset)(a => IssuedAsset(ByteStr.decodeBase58(a).get)))))
  }

  def balanceDetails: Route = (path("balance" / "details" / AddrSegment) & get) { address =>
    commonAccountsApi
      .balanceDetails(address)
      .fold(
        e => complete(CustomValidationError(e)),
        { details =>
          import details.*
          complete(
            Json.obj(
              "address"    -> address,
              "regular"    -> regular,
              "generating" -> generating,
              "available"  -> available,
              "effective"  -> effective
            )
          )
        }
      )
  }

  def balanceWithConfirmations: Route = {
    (path("balance" / AddrSegment / IntNumber) & get) { case (address, confirmations) =>
      validateBalanceDepth(blockchain.height - confirmations)(
        complete(balanceJson(address, confirmations))
      )
    }
  }

  def effectiveBalance: Route = {
    path("effectiveBalance" / AddrSegment) { address =>
      complete(effectiveBalanceJson(address, 0))
    }
  }

  def effectiveBalanceWithConfirmations: Route = {
    path("effectiveBalance" / AddrSegment / IntNumber) { (address, confirmations) =>
      validateBalanceDepth(blockchain.height - confirmations)(
        complete(effectiveBalanceJson(address, confirmations))
      )
    }
  }

  /** The endorser public key of one of this node's generators, so that an operator can see what a commitment from this
    * node would register. Only the accounts in `waves.miner.accounts` have one - the wallet holds no BLS keys.
    */
  def blsPublicKey: Route = (path("bls" / AddrSegment) & get) { address =>
    complete(
      generatorKeys
        .endorserPublicKey(address)
        .fold[ToResponseMarshallable](MissingSenderPrivateKey)(pk => Json.obj("blsPublicKey" -> pk.base58))
    )
  }

  def validate: Route = (path("validate" / Segment) & get) { addressBytes =>
    complete(Json.obj("address" -> addressBytes, "valid" -> Address.fromString(addressBytes).isRight))
  }

  def root: Route = (path("addresses") & get) {
    complete(wallet.privateKeyAccounts.map(_.toAddress))
  }

  def seq: Route = {
    (path("seq" / IntNumber / IntNumber) & get) { case (start, end) =>
      if (start < 0 || end < 0 || start > end) complete(GenericError("Invalid sequence"))
      else if (end - start >= MaxAddressesPerRequest) complete(TooBigArrayAllocation(MaxAddressesPerRequest))
      else complete(wallet.privateKeyAccounts.map(_.toAddress).slice(start, end))
    }
  }

  def create: Route = (path("addresses") & post & withAuth) {
    wallet.generateNewAccount() match {
      case Some(pka) => complete(Json.obj("address" -> pka.toAddress))
      case None      => complete(Unknown)
    }
  }

  private def balancesJson(height: Int, addresses: Seq[String], assetId: Asset): ToResponseMarshallable =
    if (addresses.length > settings.transactionsByAddressLimit) TooBigArrayAllocation
    else if (height < 1 || height > blockchain.height) CustomValidationError(s"Illegal height: $height")
    else {
      implicit val balancesWrites: Writes[(String, Long)] = Writes[(String, Long)] { b =>
        Json.obj("id" -> b._1, "balance" -> b._2)
      }

      val balances = for {
        addressStr <- addresses.toSet[String]
        address    <- Address.fromString(addressStr).toOption
      } yield blockchain.balanceAtHeight(address, height, assetId).fold(addressStr -> 0L)(addressStr -> _._2)

      ToResponseMarshallable(balances)
    }

  private def balanceJson(acc: Address, confirmations: Int) = {
    Balance(acc.toString, confirmations, commonAccountsApi.balance(acc, confirmations))
  }

  private def balanceJson(acc: Address) = Balance(acc.toString, 0, commonAccountsApi.balance(acc))

  private def effectiveBalanceJson(acc: Address, confirmations: Int) = {
    Balance(acc.toString, confirmations, commonAccountsApi.effectiveBalance(acc, confirmations))
  }

  private def validateBalanceDepth(height: Int): Directive0 = {
    if (height < blockchain.height - maxBalanceDepth)
      complete(CustomValidationError(s"Unable to get balance past height ${blockchain.height - maxBalanceDepth}"))
    else
      pass
  }

  def publicKey: Route = (path("publicKey" / PublicKeySegment) & get) { publicKey =>
    complete(Json.obj("address" -> Address.fromPublicKey(publicKey).toString))
  }
}

object AddressApiRoute {
  case class Signed(message: String, publicKey: String, signature: String)

  object Signed {
    import play.api.libs.functional.syntax.*

    implicit val signedFormat: Format[Signed] = Format(
      ((JsPath \ "message").read[String] and
        (JsPath \ "publickey")
          .read[String]
          .orElse((JsPath \ "publicKey").read[String])
        and (JsPath \ "signature").read[String])(Signed.apply),
      Json.writes[Signed]
    )
  }

  case class Balance(address: String, confirmations: Int, balance: Long)

  object Balance {
    implicit val balanceFormat: Format[Balance] = Json.format
  }
}
