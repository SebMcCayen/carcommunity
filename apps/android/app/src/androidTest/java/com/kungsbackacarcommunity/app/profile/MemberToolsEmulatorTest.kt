package com.kungsbackacarcommunity.app.profile

import com.kungsbackacarcommunity.app.access.FirebaseCarCommunityAccessRepository
import com.kungsbackacarcommunity.app.access.CarCommunityGateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.withTimeout
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.firebase.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Named SDK apps, fake demo accounts and fixed emulator-host endpoints only. */
class MemberToolsEmulatorTest {
    @Test fun adminDecisionIsReadableOnlyByItsMemberAndRevocationDisappears() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kccNativeMembers") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = FirebaseOptions.Builder().setProjectId("demo-kcc-ios-ci").setApplicationId("1:123456789012:android:0000000000000000").setApiKey("AIza" + "0".repeat(35)).build()
        val memberApp = FirebaseApp.initializeApp(context, options, "native-member-${UUID.randomUUID()}")
        val actorApp = FirebaseApp.initializeApp(context, options, "native-actor-${UUID.randomUUID()}")
        try {
            val memberAuth = FirebaseAuth.getInstance(memberApp).apply { useEmulator("10.0.2.2", 19098) }
            val actorAuth = FirebaseAuth.getInstance(actorApp).apply { useEmulator("10.0.2.2", 19098) }
            memberAuth.signInWithEmailAndPassword("native-member@example.com", "Native-local-only-12345").await()
            actorAuth.signInWithEmailAndPassword("native-admin@example.com", "Native-local-only-12345").await()
            val own = FirebaseFunctions.getInstance(memberApp, "europe-west1").apply { useEmulator("10.0.2.2", 15001) }
            val admin = FirebaseFunctions.getInstance(actorApp, "europe-west1").apply { useEmulator("10.0.2.2", 15001) }
            val firestore = FirebaseFirestore.getInstance(memberApp).apply { useEmulator("10.0.2.2", 18088) }
            val decision = admin.getHttpsCallable("admin-warnUser").call(mapOf("targetUid" to "native-member", "reason" to "Native acceptance warning", "internalNote" to "PRIVATE-NOTE", "durationDays" to 7, "idempotencyKey" to UUID.randomUUID().toString())).await().data as Map<*, *>
            val id = decision["actionId"] as String
            val started = SystemClock.elapsedRealtime()
            val result = own.getHttpsCallable("account-listWarnings").call(emptyMap<String, Any>()).await().data as Map<*, *>
            assertEquals("native-member", result["userId"])
            val rows = result["warnings"] as List<*>
            assertEquals(1, rows.size)
            val row = rows.first() as Map<*, *>
            assertFalse(row.containsKey("internalNote"))
            val warning = AccountWarning.decode(row, started)!!
            assertEquals("Native acceptance warning", warning.reason)
            assertTrue(warning.active(SystemClock.elapsedRealtime()))
            assertTrue(firestore.collection("accountWarningSignals").document("native-member").get(Source.SERVER).await().exists())
            try {
                firestore.collection("userWarnings").document("native-member").collection("warnings").document(id).get(Source.SERVER).await()
                fail("Private warning lifecycle must be denied")
            } catch (error: FirebaseFirestoreException) { assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, error.code) }
            val adjustment = mapOf("targetUid" to "native-member", "type" to "adjustment_credit", "amount" to 17, "reason" to "Native acceptance", "idempotencyKey" to UUID.randomUUID().toString())
            admin.getHttpsCallable("points-adminAdjust").call(adjustment).await()
            admin.getHttpsCallable("points-adminAdjust").call(adjustment).await()
            assertEquals(117L, firestore.collection("pointsLedger").document("native-member").get(Source.SERVER).await().getLong("balance"))
            admin.getHttpsCallable("admin-updateDisplayName").call(mapOf("targetUid" to "native-member", "displayName" to "Native renamed", "previousDisplayName" to "native-member", "expectedActorUid" to "native-admin", "reason" to "Native acceptance", "idempotencyKey" to UUID.randomUUID().toString())).await()
            assertEquals("Native renamed", firestore.collection("users").document("native-member").get(Source.SERVER).await().getString("displayName"))
            admin.getHttpsCallable("admin-revokeWarning").call(mapOf("targetUid" to "native-member", "warningId" to id, "expectedVersion" to 1, "reason" to "Native acceptance complete", "idempotencyKey" to UUID.randomUUID().toString())).await()
            val after = own.getHttpsCallable("account-listWarnings").call(emptyMap<String, Any>()).await().data as Map<*, *>
            assertEquals(0, (after["warnings"] as List<*>).size)
            val accessRepository = FirebaseCarCommunityAccessRepository(memberAuth, own, firestore)
            suspend fun awaitAccess(restricted: Boolean) = withTimeout(30000) {
                accessRepository.updates("native-member").first { it is CarCommunityGateState.Ready && it.status.restricted == restricted }
            }
            awaitAccess(false)
            for (action in listOf("suspend", "restore")) {
                val reviewed = admin.getHttpsCallable("admin-getAccessTarget").call(mapOf("targetUid" to "native-member")).await().data as Map<*, *>
                val decisionPayload = mapOf("targetUid" to "native-member", "action" to action, "expectedActorUid" to "native-admin", "expectedVersion" to reviewed["version"], "expectedBlacklistIds" to reviewed["blacklistIds"], "reason" to "Native product-only access", "idempotencyKey" to UUID.randomUUID().toString())
                admin.getHttpsCallable("admin-updateAccess").call(decisionPayload).await()
                admin.getHttpsCallable("admin-updateAccess").call(decisionPayload).await()
                val status = (awaitAccess(action == "suspend") as CarCommunityGateState.Ready).status
                assertEquals("native-member", status.uid)
                assertFalse(status.sharedRestricted)
                assertFalse(status.blacklisted)
                memberAuth.currentUser!!.getIdToken(true).await()
                val canonical = firestore.collection("users").document("native-member").get(Source.SERVER).await()
                assertEquals(false, canonical.getBoolean("suspended"))
                assertEquals(false, canonical.getBoolean("deleted"))
            }
            val eventUpdate = async(start = CoroutineStart.UNDISPATCHED) { awaitDocument(firestore.document("events/native-moderation/messages/native-event-message")) { it.getString("moderationState") == "removed" && it.getString("message") == "" } }
            val publicUpdate = async(start = CoroutineStart.UNDISPATCHED) { awaitDocument(firestore.document("communityChat/global/messages/native-public-message")) { !it.exists() } }
            val quoteUpdate = async(start = CoroutineStart.UNDISPATCHED) { awaitDocument(firestore.document("communityChat/global/messages/native-public-reply")) { it.exists() && it.get("replyTo") == null } }
            for (source in listOf("event", "community")) {
                val target = if (source == "event") mapOf("source" to source, "eventId" to "native-moderation", "messageId" to "native-event-message") else mapOf("source" to source, "messageId" to "native-public-message")
                val evidence = admin.getHttpsCallable("admin-getPublicModerationCase").call(target).await().data as Map<*, *>
                val removal = mapOf("target" to target, "expectedActorUid" to "native-admin", "expectedVersion" to evidence["version"], "action" to "remove", "reason" to "Native public moderation", "idempotencyKey" to UUID.randomUUID().toString())
                admin.getHttpsCallable("admin-decidePublicModeration").call(removal).await()
                admin.getHttpsCallable("admin-decidePublicModeration").call(removal).await()
            }
            eventUpdate.await(); publicUpdate.await(); quoteUpdate.await()
            val removedEvent = firestore.document("events/native-moderation/messages/native-event-message").get(Source.SERVER).await()
            assertEquals("removed", removedEvent.getString("moderationState"))
            assertEquals("", removedEvent.getString("message"))
            assertFalse(firestore.document("communityChat/global/messages/native-public-message").get(Source.SERVER).await().exists())
            val preservedReply = firestore.document("communityChat/global/messages/native-public-reply").get(Source.SERVER).await()
            assertEquals("Reply preserved", preservedReply.getString("text"))
            assertNull(preservedReply.get("replyTo"))
            actorAuth.signOut(); memberAuth.signOut()
        } finally { memberApp.delete(); actorApp.delete() }
    }
    private suspend fun awaitDocument(reference: DocumentReference, predicate: (DocumentSnapshot) -> Boolean): DocumentSnapshot = withTimeout(30000) {
        callbackFlow<DocumentSnapshot> {
            val listener = reference.addSnapshotListener { snapshot, error ->
                if (error != null) close(error) else if (snapshot != null) trySend(snapshot)
            }
            awaitClose { listener.remove() }
        }.first { !it.metadata.isFromCache && predicate(it) }
    }
}
