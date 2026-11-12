package com.onyx.browser.data.repository

import android.content.Context
import android.util.Log
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.local.SitePermissionDao
import com.onyx.browser.data.model.SitePermissionEntity
import com.onyx.browser.data.model.SitePermissionType
import com.onyx.browser.data.preferences.SitePermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SitePermissionRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + NonCancellable)

    private val dao: SitePermissionDao by lazy {
        AppDatabase.getInstance(appContext).sitePermissionDao()
    }

    /**
     * Lock-free O(1) in-memory cache mapping each [SitePermissionType] to
     * Pair(allowedDomains, blockedDomains).
     */
    private val permissionCache =
        ConcurrentHashMap<SitePermissionType, Pair<MutableSet<String>, MutableSet<String>>>()

    private val modifiedKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        for (type in SitePermissionType.values()) {
            permissionCache[type] = Pair(
                ConcurrentHashMap.newKeySet(),
                ConcurrentHashMap.newKeySet()
            )
        }
        ioScope.launch {
            try {
                val allEntities = dao.getAllPermissions()
                for (entity in allEntities) {
                    val key = "${entity.domain}:${entity.permissionType}"
                    if (modifiedKeys.contains(key)) continue
                    val type = try {
                        SitePermissionType.valueOf(entity.permissionType)
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: continue
                    val pair = getOrCreateSets(type)
                    when (entity.state) {
                        SitePermissionState.ALLOWED.name -> {
                            pair.first.add(entity.domain)
                            pair.second.remove(entity.domain)
                        }
                        SitePermissionState.BLOCKED.name -> {
                            pair.second.add(entity.domain)
                            pair.first.remove(entity.domain)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load site permissions from Room", e)
            }
        }
    }

    private fun getOrCreateSets(type: SitePermissionType): Pair<MutableSet<String>, MutableSet<String>> {
        return permissionCache.getOrPut(type) {
            Pair(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet())
        }
    }

    fun getPermissionState(domain: String, type: SitePermissionType): SitePermissionState {
        val clean = domain.trim().lowercase()
        if (clean.isBlank()) return SitePermissionState.ASK
        val pair = getOrCreateSets(type)
        return when {
            pair.first.contains(clean) -> SitePermissionState.ALLOWED
            pair.second.contains(clean) -> SitePermissionState.BLOCKED
            else -> SitePermissionState.ASK
        }
    }

    fun isAllowed(domain: String, type: SitePermissionType): Boolean? {
        return when (getPermissionState(domain, type)) {
            SitePermissionState.ALLOWED -> true
            SitePermissionState.BLOCKED -> false
            SitePermissionState.ASK -> null
        }
    }

    fun setPermissionState(domain: String, type: SitePermissionType, state: SitePermissionState) {
        val clean = domain.trim().lowercase()
        if (clean.isBlank()) return
        modifiedKeys.add("$clean:${type.name}")
        val pair = getOrCreateSets(type)
        when (state) {
            SitePermissionState.ALLOWED -> {
                pair.first.add(clean)
                pair.second.remove(clean)
            }
            SitePermissionState.BLOCKED -> {
                pair.second.add(clean)
                pair.first.remove(clean)
            }
            SitePermissionState.ASK -> {
                pair.first.remove(clean)
                pair.second.remove(clean)
            }
        }
        ioScope.launch {
            try {
                if (state == SitePermissionState.ASK) {
                    dao.deletePermission(clean, type.name)
                } else {
                    dao.upsertPermission(
                        SitePermissionEntity(
                            domain = clean,
                            permissionType = type.name,
                            state = state.name,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist site permission ($clean, $type -> $state)", e)
            }
        }
    }

    fun getAllowedDomains(type: SitePermissionType): Set<String> {
        return getOrCreateSets(type).first.toSet()
    }

    fun getBlockedDomains(type: SitePermissionType): Set<String> {
        return getOrCreateSets(type).second.toSet()
    }

    fun clearAllSitePermissions() {
        for (type in SitePermissionType.values()) {
            val pair = getOrCreateSets(type)
            pair.first.clear()
            pair.second.clear()
        }
        modifiedKeys.clear()
        ioScope.launch {
            try {
                dao.deleteAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all site permissions in Room", e)
            }
        }
    }

    fun clearDomainPermissions(domain: String) {
        val clean = domain.trim().lowercase()
        if (clean.isBlank()) return
        for (type in SitePermissionType.values()) {
            modifiedKeys.add("$clean:${type.name}")
            val pair = getOrCreateSets(type)
            pair.first.remove(clean)
            pair.second.remove(clean)
        }
        ioScope.launch {
            try {
                dao.deleteAllForDomain(clean)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear site permissions for domain $clean in Room", e)
            }
        }
    }

    fun importFromLegacyPreferences(legacyEntries: List<SitePermissionEntity>) {
        if (legacyEntries.isEmpty()) return
        for (entity in legacyEntries) {
            val clean = entity.domain.trim().lowercase()
            if (clean.isBlank()) continue
            val type = try {
                SitePermissionType.valueOf(entity.permissionType)
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            modifiedKeys.add("$clean:${type.name}")
            val pair = getOrCreateSets(type)
            when (entity.state) {
                SitePermissionState.ALLOWED.name -> {
                    pair.first.add(clean)
                    pair.second.remove(clean)
                }
                SitePermissionState.BLOCKED.name -> {
                    pair.second.add(clean)
                    pair.first.remove(clean)
                }
            }
        }
        ioScope.launch {
            try {
                dao.upsertPermissions(legacyEntries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import legacy site permissions into Room", e)
            }
        }
    }

    companion object {
        private const val TAG = "SitePermissionRepo"

        @Volatile
        private var INSTANCE: SitePermissionRepository? = null

        fun getInstance(context: Context): SitePermissionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SitePermissionRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
