package com.wavesplatform.network

import com.wavesplatform.common.utils.Base64
import com.wavesplatform.test.*

class MicroBlockResponseSpec extends FreeSpec {
  private val microBlockBytes = Base64.decode(
    "CpICEiCxsIIj/SVjwI1AfoPGkry0R5onCUKCVAbiJP3lbFNQzRpAehUOUbXKTZ/q4mjvNhYfSWhI29IA0MQ2sJL0/PbE4janvg3ofUt0JVs4xj0ns8AnPv9PwMXQNRG2BaGiOfgfZCIgXP0u9x89IEv7fZ20z1GLUF96Bsd31r6ei7ykbaw0VYUyIAHFCfEKkEuRXTkpXkEow3kXS4Zc/N78fJ31KTDztRRkOmgKAQEQkU4aYKo7wvtFlDLJPySgsO23JJ1Z3epWHmBg3OJCo6Ni5D5Tp/F95mf6CxVs151/UPCuOFBdZo9elJaTe4riw2sDJJFcANlazfmFjg0HJduaDjJMkVfkGmuHqt0OuzFS/8IDSRJACq0j1ECqx42J2WYYDznUcsL3ubiLcfWHFEF3YNU4eT3R4EWiX2NZiZ+zg1oFhc8tSjrDXtPrW4pU//EX/eQMDRpA/4Hh0ApzVNFgSipjVAIeTIaaI3WFQucjfhgF1GRf+8BHz3po88jgnWQhzSuetzZzPwE5VGQwhtxG9i4pyGnS/g=="
  )

  "Signature is valid" in {
    val parsedMicroBlock = PBMicroBlockSpec.deserializeData(microBlockBytes).get.microblock
    parsedMicroBlock.signatureValid() shouldBe true
  }
}
