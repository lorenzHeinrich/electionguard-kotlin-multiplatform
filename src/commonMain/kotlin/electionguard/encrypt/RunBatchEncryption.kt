@file:OptIn(ExperimentalCli::class, ExperimentalCoroutinesApi::class)

package electionguard.encrypt

import com.github.michaelbull.result.getOrThrow
import electionguard.ballot.ElectionInitialized
import electionguard.ballot.PlaintextBallot
import electionguard.ballot.EncryptedBallot
import electionguard.core.ElGamalPublicKey
import electionguard.core.ElementModQ
import electionguard.core.GroupContext
import electionguard.core.getSystemDate
import electionguard.core.getSystemTimeInMillis
import electionguard.core.productionGroup
import electionguard.core.randomElementModQ
import electionguard.core.toElementModQ
import electionguard.input.BallotInputValidation
import electionguard.input.ManifestInputValidation
import electionguard.publish.ElectionRecord
import electionguard.publish.Publisher
import electionguard.publish.PublisherMode
import electionguard.publish.EncryptedBallotSinkIF
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.required
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
import mu.KotlinLogging

private val logger = KotlinLogging.logger("RunBatchEncryption")

/**
 * Run ballot encryption in batch mode.
 * Read election record from inputDir, write to outputDir.
 * Read plaintext ballots from ballotDir.
 * All ballots will be cast.
 */
fun main(args: Array<String>) {
    val parser = ArgParser("RunBatchEncryption.kexe")
    val inputDir by parser.option(
        ArgType.String,
        shortName = "in",
        description = "Directory containing input ElectionInitialized.protobuf file"
    ).required()
    val ballotDir by parser.option(
        ArgType.String,
        shortName = "ballots",
        description = "Directory to read Plaintext ballots from"
    ).required()
    val outputDir by parser.option(
        ArgType.String,
        shortName = "out",
        description = "Directory to write output election record"
    ).required()
    val invalidDir by parser.option(
        ArgType.String,
        shortName = "invalid",
        description = "Directory to write invalid input ballots to"
    )
    val fixedNonces by parser.option(
        ArgType.Boolean,
        shortName = "fixed",
        description = "Encrypt with fixed nonces and timestamp"
    )
    val nthreads by parser.option(
        ArgType.Int,
        shortName = "nthreads",
        description = "Number of parallel threads to use"
    )
    val createdBy by parser.option(
        ArgType.String,
        shortName = "createdBy",
        description = "who created"
    )
    parser.parse(args)
    println("RunBatchEncryption starting\n   input= $inputDir\n   ballots = $ballotDir\n   output = $outputDir")

    batchEncryption(
        productionGroup(),
        inputDir,
        outputDir,
        ballotDir,
        invalidDir,
        fixedNonces ?: false,
        nthreads ?: 11,
        createdBy
    )
}

