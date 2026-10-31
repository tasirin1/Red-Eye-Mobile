package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldPhotosTest {

    private fun legacy(hash: Long) = HeldPhotos.Entry(null, hash)

    private fun named(name: String, hash: Long) = HeldPhotos.Entry(name, hash)

    @Test
    fun parseFormatLamaDanCampuran() {
        assertEquals(listOf(legacy(123), legacy(456)), HeldPhotos.parse("123,456"))
        assertEquals(
            listOf(named("camera_1.jpg", 123), legacy(456), named("camera_2.jpg", -789)),
            HeldPhotos.parse("camera_1.jpg=123,456,camera_2.jpg=-789")
        )
        assertEquals(
            "camera_1.jpg=123,456,camera_2.jpg=-789",
            HeldPhotos.serialize(HeldPhotos.parse("camera_1.jpg=123,456,camera_2.jpg=-789"))
        )
    }

    @Test
    fun parseTokenTakDikenalDipertahankanApaAdanya() {
        val parsed = HeldPhotos.parse("aneh,123,x=y,=5")
        assertEquals(HeldPhotos.Entry(null, 0L, "aneh"), parsed[0])
        assertEquals(legacy(123), parsed[1])
        assertEquals(HeldPhotos.Entry(null, 0L, "x=y"), parsed[2])
        assertEquals(HeldPhotos.Entry(null, 0L, "=5"), parsed[3])
        assertEquals("aneh,123,x=y,=5", HeldPhotos.serialize(parsed))
    }

    @Test
    fun hashesOfMengabaikanTokenTakDikenalDanNol() {
        val entries = HeldPhotos.parse("camera_1.jpg=123,456,aneh,camera_2.jpg=-789")
        assertEquals(linkedSetOf(123L, 456L, -789L), HeldPhotos.hashesOf(entries))
    }

    @Test
    fun healBernamaHanyaMemeriksaKeberadaanBerkas() {
        val entries = listOf(named("camera_1.jpg", 1), named("camera_2.jpg", 2))
        assertEquals(listOf(named("camera_1.jpg", 1)), HeldPhotos.heal(entries, setOf("camera_1.jpg"), null))
    }

    @Test
    fun healWarisanTanpaDecodeDipertahankan() {
        val entries = listOf(legacy(10), legacy(20))
        assertEquals(entries, HeldPhotos.heal(entries, setOf("x.jpg"), null))
    }

    @Test
    fun healWarisanDenganDecodeBerpindahKeBernamaDanMemangkasYangMati() {
        val entries = listOf(legacy(10), legacy(20))
        val healed = HeldPhotos.heal(entries, setOf("x.jpg", "y.jpg"), linkedMapOf("x.jpg" to 10L, "y.jpg" to 99L))
        assertEquals(listOf(named("x.jpg", 10)), healed)
    }

    @Test
    fun healTokenTakDikenalTakPernahDipangkas() {
        val entries = HeldPhotos.parse("aneh,camera_1.jpg=1")
        assertEquals(listOf(HeldPhotos.Entry(null, 0L, "aneh")), HeldPhotos.heal(entries, emptySet(), null))
    }

    @Test
    fun healKonversiWarisanBentrokNamaMengutamakanYangPertama() {
        val entries = listOf(named("camera_1.jpg", 1), legacy(2))
        val healed = HeldPhotos.heal(entries, setOf("camera_1.jpg"), linkedMapOf("camera_1.jpg" to 2L))
        assertEquals(listOf(named("camera_1.jpg", 1)), healed)
    }

    @Test
    fun withHeldMenambahMenggantiDanMengusirTertua() {
        val base = listOf(named("camera_1.jpg", 1), legacy(2))
        assertEquals(
            listOf(named("camera_1.jpg", 1), legacy(2), named("camera_2.jpg", 3)),
            HeldPhotos.withHeld(base, "camera_2.jpg", 3, 10)
        )
        assertEquals(
            listOf(named("camera_1.jpg", 1), named("camera_3.jpg", 2)),
            HeldPhotos.withHeld(base, "camera_3.jpg", 2, 10)
        )
        assertEquals(
            listOf(legacy(2), named("camera_1.jpg", 9)),
            HeldPhotos.withHeld(base, "camera_1.jpg", 9, 10)
        )
        assertEquals(
            listOf(named("camera_1.jpg", 2), named("camera_2.jpg", 3)),
            HeldPhotos.withHeld(listOf(legacy(1), named("camera_1.jpg", 2)), "camera_2.jpg", 3, 2)
        )
        assertEquals(base, HeldPhotos.withHeld(base, "camera_2.jpg", 0, 10))
    }

    @Test
    fun withHeldMenolakSidikTakTerbandingkan() {
        val base = listOf(named("camera_1.jpg", 2), legacy(3))
        assertEquals(base, HeldPhotos.withHeld(base, "camera_2.jpg", 0, 10))
        assertEquals(base, HeldPhotos.withHeld(base, "camera_2.jpg", 1, 10))
    }

    @Test
    fun withoutNamesDanHashesSelektif() {
        val entries = HeldPhotos.parse("camera_1.jpg=1,2,aneh")
        assertEquals(
            listOf(legacy(2), HeldPhotos.Entry(null, 0L, "aneh")),
            HeldPhotos.withoutNames(entries, setOf("camera_1.jpg"))
        )
        assertEquals(
            listOf(HeldPhotos.Entry(null, 0L, "aneh")),
            HeldPhotos.withoutHashes(entries, setOf(1L, 2L))
        )
    }
}
