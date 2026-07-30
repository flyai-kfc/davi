package com.davi.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DogfoodDeviceIdTest {

    /** SHA-256("abc") 표준 벡터의 앞 16자. 이 값이 흔들리면 등록된 화이트리스트가 전부 깨진다. */
    @Test
    fun `hashes a known vector to the first 16 lowercase hex chars`() {
        assertEquals("ba7816bf8f01cfea", DogfoodDeviceId.hash("abc"))
    }

    @Test
    fun `trims whitespace before hashing`() {
        assertEquals(DogfoodDeviceId.hash("abc"), DogfoodDeviceId.hash(" abc "))
    }

    @Test
    fun `returns null for null or blank input`() {
        assertNull(DogfoodDeviceId.hash(null))
        assertNull(DogfoodDeviceId.hash(""))
        assertNull(DogfoodDeviceId.hash("   "))
    }

    @Test
    fun `different ids produce different hashes`() {
        assertNotEquals(DogfoodDeviceId.hash("abc"), DogfoodDeviceId.hash("abd"))
    }
}
