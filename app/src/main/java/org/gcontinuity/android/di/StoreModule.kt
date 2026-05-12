package org.gcontinuity.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.gcontinuity.android.store.PluginStore
import javax.inject.Singleton

/**
 * Hilt module that provides store-layer singletons.
 *
 * ## Why this module exists
 * [PluginStore] takes a plain [Context] constructor parameter — it has no @Inject
 * annotation and cannot be created by Hilt automatically. This @Provides method
 * teaches Hilt how to construct it so it can be injected into [PluginManager].
 *
 * ## Scope
 * @Singleton matches [PluginManager]'s scope (SingletonComponent). A single
 * PluginStore instance is shared across the process lifetime, which is correct
 * because EncryptedSharedPreferences is safe to use as a singleton.
 *
 * ## Note on MainViewModel
 * [org.gcontinuity.android.viewmodel.MainViewModel] constructs its own PluginStore
 * instance manually (via PluginStore(application)) for the plugin-settings UI.
 * That is intentional — MainViewModel is not Hilt-injected and follows the
 * existing GContinuityService.instance pattern used throughout the project.
 * The two instances share the same underlying EncryptedSharedPreferences file
 * ("gcontinuity_plugins") so reads and writes are consistent.
 */
@Module
@InstallIn(SingletonComponent::class)
object StoreModule {

    @Provides
    @Singleton
    fun providePluginStore(
        @ApplicationContext context: Context,
    ): PluginStore = PluginStore(context)
}