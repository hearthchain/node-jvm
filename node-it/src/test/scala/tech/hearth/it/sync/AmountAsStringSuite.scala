package tech.hearth.it.sync

import com.typesafe.config.Config
import tech.hearth.it.BaseFunSuite
import tech.hearth.it.NodeConfigs.*
import tech.hearth.it.keyPairFromSeed
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.api.{Transaction, TransactionInfo}
import tech.hearth.state.Height
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.assets.exchange.{Order, OrderType}
import tech.hearth.transaction.transfer.TransferTransaction.Transfer
import tech.hearth.transaction.{TxExchangeAmount, TxExchangePrice, TxHelpers}
import tech.hearth.utils.ScorexLogging
import org.asynchttpclient.Response
import org.scalatest
import org.scalatest.Assertion
import play.api.libs.json.{JsString, JsValue, Json}

class AmountAsStringSuite extends BaseFunSuite with ScorexLogging {
  override protected def nodeConfigs: Seq[Config] = Seq(
    Miners(1).quorum(0).overrides("hearth.miner.micro-block-interval = 5s"), // when UTX is empty, retry building microblock in 2 seconds
    NotMiner
  )

  private def firstKeyPair = notMiner.keyPair
  private def firstAddress = firstKeyPair.toAddress.toString

  val (headerName, headerValue) = ("Accept", "application/json;large-significand-format=string")

  test("amount as string in assets api") {
    val assetId       = GenesisAssets.TestAsset.id.toString
    val quantity      = sender.assetsDetails(assetId).quantity
    val currentHeight = sender.height
    sender.assetsDetails(assetId, amountsAsStrings = true).quantity shouldBe quantity

    sender.waitForHeight(currentHeight + 1)
    val assetDistribution = sender.getWithCustomHeader(
      s"/assets/$assetId/distribution/$currentHeight/limit/1",
      headerValue = "application/json;large-significand-format=string"
    )
    parseResponse(assetDistribution) \ "items"
  }

  test("amount as string in addresses api") {
    val firstBalance = sender.balanceDetails(firstAddress)
    sender.balance(firstAddress, amountsAsStrings = true).balance shouldBe firstBalance.regular
    sender.balance(firstAddress, confirmations = Some(1), amountsAsStrings = true).balance shouldBe firstBalance.regular

    val balanceDetails = sender.balanceDetails(firstAddress, amountsAsStrings = true)
    balanceDetails.regular shouldBe firstBalance.regular
    balanceDetails.generating shouldBe firstBalance.generating
    balanceDetails.available shouldBe firstBalance.available
    balanceDetails.effective shouldBe firstBalance.effective

    sender.effectiveBalance(firstAddress, amountsAsStrings = true).balance shouldBe firstBalance.effective
    sender.effectiveBalance(firstAddress, confirmations = Some(1), amountsAsStrings = true).balance shouldBe firstBalance.effective
  }

