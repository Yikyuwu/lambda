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

@file:Suppress("unused")

package com.lambda.config.serializers

import com.lambda.config.Deserializer
import com.lambda.config.Serializer
import com.lambda.config.Stringifiable
import net.minecraft.util.math.BlockPos
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext

object BlockPosSerializer : Serializer<BlockPos>(BlockPos::class.java), Stringifiable<BlockPos> {
    override fun serialize(pos: BlockPos, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeStartObject()
        gen.writeNumberProperty("x", pos.x)
        gen.writeNumberProperty("y", pos.y)
        gen.writeNumberProperty("z", pos.z)
        gen.writeEndObject()
    }

    override fun stringify(value: BlockPos) = value.toShortString()
}

/** Reads the tokens directly, since config values are loaded by parsers that can't build trees. */
object BlockPosDeserializer : Deserializer<BlockPos>(BlockPos::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): BlockPos {
        var x = 0
        var y = 0
        var z = 0
        var token = p.currentToken()
        // Anything else counts as unset, and an array is skipped so the values after it still load.
        if (token != JsonToken.START_OBJECT && token != JsonToken.PROPERTY_NAME) {
            p.skipChildren()
            return BlockPos.ORIGIN
        }
        if (token == JsonToken.START_OBJECT) token = p.nextToken()
        while (token == JsonToken.PROPERTY_NAME) {
            val name = p.currentName()
            val value = p.nextToken()
            if (!value.isNumeric) p.skipChildren()
            else when (name) {
                "x" -> x = p.intValue
                "y" -> y = p.intValue
                "z" -> z = p.intValue
            }
            token = p.nextToken()
        }
        return BlockPos(x, y, z)
    }
}
