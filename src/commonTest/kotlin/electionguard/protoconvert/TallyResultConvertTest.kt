package electionguard.protoconvert

import com.github.michaelbull.result.getOrThrow
import electionguard.ballot.*
import electionguard.core.GroupContext
import electionguard.core.tinyGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TallyResultConvertTest {

    @Test
    fun roundtripTallyResult() {
        val context = tinyGroup()
        val electionRecord = generateTallyResult(context)
        val proto = electionRecord.publishTallyResult()
        val roundtrip = context.importTallyResult(proto).getOrThrow { IllegalStateException(it) }
        assertNotNull(roundtrip)

        //     val group: GroupContext,
        //    val electionIntialized: ElectionInitialized,
        //    val ciphertextTally: CiphertextTally,
        //    val metadata: Map<String, String> = emptyMap()
        assertEquals(roundtrip.group, electionRecord.group)
        assertEquals(roundtrip.electionIntialized, electionRecord.electionIntialized)
        assertEquals(roundtrip.ciphertextTally, electionRecord.ciphertextTally)
        assertEquals(roundtrip.metadata, electionRecord.metadata)

        assertTrue(roundtrip.equals(electionRecord))
        assertEquals(roundtrip, electionRecord)
    }
}

fun generateTallyResult(context: GroupContext): TallyResult {
    //     val group: GroupContext,
    //    val electionIntialized: ElectionInitialized,
    //    val ciphertextTally: CiphertextTally,
    //    val metadata: Map<String, String> = emptyMap()
    return TallyResult(
        context,
        generateElectionInitialized(context),
        CiphertextTallyConvertTest.generateFakeTally(context),
    )
}