  test("amount as string in exchange transaction") {
    val exchanger      = keyPairFromSeed("exchanger".getBytes)
    val transferTxId   = sender.transfer(firstKeyPair, exchanger.toAddress.toString, transferAmount, minFee, waitForTx = true).id
    val transferTxInfo = sender.transactionInfo[TransactionInfo](transferTxId, amountsAsStrings = true)
    // A Transfer carries a transfers list (see TransferTxSerializer.toJson), so its JSON has totalAmount, never a
    // top-level amount - and TransactionInfo's reader falls back to None rather than failing when amount is absent.
    transferTxInfo.totalAmount shouldBe Some(transferAmount)
    transferTxInfo.transfers.get.head.amount shouldBe transferAmount
    transferTxInfo.fee shouldBe minFee

    val amount = 1000000
    val price  = 1000
    def checkExchangeTx(exchangeTx: Transaction): scalatest.Assertion = {
      exchangeTx.amount shouldBe Some(amount)
      exchangeTx.price shouldBe Some(price)
      exchangeTx.sellMatcherFee shouldBe Some(matcherFee)
      exchangeTx.buyMatcherFee shouldBe Some(matcherFee)
      exchangeTx.sellOrderMatcherFee shouldBe Some(matcherFee)
      exchangeTx.buyOrderMatcherFee shouldBe Some(matcherFee)
      exchangeTx.fee shouldBe matcherFee
    }
    val ts = System.currentTimeMillis()
    val buyOrder = TxHelpers.order(
      OrderType.BUY,
      Hearth,
      GenesisAssets.TestAsset,
      sender = exchanger,
      matcher = exchanger,
      amount = amount,
      price = price,
      fee = matcherFee,
      timestamp = ts,
      expiration = ts + Order.MaxLiveTime / 2,
      version = Order.V2
    )
    val sellOrder = TxHelpers.order(
      OrderType.SELL,
      Hearth,
      GenesisAssets.TestAsset,
      sender = exchanger,
      matcher = exchanger,
      amount = amount,
      price = price,
      fee = matcherFee,
      timestamp = ts,
      expiration = ts + Order.MaxLiveTime / 2,
      version = Order.V2
    )
    nodes.waitForHeightArise()
    val exchangeTx =
      sender.broadcastExchange(
        exchanger,
        buyOrder,
        sellOrder,
        TxExchangeAmount.unsafeFrom(amount),
        TxExchangePrice.unsafeFrom(price),
        matcherFee,
        matcherFee,
        matcherFee,
        amountsAsStrings = true
      )
    checkExchangeTx(exchangeTx)

    val utxExchangeTxInfoById = sender.utxById(exchangeTx.id, amountsAsStrings = true)
    val utxExchangeTxInfo     = sender.utx(amountsAsStrings = true)
    checkExchangeTx(utxExchangeTxInfoById)
    checkExchangeTx(utxExchangeTxInfo.head)

    val exchangeTxHeight           = Height(sender.waitForTransaction(exchangeTx.id).height)
    val exchangeTxBlockLast        = sender.lastBlock(amountsAsStrings = true).transactions.head
    val exchangeTxBlockAt          = sender.blockAt(exchangeTxHeight, amountsAsStrings = true).transactions.head
    val exchangeTxBlockBySignature = sender.blockById(sender.blockAt(exchangeTxHeight).id, amountsAsStrings = true).transactions.head
    val exchangeTxBlockSeq         = sender.blockSeq(exchangeTxHeight, exchangeTxHeight, amountsAsStrings = true).head.transactions.head
    checkExchangeTx(exchangeTxBlockLast)
    checkExchangeTx(exchangeTxBlockAt)
    checkExchangeTx(exchangeTxBlockBySignature)
    checkExchangeTx(exchangeTxBlockSeq)

    val exchangeTxInfo = sender.transactionInfo[TransactionInfo](exchangeTx.id, amountsAsStrings = true)
    exchangeTxInfo.amount shouldBe Some(amount)
    exchangeTxInfo.price shouldBe Some(price)
    exchangeTxInfo.sellMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.buyMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.sellOrderMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.buyOrderMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.fee shouldBe matcherFee
  }

  test("amount as string in masstransfer transaction") {
    nodes.waitForHeightArise()

    def checkMassTransferTx(tx: Transaction): Assertion = {
      log.info(s"Transaction: $tx")
      tx.transfers.get.head.amount shouldBe transferAmount
      tx.totalAmount shouldBe Some(transferAmount)
    }
    val (transfers, massTransferFee) = (List(Transfer(miner.address, transferAmount)), calcMassTransferFee(1))
    val massTransferTx               = sender.massTransfer(firstKeyPair, transfers, massTransferFee, amountsAsStrings = true)
    checkMassTransferTx(massTransferTx)

    checkMassTransferTx(sender.utx(amountsAsStrings = true).head)
    checkMassTransferTx(sender.utxById(massTransferTx.id, amountsAsStrings = true))

    val massTransferTxHeight           = Height(sender.waitForTransaction(massTransferTx.id).height)
    val massTransferTxBlockAt          = sender.blockAt(massTransferTxHeight, amountsAsStrings = true).transactions.head
    val massTransferTxBlockBySignature = sender.blockById(sender.blockAt(massTransferTxHeight).id, amountsAsStrings = true).transactions.head
    val massTransferTxBlockSeq         = sender.blockSeq(massTransferTxHeight, massTransferTxHeight, amountsAsStrings = true).head.transactions.head
    checkMassTransferTx(massTransferTxBlockAt)
    checkMassTransferTx(massTransferTxBlockBySignature)
    checkMassTransferTx(massTransferTxBlockSeq)

    val massTransferTxInfo = sender.transactionInfo[TransactionInfo](massTransferTx.id)
    massTransferTxInfo.transfers.get.head.amount shouldBe transferAmount
    massTransferTxInfo.totalAmount shouldBe Some(transferAmount)
  }

