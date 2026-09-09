package com.lazydoglab.zisee.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreIdentityRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun concurrentCreationRenameAndReloadKeepIdentity() = runBlocking {
        val file = folder.root.resolve("identity.preferences_pb")
        var job = SupervisorJob()
        var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            var repository = DataStoreIdentityRepository(store)
            assertNull(repository.identity.first())
            coroutineScope { repeat(20) { launch { repository.create("九云") } } }
            val original = repository.identity.first()!!
            repository.rename("Jiu")
            val renamed = repository.identity.first()!!
            assertEquals(original.identityId, renamed.identityId)
            assertEquals(original.createdAt, renamed.createdAt)
            assertEquals("Jiu", renamed.displayName)
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            repository = DataStoreIdentityRepository(store)
            assertEquals(renamed, repository.identity.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun incompleteIdentityIsNotSilentlyReplaced() {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            folder.root.resolve("corrupt.preferences_pb")
        }
        try {
            runBlocking { store.edit { it[stringPreferencesKey("display_name")] = "Existing" } }
            val repository = DataStoreIdentityRepository(store)
            assertThrows(IOException::class.java) { runBlocking { repository.identity.first() } }
            assertThrows(IOException::class.java) { runBlocking { repository.create("Replacement") } }
            assertEquals("Existing", runBlocking { store.data.first()[stringPreferencesKey("display_name")] })
        } finally {
            runBlocking { job.cancelAndJoin() }
        }
    }
}
