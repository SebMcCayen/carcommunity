package com.kungsbackacarcommunity.app.drives

import java.io.File

/**
 * A recording rehydrated from its on-disk journal: the ORIGINAL start moment plus
 * every fix that had been flushed before the process died. [startedAtMillis] is
 * the recording's real start (not "now"), so a resumed drive's duration is
 * correct rather than restarted from the moment the app relaunched.
 */
data class JournalSnapshot(
    val startedAtMillis: Long,
    val points: List<RecordedPoint>,
)

/**
 * Append-only on-disk journal of an IN-FLIGHT drive recording, keyed by
 * `sourceSessionId`.
 *
 * ## Why this exists (#849)
 * A live drive recording used to live ONLY in process memory
 * ([DriveRecorder]). When the OS killed the backgrounded process — routine on
 * Samsung / under Doze / aggressive battery optimization — the whole in-flight
 * drive vanished, and on relaunch the still-live session started a fresh, empty
 * recording. This journal persists the recording incrementally so a killed-and-
 * relaunched session can RESUME the same drive instead of losing it.
 *
 * ## Format — append-only, line-oriented, kill-tolerant
 * A journal file is a header line followed by one line per accepted fix:
 * ```
 * H,<startedAtMillis>
 * P,<latitude>,<longitude>,<timestampMs>
 * P,...
 * ```
 * The header is written once at [begin]; fixes are appended (in batches — see
 * [appendPoints]) as they arrive. Appending only ever ADDS a whole line and
 * flushes it to the OS, so:
 * - a process kill (the threat here — the OS reclaims the app process but keeps
 *   its page cache) never loses a flushed line;
 * - a kill MID-write can leave a truncated final line, which [load] simply skips
 *   (parse-and-drop), so at worst the last partial fix is lost, never the drive.
 *
 * A file-based journal is used deliberately rather than
 * `rememberSaveable`/`savedInstanceState`: a recording holds up to
 * [DriveRecorder.MAX_ROUTE_POINTS] (20k) points, far past the Bundle
 * transaction limit that forced the memory-only design in the first place.
 *
 * Not thread-safe by contract: driven from the same single (main) thread as
 * [DriveRecordingCoordinator], which owns the batching.
 *
 * @param directory the folder journals live in (e.g. `context.filesDir/drive-journals`);
 *   created on demand.
 */
class DriveRecordingJournal(private val directory: File) {

    private fun fileFor(sourceSessionId: String): File =
        File(directory, sanitize(sourceSessionId) + FILE_SUFFIX)

    /**
     * Starts a FRESH journal for [sourceSessionId]: (re)creates the directory,
     * truncates any existing file, and writes the header carrying
     * [startedAtMillis]. Also prunes every OTHER journal file — only one live
     * session records at a time per process, so any leftover journal belongs to a
     * long-gone session (one killed between stop and the save that clears it) and
     * is safe to remove, bounding disk use. The journal being RESUMED never goes
     * through here (resume reads via [load], which does not prune).
     */
    fun begin(sourceSessionId: String, startedAtMillis: Long) {
        directory.mkdirs()
        val target = fileFor(sourceSessionId)
        pruneOthers(keep = target)
        runCatching {
            target.writeText("$HEADER_PREFIX$startedAtMillis\n")
        }
    }

    /**
     * Appends [points] as `P,` lines. Called by the coordinator in small batches
     * (throttled), not once per fix, so the write cadence stays coarse. A missing
     * header file (never begun, or externally cleared) is tolerated: the append is
     * a best-effort no-op-on-failure and never throws into the recording path.
     */
    fun appendPoints(sourceSessionId: String, points: List<RecordedPoint>) {
        if (points.isEmpty()) return
        val target = fileFor(sourceSessionId)
        if (!target.exists()) return
        runCatching {
            val builder = StringBuilder()
            for (p in points) {
                builder.append(POINT_PREFIX)
                    .append(p.latitude).append(',')
                    .append(p.longitude).append(',')
                    .append(p.timestampMs).append('\n')
            }
            // append = true; the OutputStream flush pushes the bytes to the OS,
            // which is what survives a process kill (no fsync needed — only a
            // power loss would need that, which is out of scope).
            target.appendText(builder.toString())
        }
    }

    /**
     * Loads the persisted recording for [sourceSessionId], or null when there is
     * no journal or its header is unreadable (treated as "nothing to resume").
     * Tolerates a truncated final line from a mid-write kill by skipping any line
     * it cannot parse.
     */
    fun load(sourceSessionId: String): JournalSnapshot? {
        val target = fileFor(sourceSessionId)
        if (!target.exists()) return null
        val lines = runCatching { target.readLines() }.getOrNull() ?: return null
        val header = lines.firstOrNull() ?: return null
        if (!header.startsWith(HEADER_PREFIX)) return null
        val startedAt = header.removePrefix(HEADER_PREFIX).trim().toLongOrNull() ?: return null
        val points = ArrayList<RecordedPoint>(lines.size)
        for (i in 1 until lines.size) {
            parsePoint(lines[i])?.let { points.add(it) }
        }
        return JournalSnapshot(startedAtMillis = startedAt, points = points)
    }

    /** Removes the journal for [sourceSessionId] (called on save / delete / discard). */
    fun clear(sourceSessionId: String) {
        runCatching { fileFor(sourceSessionId).delete() }
    }

    private fun pruneOthers(keep: File) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(FILE_SUFFIX) && file != keep) {
                runCatching { file.delete() }
            }
        }
    }

    private fun parsePoint(line: String): RecordedPoint? {
        if (!line.startsWith(POINT_PREFIX)) return null
        val parts = line.split(',')
        // "P", lat, lon, ts — a truncated final line has fewer fields or an
        // unparseable tail, so any failure here drops just that line.
        if (parts.size != 4) return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        val ts = parts[3].toLongOrNull() ?: return null
        return RecordedPoint(latitude = lat, longitude = lon, timestampMs = ts)
    }

    private fun sanitize(sourceSessionId: String): String {
        val cleaned = sourceSessionId.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
        }.joinToString("")
        // Guard against a pathological all-unsafe / empty id colliding by folding
        // in a stable hash of the original.
        return cleaned.take(120) + "-" + Integer.toHexString(sourceSessionId.hashCode())
    }

    private companion object {
        const val FILE_SUFFIX = ".journal"
        const val HEADER_PREFIX = "H,"
        const val POINT_PREFIX = "P,"
    }
}