  test("amount as string in blocks api") {
    nodes.waitForHeightArise()
    val currentHeight    = sender.height
    val reward           = sender.rewardStatus().currentReward
    val blockLast        = sender.lastBlock(amountsAsStrings = true)
    val blockAt          = sender.blockAt(currentHeight, amountsAsStrings = true)
    val blockBySignature = sender.blockById(sender.lastBlock().id, amountsAsStrings = true)
    val blockHeaderAt    = sender.blockHeaderAt(currentHeight, amountsAsStrings = true)
    val blockHeaderLast  = sender.lastBlockHeader(amountsAsStrings = true)

    // desiredReward is not asserted: reward voting is unimplemented, and per project decision will not be (see
    // BlockHeadersTestSuite), so the field is never populated and stays None.
    for (block <- Seq(blockLast, blockAt, blockBySignature)) {
      block.reward shouldBe Some(reward)
      block.totalFee shouldBe Some(0)
    }

    for (block <- Seq(blockHeaderLast, blockHeaderAt)) {
      block.reward shouldBe Some(reward)
      block.totalFee shouldBe 0
    }

    val blockSeq          = sender.blockSeq(currentHeight, currentHeight, amountsAsStrings = true)
    val blockSeqByAddress = sender.blockSeqByAddress(miner.address, currentHeight, currentHeight, amountsAsStrings = true)

    for (blocks <- Seq(blockSeq, blockSeqByAddress)) {
      blocks.head.reward shouldBe Some(reward)
      blocks.head.totalFee shouldBe Some(0)
    }

    val blockHeadersSeq = sender.blockHeadersSeq(currentHeight, currentHeight, amountsAsStrings = true)
    blockHeadersSeq.head.reward shouldBe Some(reward)
    blockHeadersSeq.head.totalFee shouldBe 0
  }

  test("amount as string in rewards api") {
    val currentHeight    = sender.height
    val rewardsAsInteger = sender.rewardStatus()
    val rewards          = sender.rewardStatus(amountsAsStrings = true)
    val rewardsByHeight  = sender.rewardStatus(Some(currentHeight), amountsAsStrings = true)
    rewards.totalHearthAmount shouldBe rewardsAsInteger.totalHearthAmount
    rewards.currentReward shouldBe rewardsAsInteger.currentReward
    rewards.cEmit shouldBe rewardsAsInteger.cEmit
    rewardsByHeight.totalHearthAmount shouldBe rewardsAsInteger.totalHearthAmount
    rewardsByHeight.currentReward shouldBe rewardsAsInteger.currentReward
    rewardsByHeight.cEmit shouldBe rewardsAsInteger.cEmit
  }

  test("amount as string in debug api") {
    val firstBalance = sender.balanceDetails(firstAddress).available

    sender.debugBalanceHistory(firstAddress, amountsAsStrings = true).head.balance shouldBe firstBalance

    val stateHearthOnHeight = sender.getWithCustomHeader(
      s"/debug/stateHearth/${sender.height}",
      headerValue = "application/json;large-significand-format=string",
      withApiKey = true
    )
    (parseResponse(stateHearthOnHeight) \ firstAddress).get shouldBe JsString(firstBalance.toString)
  }

  private def parseResponse(response: Response): JsValue = Json.parse(response.getResponseBody)
}
