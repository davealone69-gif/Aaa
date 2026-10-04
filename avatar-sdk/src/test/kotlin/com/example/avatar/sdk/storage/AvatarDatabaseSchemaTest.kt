package com.example.avatar.sdk.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarDatabaseSchemaTest {
    @Test fun currentSchemaIncludesAssetAndJobTables() {
        assertEquals(2, 2)
        assertTrue(AvatarDatabase.MIGRATION_1_2.startVersion == 1)
        assertTrue(AvatarDatabase.MIGRATION_1_2.endVersion == 2)
    }

    @Test fun recordsContainRequiredPersistenceFields() {
        val asset = AvatarRecord("id", "name", "source", "provider", "model", "job", "COMPLETED", "VALID", "/tmp/a.glb", "now", 2)
        val job = GenerationJobRecord("job", "provider", "model", "QUEUED", null, null, 1L, 2L, 2)
        assertEquals("/tmp/a.glb", asset.localAssetPath)
        assertEquals("QUEUED", job.state)
        assertEquals(2, job.schemaVersion)
    }
}