fun batchEncryption(
    group: GroupContext, inputDir: String, outputDir: String, ballotDir: String,
    invalidDir: String?, fixedNonces: Boolean, nthreads: Int, createdBy: String?
) {
    val electionRecordIn = ElectionRecord(inputDir, group)
    val electionInit: ElectionInitialized =
        electionRecordIn.readElectionInitialized().getOrThrow { IllegalStateException(it) }

    // ManifestInputValidation
    val manifestValidator = ManifestInputValidation(electionInit.manifest())
    val errors = manifestValidator.validate()
    if (errors.hasErrors()) {
        println("*** ManifestInputValidation FAILED on election record in $inputDir")
        println("$errors")
        // kotlin.system.exitProcess(1) // kotlin 1.6.20
        return
    }
    // Map<BallotStyle: String, selectionCount: Int>
    val styleCount = manifestValidator.countEncryptions()

    // BallotInputValidation
    var countEncryptions = 0
    val invalidBallots = ArrayList<PlaintextBallot>()
    val ballotValidator = BallotInputValidation(electionInit.manifest())
    val filter: ((PlaintextBallot) -> Boolean) = {
        val mess = ballotValidator.validate(it)
        if (mess.hasErrors()) {
            println("*** BallotInputValidation FAILED on ballot ${it.ballotId}")
            println("$mess\n")
            invalidBallots.add(PlaintextBallot(it, mess.toString()))
            false
        } else {
            countEncryptions += styleCount[it.ballotStyleId] ?: 0
            true
        }
    }

    val codeSeed: ElementModQ = electionInit.cryptoExtendedBaseHash.toElementModQ(group)
    val masterNonce = if (fixedNonces) group.TWO_MOD_Q else null
    val starting = getSystemTimeInMillis()
    val encryptor = Encryptor(
        group,
        electionInit.manifest(),
        ElGamalPublicKey(electionInit.jointPublicKey),
        electionInit.cryptoExtendedBaseHash
    )

    val publisher = Publisher(outputDir, PublisherMode.createIfMissing)
    val sink: EncryptedBallotSinkIF = publisher.encryptedBallotSink()

    runBlocking {
        val outputChannel = Channel<CiphertextBallot>()
        val encryptorJobs = mutableListOf<Job>()
        val ballotProducer = produceBallots(electionRecordIn.iteratePlaintextBallots(ballotDir, filter))
        repeat(nthreads) {
            encryptorJobs.add(
                launchEncryptor(
                    it,
                    group,
                    ballotProducer,
                    encryptor,
                    codeSeed,
                    masterNonce,
                    outputChannel
                )
            )
        }
        launchSink(outputChannel, sink)

        // wait for all encryptions to be done, then close everything
        joinAll(*encryptorJobs.toTypedArray())
        outputChannel.close()
    }
    sink.close()

    // LOOK i dont know if this is a good idea
    publisher.writeElectionInitialized(electionInit.addMetadata(
            Pair("Used", createdBy ?: "RunBatchEncryption"),
            Pair("UsedOn", getSystemDate().toString()),
            Pair("CreatedFromDir", inputDir))
        )
    if (invalidDir != null && !invalidBallots.isEmpty()) {
        publisher.writePlaintextBallot(invalidDir, invalidBallots)
        println(" wrote ${invalidBallots.size} invalid ballots to $invalidDir")
    }

    val took = getSystemTimeInMillis() - starting
    val msecsPerBallot = (took.toDouble() / count).roundToInt()
    println("Encryption with nthreads = $nthreads took $took millisecs for $count ballots = $msecsPerBallot msecs/ballot")
    val msecPerEncryption = (took.toDouble() / countEncryptions)
    val encryptionPerBallot = (countEncryptions / count)
    println("    $countEncryptions total encryptions = $encryptionPerBallot per ballot = $msecPerEncryption millisecs/encryption")
}

// place the ballot reading into its own coroutine
private fun CoroutineScope.produceBallots(producer: Iterable<PlaintextBallot>): ReceiveChannel<PlaintextBallot> = produce {
    for (ballot in producer) {
        logger.debug{ "Producer sending plaintext ballot ${ballot.ballotId}" }
        send(ballot)
        yield()
    }
    channel.close()
}

// coroutines allow parallel encryption at the ballot level
// LOOK not possible to do ballot chaining, since the order is indeterminate?
// LOOK or do we just have to work harder??
private fun CoroutineScope.launchEncryptor(
    id: Int,
    group: GroupContext,
    input: ReceiveChannel<PlaintextBallot>,
    encryptor: Encryptor,
    codeSeed: ElementModQ,
    masterNonce: ElementModQ?,
    output: SendChannel<CiphertextBallot>,
) = launch(Dispatchers.Default) {
    for (ballot in input) {
        val encrypted = if (masterNonce != null) // make deterministic
            encryptor.encrypt(ballot, codeSeed, masterNonce, 0)
        else
            encryptor.encrypt(ballot, codeSeed, group.randomElementModQ())

        logger.debug{" Encryptor #$id sending ciphertext ballot ${encrypted.ballotId}"}
        output.send(encrypted)
        yield()
    }
    logger.debug{"Encryptor #$id done"}
}

// place the ballot writing into its own coroutine
private var count = 0
private fun CoroutineScope.launchSink(
    input: Channel<CiphertextBallot>, sink: EncryptedBallotSinkIF,
) = launch {
    for (ballot in input) {
        sink.writeEncryptedBallot(ballot.submit(EncryptedBallot.BallotState.CAST))
        logger.debug{" Sink wrote $count submitted ballot ${ballot.ballotId}"}
        count++
    }
}


