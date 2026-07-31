package com.kungsbackacarcommunity.app.garage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarageCoordinatorTest {

    private class FakeRepo : GarageRepository {
        val added = mutableListOf<VehicleInput>()
        val updated = mutableListOf<Pair<String, VehicleInput>>()
        val deleted = mutableListOf<String>()
        val mainSet = mutableListOf<Pair<String, Boolean>>()
        val imagePaths = mutableListOf<Pair<String, String>>()
        val addedPhotos = mutableListOf<Pair<String, String>>()
        val removedPhotos = mutableListOf<Pair<String, String>>()
        val reordered = mutableListOf<Pair<String, List<String>>>()
        var failWith: Exception? = null

        /** The id the fake garage-addVehicle mints for the next add. */
        var newVehicleId: String = "new-vehicle-1"

        override fun observeGarage(uid: String): Flow<GarageState> = flowOf(GarageState.Loading)

        override suspend fun addVehicle(input: VehicleInput): String {
            failWith?.let { throw it }
            added += input
            return newVehicleId
        }

        override suspend fun updateVehicle(vehicleId: String, input: VehicleInput) {
            failWith?.let { throw it }
            updated += vehicleId to input
        }

        override suspend fun updateVehicleImagePath(vehicleId: String, imagePath: String) {
            failWith?.let { throw it }
            imagePaths += vehicleId to imagePath
        }

        override suspend fun addVehiclePhoto(vehicleId: String, photoPath: String) {
            failWith?.let { throw it }
            addedPhotos += vehicleId to photoPath
        }

        override suspend fun removeVehiclePhoto(vehicleId: String, photoPath: String) {
            failWith?.let { throw it }
            removedPhotos += vehicleId to photoPath
        }

        override suspend fun reorderVehiclePhotos(vehicleId: String, orderedPaths: List<String>) {
            failWith?.let { throw it }
            reordered += vehicleId to orderedPaths
        }

        override suspend fun setMainVehicle(vehicleId: String, isMain: Boolean) {
            failWith?.let { throw it }
            mainSet += vehicleId to isMain
        }

        override suspend fun deleteVehicle(vehicleId: String) {
            failWith?.let { throw it }
            deleted += vehicleId
        }
    }

    private val input =
        VehicleInput(
            makeId = "volvo",
            modelId = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = null,
            modifications = null,
        )

    @Test
    fun `save with null id adds and ends Saved`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.save(input, editingVehicleId = null)
        assertEquals(listOf(input), repo.added)
        assertEquals(VehicleSaveStatus.Saved, coordinator.saveStatus.value)
    }

    @Test
    fun `save with an id updates`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.save(input, editingVehicleId = "v1")
        assertEquals(listOf("v1" to input), repo.updated)
        assertEquals(VehicleSaveStatus.Saved, coordinator.saveStatus.value)
    }

    @Test
    fun `a failed save surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("limit") }
        val coordinator = GarageCoordinator(repo)
        coordinator.save(input, null)
        assertEquals(VehicleSaveStatus.Failed, coordinator.saveStatus.value)
        coordinator.reset()
        assertEquals(VehicleSaveStatus.Idle, coordinator.saveStatus.value)
    }

    @Test
    fun `save cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("c") }
        val coordinator = GarageCoordinator(repo)
        var rethrown = false
        try {
            coordinator.save(input, null)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(VehicleSaveStatus.Idle, coordinator.saveStatus.value)
    }

    // --- save() returns the vehicle id -------------------------------------
    // The add-photo flow is built on this: vehicleImages/{uid}/{vehicleId}/
    // cannot be keyed until garage-addVehicle mints the id, so a save that
    // reports success without returning the NEW id silently costs the vehicle
    // its photo. These pin the exact id, not merely "non-null".

    @Test
    fun `an add returns the NEW id minted by the backend`() = runTest {
        val repo = FakeRepo().apply { newVehicleId = "minted-abc" }
        val coordinator = GarageCoordinator(repo)
        assertEquals("minted-abc", coordinator.save(input, editingVehicleId = null))
    }

    @Test
    fun `an update returns the id being edited`() = runTest {
        val repo = FakeRepo().apply { newVehicleId = "must-not-be-used" }
        val coordinator = GarageCoordinator(repo)
        // The edited car's own id — never the add path's minted id.
        assertEquals("v1", coordinator.save(input, editingVehicleId = "v1"))
    }

    @Test
    fun `a failed save returns null so no photo is uploaded`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("limit") }
        val coordinator = GarageCoordinator(repo)
        assertNull(coordinator.save(input, editingVehicleId = null))
    }

    @Test
    fun `a re-entrant save returns null instead of a bogus id`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        // Drive the guard directly: a second save while one is Saving is a
        // no-op, and must NOT report an id the caller would attach a photo to.
        val gate = CompletableDeferred<Unit>()
        val slowRepo =
            object : GarageRepository by repo {
                override suspend fun addVehicle(input: VehicleInput): String {
                    gate.await()
                    return "first"
                }
            }
        val slow = GarageCoordinator(slowRepo)
        val first = async { slow.save(input, editingVehicleId = null) }
        // Wait until the first save has actually flipped the status to Saving.
        while (slow.saveStatus.value != VehicleSaveStatus.Saving) yield()
        assertNull(slow.save(input, editingVehicleId = null))
        gate.complete(Unit)
        assertEquals("first", first.await())
    }

    @Test
    fun `delete calls through`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.delete("v9")
        assertEquals(listOf("v9"), repo.deleted)
    }

    @Test
    fun `setMain calls through with the flag`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.setMain("v3", isMain = true)
        coordinator.setMain("v3", isMain = false)
        assertEquals(listOf("v3" to true, "v3" to false), repo.mainSet)
    }

    @Test
    fun `addPhoto, removePhoto and reorderPhotos call through`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.addPhoto("v1", "vehicleImages/u/v1/a.jpg")
        coordinator.removePhoto("v1", "vehicleImages/u/v1/a.jpg")
        coordinator.reorderPhotos("v1", listOf("vehicleImages/u/v1/b.jpg", "vehicleImages/u/v1/a.jpg"))
        assertEquals(listOf("v1" to "vehicleImages/u/v1/a.jpg"), repo.addedPhotos)
        assertEquals(listOf("v1" to "vehicleImages/u/v1/a.jpg"), repo.removedPhotos)
        assertEquals(
            listOf("v1" to listOf("vehicleImages/u/v1/b.jpg", "vehicleImages/u/v1/a.jpg")),
            repo.reordered,
        )
    }
}
