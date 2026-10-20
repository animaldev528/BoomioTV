package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.PinnedHomeRow
import com.nuvio.tv.domain.model.PinnedPerson
import com.nuvio.tv.domain.model.PinnedRowKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped list of the rows the user pinned onto Home.
 *
 * Mirrors [LibraryPreferences] / [LikePreferences]: one DataStore per profile via
 * [ProfileDataStoreFactory], no server round-trip. The stored value is a JSON *array*, not a
 * string set, because row order is the order they appear in on Home and a set would lose it.
 *
 * [add] is idempotent on [PinnedHomeRow.stableKey]: re-pinning a list or a search term
 * replaces that row in place. The Actors row union-merges people instead, so pinning a
 * second actor grows the same row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PinnedHomeRowsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val rowsKey = stringPreferencesKey("pinned_home_rows")
    private val rowsType = object : TypeToken<List<PinnedHomeRow>>() {}.type
    private val mutex = Mutex()

    /** The active profile's pinned rows, in Home order. */
    val rows: Flow<List<PinnedHomeRow>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> prefs.decodeRows() }
    }

    /** Pins [row]. Returns false when the cap is reached and this would be a new row. */
    suspend fun add(row: PinnedHomeRow): Boolean = mutate { current ->
        val existingIndex = current.indexOfFirst { it.stableKey == row.stableKey }
        when {
            existingIndex >= 0 -> {
                current[existingIndex] = merge(current[existingIndex], row)
                true
            }
            current.size >= MAX_ROWS -> false
            else -> {
                current.add(row)
                true
            }
        }
    }

    suspend fun remove(rowId: String) {
        mutate { current ->
            current.removeAll { it.id == rowId }
            true
        }
    }

    /** Drops one person from the Actors row, and the row itself once it is empty. */
    suspend fun removePerson(tmdbId: Int) {
        mutate { current ->
            val index = current.indexOfFirst { it.kind == PinnedRowKind.PEOPLE }
            if (index >= 0) {
                val remaining = current[index].people.filterNot { it.tmdbId == tmdbId }
                if (remaining.isEmpty()) current.removeAt(index) else current[index] = current[index].copy(people = remaining)
            }
            true
        }
    }

    /** Adds (or moves to the front of) the Actors row for [person]. */
    suspend fun addPerson(person: PinnedPerson, rowId: String = PinnedHomeRow.PEOPLE_ROW_ID): Boolean =
        add(
            PinnedHomeRow(
                id = rowId,
                kind = PinnedRowKind.PEOPLE,
                title = PEOPLE_ROW_TITLE,
                people = listOf(person)
            )
        )

    suspend fun rowsNow(): List<PinnedHomeRow> = rows.first()

    private suspend fun mutate(block: (MutableList<PinnedHomeRow>) -> Boolean): Boolean {
        var result = false
        mutex.withLock {
            val store = factory.get(profileManager.activeProfileId.value, FEATURE)
            store.edit { prefs ->
                val current = prefs.decodeRows().toMutableList()
                result = block(current)
                prefs[rowsKey] = gson.toJson(current, rowsType)
            }
        }
        return result
    }

    /**
     * An Actors row grows; every other kind of row is replaced, so a list that was renamed or
     * re-sorted on Trakt shows up under its current title.
     */
    private fun merge(existing: PinnedHomeRow, incoming: PinnedHomeRow): PinnedHomeRow {
        if (incoming.kind != PinnedRowKind.PEOPLE) return incoming
        val merged = LinkedHashMap<Int, PinnedPerson>()
        existing.people.forEach { merged[it.tmdbId] = it }
        incoming.people.forEach { merged[it.tmdbId] = it }
        return incoming.copy(people = merged.values.toList())
    }

    private fun Preferences.decodeRows(): List<PinnedHomeRow> {
        val raw = this[rowsKey]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { gson.fromJson<List<PinnedHomeRow>>(raw, rowsType) }
            .getOrNull()
            .orEmpty()
            .filter { it.id.isNotBlank() }
    }

    companion object {
        private const val FEATURE = "pinned_home_rows"

        /** Home is a wall, not a file manager; past a handful of pinned rows it stops being one. */
        const val MAX_ROWS = 8

        /** Matches [PinnedHomeRow]'s default title for the people row. */
        const val PEOPLE_ROW_TITLE = "Actors"
    }
}
