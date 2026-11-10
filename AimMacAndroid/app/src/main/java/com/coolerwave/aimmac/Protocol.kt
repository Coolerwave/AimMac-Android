// Same Bluetooth LE protocol as the Mac receiver, iPhone and Apple Watch apps
// (see Shared/Protocol.swift in the AimMac repo). Little endian, first byte = kind.
//
//   hello   01  uuid[16] kind:u8 name:utf8     who I am (kind 0 = watch, 1 = phone)
//   aim     02  t:f64 az:f32 el:f32             degrees; az grows turning left, el grows tilting up
//   press   03  t:f64                           finger went down
//   release 04  t:f64                           finger went up
//   right   05  t:f64                           right click
//   scroll  06  dx:f32 dy:f32                   pixels, natural direction
//   center  07                                  "I'm pointing at the middle of the screen now"
//   pause   08  on:u8
//   info    09  battery:u8 (255 = unknown) model:utf8
//   move    0A  dx:f32 dy:f32                   relative move in pixels (trackpad mode)
//
// Status (Mac -> phone, read + notify): state:u8 (0 waiting, 1 approved, 2 denied) paused:u8 macName:utf8

package com.coolerwave.aimmac

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object TM {
    val service: UUID = UUID.fromString("7E1A0001-3C2B-4F7D-9B61-2A9E5D0C4F10")
    val input: UUID = UUID.fromString("7E1A0002-3C2B-4F7D-9B61-2A9E5D0C4F10")
    val status: UUID = UUID.fromString("7E1A0003-3C2B-4F7D-9B61-2A9E5D0C4F10")
}

object Kind {
    const val HELLO = 0x01
    const val AIM = 0x02
    const val PRESS = 0x03
    const val RELEASE = 0x04
    const val RIGHT = 0x05
    const val SCROLL = 0x06
    const val CENTER = 0x07
    const val PAUSE = 0x08
    const val INFO = 0x09
    const val MOVE = 0x0A

    const val DEVICE_PHONE = 1
}

class PacketWriter(kind: Int) {
    private val out = ByteArrayOutputStream()

    init {
        out.write(kind)
    }

    fun u8(v: Int) {
        out.write(v and 0xFF)
    }

    fun f32(v: Float) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
    }

    fun f64(v: Double) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array())
    }

    /** The 16 bytes in the usual (big endian) order, same as the Swift side. */
    fun uuid(u: UUID) {
        out.write(
            ByteBuffer.allocate(16)
                .putLong(u.mostSignificantBits)
                .putLong(u.leastSignificantBits)
                .array()
        )
    }

    /** Rest-of-packet string, cut to keep packets small. */
    fun string(s: String, max: Int = 60) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(b, 0, minOf(b.size, max))
    }

    fun bytes(): ByteArray = out.toByteArray()
}
