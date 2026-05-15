/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * OrtAssets.kt is part of LatentJam.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml

import android.content.Context
import java.io.ByteArrayOutputStream

/** Helpers shared by the ONNX-runtime-backed components. */
internal object OrtAssets {
    fun readAsset(context: Context, path: String): ByteArray {
        context.assets.open(path).use { input ->
            ByteArrayOutputStream(input.available().coerceAtLeast(1024)).use { sink ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                }
                return sink.toByteArray()
            }
        }
    }
}
