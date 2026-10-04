package com.example.avatar.sdk.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPersistenceContractTest {
    @Test fun migrationAddsStudioSchema() {
        assertEquals(2, AvatarDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AvatarDatabase.MIGRATION_2_3.endVersion)
    }

    @Test fun recordsKeepStableRelationshipsAndMetadata() {
        val project = ProjectRecord("p", "Project", "desc", "{}", 1L, 2L)
        val scene = SceneRecord("s", project.projectId, "Scene", "{}", 1L, 2L)
        val reference = ReferenceRecord("r", project.projectId, "main.py", "text/x-python", "CODE", 12L, "abc", 3L, null, "", "/controlled/r-main.py", "TEXT_READABLE", 2)
        assertEquals(project.projectId, scene.projectId)
        assertEquals(project.projectId, reference.projectId)
        assertEquals("CODE", reference.category)
        assertTrue(reference.storagePath.startsWith("/controlled/"))
    }
}
