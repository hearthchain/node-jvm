package tech.hearth.extensions

import tech.hearth.api.common.*
import tech.hearth.common.state.ByteStr
import tech.hearth.events.UtxEvent
import tech.hearth.lang.ValidationError
import tech.hearth.settings.WavesSettings
import tech.hearth.state.Blockchain
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.{DiscardedBlocks, Transaction}
import tech.hearth.utils.Time
import tech.hearth.utx.UtxPool
import tech.hearth.wallet.Wallet
import monix.eval.Task
import monix.reactive.Observable

trait Context {
  def settings: WavesSettings
  def blockchain: Blockchain
  def rollbackTo(blockId: ByteStr): Task[Either[ValidationError, DiscardedBlocks]]
  def time: Time
  def wallet: Wallet
  def utx: UtxPool

  def transactionsApi: CommonTransactionsApi
  def blocksApi: CommonBlocksApi
  def accountsApi: CommonAccountsApi
  def assetsApi: CommonAssetsApi
  def generatorsApi: CommonGeneratorsApi

  def broadcastTransaction(tx: Transaction): TracedResult[ValidationError, Boolean]
  def utxEvents: Observable[UtxEvent]
}
