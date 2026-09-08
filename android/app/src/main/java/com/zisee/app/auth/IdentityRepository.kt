package com.zisee.app.auth

import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    /** A read error must reach the caller; it must never be interpreted as a new installation. */
    val identity: Flow<LocalIdentity?>
    suspend fun create(displayName: String)
    suspend fun rename(displayName: String)
}
