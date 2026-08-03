package tech.hearth.transaction

import tech.hearth.account.Address

case class AssetAcc(account: Address, assetId: Option[Asset])
