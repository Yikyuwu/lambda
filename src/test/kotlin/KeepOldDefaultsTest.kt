/*
 * Copyright 2026 Lambda
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.lambda.config.migration.MigrationUtils
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals

class KeepOldDefaultsTest {

    private val mapper = JsonMapper.builder().build()

    private fun keep(json: String, old: Map<String, Map<String, Any>>): Pair<String, Int> {
        val root = mapper.readTree(json) as ObjectNode
        val count = MigrationUtils.keepOldDefaults(root, old)
        return mapper.writeValueAsString(root) to count
    }

    @Test
    fun `keeps what is set`() {
        val (json, count) = keep(
            """{"HighwayTools":{"Settings":{"Left Wall":"Solid"}}}""",
            mapOf("HighwayTools" to mapOf("Left Wall" to "None")),
        )
        assertEquals("""{"HighwayTools":{"Settings":{"Left Wall":"Solid"}}}""", json)
        assertEquals(0, count)
    }

    @Test
    fun `writes the old default next to what is set`() {
        val (json, count) = keep(
            """{"HighwayTools":{"Settings":{"Right Wall":"Solid"}}}""",
            mapOf("HighwayTools" to mapOf("Left Wall" to "None", "Width" to 6)),
        )
        assertEquals("""{"HighwayTools":{"Settings":{"Right Wall":"Solid","Left Wall":"None","Width":6}}}""", json)
        assertEquals(2, count)
    }

    @Test
    fun `creates configs and groups that were never saved`() {
        val (json, _) = keep(
            """{"_schemaVersion":3}""",
            mapOf("ElytraFly" to mapOf("Bounce/Pitch" to 72.0, "Bounce/Bounce Obstacle Passer/Walk When Flagged" to true)),
        )
        assertEquals(
            """{"_schemaVersion":3,"ElytraFly":{"Settings":{"Bounce":{"Pitch":72.0,"Bounce Obstacle Passer":{"Walk When Flagged":true}}}}}""",
            json,
        )
    }

    @Test
    fun `leaves a path alone that isn't a group`() {
        val (json, count) = keep(
            """{"ElytraFly":{"Settings":{"Bounce":true}}}""",
            mapOf("ElytraFly" to mapOf("Bounce/Pitch" to 72.0)),
        )
        assertEquals("""{"ElytraFly":{"Settings":{"Bounce":true}}}""", json)
        assertEquals(0, count)
    }
}
