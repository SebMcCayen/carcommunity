package com.kungsbackacarcommunity.app.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.callChannel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ReactionRoom(val scope: String, val roomId: String)
data class ReactionChoice(val emojiId: String?, val version: Long, val fetchedAt: Long = System.currentTimeMillis())
data class ReactionEmoji(val id: String, val glyph: String, val name: String, val keywords: String, val category: String)

/** Counts carry no identities. Invalid data is never interpreted as a clickable emoji. */
fun parseReactionCounts(raw: Any?): Map<String, Long> = (raw as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
    val id = key as? String ?: return@mapNotNull null
    val count = value as? Number ?: return@mapNotNull null
    if (!Regex("[a-f0-9-]{1,100}").matches(id) || count.toDouble() != count.toLong().toDouble() || count.toLong() <= 0 || count.toLong() > 9_007_199_254_740_991L) null
    else id to count.toLong()
}?.toMap().orEmpty()

/** Pure deterministic ranking; no user, message, or history data is sent to the server. */
fun rankReactionEmojis(usage: Map<String, Pair<Long, Long>>): List<String> = usage.keys.sortedWith(
    compareByDescending<String> { usage[it]?.first ?: 0 }.thenByDescending { usage[it]?.second ?: 0 }.thenBy { it },
)

class ReactionLocalStore(context: Context, uid: String) {
    companion object {
        private val stores = mutableMapOf<String, ReactionLocalStore>()
        fun forUser(context: Context, uid: String): ReactionLocalStore = stores.getOrPut(uid) { ReactionLocalStore(context, uid) }
    }
    private var loaded = false
    private val saveMutex = kotlinx.coroutines.sync.Mutex()
    private val diskLock = Any()
    @Volatile private var generation = 0L
    private val file = File(context.noBackupFilesDir, "emoji-history-" + MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    private var data = JSONObject()
    val usage = mutableStateMapOf<String, Pair<Long, Long>>()
    suspend fun load() {
        if (loaded) return
        loaded = true
        val revision = generation
        val loaded = withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } }
        if (revision != generation) return
        data = loaded
        loaded.optJSONObject("usage")?.let { rows -> rows.keys().forEach { id ->
            val row = rows.optJSONArray(id) ?: return@forEach
            usage[id] = row.optLong(0).coerceAtLeast(0) to row.optLong(1)
        } }
    }
    fun own(room: ReactionRoom, id: String): ReactionChoice? {
        val row = data.optJSONObject("own")?.optJSONObject("${room.scope}:${room.roomId}:$id") ?: return null
        val time = row.optLong("at")
        if (System.currentTimeMillis() - time !in 0..60_000) return null
        return ReactionChoice(row.optString("emoji").takeIf { it.isNotEmpty() }, row.optLong("version"), time)
    }
    fun cache(room: ReactionRoom, id: String, choice: ReactionChoice) {
        val own = data.optJSONObject("own") ?: JSONObject().also { data.put("own", it) }
        own.put("${room.scope}:${room.roomId}:$id", JSONObject().put("emoji", choice.emojiId ?: "").put("version", choice.version).put("at", choice.fetchedAt))
        while (own.length() > 500) own.remove(own.keys().asSequence().minByOrNull { own.optJSONObject(it)?.optLong("at") ?: 0 } ?: break)
    }
    fun record(emojiId: String) {
        val old = usage[emojiId]?.first ?: 0
        val now = System.currentTimeMillis()
        usage[emojiId] = (old + 1).coerceAtMost(1_000_000_000) to now
        val rows = data.optJSONObject("usage") ?: JSONObject().also { data.put("usage", it) }
        rows.put(emojiId, JSONArray().put(usage[emojiId]!!.first).put(now))
    }
    fun forget(room: ReactionRoom) {
        val own = data.optJSONObject("own") ?: return
        own.keys().asSequence().filter { it.startsWith("${room.scope}:${room.roomId}:") }.toList().forEach(own::remove)
    }
    suspend fun save() {
        saveMutex.lock()
        try {
            val text = data.toString()
            val revision = generation
            withContext(Dispatchers.IO) { synchronized(diskLock) {
                if (revision == generation) runCatching { val temp = File(file.path + ".tmp"); temp.writeText(text); if (!temp.renameTo(file)) temp.delete() }
            } }
        } finally { saveMutex.unlock() }
    }

    fun clear() {
        usage.clear(); data = JSONObject()
        synchronized(diskLock) { generation++; file.delete(); File(file.path + ".tmp").delete() }
    }
}

