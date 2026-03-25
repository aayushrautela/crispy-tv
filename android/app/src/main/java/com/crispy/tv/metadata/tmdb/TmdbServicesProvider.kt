package com.crispy.tv.metadata.tmdb

import android.content.Context
import com.crispy.tv.metadata.TmdbImdbIdResolver

object TmdbServicesProvider {
    @Volatile
    private var identityServiceInstance: TmdbIdentityService? = null

    @Volatile
    private var titleRemoteDataSourceInstance: TmdbTitleRemoteDataSource? = null

    @Volatile
    private var personRepositoryInstance: TmdbPersonRepository? = null

    @Volatile
    private var metadataRecordRepositoryInstance: TmdbMetadataRecordRepository? = null

    @Volatile
    private var enrichmentRepositoryInstance: TmdbEnrichmentRepository? = null

    @Volatile
    private var imdbIdResolverInstance: TmdbImdbIdResolver? = null

    private fun identityService(context: Context): TmdbIdentityService {
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

    private fun titleRemoteDataSource(context: Context): TmdbTitleRemoteDataSource {
        val existing = titleRemoteDataSourceInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = titleRemoteDataSourceInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                TmdbTitleRemoteDataSource(TmdbJsonClientProvider.get(context.applicationContext)).also { created ->
                    titleRemoteDataSourceInstance = created
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
                    remoteDataSource = titleRemoteDataSource(appContext),
                    identityService = identityService(appContext),
                ).also { created ->
                    metadataRecordRepositoryInstance = created
                }
            }
        }
    }

    internal fun enrichmentRepository(context: Context): TmdbEnrichmentRepository {
        val existing = enrichmentRepositoryInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = enrichmentRepositoryInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                val appContext = context.applicationContext
                TmdbEnrichmentRepository(
                    client = TmdbJsonClientProvider.get(appContext),
                    identityService = identityService(appContext),
                    remoteDataSource = titleRemoteDataSource(appContext),
                ).also { created ->
                    enrichmentRepositoryInstance = created
                }
            }
        }
    }

    internal fun imdbIdResolver(context: Context): TmdbImdbIdResolver {
        val existing = imdbIdResolverInstance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = imdbIdResolverInstance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                TmdbImdbIdResolver(identityService(context.applicationContext)).also { created ->
                    imdbIdResolverInstance = created
                }
            }
        }
    }
}
