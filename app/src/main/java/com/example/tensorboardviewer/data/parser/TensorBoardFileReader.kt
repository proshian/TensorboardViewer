package com.example.tensorboardviewer.data.parser

import org.tensorflow.util.Event
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TensorBoardFileReader(inputStream: InputStream) {
    private val dataInput = DataInputStream(inputStream)

    /**
     * Reads the next Event from the stream.
     * Returns null if End of File is reached.
     */
    fun readEvent(): Event? {
        try {
            // 1. Read Length (8 bytes, uint64)
            // Stored as little-endian in TFRecords? No, TF uses custom format.
            // Actually, Java DataInputStream is BigEndian. TFRecords are usually LittleEndian.
            // Let's read bytes and convert.
            
            val lengthBytes = ByteArray(8)
            try {
                dataInput.readFully(lengthBytes)
            } catch (e: EOFException) {
                return null
            }
            
            val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long

            // 2. Read CRC of Length (4 bytes)
            val crcLengthBytes = ByteArray(4)
            dataInput.readFully(crcLengthBytes)
            
            // 3. Read Data
            val data = ByteArray(length.toInt())
            dataInput.readFully(data)

            // 4. Read CRC of Data (4 bytes)
            val crcDataBytes = ByteArray(4)
            dataInput.readFully(crcDataBytes)

            // 5. Parse Protobuf
            return Event.parseFrom(data)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
