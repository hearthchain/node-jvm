package tech.hearth.http

import com.typesafe.config.ConfigFactory
import tech.hearth.api.http.`X-Api-Key`
import tech.hearth.common.utils.Base16
import tech.hearth.crypto
import tech.hearth.settings.*
import pureconfig.ConfigSource

trait RestAPISettingsHelper {
  private val apiKey: String = "test_api_key"

  val ApiKeyHeader = `X-Api-Key`(apiKey)

  lazy val MaxTransactionsPerRequest = 10000
  lazy val MaxAddressesPerRequest    = 10000
  lazy val MaxKeysPerRequest         = 1000
  lazy val MaxAssetIdsPerRequest     = 100

  lazy val restAPISettings = {
    val keyHash = Base16.encode(crypto.secureHash(apiKey.getBytes("UTF-8")))
    val config = ConfigFactory
      .parseString(
        s"""hearth.rest-api {
           |  api-key-hash = $keyHash
           |  transactions-by-address-limit = $MaxTransactionsPerRequest
           |  distribution-address-limit = $MaxAddressesPerRequest
           |  data-keys-request-limit = $MaxKeysPerRequest
           |  asset-details-limit = $MaxAssetIdsPerRequest
           |}
         """.stripMargin
      )
      .withFallback(ConfigFactory.load())
    ConfigSource.fromConfig(config).at("hearth.rest-api").loadOrThrow[RestAPISettings]
  }
}