class ReactionRoomState(val room: ReactionRoom, val uid: String, private val context: Context, private val scope: CoroutineScope, private val sessionIsCurrent: (() -> Boolean)? = null, private val remoteCall: (suspend (String, Map<String, Any?>) -> Map<String, Any?>?)? = null) {
    var dismissActions: (() -> Unit)? = null
    var foreground by mutableStateOf(true)
    var enabled by mutableStateOf(false)
    var selectedId by mutableStateOf<String?>(null)
    var moreActions by mutableStateOf(false)
    var picker by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var catalog by mutableStateOf(emptyList<ReactionEmoji>())
    val own = mutableStateMapOf<String, ReactionChoice>()
    val bounds = mutableStateMapOf<String, Rect>()
    val actions = mutableMapOf<String, (() -> Unit)?>()
    val visible = mutableStateMapOf<String, Map<String, Long>>()
    val store = ReactionLocalStore.forUser(context, uid)
    private var refresh: Job? = null
    private val attempts = mutableMapOf<String, Long>()
    private var retry: Pair<Map<String, Any?>, String>? = null
    private val recordedOperations = mutableSetOf<String>()
    private fun current() = sessionIsCurrent?.invoke() ?: (FirebaseApp.getApps(context).isNotEmpty() && FirebaseAuth.getInstance().currentUser?.uid == uid)
    private suspend fun call(name: String, payload: Map<String, Any?>): Map<String, Any?>? = if (remoteCall != null) remoteCall.invoke(name, payload) else functions.callChannel(name, payload).getOrThrow()
    private val functions get() = FirebaseFunctions.getInstance("europe-west1")
    private val setName get() = if (room.scope == "club") "driver-clubsSetMessageReaction" else "chatReactions-set"
    private val mineName get() = if (room.scope == "club") "driver-clubsGetMyMessageReactions" else "chatReactions-getMine"
    suspend fun start() {
        store.load()
        catalog = withContext(Dispatchers.IO) {
            val rows = JSONObject(context.assets.open("emoji-catalog.json").bufferedReader().use { it.readText() }).getJSONArray("entries")
            (0 until rows.length()).map { index -> val r = rows.getJSONArray(index); ReactionEmoji(r.getString(0), r.getString(1), if (java.util.Locale.getDefault().language == "sv") r.getString(2) else r.optString(5, r.getString(2)), r.getString(3), r.getString(4)) }
        }
    }
    fun select(id: String) { if (enabled && id in visible) { selectedId = id; moreActions = false; picker = false; failed = false; request() } }
    fun dismiss() { val close = dismissActions; dismissActions = null; selectedId = null; picker = false; moreActions = false; failed = false; close?.invoke() }
    fun register(id: String, counts: Map<String, Long>) {
        val previous = visible[id]
        val changed = previous != counts
        if (previous != null && changed) { own.remove(id); attempts.remove(id); store.forget(room) }
        visible[id] = counts
        if (id !in own) store.own(room, id)?.let { own[id] = it }
        if (changed || id !in own) request()
    }
    fun unregister(id: String) { visible.remove(id); own.remove(id); attempts.remove(id); bounds.remove(id); actions.remove(id); if (selectedId == id) dismiss() }
    fun reload() { selectedId?.let { attempts.remove(it); own.remove(it) }; failed = false; request() }
    fun request() {
        refresh?.cancel()
        refresh = scope.launch {
            delay(100)
            if (!enabled || !foreground || !current() || busy) return@launch
            val now = System.currentTimeMillis()
            val ids = visible.keys.filter { now - (attempts[it] ?: 0) > 10_000 && (own[it] == null || now - own[it]!!.fetchedAt > 60_000) }.take(50)
            if (ids.isEmpty()) return@launch
            ids.forEach { attempts[it] = now }
            try {
                val result = call(mineName, mapOf("scope" to room.scope, "roomId" to room.roomId, "messageIds" to ids))
                if (!current()) return@launch
                (result?.get("items") as? List<*>)?.forEach { raw ->
                    val row = raw as? Map<*, *> ?: return@forEach
                    val id = row["messageId"] as? String ?: return@forEach
                    if (id !in ids || id !in visible) return@forEach
                    val choice = ReactionChoice(row["emojiId"] as? String, (row["version"] as? Number)?.toLong() ?: return@forEach)
                    own[id] = choice; store.cache(room, id, choice)
                }
                store.save()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { own.clear(); store.forget(room); failed = selectedId != null }
        }
    }
    fun choose(emojiId: String?) {
        val id = selectedId ?: return
        val before = own[id] ?: run { failed = true; request(); return }
        if (busy || !current() || id !in visible) return
        val desired = emojiId?.takeUnless { it == before.emojiId }
        if (desired == before.emojiId) { dismiss(); return }
        val existing = retry?.takeIf { it.first["messageId"] == id && it.first["emojiId"] == desired }
        val operation = existing?.second ?: UUID.randomUUID().toString()
        val payload = existing?.first ?: mapOf("scope" to room.scope, "roomId" to room.roomId, "messageId" to id, "emojiId" to desired, "expectedVersion" to before.version, "operationId" to operation)
        retry = payload to operation
        busy = true; failed = false
        scope.launch {
            try {
                val result = call(setName, payload) ?: error("Missing response")
                if (!current() || id !in visible) return@launch
                val choice = ReactionChoice(result["emojiId"] as? String, (result["version"] as? Number)?.toLong() ?: error("Missing version"))
                // Acknowledge the authoritative aggregate before the message listener re-registers it.
                visible[id] = parseReactionCounts(result["counts"])
                own[id] = choice; store.cache(room, id, choice)
                if (desired != null && choice.emojiId == desired && recordedOperations.add(operation)) store.record(desired)
                if (recordedOperations.size > 100) recordedOperations.remove(recordedOperations.first())
                retry = null; store.save(); dismiss()
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                if ((error as? com.google.firebase.functions.FirebaseFunctionsException)?.code in listOf(com.google.firebase.functions.FirebaseFunctionsException.Code.ABORTED, com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED, com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND, com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION)) retry = null
                // The same operation is retained for an explicit retry after an unknown transport result.
                failed = true; own.remove(id); attempts.remove(id); request()
            } finally { busy = false; if (failed) request() }
        }
    }
    fun invalidate() { enabled = false; dismiss(); own.clear(); store.forget(room); refresh?.cancel() }
}

val LocalReactionRoom = staticCompositionLocalOf<ReactionRoomState?> { null }
val LocalReactionRoomFactory = staticCompositionLocalOf<((ReactionRoom, String, Context, CoroutineScope) -> ReactionRoomState)?> { null }

@Composable
fun ReactionRoomHost(room: ReactionRoom, uid: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val factory = LocalReactionRoomFactory.current
    val state = remember(room, uid, factory) { factory?.invoke(room, uid, context.applicationContext, scope) ?: ReactionRoomState(room, uid, context.applicationContext, scope) }
    LaunchedEffect(state) { state.start() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        state.foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            state.foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (state.foreground) state.request() else state.dismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(state) {
        if (factory != null || FirebaseApp.getApps(context).isEmpty()) onDispose { state.invalidate() }
        else {
            val auth = FirebaseAuth.getInstance()
            // Firestore coalesces this document target with the app's existing flags listener.
            val listener = FirebaseFirestore.getInstance().document("config/featureFlags").addSnapshotListener { value, error ->
                state.enabled = error == null && value?.getBoolean("chatReactions") == true && auth.currentUser?.uid == uid
                if (!state.enabled) state.dismiss() else state.request()
            }
            val account = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) { state.invalidate(); state.store.clear() } }
            auth.addAuthStateListener(account)
            onDispose { listener.remove(); auth.removeAuthStateListener(account); state.dismiss() }
        }
    }
    CompositionLocalProvider(LocalReactionRoom provides state, content = content)
}

