package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class NetBirdConfigBlobTest {

    @Test
    fun `encode-decode round-trips all fields`() {
        val blob = NetBirdConfigBlob(
            managementUrl = "https://netbird.example.com",
            setupKey = "NB-SETUP-KEY-12345",
            hostname = "my-device",
        )
        val parsed = NetBirdConfigBlob.parse(blob.encode())
        assertEquals("https://netbird.example.com", parsed.managementUrl)
        assertEquals("NB-SETUP-KEY-12345", parsed.setupKey)
        assertEquals("my-device", parsed.hostname)
    }

    @Test
    fun `encode-decode round-trips with empty hostname omitted from JSON`() {
        val blob = NetBirdConfigBlob(
            managementUrl = "https://netbird.example.com",
            setupKey = "NB-SETUP-KEY-12345",
        )
        val encoded = blob.encode()
        val text = String(encoded, Charsets.UTF_8)
        assert(!text.contains("hostname")) {
            "hostname should not appear when empty, got: $text"
        }
        val parsed = NetBirdConfigBlob.parse(encoded)
        assertEquals("https://netbird.example.com", parsed.managementUrl)
        assertEquals("NB-SETUP-KEY-12345", parsed.setupKey)
        assertEquals("", parsed.hostname)
    }

    @Test
    fun `parse with missing optional hostname field returns empty string`() {
        val json = """{"managementUrl":"https://netbird.example.com","setupKey":"NB-KEY-123"}"""
        val parsed = NetBirdConfigBlob.parse(json.toByteArray(Charsets.UTF_8))
        assertEquals("https://netbird.example.com", parsed.managementUrl)
        assertEquals("NB-KEY-123", parsed.setupKey)
        assertEquals("", parsed.hostname)
    }

    @Test(expected = org.json.JSONException::class)
    fun `parse throws on missing managementUrl`() {
        val json = """{"setupKey":"NB-KEY-123"}"""
        NetBirdConfigBlob.parse(json.toByteArray(Charsets.UTF_8))
    }

    @Test(expected = org.json.JSONException::class)
    fun `parse throws on missing setupKey`() {
        val json = """{"managementUrl":"https://netbird.example.com"}"""
        NetBirdConfigBlob.parse(json.toByteArray(Charsets.UTF_8))
    }
}
