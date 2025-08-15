/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.intentresolver.inject

import android.app.ActivityManager
import android.app.IUriGrantsManager
import android.app.UriGrantsManager
import android.app.admin.DevicePolicyManager
import android.app.prediction.AppPredictionManager
import android.content.ClipboardManager
import android.content.ContentInterface
import android.content.ContentResolver
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutManager
import android.os.UserManager
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.android.intentresolver.data.repository.UserScopedService
import com.android.intentresolver.data.repository.UserScopedServiceImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

inline fun <reified T> Context.requireSystemService(): T {
    return checkNotNull(getSystemService())
}

@Module
@InstallIn(SingletonComponent::class)
class ActivityManagerModule {
    @Provides
    fun activityManager(@ApplicationContext ctx: Context): ActivityManager =
        ctx.requireSystemService()
}

@Module
@InstallIn(SingletonComponent::class)
class ClipboardManagerModule {
    @Provides
    fun clipboardManager(@ApplicationContext ctx: Context): ClipboardManager =
        ctx.requireSystemService()
}

@Module
@InstallIn(SingletonComponent::class)
interface ContentResolverModule {
    @Binds fun bindContentInterface(cr: ContentResolver): ContentInterface

    companion object {
        @Provides
        fun contentResolver(@ApplicationContext ctx: Context) = requireNotNull(ctx.contentResolver)
    }
}

@Module
@InstallIn(SingletonComponent::class)
class DevicePolicyManagerModule {
    @Provides
    fun devicePolicyManager(@ApplicationContext ctx: Context): DevicePolicyManager =
        ctx.requireSystemService()
}

@Module
@InstallIn(SingletonComponent::class)
class LauncherAppsModule {
    @Provides
    fun launcherApps(@ApplicationContext ctx: Context): LauncherApps = ctx.requireSystemService()
}

@Module
@InstallIn(SingletonComponent::class)
class PackageManagerModule {
    @Provides
    fun packageManager(@ApplicationContext ctx: Context) = requireNotNull(ctx.packageManager)
}

@Module
@InstallIn(SingletonComponent::class)
class PredictionManagerModule {
    @Provides
    fun scopedPredictionManager(
        @ApplicationContext ctx: Context
    ): UserScopedService<AppPredictionManager> {
        return UserScopedServiceImpl(ctx, AppPredictionManager::class)
    }
}

@Module
@InstallIn(SingletonComponent::class)
class ShortcutManagerModule {
    @Provides
    fun shortcutManager(@ApplicationContext ctx: Context): ShortcutManager {
        return ctx.requireSystemService()
    }

    @Provides
    fun scopedShortcutManager(
        @ApplicationContext ctx: Context
    ): UserScopedService<ShortcutManager> {
        return UserScopedServiceImpl(ctx, ShortcutManager::class)
    }
}

@Module
@InstallIn(SingletonComponent::class)
class UserManagerModule {
    @Provides
    fun userManager(@ApplicationContext ctx: Context): UserManager = ctx.requireSystemService()

    @Provides
    fun scopedUserManager(@ApplicationContext ctx: Context): UserScopedService<UserManager> {
        return UserScopedServiceImpl(ctx, UserManager::class)
    }
}

@Module
@InstallIn(SingletonComponent::class)
class WindowManagerModule {
    @Provides
    fun windowManager(@ApplicationContext ctx: Context): WindowManager = ctx.requireSystemService()
}

@Module
@InstallIn(SingletonComponent::class)
class UriGrantsManagerModule {
    @Provides fun uriGrantsManager(): IUriGrantsManager = UriGrantsManager.getService()
}