@Composable
fun reactionAnchor(id: String, onActions: (() -> Unit)? = null): Modifier {
    val room = LocalReactionRoom.current
    if (room?.enabled != true) return Modifier
    return Modifier.onGloballyPositioned { room?.bounds?.set(id, it.boundsInWindow()); room?.actions?.set(id, onActions) }
}

/** Intercepts only an enabled reaction gesture; callers retain existing actions and confirmations. */
fun openReactionActions(room: ReactionRoomState?, id: String, existing: (() -> Unit)?) {
    if (room?.enabled == true) { if (id !in room.visible) return; room.select(id) }
    existing?.invoke()
}

@Composable
fun MessageReactionChips(id: String, counts: Map<String, Long>) {
    val room = LocalReactionRoom.current ?: return
    LaunchedEffect(room, id, counts, room.enabled) { if (room.enabled) room.register(id, counts) }
    DisposableEffect(room, id) { onDispose { room.unregister(id) } }
    if (!room.enabled) return
    val choices = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.take(3).forEach { (key, count) ->
            val emoji = room.catalog.firstOrNull { it.id == key } ?: return@forEach
            val selected = room.own[id]?.emojiId == key
            Surface(shape = RoundedCornerShape(24.dp), border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.heightIn(min = 48.dp).clickable { room.select(id); room.choose(key) }) {
                Text("${emoji.glyph} $count${if (selected) " ✓" else ""}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = "${emoji.name}, $count"; this.selected = selected })
            }
        }
        if (choices.size > 3) TextButton(onClick = { room.select(id); room.actions[id]?.invoke(); room.picker = true }) { Text(stringResource(R.string.reactions_more)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionActionsPopup(onDismiss: () -> Unit, onReply: (() -> Unit)? = null, onReport: (() -> Unit)? = null, onBlock: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    val room = LocalReactionRoom.current ?: return
    val id = room.selectedId ?: return
    DisposableEffect(room, id) {
        room.dismissActions = onDismiss
        onDispose { room.dismissActions = null }
    }
    val anchor = room.bounds[id] ?: Rect.Zero
    val density = LocalDensity.current
    val menuWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceIn(192, 360).dp
    val quickCount = ((menuWidth.value - 8) / 48).toInt().minus(1).coerceIn(1, 5)
    val gap = with(density) { anchor.height.toDp() + 40.dp + if (room.visible[id].orEmpty().isNotEmpty()) 48.dp else 0.dp }
    if (room.picker) {
        ModalBottomSheet(onDismissRequest = { room.picker = false }, modifier = Modifier.fillMaxHeight(0.92f)) {
            var search by remember { mutableStateOf("") }
            var category by remember { mutableStateOf<String?>(null) }
            Text(stringResource(R.string.reactions_all), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.reactions_search)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val ranked = rankReactionEmojis(room.store.usage).mapNotNull { key -> room.catalog.firstOrNull { it.id == key } }
            if (search.isBlank() && ranked.isNotEmpty()) {
                Text(stringResource(R.string.reactions_frequent), modifier = Modifier.padding(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { ranked.take(5).forEach { emoji -> EmojiButton(emoji, room.busy) { room.choose(emoji.id) } } }
            }
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(vertical = 8.dp)) {
                item { TextButton(onClick = { category = null }) { Text(stringResource(R.string.reactions_all)) } }
                room.catalog.map { it.category }.distinct().forEach { name -> item { TextButton(onClick = { category = name }) { Text(stringResource(categoryResource(name))) } } }
            }
            val results = remember(search, category, room.catalog) { room.catalog.filter { (category == null || it.category == category) && (search.isBlank() || it.keywords.contains(search, ignoreCase = true) || it.glyph == search) } }
            if (room.failed) Text(stringResource(R.string.reactions_error), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp).imePadding()) {
                items(results, key = { it.id }) { emoji -> EmojiButton(emoji, room.busy) { room.choose(emoji.id) } }
            }
        }
        return
    }
    Popup(popupPositionProvider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = ((anchor.left + anchor.right) / 2 - popupContentSize.width / 2).toInt().coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
            val y = (anchor.top - with(density) { 88.dp.toPx() }).toInt().coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
            return IntOffset(x, y)
        }
    }, onDismissRequest = { room.dismiss() }, properties = PopupProperties(focusable = true)) {
        BoxWithConstraints(Modifier.widthIn(max = 360.dp).padding(horizontal = 8.dp)) {
            Column(Modifier.width(menuWidth)) {
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(4.dp).then(Modifier.semantics { contentDescription = "reaction-quick-row" }), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val defaults = listOf("1f44d", "2764-fe0f", "1f602", "1f60a", "1f451")
                        val keys = (rankReactionEmojis(room.store.usage) + defaults).distinct().take(quickCount)
                        keys.mapNotNull { key -> room.catalog.firstOrNull { it.id == key } }.forEach { emoji -> EmojiButton(emoji, room.busy || room.own[id] == null) { room.choose(emoji.id) } }
                        IconButton(onClick = { room.picker = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, stringResource(R.string.reactions_all)) }
                    }
                }
                Spacer(Modifier.height(gap))
                Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            onReply?.let { action -> MenuAction(Icons.AutoMirrored.Filled.Reply, R.string.moderation_reply) { room.dismiss(); action() } }
                            onReport?.let { action -> MenuAction(Icons.Default.Flag, R.string.moderation_reportMessage) { room.dismiss(); action() } }
                            onBlock?.let { action -> MenuAction(Icons.Default.Block, R.string.blocking_blockUser) { room.dismiss(); action() } }
                        }
                        onMore?.let { action -> TextButton(onClick = { room.moreActions = true; action() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reactions_actions)) } }
                        if (room.failed) TextButton(onClick = { room.reload() }) { Text(stringResource(R.string.reactions_error), color = MaterialTheme.colorScheme.error) }
                        if (room.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiButton(emoji: ReactionEmoji, busy: Boolean, onClick: () -> Unit) {
    val room = LocalReactionRoom.current
    val selected = room?.own?.get(room.selectedId ?: "")?.emojiId == emoji.id
    TextButton(onClick = onClick, enabled = !busy, shape = CircleShape,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.size(48.dp).semantics { contentDescription = emoji.name; this.selected = selected }, contentPadding = PaddingValues(0.dp)) {
        Text(emoji.glyph, fontSize = 24.sp)
    }
}
@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(stringResource(label), style = MaterialTheme.typography.labelSmall) }
    }
}

private fun categoryResource(name: String): Int = when (name) {
    "People & Body" -> R.string.reactions_category1
    "Component" -> R.string.reactions_category2
    "Animals & Nature" -> R.string.reactions_category3
    "Food & Drink" -> R.string.reactions_category4
    "Travel & Places" -> R.string.reactions_category5
    "Activities" -> R.string.reactions_category6
    "Objects" -> R.string.reactions_category7
    "Symbols" -> R.string.reactions_category8
    "Flags" -> R.string.reactions_category9
    else -> R.string.reactions_category0
}
