package com.crispy.tv.metadata.tmdb

import android.content.Context

object TmdbServicesProvider {
    @Volatile
    private var identityServiceInstance: TmdbIdentityService? = null

    @Volatile
    private var searchRemoteDataSourceInstance: TmdbSearchRemoteDataSource? = null

    @Volatile
    private var personRepositoryInstance: TmdbPersonRepository? = null

    @Volatile
    private var metadataRecordRepositoryInstance: TmdbMetadataRecordRepository? = null

    internal fun identityService(context: Context): TmdbIdentityService {
        val existing = identityServiceInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = identityServiceInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                TmdbIdentityService(TmdbJsonClientProvider.get(context.applicationContext)).also { created ->
                    identityServiceInstance = created
                }
            }
        }
    }

    internal fun searchRemoteDataSource(context: Context): TmdbSearchRemoteDataSource {
        val existing = searchRemoteDataSourceInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = searchRemoteDataSourceInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                TmdbSearchRemoteDataSource(TmdbJsonClientProvider.get(context.applicationContext)).also { created ->
                    searchRemoteDataSourceInstance = created
                }
            }
        }
    }

    internal fun personRepository(context: Context): TmdbPersonRepository {
        val existing = personRepositoryInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = personRepositoryInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                TmdbPersonRepository(TmdbJsonClientProvider.get(context.applicationContext)).also { created ->
                    personRepositoryInstance = created
                }
            }
        }
    }

    internal fun metadataRecordRepository(context: Context): TmdbMetadataRecordRepository {
        val existing = metadataRecordRepositoryInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = metadataRecordRepositoryInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                val appContext = context.applicationContext
                TmdbMetadataRecordRepository(
                    client = TmdbJsonClientProvider.get(appContext),
                    identityService = identityService(appContext),
                ).also { created ->
                    metadataRecordRepositoryInstance = created
                }
            }
        }
    }
}
