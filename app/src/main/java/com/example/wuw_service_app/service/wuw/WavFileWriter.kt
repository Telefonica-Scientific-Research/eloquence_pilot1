// © 2026 Telefónica Innovación Digital S.L.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.example.wuw_service_app.service.wuw

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFileWriter {

    // Helper functions must be defined BEFORE they're used in lambdas
    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
        writeByte((value shr 16) and 0xFF)
        writeByte((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
    }

    fun writeWavFile(file: File, audioData: ShortArray, sampleRate: Int) {
        val byteBuffer = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            byteBuffer[i * 2] = (audioData[i].toInt() and 0x00FF).toByte()
            byteBuffer[i * 2 + 1] = ((audioData[i].toInt() shr 8) and 0xFF).toByte()
        }

        val outputStream = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))

        val totalDataLen = byteBuffer.size + 36
        val byteRate = sampleRate * 2

        // Write WAV header
        outputStream.writeBytes("RIFF")
        outputStream.writeIntLE(totalDataLen)
        outputStream.writeBytes("WAVE")
        outputStream.writeBytes("fmt ")
        outputStream.writeIntLE(16) // Subchunk1Size
        outputStream.writeShortLE(1) // AudioFormat (PCM)
        outputStream.writeShortLE(1) // NumChannels
        outputStream.writeIntLE(sampleRate)
        outputStream.writeIntLE(byteRate)
        outputStream.writeShortLE(2) // BlockAlign
        outputStream.writeShortLE(16) // BitsPerSample
        outputStream.writeBytes("data")
        outputStream.writeIntLE(byteBuffer.size)
        outputStream.write(byteBuffer)

        outputStream.flush()
        outputStream.close()
    }

    fun writeWavToStream(outputStream: OutputStream, audioData: ShortArray, sampleRate: Int) {
        val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        audioData.forEach { byteBuffer.putShort(it) }

        val audioBytes = byteBuffer.array()
        val totalDataLen = 36 + audioBytes.size
        val byteRate = sampleRate * 2

        val header = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(intToByteArray(totalDataLen))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToByteArray(16)) // Subchunk1Size
            write(shortToByteArray(1)) // AudioFormat (PCM)
            write(shortToByteArray(1)) // NumChannels
            write(intToByteArray(sampleRate))
            write(intToByteArray(byteRate))
            write(shortToByteArray(2)) // BlockAlign
            write(shortToByteArray(16)) // BitsPerSample
            write("data".toByteArray())
            write(intToByteArray(audioBytes.size))
        }.toByteArray()

        outputStream.write(header)
        outputStream.write(audioBytes)
        outputStream.flush()
    }
}

