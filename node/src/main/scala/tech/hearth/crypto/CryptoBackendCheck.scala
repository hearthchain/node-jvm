package tech.hearth.crypto

/** tech.hearth:crypto falls back to its pure-JVM backend without a word when libsodium does not load. A node must not
  * run on it unasked: it verifies about ten times slower, and its VRF arithmetic, which proves with the generator's
  * key, is not constant-time. HEARTH_CRYPTO_BACKEND=jvm, the library's own switch, is how to choose it on purpose.
  */
object CryptoBackendCheck {
  val RequestVariable = "HEARTH_CRYPTO_BACKEND"

  def refusal(backendName: String, requested: Option[String]): Option[String] =
    Option.when(backendName != "libsodium" && !requested.exists(_.trim.equalsIgnoreCase("jvm"))) {
      s"libsodium did not load, so signatures would be checked by the slow, non-constant-time $backendName backend. " +
        "Install it (Debian/Ubuntu: apt install libsodium23, macOS: brew install libsodium, Windows: libsodium.dll " +
        "on PATH), or point HEARTH_SODIUM_LIB at the library. To run on the pure-JVM backend anyway, set " +
        s"$RequestVariable=jvm."
    }
}
