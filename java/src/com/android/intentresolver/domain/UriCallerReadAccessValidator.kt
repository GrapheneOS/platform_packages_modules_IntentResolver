/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.intentresolver.domain

import android.app.IUriGrantsManager
import android.content.ContentProvider.getUriWithoutUserId
import android.content.ContentProvider.getUserIdFromUri
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.android.intentresolver.data.repository.ActivityModelRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Qualifier

private const val TAG = "ChooserActivity"

@Qualifier @MustBeDocumented @Retention(AnnotationRetention.BINARY) annotation class PackageName

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
annotation class LaunchedFromUid

/** Verifies that the Chooser activity caller has read access to a URI. */
class UriCallerReadAccessValidator
@Inject
constructor(
    private val uriGrantsManager: IUriGrantsManager,
    @param:LaunchedFromUid private val launchedFromUid: Int,
    @param:PackageName private val packageName: String,
) {
    fun checkAccess(uri: Uri): Boolean =
        runCatching {
                uriGrantsManager.checkGrantUriPermission_ignoreNonSystem(
                    launchedFromUid,
                    packageName,
                    getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    getUserIdFromUri(uri),
                )
                true
            }
            .getOrElse {
                Log.e(TAG, "Failed to get URI permission for $uri", it)
                false
            }
}

@Module
@InstallIn(ActivityRetainedComponent::class)
object CallerUriReadAccessValidatorModule {
    @Provides
    @PackageName
    fun packageName(@ApplicationContext context: Context): String = context.packageName

    @Provides
    @LaunchedFromUid
    fun launchedFromUid(activityModelRepository: ActivityModelRepository): Int =
        activityModelRepository.value.launchedFromUid
}
