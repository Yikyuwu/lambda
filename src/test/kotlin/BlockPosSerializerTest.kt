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

import com.lambda.config.serializers.BlockPosDeserializer
import com.lambda.config.serializers.BlockPosSerializer
import net.minecraft.util.math.BlockPos
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockPosSerializerTest {

    private val mapper = JsonMapper.builder()
        .addModule(
            SimpleModule()
                .addSerializer(BlockPos::class.java, BlockPosSerializer)
                .addDeserializer(BlockPos::class.java, BlockPosDeserializer)
                .addDeserializer(Entry::class.java, EntryDeserializer)
        )
        .build()

    @Test
    fun `writes x y and z`() {
        assertEquals("""{"x":-112,"y":71,"z":29}""", mapper.writeValueAsString(BlockPos(-112, 71, 29)))
    }

    @Test
    fun `reads back what it writes`() {
        val pos = BlockPos(-30000000, -64, 30000000)
        assertEquals(pos, mapper.readValue(mapper.writeValueAsString(pos), BlockPos::class.java))
    }

    @Test
    fun `writes mutable positions the same way`() {
        assertEquals("""{"x":1,"y":2,"z":3}""", mapper.writeValueAsString(BlockPos.Mutable(1, 2, 3)))
    }

    @Test
    fun `loads like the config loader does`() {
        // Settings load by updating their entry from a tree, and the entry reads its value with ctxt.readValue,
        // whose parser can't build trees.
        val entry = mapper.updateValue(Entry(), mapper.readTree("""{"x":-111,"y":70,"z":28}"""))
        assertEquals(BlockPos(-111, 70, 28), entry.value)
    }

    class Entry {
        var value: BlockPos = BlockPos.ORIGIN
    }

    /** Like ConfigEntryFallbackDeserializer: updates the existing entry instead of making a new one. */
    object EntryDeserializer : StdDeserializer<Entry>(Entry::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Entry = error("Entries are only updated")

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext, entry: Entry) =
            entry.apply { value = ctxt.readValue(p, BlockPos::class.java) }
    }
}
