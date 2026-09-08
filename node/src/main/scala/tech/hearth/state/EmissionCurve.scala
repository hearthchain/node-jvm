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

  /** The block reward `h` blocks after the first rewarded block, floored to the nearest ember. `h` counts blocks
    * since the *first rewarded block*, not chain height: `h = 0` is chain height 2, because genesis (height 1)
    * earns nothing. `rewardAt(0)` is therefore `initialReward` exactly.
    *
    * The running sum of rewards stays strictly below `C_emit` because `initialReward` is derived from the discrete
    * sum this method actually pays out - `sum_h R0 * 2^(-h/Hhalf) = R0 / (1 - 2^(-1/Hhalf))` - rather than from the
    * curve's continuous integral `R0 * Hhalf / ln2`, which is larger (see the derivation note on
    * [[tech.hearth.settings.RewardsSettings]]). Per-block flooring then only ever subtracts, so the hard cap holds
    * by construction - no separate runtime clamp is needed. hearth-specs' `derive.py --mode simulate` verifies this
    * block by block over the whole curve; the last ~0.88 HRTH of MAINNET's `C_emit` is simply never minted.
    */
  def rewardAt(h: Long, initialReward: Long, decayRatioFixed: BigInt): Long =
    ((BigInt(initialReward) * powFixed(decayRatioFixed, h)) >> FixedPointBits).toLong
}
