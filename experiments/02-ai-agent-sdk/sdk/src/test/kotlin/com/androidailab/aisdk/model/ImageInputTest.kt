package com.androidailab.aisdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-002 / M-003 ImageInput 단위 테스트.
 *
 * 사양 참조:
 * - data-model.md M-003 (Variant 정의 — Uri/Bytes/Url)
 * - R-016: Bytes의 equals/hashCode는 contentEquals + contentHashCode + mimeType 조합
 * - features.md F-002 / provider-spec.md "ImageInput.Url SSRF 방어 정책 (R-023)" — SDK는 형식 검증만 수행
 *
 * 검증:
 * 1. R-016 Bytes equals/hashCode (ByteArray reference 비교 회피)
 * 2. Bytes init 검증 (data 비어있지 않음, mimeType 비어있지 않음)
 * 3. Url init 검증 (https 스킴 강제, 빈 문자열 거부)
 */
class ImageInputTest {

    // -----------------------------------------------------------------
    // R-016: Bytes equals/hashCode
    // -----------------------------------------------------------------

    @Test
    fun `R-016 — 동일한 data + mimeType 의 Bytes 는 equals 가 true`() {
        val a = ImageInput.Bytes(byteArrayOf(1, 2, 3, 4, 5), "image/png")
        val b = ImageInput.Bytes(byteArrayOf(1, 2, 3, 4, 5), "image/png")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `R-016 — 동일 data 라도 mimeType 다르면 equals 가 false`() {
        val a = ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/png")
        val b = ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/jpeg")

        assertNotEquals(a, b)
    }

    @Test
    fun `R-016 — 동일 mimeType 라도 data 다르면 equals 가 false`() {
        val a = ImageInput.Bytes(byteArrayOf(1, 2, 3), "image/png")
        val b = ImageInput.Bytes(byteArrayOf(1, 2, 4), "image/png")

        assertNotEquals(a, b)
    }

    @Test
    fun `R-016 — ByteArray reference 가 달라도 contentEquals 가 같으면 equals 가 true`() {
        // data class의 자동 equals는 ByteArray reference 비교라 false. 본 테스트는 명시 정의가
        // 동작함을 검증한다 — R-016 핵심.
        val data1 = byteArrayOf(10, 20, 30)
        val data2 = byteArrayOf(10, 20, 30) // reference는 다르지만 contentEquals
        val a = ImageInput.Bytes(data1, "image/jpeg")
        val b = ImageInput.Bytes(data2, "image/jpeg")

        assertTrue("R-016: contentEquals이 같은 ByteArray는 equals=true", a == b)
        // hashCode도 일관 — equals true이면 hashCode 동일 (contract)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `R-016 — toString 은 mimeType 과 size 만 노출 (data 본문 노출 금지)`() {
        val a = ImageInput.Bytes(byteArrayOf(1, 2, 3, 4, 5), "image/png")

        val str = a.toString()
        assertTrue("toString 에 size 포함", str.contains("size=5"))
        assertTrue("toString 에 mimeType 포함", str.contains("image/png"))
        assertFalse("toString 에 data 본문 노출 금지", str.contains("[1, 2, 3"))
    }

    // -----------------------------------------------------------------
    // Bytes init 검증 (T12 신규)
    // -----------------------------------------------------------------

    @Test
    fun `Bytes init — data 가 빈 배열이면 IllegalArgumentException`() {
        try {
            ImageInput.Bytes(byteArrayOf(), "image/png")
            fail("빈 data 는 require 로 거부되어야 함")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `Bytes init — mimeType 이 빈 문자열이면 IllegalArgumentException`() {
        try {
            ImageInput.Bytes(byteArrayOf(1, 2, 3), "")
            fail("빈 mimeType 는 require 로 거부되어야 함")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // -----------------------------------------------------------------
    // Url init 검증 (T12 신규, R-023 형식 검증)
    // -----------------------------------------------------------------

    @Test
    fun `Url init — 정상 https URL 은 통과`() {
        val u = ImageInput.Url("https://example.com/image.png")
        assertEquals("https://example.com/image.png", u.url)
    }

    @Test
    fun `Url init — 빈 문자열은 IllegalArgumentException`() {
        try {
            ImageInput.Url("")
            fail("빈 url 은 require 로 거부되어야 함")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `Url init — http (non-https) 스킴은 거부 (R-023 형식 검증)`() {
        try {
            ImageInput.Url("http://example.com/img.png")
            fail("http:// 스킴은 require 로 거부되어야 함")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "메시지에 https 안내 포함",
                e.message?.contains("https://") == true,
            )
        }
    }

    @Test
    fun `Url init — file 스킴은 거부 (R-023 형식 검증)`() {
        try {
            ImageInput.Url("file:///etc/passwd")
            fail("file:// 스킴은 require 로 거부되어야 함")
        } catch (e: IllegalArgumentException) {
            // 통과
        }
    }
}
