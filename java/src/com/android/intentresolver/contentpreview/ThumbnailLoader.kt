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

package com.android.intentresolver.contentpreview

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.android.intentresolver.domain.UriCallerReadAccessValidator
import com.android.intentresolver.util.withCancellationSignal
import javax.inject.Inject

/** Interface for objects that can attempt load a [Bitmap] from a [Uri]. */
interface ThumbnailLoader {
    /**
     * Loads a thumbnail for the given [uri].
     *
     * The size of the thumbnail is determined by the implementation.
     */
    suspend fun loadThumbnail(uri: Uri): Bitmap?

    /**
     * Loads a thumbnail for the given [uri] and [size].
     *
     * The [size] is the size of the thumbnail in pixels.
     */
    suspend fun loadThumbnail(uri: Uri, size: Size): Bitmap?
}

/** Default implementation of [ThumbnailLoader]. */
class ThumbnailLoaderImpl
@Inject
constructor(
    private val contentResolver: ContentResolver,
    @ThumbnailSize thumbnailSize: Int,
    private val uriAccessValidator: UriCallerReadAccessValidator,
) : ThumbnailLoader {

    private val size = Size(thumbnailSize, thumbnailSize)

    override suspend fun loadThumbnail(uri: Uri): Bitmap {
        assertAccess(uri)
        return contentResolver.loadThumbnail(uri, size, /* signal= */ null)
    }

    override suspend fun loadThumbnail(uri: Uri, size: Size): Bitmap {
        assertAccess(uri)
        return withCancellationSignal { signal -> contentResolver.loadThumbnail(uri, size, signal) }
    }

    private fun assertAccess(uri: Uri) {
        if (!uriAccessValidator.checkAccess(uri)) {
            throw SecurityException("The calling application does not have access to $uri")
        }
    }
}
