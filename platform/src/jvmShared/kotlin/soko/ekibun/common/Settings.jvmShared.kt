package soko.ekibun.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

actual fun createDataStore(path: String): DataStore<Preferences> =
  PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
