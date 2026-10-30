package com.kungsbackacarcommunity.app.clubs

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.kungsbackacarcommunity.app.blocking.FirebaseBlockVisibilityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Private views require fresh server membership. SDK offline documents are never an access grant. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseClubRepository private constructor(
    private val uid: String, private val db: FirebaseFirestore,
    private val functions: FirebaseFunctions, private val storage: FirebaseStorage,
    private val auth: FirebaseAuth, private val context: Context,
) : ClubRepository {
    private data class ServerDocument(val confirmed: Boolean, val snapshot: DocumentSnapshot?)
    private fun document(path: String): Flow<ServerDocument> = callbackFlow {
        val registration = db.document(path).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            val confirmed = error == null && snapshot != null && !snapshot.metadata.isFromCache &&
                !snapshot.metadata.hasPendingWrites() && auth.currentUser?.uid == uid
            trySend(ServerDocument(confirmed, snapshot.takeIf { confirmed }))
        }
        awaitClose { registration.remove() }
    }
    override fun observeEnabled(): Flow<Boolean> = document("config/sharedClubs")
        .map { it.confirmed && it.snapshot?.getBoolean("enabled") == true && it.snapshot?.getTimestamp("activatedAt") != null }
        .distinctUntilChanged()

    override fun observePublicAttendanceReady(): Flow<Boolean> = document("config/sharedClubs")
        .map { it.confirmed && it.snapshot?.getBoolean("enabled") == true && it.snapshot?.getTimestamp("activatedAt") != null && it.snapshot?.getBoolean("publicAttendanceReady") == true }
        .distinctUntilChanged()
    override fun observeInvitationsRevision(): Flow<Long?> = document("driverClubAccounts/$uid")
        .map { if (it.confirmed) it.snapshot?.getLong("invitationsRevision") ?: 0L else null }
        .distinctUntilChanged()

    override fun observeAccess(): Flow<ClubAccess> = combine(observeEnabled(), document("driverClubAccounts/$uid")) { enabled, account ->
        if (enabled) account else ServerDocument(false, null)
    }.flatMapLatest { account ->
        if (!account.confirmed) return@flatMapLatest flowOf(ClubAccess.Unverified)
        val clubId = account.snapshot?.getString("activeClubId")?.takeIf { it.isNotBlank() }
            ?: return@flatMapLatest flowOf(ClubAccess.None)
        combine(document("driverClubs/$clubId"), document("driverClubs/$clubId/members/$uid")) { club, member ->
            if (!club.confirmed || !member.confirmed) ClubAccess.Unverified
            else if (club.snapshot?.getString("status") != "active" || member.snapshot?.exists() != true) ClubAccess.None
            else {
                val role = ClubRole.parse(member.snapshot.getString("role"))
                val joined = member.snapshot.getTimestamp("joinedAt")?.toDate()?.toInstant()?.toString()
                if (role == null || joined == null) ClubAccess.Unverified
                else ClubAccess.Member(ClubMembership(clubId, role, joined))
            }
        }
    }.distinctUntilChanged()

    private suspend fun call(name: String, fields: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
        try {
            val result = functions.getHttpsCallable("driver-$name").call(fields).clubAwait().data.clubMap()
            if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: FirebaseFunctionsException) {
            throw clubCallableFailure(error.code.name, error.details, auth.currentUser?.uid == uid)
        }
    }
    override suspend fun directory(cursor: String?): ClubPage<Club> {
        val data = call("clubsList", mapOf("limit" to 25, "cursor" to cursor).filterValues { it != null })
        return ClubPage(data.items("items").mapNotNull(::parseClub), data.nullableText("nextCursor"))
    }
    override suspend fun state() = parseClubState(call("clubsState"))
    override suspend fun get(clubId: String): Club = parseClub(call("clubsGet", mapOf("clubId" to clubId))["club"].clubMap())
        ?: throw ClubException("unavailable")
    override suspend fun members(clubId: String, cursor: String?): ClubPage<ClubMember> {
        val data = call("clubsMembers", mapOf("clubId" to clubId, "cursor" to cursor, "limit" to 25).filterValues { it != null })
        return ClubPage(data.items("items").mapNotNull { member ->
            val role = ClubRole.parse(member["role"]) ?: return@mapNotNull null
            member.text("uid").takeIf { it.isNotBlank() }?.let {
                ClubMember(it, member.text("displayName"), role, safeClubImage(member.nullableText("avatarUrl")))
            }
        }, data.nullableText("nextCursor"))
    }
    override suspend fun manage(action: String, fields: Map<String, Any?>, operationId: String) =
        call("clubsManage", fields + mapOf("action" to action, "operationId" to operationId))
    override suspend fun uploadLogo(clubId: String, bytes: ByteArray): String {
        if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
        if (bytes.isEmpty() || bytes.size > CLUB_LOGO_UPLOAD_BYTES) throw ClubException("invalid-logo")
        val path = "clubLogos/$clubId/$uid/${UUID.randomUUID()}.jpg"
        try {
            storage.reference.child(path).putBytes(bytes, StorageMetadata.Builder().setContentType("image/jpeg").build()).clubAwait()
        } catch (e: CancellationException) { throw e }
        catch (e: StorageException) {
            throw ClubException(if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED ||
                e.errorCode == StorageException.ERROR_NOT_AUTHENTICATED) "logo-permission-denied" else "logo-upload-failed")
        }
        if (auth.currentUser?.uid != uid) throw ClubException("unauthenticated")
        return path
    }
    override fun chat(): Flow<ClubChatState> = observeAccess().flatMapLatest { access ->
        if (access !is ClubAccess.Member) return@flatMapLatest flowOf(ClubChatState.Unavailable)
        val membership = access.membership
        combine(messages(membership.clubId), FirebaseBlockVisibilityRepository.createOrEmpty(context).observeHiddenUids()) { result, hidden ->
            if (result == null) ClubChatState.Unavailable
            else ClubChatState.Ready(membership, result.rows, hidden, result.cursor, result.hasMore)
        }
    }
    private data class MessageSnapshot(val rows: List<ClubMessage>, val cursor: ClubMessageCursor?, val hasMore: Boolean)
    private fun cursor(doc: DocumentSnapshot?): ClubMessageCursor? = doc?.getTimestamp("createdAt")?.let {
        ClubMessageCursor(doc.id, it.seconds, it.nanoseconds)
    }
    private fun messages(clubId: String): Flow<MessageSnapshot?> = callbackFlow {
        val registration = messagesQuery(clubId).limit(25).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null || snapshot == null || snapshot.metadata.isFromCache || auth.currentUser?.uid != uid) trySend(null)
            else trySend(MessageSnapshot(snapshot.documents.mapNotNull(::message), cursor(snapshot.documents.lastOrNull()), snapshot.size() == 25))
        }
        awaitClose { registration.remove() }
    }
    private fun messagesQuery(clubId: String) = db.collection("driverClubs").document(clubId).collection("messages")
        .orderBy("createdAt", Query.Direction.DESCENDING).orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
    override suspend fun olderMessages(clubId: String, before: ClubMessageCursor): ClubMessagePage {
        // Preserve raw document cursor and exhaustion separately from redacted/blocked rows.
        val result = messagesQuery(clubId).startAfter(Timestamp(before.seconds, before.nanoseconds), before.id)
            .limit(25).get(Source.SERVER).clubAwait()
        val account = db.document("driverClubAccounts/$uid").get(Source.SERVER).clubAwait()
        val member = db.document("driverClubs/$clubId/members/$uid").get(Source.SERVER).clubAwait()
        val club = db.document("driverClubs/$clubId").get(Source.SERVER).clubAwait()
        if (auth.currentUser?.uid != uid || !member.exists() || account.getString("activeClubId") != clubId ||
            club.getString("status") != "active") throw ClubException("permission-denied")
        return ClubMessagePage(result.documents.map { it.id }, cursor(result.documents.lastOrNull()), result.size() == 25)
    }
    override fun observeMessageRows(clubId: String, messageIds: List<String>): Flow<List<ClubMessage>> {
        require(messageIds.size <= 25)
        if (messageIds.isEmpty()) return flowOf(emptyList())
        return observeAccess().flatMapLatest { access ->
            if (access !is ClubAccess.Member || access.membership.clubId != clubId) flowOf(emptyList())
            else messageRows(clubId, messageIds)
        }
    }
    private fun messageRows(clubId: String, ids: List<String>): Flow<List<ClubMessage>> = callbackFlow {
        val registration = db.collection("driverClubs").document(clubId).collection("messages")
            .whereIn(FieldPath.documentId(), ids).limit(25)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null || snapshot == null || snapshot.metadata.isFromCache || auth.currentUser?.uid != uid) trySend(emptyList())
                else trySend(snapshot.documents.mapNotNull(::message))
            }
        awaitClose { registration.remove() }
    }
    override suspend fun send(clubId: String, text: String, operationId: String) {
        if (text.isBlank() || text.codePointCount(0, text.length) > 2000) throw ClubException("invalid-argument")
        call("clubsSendMessage", mapOf("clubId" to clubId, "text" to text, "operationId" to operationId))
    }
    override suspend fun events(clubId: String, cursor: String?): ClubPage<ClubEvent> {
        val data = call("clubsEvents", mapOf("clubId" to clubId, "cursor" to cursor, "limit" to 25).filterValues { it != null })
        return ClubPage(data.items("items").mapNotNull { event ->
            val id = event.text("eventId"); val start = (event["startMillis"] as? Number)?.toLong()
            if (id.isBlank() || start == null) null else ClubEvent(id, event.text("title"), start,
                (event["endMillis"] as? Number)?.toLong(), event.nullableText("locationName"))
        }, data.nullableText("nextCursor"))
    }
    override fun eventParticipants(eventId: String): Flow<List<Club>> = observePublicAttendanceReady().flatMapLatest { ready ->
        if (ready) participantSnapshots(eventId) else flowOf(emptyList())
    }
    private fun participantSnapshots(eventId: String): Flow<List<Club>> = callbackFlow {
        val registration = db.collection("events").document(eventId).collection("clubs").orderBy(FieldPath.documentId()).limit(25)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null || snapshot == null || snapshot.metadata.isFromCache || auth.currentUser?.uid != uid) trySend(emptyList())
                else trySend(snapshot?.documents?.mapNotNull { doc ->
                    parseClub(doc.data.orEmpty() + mapOf("id" to (doc.getString("clubId") ?: doc.id),
                        "name" to (doc.getString("clubName") ?: doc.getString("name"))))
                }.orEmpty())
            }
        awaitClose { registration.remove() }
    }
    override fun observeEventParticipants(eventId: String, clubIds: List<String>): Flow<List<Club>> {
        require(clubIds.size <= 25)
        if (clubIds.isEmpty()) return flowOf(emptyList())
        return observePublicAttendanceReady().flatMapLatest { ready ->
            if (ready) participantRows(eventId, clubIds) else flowOf(emptyList())
        }
    }
    private fun participantRows(eventId: String, ids: List<String>): Flow<List<Club>> = callbackFlow {
        val registration = db.collection("events").document(eventId).collection("clubs")
            .whereIn(FieldPath.documentId(), ids).limit(25)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null || snapshot == null || snapshot.metadata.isFromCache || auth.currentUser?.uid != uid) trySend(emptyList())
                else trySend(snapshot.documents.mapNotNull { doc ->
                    parseClub(doc.data.orEmpty() + mapOf("id" to doc.id, "name" to (doc.getString("name") ?: doc.getString("clubName"))))
                })
            }
        awaitClose { registration.remove() }
    }
    override fun eventAttendance(eventId: String, clubId: String): Flow<Boolean?> = managedClubEventAttendance(
        eventId, clubId, observePublicAttendanceReady(), observeAccess(),
    ) { path ->
        callbackFlow {
            val registration = db.document(path).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                when {
                    error != null -> close(if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        ClubException("permission-denied") else error)
                    auth.currentUser?.uid != uid -> close(ClubException("unauthenticated"))
                    snapshot == null || snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites() -> trySend(null)
                    else -> trySend(snapshot.exists())
                }
            }
            awaitClose { registration.remove() }
        }
    }
    override suspend fun moreEventParticipants(eventId: String, afterId: String): List<Club> {
        requirePublicAttendance()
        val result = db.collection("events").document(eventId).collection("clubs").orderBy(FieldPath.documentId())
            .startAfter(afterId).limit(25).get(Source.SERVER).clubAwait().documents.mapNotNull { doc ->
                parseClub(doc.data.orEmpty() + mapOf("id" to doc.id, "name" to (doc.getString("clubName") ?: doc.getString("name"))))
            }
        requirePublicAttendance()
        return result
    }
    private suspend fun requirePublicAttendance() {
        val config = db.document("config/sharedClubs").get(Source.SERVER).clubAwait()
        if (auth.currentUser?.uid != uid || config.getBoolean("enabled") != true || config.getTimestamp("activatedAt") == null || config.getBoolean("publicAttendanceReady") != true)
            throw ClubException("permission-denied")
    }
    override suspend fun setEventAttendance(clubId: String, eventId: String, attending: Boolean, operationId: String) {
        call("clubsEventAttendance", mapOf("clubId" to clubId, "eventId" to eventId, "attending" to attending, "operationId" to operationId))
    }
    override suspend fun blockAuthor(uid: String) {
        functions.getHttpsCallable("blocking-block").call(mapOf("targetUserId" to uid)).clubAwait()
    }
    private fun message(doc: DocumentSnapshot): ClubMessage? {
        if (doc.getString("status") in setOf("hidden", "removed")) return null
        val author = doc.getString("authorUid") ?: return null
        val text = doc.getString("text")?.takeIf { it.isNotBlank() } ?: return null
        return ClubMessage(doc.id, author, doc.getString("authorName"), text, doc.getTimestamp("createdAt")?.toDate()?.time)
    }
    companion object {
        fun createIfAvailable(context: Context, uid: String): ClubRepository? {
            if (FirebaseApp.getApps(context).isEmpty() || uid.isBlank()) return null
            return FirebaseClubRepository(uid, FirebaseFirestore.getInstance(), FirebaseFunctions.getInstance("europe-west1"),
                FirebaseStorage.getInstance(), FirebaseAuth.getInstance(), context.applicationContext)
        }
    }
}

internal suspend fun <T> Task<T>.clubAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: ClubException("unavailable"))
    }
}
