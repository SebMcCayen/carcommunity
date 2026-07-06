package com.kungsbackacarcommunity.app.garage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarageCoordinatorTest {

    private class FakeRepo : GarageRepository {
        val added = mutableListOf<VehicleInput>()
        val updated = mutableListOf<Pair<String, VehicleInput>>()
        val deleted = mutableListOf<String>()
        var failWith: Exception? = null

        override fun observeGarage(uid: String): Flow<GarageState> = flowOf(GarageState.Loading)

        override suspend fun addVehicle(input: VehicleInput) {
            failWith?.let { throw it }
            added += input
        }

        override suspend fun updateVehicle(vehicleId: String, input: VehicleInput) {
            failWith?.let { throw it }
            updated += vehicleId to input
        }

        override suspend fun deleteVehicle(vehicleId: String) {
            failWith?.let { throw it }
            deleted += vehicleId
        }
    }

    private val input = VehicleInput("Volvo", "240", 1988, VehiclePowertrain.PETROL, null)

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

    @Test
    fun `delete calls through`() = runTest {
        val repo = FakeRepo()
        val coordinator = GarageCoordinator(repo)
        coordinator.delete("v9")
        assertEquals(listOf("v9"), repo.deleted)
    }
}
