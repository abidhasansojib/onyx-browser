package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.onyx.browser.data.model.SitePermissionEntity

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions")
    suspend fun getAllPermissions(): List<SitePermissionEntity>

    @Query("SELECT * FROM site_permissions")
    fun getAllPermissionsBlocking(): List<SitePermissionEntity>

    @Query("SELECT * FROM site_permissions WHERE domain = :domain")
    suspend fun getPermissionsForDomain(domain: String): List<SitePermissionEntity>

    @Query("SELECT * FROM site_permissions WHERE permissionType = :permissionType AND state = :state")
    suspend fun getDomainsByTypeAndState(permissionType: String, state: String): List<SitePermissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermission(entity: SitePermissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermissions(entities: List<SitePermissionEntity>)

    @Query("DELETE FROM site_permissions WHERE domain = :domain AND permissionType = :permissionType")
    suspend fun deletePermission(domain: String, permissionType: String)

    @Query("DELETE FROM site_permissions WHERE domain = :domain")
    suspend fun deleteAllForDomain(domain: String)

    @Query("DELETE FROM site_permissions")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT domain FROM site_permissions ORDER BY domain ASC")
    suspend fun getAllConfiguredDomains(): List<String>
}
