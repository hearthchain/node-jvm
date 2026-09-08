package tech.hearth.settings

import pureconfig.*
import java.io.File

/** @param mnemonic The BIP-39 phrase every account of this wallet is derived from, so the wallet can be carried to any
  *                 implementation of the same standard. Generated on first start when it is not configured.
  */
case class WalletSettings(file: Option[File], password: Option[String], mnemonic: Option[String]) derives ConfigReader
