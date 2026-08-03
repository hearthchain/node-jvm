package tech.hearth

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

import tech.hearth.account.Address
import tech.hearth.settings.{GenesisBalanceSettings, GenesisSettings, PredefinedSnapshotSettings}
import tech.hearth.state.GenesisBlockHeight

import scala.concurrent.duration.*

object TestHelpers {
  def genesisSettings(blockTimestamp: Long = System.currentTimeMillis()): GenesisSettings =
    GenesisSettings(blockTimestamp, None, 1000, 60.seconds)

  def genesisSnapshotSettings(balances: Map[Address, Long]): PredefinedSnapshotSettings =
    PredefinedSnapshotSettings(GenesisBlockHeight.toInt, balances = genesisBalances(balances))

  def genesisBalances(balances: Map[Address, Long]): Seq[GenesisBalanceSettings] =
    balances.map { case (account, amount) => GenesisBalanceSettings(account.toBech32, amount) }.toSeq

  def deleteRecursively(path: Path): Unit = Files.walkFileTree(
    path,
    new SimpleFileVisitor[Path] {
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Option(exc).fold {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }(throw _)
      }

      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
    }
  )
}
