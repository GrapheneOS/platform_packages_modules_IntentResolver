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
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

class UriCallerReadAccessValidatorTest {
    private val accessibleUri = Uri.parse("content://org.pkg.app/ok")
    private val inaccessibleUri = Uri.parse("content://org.pkg.app/fail")
    private val exceptionUri = Uri.parse("content://org.pkg.app/exception")

    private val uriGrantsManager =
        mock<IUriGrantsManager> {
            on {
                checkGrantUriPermission_ignoreNonSystem(
                    any(),
                    any(),
                    eq(accessibleUri),
                    any(),
                    any(),
                )
            } doReturn 0

            on {
                checkGrantUriPermission_ignoreNonSystem(
                    any(),
                    any(),
                    eq(inaccessibleUri),
                    any(),
                    any(),
                )
            } doThrow SecurityException()

            on {
                checkGrantUriPermission_ignoreNonSystem(
                    any(),
                    any(),
                    eq(exceptionUri),
                    any(),
                    any(),
                )
            } doThrow RuntimeException()
        }

    private val testSubject = UriCallerReadAccessValidator(uriGrantsManager, 1234, "pkg")

    @Test
    fun test_checkAccess() {
        assertThat(testSubject.checkAccess(accessibleUri)).isTrue()
        assertThat(testSubject.checkAccess(inaccessibleUri)).isFalse()
        assertThat(testSubject.checkAccess(exceptionUri)).isFalse()
    }
}
