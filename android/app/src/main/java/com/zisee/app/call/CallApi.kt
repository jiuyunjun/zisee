package com.zisee.app.call

import com.zisee.app.auth.remote.AccessSession
import com.zisee.app.auth.remote.BackendApi
import java.time.Instant
import org.json.JSONObject

data class RemoteCall(val id: String, val caller: String, val callee: String, val state: String) {
    companion object {
        fun parse(json: JSONObject) = RemoteCall(json.getString("callId"), json.getString("callerId"),
            json.getString("calleeId"), json.getString("state"))
    }
}

data class Contact(val identityId: String, val displayName: String)

/** Framework-neutral ICE configuration; the RTC layer maps it to native servers. */
data class IceServerConfig(val urls: List<String>, val username: String, val credential: String)

class CallApi(private val api: BackendApi) {
    suspend fun contacts(session: AccessSession): List<Contact> {
        val rows = api.request("GET", "v1/contacts", null, session.token).getJSONArray("contacts")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            Contact(row.getString("identityId"), row.getString("displayName")) } }
    }
    suspend fun callContact(session: AccessSession, peer: String): RemoteCall = RemoteCall.parse(
        api.request("POST", "v1/contacts/$peer/calls", JSONObject(), session.token))
    suspend fun removeContact(session: AccessSession, peer: String) {
        api.request("DELETE", "v1/contacts/$peer", null, session.token)
    }

    /** Short-lived relay credentials. The Cloudflare API token never reaches the device. */
    suspend fun iceServers(session: AccessSession): List<IceServerConfig> {
        val servers = api.request("GET", "v1/ice", null, session.token).getJSONArray("iceServers")
        val parsed = (0 until servers.length()).map { index ->
            val entry = servers.getJSONObject(index)
            val urls = entry.getJSONArray("urls")
            IceServerConfig(
                urls = (0 until urls.length()).map { urls.getString(it) }
                    .filter { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") },
                username = entry.optString("username"), credential = entry.optString("credential"))
        }.filter { it.urls.isNotEmpty() }
        require(parsed.isNotEmpty()) { "no_ice_servers" }
        return parsed
    }
    suspend fun invite(session: AccessSession): Pair<String, Instant> {
        val json = api.request("POST", "v1/invites", JSONObject(), session.token)
        return json.getString("token") to Instant.parse(json.getString("expiresAt"))
    }
    suspend fun redeem(session: AccessSession, invite: String): RemoteCall = RemoteCall.parse(
        api.request("POST", "v1/invites/redeem", JSONObject().put("token", invite), session.token))
    suspend fun action(session: AccessSession, id: String, action: String): RemoteCall = RemoteCall.parse(
        api.request("POST", "v1/calls/$id/actions", JSONObject().put("action", action), session.token))
}
