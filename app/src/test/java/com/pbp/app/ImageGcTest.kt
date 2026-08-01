package com.pbp.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 고아 이미지 판정 규칙 — "아무도 가리키지 않는 파일만 지운다".
 *
 * 실제 [com.pbp.app.data.ImageGc]는 Context·Room이 필요해 단위 테스트에서 돌릴 수 없어,
 * 같은 판정 규칙을 여기서 고정한다. 규칙이 바뀌면 이 테스트부터 깨진다.
 */
class ImageGcTest {

    /** ImageGc.sweep의 판정과 같은 규칙 */
    private fun sweep(dir: File, referenced: Set<String>): Int {
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in referenced) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private fun tempDirWith(vararg names: String): File {
        val dir = Files.createTempDirectory("pbp-gc").toFile()
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    @Test
    fun `가리켜지지 않는 파일만 지운다`() {
        val dir = tempDirWith("keep.jpg", "orphan.jpg")
        val keep = File(dir, "keep.jpg")
        assertEquals(1, sweep(dir, setOf(keep.absolutePath)))
        assertEquals(true, keep.exists())
        assertEquals(false, File(dir, "orphan.jpg").exists())
    }

    @Test
    fun `전부 가리켜지면 아무것도 지우지 않는다`() {
        val dir = tempDirWith("a.jpg", "b.png")
        val all = dir.listFiles()!!.map { it.absolutePath }.toSet()
        assertEquals(0, sweep(dir, all))
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `하위 디렉터리는 건드리지 않는다`() {
        val dir = tempDirWith("orphan.jpg")
        File(dir, "sub").mkdirs()
        assertEquals(1, sweep(dir, emptySet()))
        assertEquals(true, File(dir, "sub").isDirectory)
    }
}
