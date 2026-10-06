package com.btv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: UserInfo? = null,
    @SerialName("server_info") val serverInfo: ServerInfo? = null
)

@Serializable
data class UserInfo(
    val auth: Int = 0,
    val status: String? = null,
    val username: String? = null,
    val exp_date: String? = null,
    val max_connections: Int? = null,
    @SerialName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList()
)

@Serializable
data class ServerInfo(
    val url: String? = null,
    val port: Int? = null,
    val https_port: Int? = null
)

@Serializable
data class AuthSession(
    val serverUrl: String,
    val username: String,
    val password: String,
    val userInfo: UserInfo
)
