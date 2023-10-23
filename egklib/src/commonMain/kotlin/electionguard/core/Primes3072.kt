package electionguard.core

import electionguard.core.Base16.fromHex

// copied from the ElectionGuard spec, Appendix "Other Parameters"
object Primes3072 {
    const val nbits = 3072
    const val nbytes = nbits/8

    private const val pOrg =
        "FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF" +
                "B17217F7 D1CF79AB C9E3B398 03F2F6AF 40F34326 7298B62D 8A0D175B 8BAAFA2B" +
                "E7B87620 6DEBAC98 559552FB 4AFA1B10 ED2EAE35 C1382144 27573B29 1169B825" +
                "3E96CA16 224AE8C5 1ACBDA11 317C387E B9EA9BC3 B136603B 256FA0EC 7657F74B" +
                "72CE87B1 9D6548CA F5DFA6BD 38303248 655FA187 2F20E3A2 DA2D97C5 0F3FD5C6" +
                "07F4CA11 FB5BFB90 610D30F8 8FE551A2 EE569D6D FC1EFA15 7D2E23DE 1400B396" +
                "17460775 DB8990E5 C943E732 B479CD33 CCCC4E65 9393514C 4C1A1E0B D1D6095D" +
                "25669B33 3564A337 6A9C7F8A 5E148E82 074DB601 5CFE7AA3 0C480A54 17350D2C" +
                "955D5179 B1E17B9D AE313CDB 6C606CB1 078F735D 1B2DB31B 5F50B518 5064C18B" +
                "4D162DB3 B365853D 7598A195 1AE273EE 5570B6C6 8F969834 96D4E6D3 30D6E582" +
                "CAB40D66 550984EF 0C42A457 4280B378 45189610 AE3E4BB2 2590A08F 6AD27BFB" +
                "FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF"

    val pStr = pOrg.filterNot { it.isWhitespace() }
    val largePrimeBytes = pStr.fromHex()!!.normalize(nbytes)

    const val qStr = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF43"
    val smallPrimeBytes = qStr.fromHex()!!.normalize(32)

    private const val rOrg =
        "1 00000000 00000000 00000000 00000000 00000000 00000000 00000000 000000BC" +
                "B17217F7 D1CF79AB C9E3B398 03F2F6AF 40F34326 7298B62D 8A0D175B 8BAB857A" +
                "E8F42816 5418806C 62B0EA36 355A3A73 E0C74198 5BF6A0E3 130179BF 2F0B43E3" +
                "3AD86292 3861B8C9 F768C416 9519600B AD06093F 964B27E0 2D868312 31A9160D" +
                "E48F4DA5 3D8AB5E6 9E386B69 4BEC1AE7 22D47579 249D5424 767C5C33 B9151E07" +
                "C5C11D10 6AC446D3 30B47DB5 9D352E47 A53157DE 04461900 F6FE360D B897DF53" +
                "16D87C94 AE71DAD0 BE84B647 C4BCF818 C23A2D4E BB53C702 A5C8062D 19F5E9B5" +
                "033A94F7 FF732F54 12971286 9D97B8C9 6C412921 A9D86797 70F499A0 41C297CF" +
                "F79D4C91 49EB6CAF 67B9EA3D C563D965 F3AAD137 7FF22DE9 C3E62068 DD0ED615" +
                "1C37B4F7 4634C2BD 09DA912F D599F433 3A8D2CC0 05627DCA 37BAD43E 64CAF318" +
                "9FD4A7F5 29FD4A7F 529FD4A7 F529FD4A 7F529FD4 A7F529FD 4A7F529F D4A7F52A"

    val rStr = rOrg.filterNot { it.isWhitespace() }
    val residualBytes = rStr.fromHex()!!.normalize(nbytes)

    private const val gOrg =
        "4A1523CB 0111B381 04EBCDE5 163F581E EEDD9163 7AC57544 C1D22832 34272732" +
                "FF0CD85F 38539544 3F573701 32A237FF 38702AB0 37F35E7C 7003669D 83697BA1" +
                "3BED69B6 3C88BD61 0D33C6A8 9E4882EE 6F849F05 06A4A8F0 B169E5CA 000A21DC" +
                "16D7DCEC C69E593C 65967739 3B6CE260 D3D6A578 E74E42A1 B2ADE1ED 8627050C" +
                "FB59E604 CAC389E9 9161DA6E 6E9407DF 94517864 01003A8B 7626AC5E 90B888EA" +
                "BB5E07E9 96B18662 9B17165F D630E139 788F674D FF4978A6 B74C6D02 0A6570CC" +
                "7C7A9E38 21283571 BA3FA1FC C6901A8C 28D02EF8 B8C4B019 F7DDADE5 1A089C57" +
                "EF90C2CE 50761754 D778BC9A BFD84809 5C4A0ED0 FA7B7AE5 2CDA4BD6 E2CB16F3" +
                "8EDC033F 32F259C5 13DD9E0D 1F780886 D71D7DB8 35F3F08D B11CC9CD 41EB0D5A" +
                "37AC6DBA 1A1EBA55 C378BC06 95B9D93A A59903EB A1CE5288 6A0BAAFB 15354863" +
                "1BCEAC52 07B97205 BE8FDF83 0F27348C 7AE852F9 F8876887 D23B8054 A077DC8A" +
                "EC0BF615 A1FA74BC 727014CF AC40E20E A194489F 63A6C224 27CB999C 9D04AA61"

    val gStr = gOrg.filterNot { it.isWhitespace() }
    val generatorBytes = gStr.fromHex()!!.normalize(nbytes)
}