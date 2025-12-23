package com.alibaba.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alibaba.domain.model.AppSettings
import com.alibaba.domain.repo.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            streamTestSampleSize = prefs[Keys.STREAM_TEST_SAMPLE_SIZE] ?: AppSettings().streamTestSampleSize,
            streamTestTimeoutMs = prefs[Keys.STREAM_TEST_TIMEOUT_MS] ?: AppSettings().streamTestTimeoutMs,
            minPlayableStreamsToPass = prefs[Keys.MIN_PLAYABLE_STREAMS_TO_PASS] ?: AppSettings().minPlayableStreamsToPass,
            delayBetweenStreamTestsMs = prefs[Keys.DELAY_BETWEEN_TESTS_MS] ?: AppSettings().delayBetweenStreamTestsMs,
            skipAdultGroups = prefs[Keys.SKIP_ADULT_GROUPS] ?: AppSettings().skipAdultGroups,
            shuffleCandidates = prefs[Keys.SHUFFLE_CANDIDATES] ?: AppSettings().shuffleCandidates,
            enableCountryFiltering = prefs[Keys.ENABLE_COUNTRY_FILTERING] ?: AppSettings().enableCountryFiltering
        )
    }

    override suspend fun setStreamTestSampleSize(value: Int) {
        val v = value.coerceIn(1, 50)
        context.settingsDataStore.edit { it[Keys.STREAM_TEST_SAMPLE_SIZE] = v }
    }

    override suspend fun setStreamTestTimeoutMs(value: Long) {
        val v = value.coerceIn(1_000L, 30_000L)
        context.settingsDataStore.edit { it[Keys.STREAM_TEST_TIMEOUT_MS] = v }
    }

    override suspend fun setMinPlayableStreamsToPass(value: Int) {
        val v = value.coerceIn(1, 5)
        context.settingsDataStore.edit { it[Keys.MIN_PLAYABLE_STREAMS_TO_PASS] = v }
    }

    override suspend fun setDelayBetweenStreamTestsMs(value: Long) {
        val v = value.coerceIn(0L, 5_000L)
        context.settingsDataStore.edit { it[Keys.DELAY_BETWEEN_TESTS_MS] = v }
    }

    override suspend fun setSkipAdultGroups(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SKIP_ADULT_GROUPS] = value }
    }

    override suspend fun setShuffleCandidates(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHUFFLE_CANDIDATES] = value }
    }

    override suspend fun setEnableCountryFiltering(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.ENABLE_COUNTRY_FILTERING] = value }
    }

    override suspend fun resetToDefaults() {
        context.settingsDataStore.edit { it.clear() }
    }

    private object Keys {
        val STREAM_TEST_SAMPLE_SIZE = intPreferencesKey("streamTestSampleSize")
        val STREAM_TEST_TIMEOUT_MS = longPreferencesKey("streamTestTimeoutMs")
        val MIN_PLAYABLE_STREAMS_TO_PASS = intPreferencesKey("minPlayableStreamsToPass")
        val DELAY_BETWEEN_TESTS_MS = longPreferencesKey("delayBetweenStreamTestsMs")
        val SKIP_ADULT_GROUPS = booleanPreferencesKey("skipAdultGroups")
        val SHUFFLE_CANDIDATES = booleanPreferencesKey("shuffleCandidates")
        val ENABLE_COUNTRY_FILTERING = booleanPreferencesKey("enableCountryFiltering")
    }
}
