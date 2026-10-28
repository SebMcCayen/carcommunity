package com.kungsbackacarcommunity.app.profile

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
            actorAuth.signOut(); memberAuth.signOut()
        } finally { memberApp.delete(); actorApp.delete() }
    }
}
