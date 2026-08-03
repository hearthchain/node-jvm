package tech.hearth.settings

import pureconfig.*
import java.io.File

import tech.hearth.common.state.ByteStr

case class WalletSettings(file: Option[File], password: Option[String], seed: Option[ByteStr]) derives ConfigReader
