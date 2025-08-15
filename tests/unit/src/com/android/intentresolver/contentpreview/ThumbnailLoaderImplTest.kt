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

package com.android.intentresolver.contentpreview

import android.content.ContentResolver
import android.net.Uri
import android.util.Size
import com.android.intentresolver.domain.FakeUriGrantsManager
import com.android.intentresolver.domain.UriCallerReadAccessValidator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock

class ThumbnailLoaderImplTest {
    @Test
    fun testLoadInaccessibleUri_loadThumbnailByUriOnlyThrows() =
        testLoadThumbnailThrowsOnInaccessibleUri { uri ->
            loadThumbnail(uri)
        }

    @Test
    fun testLoadInaccessibleUri_loadThumbnailByUriAndSizeThrows() =
        testLoadThumbnailThrowsOnInaccessibleUri { uri ->
            loadThumbnail(uri, Size(100, 100))
        }

    private fun testLoadThumbnailThrowsOnInaccessibleUri(
        methodOverload: suspend ThumbnailLoaderImpl.(Uri) -> Unit
    ) = runTest {
        val uri = Uri.parse("content://org.pkg.app/image")
        var exception: SecurityException? = null
        val loader =
            ThumbnailLoaderImpl(
                mock<ContentResolver>(),
                500,
                UriCallerReadAccessValidator(
                    FakeUriGrantsManager(mutableSetOf(uri)),
                    1234,
                    "package.name",
                ),
            )
        try {
            loader.methodOverload(uri)
        } catch (e: SecurityException) {
            exception = e
        }

        assertThat(exception).isNotNull()
    }
}
