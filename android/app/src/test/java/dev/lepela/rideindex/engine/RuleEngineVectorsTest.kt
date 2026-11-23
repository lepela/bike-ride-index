package dev.lepela.rideindex.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** rules/test_vectors 의 JSON 케이스 — Python test_engine.test_vectors 와 같은 값을 비교한다. */
class RuleEngineVectorsTest {
    @Serializable
    data class Expected(
        @SerialName("ice_risk") val iceRisk: String,
        @SerialName("rule_score") val ruleScore: Int,
        val confidence: String,
        @SerialName("floor_cap") val floorCap: Int?,
        @SerialName("floor_label") val floorLabel: String?,
        @SerialName("final_score") val finalScore: Int,
        val grade: String,
    )

    @Serializable
    data class Vector(
        val name: String,
        val points: Map<String, Map<String, ModelWindow>>,
        val expected: Expected,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val rules by lazy { loadRules(text("rules.json")) }

    @Test
    fun vectorsMatchPython() {
        val dir = File(javaClass.classLoader!!.getResource("test_vectors")!!.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertEquals(4, files.size)
        for (f in files) {
            val v = json.decodeFromString(Vector.serializer(), f.readText())
            val r = RuleEngine.evaluateSlot(v.points, rules)
            val e = v.expected
            assertEquals(v.name, listOf(e.iceRisk, e.ruleScore, e.confidence, e.floorCap, e.floorLabel),
                listOf(r.iceRisk, r.ruleScore, r.confidence, r.floorCap, r.floorLabel))
            val (final, _) = RuleEngine.applyFloor(r.ruleScore, r.floorCap, r.floorLabel)
            assertEquals(v.name, e.finalScore, final)
            assertEquals(v.name, e.grade, RuleEngine.grade(final, rules))
        }
    }

    @Test
    fun missingVariables() {
        val nulls = listOf<Double?>(null, null)
        val dead = ModelWindow(nulls, nulls, nulls, nulls, nulls, nulls, nulls, nulls, null, null)
        val ok = dead.copy(temp = listOf(3.0, 2.0), precip = listOf(0.0, 0.0), snowfall = listOf(0.0, 0.0))
        val pf = RuleEngine.pointFeatures(mapOf("jma" to ok, "dead" to dead), rules)
        assertEquals(1, pf.nModels)
        assertNull(pf.gustMs)
        assertNull(pf.surfaceC)
        assertEquals("없음", RuleEngine.iceRisk(pf, rules))
    }

    @Test
    fun applyFloor() {
        assertEquals(59 to "ice", RuleEngine.applyFloor(72, 59, "ice"))
        assertEquals(30 to null, RuleEngine.applyFloor(30, 39, "snow"))
        assertEquals(72 to null, RuleEngine.applyFloor(72, null, null))
    }

    @Test
    fun gradeBounds() {
        assertEquals(
            listOf("좋음", "좋음", "주의", "주의", "비추천", "비추천", "타지 않음", "타지 않음"),
            listOf(100, 80, 79, 60, 59, 40, 39, 0).map { RuleEngine.grade(it, rules) },
        )
    }
}
