package electionguard.ballot

import electionguard.core.ElGamalPublicKey
import electionguard.core.ElementModQ
import electionguard.core.UInt256

/** Results of tallying some collection of ballots, namely an EncryptedTally. */
data class TallyResult(
    val electionInitialized: ElectionInitialized,
    val encryptedTally: EncryptedTally,
    val ballotIds: List<String>,
    val tallyIds: List<String>,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun jointPublicKey(): ElGamalPublicKey {
        return ElGamalPublicKey(electionInitialized.jointPublicKey)
    }
    fun cryptoExtendedBaseHash(): UInt256 {
        return electionInitialized.extendedBaseHash
    }
    fun numberOfGuardians(): Int {
        return electionInitialized.config.numberOfGuardians
    }
    fun quorum(): Int {
        return electionInitialized.config.quorum
    }
}

/** Results of decrypting an EncryptedTally, namely a DecryptedTallyOrBallot. */
data class DecryptionResult(
    val tallyResult: TallyResult,
    val decryptedTally: DecryptedTallyOrBallot,
    // val lagrangeCoordinates: List<LagrangeCoordinate>,
    val metadata: Map<String, String> = emptyMap(),
)

data class LagrangeCoordinate(
    var guardianId: String,
    var xCoordinate: Int,
    var lagrangeCoefficient: ElementModQ, // wℓ, spec 1.9 eq 68
) {
    init {
        require(guardianId.isNotEmpty())
        require(xCoordinate > 0)
    }
}