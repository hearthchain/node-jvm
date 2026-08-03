package tech.hearth

import java.nio.file.Files

import tech.hearth.database.RDB
import tech.hearth.db.DBCacheSettings
import tech.hearth.events.BlockchainUpdateTriggers
import org.scalatest.{BeforeAndAfterEach, Suite}

trait WithNewDBForEachTest extends BeforeAndAfterEach with DBCacheSettings {
  this: Suite =>

  private val path                   = Files.createTempDirectory(s"rocks-${getClass.getSimpleName}").toAbsolutePath
  private var currentDBInstance: RDB = compiletime.uninitialized

  protected val ignoreBlockchainUpdateTriggers: BlockchainUpdateTriggers = BlockchainUpdateTriggers.noop

  def db: RDB = currentDBInstance

  override def beforeEach(): Unit = {
    currentDBInstance = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))
    super.beforeEach()
  }

  override def afterEach(): Unit =
    try {
      super.afterEach()
      db.close()
    } finally {
      TestHelpers.deleteRecursively(path)
    }
}
