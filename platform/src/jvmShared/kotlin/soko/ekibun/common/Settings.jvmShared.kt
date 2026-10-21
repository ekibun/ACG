package soko.ekibun.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

/** 用 [PreferenceDataStoreFactory] 在 [path] 上建 DataStore。 */
actual fun createDataStore(path: String): DataStore<Preferences> =
  PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
