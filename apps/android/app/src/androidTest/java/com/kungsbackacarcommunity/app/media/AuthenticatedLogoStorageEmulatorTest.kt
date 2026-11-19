package com.kungsbackacarcommunity.app.media

import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.SuccessResult
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.kungsbackacarcommunity.app.firebase.await
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Disposable named SDK app only. The Auth (19098) and Storage (19199) emulators
 * serve demo-kcc-ios-ci with the repository's real Storage rules. Admin SDK
 * fixtures assert no GCS token or raw Firebase downloadTokens before this test.
 * The emulator itself adds a token on every Firebase GET; this checks the
 * tokenless initial state and an authenticated byte request, never a URL lookup.
 * Opt in with instrumentation argument kccLogoStorage=1 after seeding the two
 * paths below and native-member@example.com / Native-local-only-12345.
 */
class AuthenticatedLogoStorageEmulatorTest {
    @Test fun authenticatedBytesLoadCompanyAndBillboardLogosFromTokenlessInitialState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kccLogoStorage") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-kcc-ios-ci")
            .setApplicationId("1:123456789012:android:0000000000000000")
            .setApiKey("AIza" + "0".repeat(35))
            .setStorageBucket("demo-kcc-ios-ci.appspot.com").build(), "logo-test-${UUID.randomUUID()}")
        val loader = ImageLoader.Builder(context).diskCache(null).memoryCache(null).build()
        try {
            val auth = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 19098) }
            val storage = FirebaseStorage.getInstance(app).apply {
                useEmulator("10.0.2.2", 19199)
                maxDownloadRetryTimeMillis = 3000
            }
            withTimeout(30000) {
                auth.signInWithEmailAndPassword("native-member@example.com", "Native-local-only-12345").await()
                val session = LogoStorageSession(auth, storage, auth.currentUser!!.uid)
                for (path in listOf("companyImages/native-partner/admin.png", "billboardImages/native-board/admin.png")) {
                    val request = requireNotNull(session.request(context, path, 128))
                    val result = loader.execute(request)
                    assertTrue("Authenticated bytes must decode without a download token: $result", result is SuccessResult)
                    assertTrue((result as SuccessResult).drawable.intrinsicWidth > 0)
                }
                auth.signOut()
                assertNull(session.request(context, "companyImages/native-partner/admin.png", 128))
            }
        } finally {
            loader.shutdown()
            app.delete()
        }
    }
}
