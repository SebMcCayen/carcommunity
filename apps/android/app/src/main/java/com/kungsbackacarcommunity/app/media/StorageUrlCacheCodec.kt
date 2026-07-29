package com.kungsbackacarcommunity.app.media

/**
 * One remembered `storage path -> download URL` mapping.
 *
 * The URL is the tokenized Firebase download link produced by `getDownloadUrl()`
 * (see [resolveStorageDownloadUrl]). It is long-lived, not a short-lived signed
 * URL, and the object it points at is immutable — every upload mints a fresh
 * `<uuid>.<ext>` object id ([MediaUpload.newImageId]), so a path's bytes never
 * change under a cached URL. That is what makes remembering the mapping safe.
 */
internal data class StorageUrlEntry(val path: String, val url: String)

/**
 * Pure (Android-free, JVM-testable) serialisation + LRU bookkeeping for the
 * persisted `path -> download URL` map behind [StorageDownloadUrlCache].
 *
 * The list is ordered MOST-RECENTLY-USED FIRST and capped at [MAX_ENTRIES];
 * everything past the cap falls off the end. The cap exists because the app
 * resolves a URL for every avatar it has ever rendered (chat, friends, member
 * search, convoy…), which is unbounded over a long-lived install, while the
 * things a user actually revisits — their own cars, their friends' avatars — sit
 * at the head of the list.
 *
 * ## Wire format
 * One record per line, `path<TAB>url`. Deliberately not JSON: the values are two
 * flat strings, and a hand-rolled split has no parser to keep in sync and no
 * dependency in a class that has to stay unit-testable off-device. Neither
 * separator can occur in a Cloud Storage object path we mint
 * (`vehicleImages/{uid}/{vehicleId}/{uuid}.{ext}`) nor in a URL, and [encode]
 * drops any entry that somehow contains one rather than writing a record that
 * would decode into two wrong entries.
 */
internal object StorageUrlCacheCodec {

    /**
     * Hard cap on remembered mappings. ~128 entries is a few KB of preferences —
     * far more than the handful of images any one screen shows, small enough to
     * read in a single blocking preferences load.
     */
    const val MAX_ENTRIES: Int = 128

    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    /** True when [entry] can round-trip through [encode]/[decode] unambiguously. */
    fun isStorable(entry: StorageUrlEntry): Boolean =
        entry.path.isNotBlank() &&
            entry.url.isNotEmpty() &&
            entry.path.none { it == RECORD_SEPARATOR || it == FIELD_SEPARATOR } &&
            entry.url.none { it == RECORD_SEPARATOR || it == FIELD_SEPARATOR }

    /** Serialises [entries] (MRU first), dropping unstorable ones and the tail past [MAX_ENTRIES]. */
    fun encode(entries: List<StorageUrlEntry>): String =
        entries
            .asSequence()
            .filter(::isStorable)
            .take(MAX_ENTRIES)
            .joinToString(RECORD_SEPARATOR.toString()) { "${it.path}$FIELD_SEPARATOR${it.url}" }

    /**
     * Parses what [encode] wrote. Order is preserved (so MRU stays MRU), the
     * first record for a path wins, and malformed records are skipped rather
     * than failing the whole read — a corrupt preferences value must degrade to
     * "resolve it again", never to a crash on launch.
     */
    fun decode(raw: String?): List<StorageUrlEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        val seen = HashSet<String>()
        val parsed = ArrayList<StorageUrlEntry>()
        for (line in raw.split(RECORD_SEPARATOR)) {
            if (parsed.size >= MAX_ENTRIES) break
            val separator = line.indexOf(FIELD_SEPARATOR)
            if (separator <= 0) continue
            val entry = StorageUrlEntry(
                path = line.substring(0, separator),
                url = line.substring(separator + 1),
            )
            if (!isStorable(entry)) continue
            if (!seen.add(entry.path)) continue
            parsed += entry
        }
        return parsed
    }

    /** [entries] with [path] promoted to the head and mapped to [url], re-capped. */
    fun touch(entries: List<StorageUrlEntry>, path: String, url: String): List<StorageUrlEntry> {
        val promoted = ArrayList<StorageUrlEntry>(minOf(entries.size + 1, MAX_ENTRIES))
        promoted += StorageUrlEntry(path, url)
        for (entry in entries) {
            if (promoted.size >= MAX_ENTRIES) break
            if (entry.path != path) promoted += entry
        }
        return promoted
    }

    /** [entries] without [path] — used when a cached URL turns out to be dead. */
    fun remove(entries: List<StorageUrlEntry>, path: String): List<StorageUrlEntry> =
        entries.filterNot { it.path == path }

    /**
     * Folds a just-read-from-disk [stored] list under the [live] in-memory one.
     *
     * [live] wins on conflict and keeps its position: anything resolved while the
     * disk read was in flight was resolved from the network *now*, so it is at
     * least as fresh as — and more recently used than — whatever the last process
     * happened to persist.
     */
    fun merge(live: List<StorageUrlEntry>, stored: List<StorageUrlEntry>): List<StorageUrlEntry> {
        val livePaths = live.mapTo(HashSet()) { it.path }
        return (live + stored.filterNot { it.path in livePaths }).take(MAX_ENTRIES)
    }
}
