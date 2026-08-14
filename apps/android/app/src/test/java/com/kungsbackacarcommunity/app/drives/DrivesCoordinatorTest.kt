package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the delete-from-History path (#853). Since a finished live session's drive
 * is now auto-saved AND auto-kept with no Keep/Delete prompt, removing an unwanted
 * drive happens from the History list / detail instead — routed through this
 * coordinator's [DrivesCoordinator.delete], which forwards to the `drives-delete`
 * callable and tracks a status so the detail view can close on success or surface
 * an error.
 */
class DrivesCoordinatorTest {

    private class FakeRepository(private val failWith: Exception? = null) : DrivesRepository {
        val deletedRideIds = mutableListOf<String>()

        override fun observeDrives(uid: String) = throw UnsupportedOperationException()

        override suspend fun saveDrive(request: Map<String, Any?>): DriveSaveResult =
            throw UnsupportedOperationException()

        override suspend fun deleteDrive(rideId: String) {
            failWith?.let { throw it }
            deletedRideIds.add(rideId)
        }
    }

    @Test
    fun `delete removes the drive from History and lands in Deleted`() = runTest {
        val repo = FakeRepository()
        val coordinator = DrivesCoordinator(repo)

        coordinator.delete("ride-1")

        assertEquals(listOf("ride-1"), repo.deletedRideIds)
        assertEquals(DriveDeleteStatus.Deleted, coordinator.deleteStatus.value)
    }

    @Test
    fun `a delete failure surfaces Failed without removing anything`() = runTest {
        val repo = FakeRepository(failWith = IllegalStateException("boom"))
        val coordinator = DrivesCoordinator(repo)

        coordinator.delete("ride-1")

        assertEquals(emptyList<String>(), repo.deletedRideIds)
        assertEquals(DriveDeleteStatus.Failed, coordinator.deleteStatus.value)
    }
}
