package tech.hearth.it.sync.debug

import com.typesafe.config.Config
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.{BaseFunSuite, NodeConfigs}

class DebugConfigInfo extends BaseFunSuite {

  override protected val nodeConfigs: Seq[Config] = NodeConfigs.newBuilder.withDefault(1).build()

  test("getting a configInfo") {
    nodes.head.getWithApiKey(s"/debug/configInfo?full=false")
    nodes.last.getWithApiKey(s"/debug/configInfo?full=true")
  }

}
