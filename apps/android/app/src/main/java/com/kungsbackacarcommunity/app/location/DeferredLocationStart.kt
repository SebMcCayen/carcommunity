package com.kungsbackacarcommunity.app.location

/** A refused start may retry only while it still belongs to the caller's latest start request. */
internal class DeferredLocationStart {
    private var requestedUid: String? = null
    @Volatile var pendingUid: String? = null
        private set

    @Synchronized fun requested(uid: String) {
        requestedUid = uid
        pendingUid = null
    }

    @Synchronized fun started(uid: String) {
        if (requestedUid == uid) pendingUid = null
    }

    @Synchronized fun defer(uid: String): Boolean {
        if (requestedUid != uid) return false
        pendingUid = uid
        return true
    }

    @Synchronized fun stop() {
        requestedUid = null
        pendingUid = null
    }
}
