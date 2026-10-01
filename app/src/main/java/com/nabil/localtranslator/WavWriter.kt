package com.nabil.localtranslator

import java.io.File
import java.io.RandomAccessFile

object WavWriter {
    fun writePcm16Mono(file: File, pcm: ByteArray, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { out ->
            val dataLen = pcm.size
            val byteRate = sampleRate * 2
            out.setLength(0)
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(1)
            out.writeIntLE(sampleRate)
            out.writeIntLE(byteRate)
            out.writeShortLE(2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            out.write(pcm)
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff)
        write((v ushr 8) and 0xff)
        write((v ushr 16) and 0xff)
        write((v ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff)
        write((v ushr 8) and 0xff)
    }
}
