package com.kungsbackacarcommunity.app.driverfeed

import kotlinx.coroutines.CoroutineScope

/** In-memory navigation belongs to one mounted host and one authenticated account. */
class DriverFeedSessionOwner(private val uid: String, private val scope: CoroutineScope) {
    private var repository: DriverFeedRepository? = null
    private var coordinator: DriverFeedCoordinator? = null
    private var closed = false
    private var defaultRepository: DriverFeedRepository? = null
    private var defaultRepositoryCreated = false

    /** The route leaves composition on each tab switch, so its default adapter must live here too. */
    fun defaultRepository(create: () -> DriverFeedRepository?): DriverFeedRepository? {
        check(!closed) { "Driver host is unmounted" }
        if (!defaultRepositoryCreated) {
            defaultRepository = create()
            defaultRepositoryCreated = true
        }
        return defaultRepository
    }

    fun coordinator(accountUid: String?, source: DriverFeedRepository?): DriverFeedCoordinator {
        check(!closed) { "Driver host is unmounted" }
        require(accountUid == uid) { "Driver host belongs to another account" }
        if (coordinator == null || repository !== source) {
            coordinator?.stop()
            repository = source
            coordinator = DriverFeedCoordinator(source, uid, scope)
        }
        return checkNotNull(coordinator)
    }

    fun close() {
        closed = true
        coordinator?.stop()
        coordinator = null
        repository = null
        defaultRepository = null
        defaultRepositoryCreated = false
    }
}
