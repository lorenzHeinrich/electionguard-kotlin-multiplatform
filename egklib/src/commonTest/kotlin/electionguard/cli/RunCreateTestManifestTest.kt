package electionguard.cli

import electionguard.core.productionGroup
import electionguard.publish.readAndCheckManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCreateTestManifestTest {

    @Test
    fun runCreateTestManifest() {
        RunCreateTestManifest.main(
            arrayOf(
                "-ncontests", "20",
                "-nselections", "5",
                "-out", "testOut/manifest/runCreateTestManifest",
            )
        )
    }

    @Test
    fun runConvertManifest() {

        RunConvertManifest.main(
            arrayOf(
                "-manifest",
                "testOut/manifest/runCreateTestManifest",
                "-out",
                "testOut/manifest/testConvertManifestFromJsonToProto",
            )
        )

        RunConvertManifest.main(
            arrayOf(
                "-manifest",
                "testOut/manifest/testConvertManifestFromJsonToProto",
                "-out",
                "testOut/manifest/testConvertManifestFromProtoToJson",
            )
        )

        // get all and see if they compare equal
        val group = productionGroup()
        val (isJsonOrg, manifestOrg, _) = readAndCheckManifest(group, "testOut/manifest/runCreateTestManifest")
        assertTrue(isJsonOrg)

        val (isJsonProto, manifestProto, _) = readAndCheckManifest(group, "testOut/manifest/testConvertManifestFromJsonToProto")
        assertFalse(isJsonProto)
        assertEquals(manifestOrg, manifestProto)

        val (isJsonRoundTrip, manifestRoundtrip, _) = readAndCheckManifest(group, "testOut/manifest/testConvertManifestFromProtoToJson")
        assertTrue(isJsonRoundTrip)
        assertEquals(manifestProto, manifestRoundtrip)

        assertEquals(manifestOrg, manifestRoundtrip)
    }

}

