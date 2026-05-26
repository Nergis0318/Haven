package sh.haven.core.tunnel

import org.json.JSONObject

data class NetBirdConfigBlob(
    val managementUrl: String,
    val setupKey: String,
    val hostname: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("managementUrl", managementUrl)
            put("setupKey", setupKey)
            if (hostname.isNotBlank()) put("hostname", hostname)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun parse(bytes: ByteArray): NetBirdConfigBlob {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return NetBirdConfigBlob(
                managementUrl = json.getString("managementUrl"),
                setupKey = json.getString("setupKey"),
                hostname = json.optString("hostname", "").trim(),
            )
        }
    }
}
