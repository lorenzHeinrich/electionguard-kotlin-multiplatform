package electionguard.verifier

import com.github.michaelbull.result.*
import electionguard.ballot.Manifest
import electionguard.ballot.DecryptedTallyOrBallot
import electionguard.core.*
import electionguard.core.Base16.toHex
import kotlin.collections.mutableSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

private const val debug = false

/** Box 8, 9, 10, 11, 12, 13, 14. verifySpoiledBallotTallies can be multithreaded. */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyDecryption(
    val group: GroupContext,
    val manifest: Manifest,
    val publicKey: ElGamalPublicKey,
    val extendedBaseHash: ElementModQ,
) {

    fun verify(decrypted: DecryptedTallyOrBallot, isBallot: Boolean, stats: Stats): Result<Boolean, String> {
        val starting = getSystemTimeInMillis()

        var nselections = 0
        val results = mutableListOf<Result<Boolean, String>>()
        val ballotSelectionSet = mutableSetOf<String>()

        for (contest in decrypted.contests) {
            val where = "${decrypted.id}/${contest.contestId}"
            // (9.C) The contest text label occurs as a contest label in the list of contests in the election manifest.
            if (manifest.contestIdToLimit[contest.contestId] == null) {
                results.add(Err("    9.C,13.C Ballot contains contest not in manifest: '$where' "))
                continue
            }

            if (contest.decryptedContestData != null) {
                verifyContestData(where, contest.decryptedContestData)
            }

            var contestVotes = 0
            for (selection in contest.selections) {
                nselections++
                val here = "${contest.contestId}/${selection.selectionId}"
                val where2 = "$${decrypted.id}/$here"
                ballotSelectionSet.add(here)

                // (9.D) For each option in the contest, the option text label occurs as an option label for the contest
                // in the election manifest.
                if (!manifest.contestAndSelectionSet.contains(here)) {
                    results.add(Err("    9.D,13.D Ballot contains selection not in manifest: '$where2' "))
                    continue
                }

                if (!selection.proof.r.inBounds()) {
                    results.add(Err("    8.A,11.A response out of bounds: '$where2' "))
                }

                // 8.B, 11.B
                if (!selection.verifySelection()) {
                    results.add(Err("    8.B,11.B Challenge does not match: '$where2' "))
                }

                // M = K^t mod p.
                val tallyQ = selection.tally.toElementModQ(group)
                if (selection.value != publicKey powP tallyQ) {
                    results.add(Err("    9.B,12.B Tally Decryption M = K^t mod p failed: '$where2'"))
                }

                if (isBallot && (selection.tally !in (0..1))) {
                    results.add(Err("     13.A ballot vote ${selection.tally} must be a 0 or a 1: '$where2'"))
                }
                contestVotes += selection.tally
            }
            if (isBallot) {
                val limit = manifest.contestIdToLimit[contest.contestId]!!
                if (contestVotes !in (0..limit)) {
                    results.add(Err("     13.B sum of votes ${contestVotes} in contest must be less than $limit: '$where'"))
                }
            }
        }

        // (9.E) For each option text label listed for this contest in the election manifest, the option label
        //occurs for a option in the decrypted tally contest.
        // (13.E) For each option text label listed for this contest in the election manifest, the option label
        //occurs for a option in the decrypted spoiled ballot.
        manifest.contestAndSelectionSet.forEach {
            if (!ballotSelectionSet.contains(it)) {
                results.add(Err("    9.E,13.E Manifest contains selection not in ballot: '$it' "))
            }
        }

        stats.of("verifyDecryption", "selections").accum(getSystemTimeInMillis() - starting, nselections)
        return results.merge()
    }

    // this is the verifier proof (box 8)
    private var first = false
    private fun DecryptedTallyOrBallot.Selection.verifySelection(): Boolean {
        val Mbar: ElementModP = this.ciphertext.data / this.value // 8.1
        val a = group.gPowP(this.proof.r) * (publicKey powP this.proof.c) // 8.2
        val b = (this.ciphertext.pad powP this.proof.r) * (Mbar powP this.proof.c) // 8.3

        // The challenge value c satisfies c = H(HE ; 30, K, A, B, a, b, M ). 8.B, eq 72
        val challenge = hashFunction(extendedBaseHash.byteArray(), 0x30.toByte(), publicKey.key, this.ciphertext.pad, this.ciphertext.data, a, b, Mbar)
        if (first) {
            println(" verify qbar = $extendedBaseHash")
            println(" jointPublicKey = $publicKey")
            println(" this.message.pad = ${this.ciphertext.pad}")
            println(" this.message.data = ${this.ciphertext.data}")
            println(" a= $a")
            println(" b= $b")
            println(" Mbar = $Mbar")
            println(" c = $challenge")
            first = false
        }
        return (challenge.toElementModQ(group) == this.proof.c)
    }

    // Verification 10 (Correctness of decryptions of contest data)
    private fun verifyContestData(where: String, decryptedContestData: DecryptedTallyOrBallot.DecryptedContestData): Result<Boolean, String> {
        val proof = decryptedContestData.proof
        val hashedCiphertext = decryptedContestData.encryptedContestData
        // a = g^v * K^c  (10.1,14.1)
        val a = group.gPowP(proof.r) * (publicKey powP proof.c)

        // b = C0^v * β^c (10.2,14.2)
        val b = (hashedCiphertext.c0 powP proof.r) * (decryptedContestData.beta powP proof.c)

        val results = mutableListOf<Result<Boolean, String>>()

        // (10.A,14.A) The given value v is in the set Zq.
        if (!proof.r.inBounds()) {
            results.add(Err("     (10.A,14.A) The value v is not in the set Zq.: '$where'"))
        }

        // (10.B) The challenge value c satisfies c = H(HE ; 31, K, C0 , C1 , C2 , a, b, β).
        val challenge = hashFunction(extendedBaseHash.byteArray(), 0x31.toByte(), publicKey.key,
            hashedCiphertext.c0,
            hashedCiphertext.c1.toHex(),
            hashedCiphertext.c2,
            a, b, decryptedContestData.beta)

        if (challenge != proof.c.toUInt256()) {
            results.add(Err("     (10.B,14.B) The challenge value is wrong: '$where'"))
        }
        return results.merge()
    }

    ////////////////////////////////////////////////////////////////////////////////
    // coroutines

    fun verifySpoiledBallotTallies(
        ballots: Iterable<DecryptedTallyOrBallot>,
        nthreads: Int,
        stats: Stats,
        showTime: Boolean,
    ): Result<Boolean, String> {
        val starting = getSystemTimeInMillis()

        runBlocking {
            val verifierJobs = mutableListOf<Job>()
            val ballotProducer = produceTallies(ballots)
            repeat(nthreads) {
                verifierJobs.add(launchVerifier(it, ballotProducer) { ballot -> verify(ballot, isBallot = true, stats) })
            }

            // wait for all encryptions to be done, then close everything
            joinAll(*verifierJobs.toTypedArray())
        }

        val took = getSystemTimeInMillis() - starting
        val perBallot = if (count == 0) 0 else (took.toDouble() / count).roundToInt()
        if (showTime) println("   verifySpoiledBallotTallies took $took millisecs for $count ballots = $perBallot msecs/ballot wallclock")
        return globalResults.merge()
    }

    private val globalResults = mutableListOf<Result<Boolean, String>>()
    private var count = 0
    private fun CoroutineScope.produceTallies(producer: Iterable<DecryptedTallyOrBallot>): ReceiveChannel<DecryptedTallyOrBallot> =
        produce {
            for (tally in producer) {
                send(tally)
                yield()
                count++
            }
            channel.close()
        }

    private val mutex = Mutex()

    private fun CoroutineScope.launchVerifier(
        id: Int,
        input: ReceiveChannel<DecryptedTallyOrBallot>,
        verify: (DecryptedTallyOrBallot) -> Result<Boolean, String>,
    ) = launch(Dispatchers.Default) {
        for (tally in input) {
            if (debug) println("$id channel working on ${tally.id}")
            val stat = verify(tally)
            mutex.withLock {
                globalResults.add(stat)
            }
            yield()
        }
    }

}