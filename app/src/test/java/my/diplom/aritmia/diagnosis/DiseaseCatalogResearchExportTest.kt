package my.diplom.aritmia.diagnosis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Research-only bridge: exports the exact Android DiseaseCatalog weights for Python evaluation. */
class DiseaseCatalogResearchExportTest {

    @Test
    fun exportExactCatalogWeights() {
        assertEquals(47, DiseaseCatalog.concepts.size)
        assertEquals(14, DiseaseCatalog.definitions.size)

        val output = File("build/disease_catalog_weights.json")
        output.parentFile?.mkdirs()
        output.writeText(toJson(), Charsets.UTF_8)

        assertTrue(output.isFile && output.length() > 0L)
        println("Disease catalog weights written to ${output.absolutePath}")
    }

    private fun toJson(): String = buildString {
        append("{\n")
        append("  \"conceptIds\": [")
        append(DiseaseCatalog.concepts.joinToString(", ") { "\"${it.id}\"" })
        append("],\n")
        append("  \"diseases\": [\n")
        DiseaseCatalog.definitions.forEachIndexed { diseaseIndex, disease ->
            append("    {\"id\": \"${disease.id}\", \"weights\": {")
            append(
                disease.conceptWeights.entries.joinToString(", ") { (conceptId, weight) ->
                    "\"$conceptId\": $weight"
                }
            )
            append("}}")
            if (diseaseIndex != DiseaseCatalog.definitions.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }
}
