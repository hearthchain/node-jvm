package tech.hearth.state

/** HRTH's block reward decay curve (hearth-tokenomics-spec S2): R(h) = R0 * 2^(-h/Hhalf), where h counts blocks
  * since the first rewarded block. Computed as pure fixed-point integer arithmetic - no floating point, no
  * transcendental function call at runtime - so it is bit-for-bit reproducible by any client implementation (not
  * only this JVM one) that follows the same algorithm against the same pinned `decayRatioFixed` constant. A
  * `Math.pow`/`Math.log`-based formula would only be accurate to within an implementation-defined error bound,
  * which is fine within a single, homogeneous JVM ecosystem but not guaranteed to agree bit-for-bit with an
  * independent implementation in another language.
  *
  * `decayRatioFixed` is `2^(-1/Hhalf)` pre-derived offline (once, at arbitrary precision) and represented as a
  * `Q(FixedPointBits)` fixed-point integer: `round(2^(-1/Hhalf) * 2^FixedPointBits)`. `RewardsSettings` carries one
  * such literal per network, alongside the `initialReward` (R0) it was derived from.
  */
object EmissionCurve {
  val FixedPointBits: Int = 128

  private val OneFixed: BigInt = BigInt(1) << FixedPointBits

  private def fixedMul(a: BigInt, b: BigInt): BigInt = (a * b) >> FixedPointBits

  /** `ratioFixed^h`, in the same `Q(FixedPointBits)` format as `ratioFixed`, via fixed-point binary exponentiation:
    * `O(log h)` multiplications, each exact (`BigInt` multiply) followed by a floor right-shift. Both operands are
    * always non-negative, so that shift is an unambiguous floor - not a language- or sign-dependent rounding choice.
    */
  def powFixed(ratioFixed: BigInt, h: Long): BigInt = {
    require(h >= 0, s"h must be non-negative, was $h")

    var result = OneFixed
    var base   = ratioFixed
    var e      = h
    while (e > 0) {
      if ((e & 1) == 1) result = fixedMul(result, base)
      base = fixedMul(base, base)
      e >>= 1
    }
    result
  }

  /** The block reward `h` blocks after the first rewarded block, floored to the nearest ember. Flooring at every
    * block means the running sum of rewards always stays strictly below `initialReward * Hhalf / ln2` (the curve's
    * asymptote, `C_emit`), so the hard cap holds by construction - no separate runtime clamp is needed.
    */
  def rewardAt(h: Long, initialReward: Long, decayRatioFixed: BigInt): Long =
    ((BigInt(initialReward) * powFixed(decayRatioFixed, h)) >> FixedPointBits).toLong
}
