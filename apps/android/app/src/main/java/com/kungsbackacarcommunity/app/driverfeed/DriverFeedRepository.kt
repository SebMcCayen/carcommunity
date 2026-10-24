package com.kungsbackacarcommunity.app.driverfeed

import kotlinx.coroutines.flow.Flow

interface DriverFeedRepository {
    suspend fun feed(uid: String, after: DriverCursor?): DriverPage<DriverPost>
    suspend fun detail(uid: String, id: String, after: DriverCursor?): DriverDetailPage
    /** Invalidates in-memory content on account, access, block, or parent changes. */
    fun invalidations(uid: String, threadId: String?): Flow<Unit>
}
