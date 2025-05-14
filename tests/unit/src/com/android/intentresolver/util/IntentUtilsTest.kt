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

package com.android.intentresolver.util

import android.content.ComponentName
import android.content.Intent
import android.content.Intent.ACTION_SEND
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntentUtilsTest {
    @Test
    fun test_sanitizePayloadIntents() {
        val intents =
            listOf(
                Intent(ACTION_SEND).apply { setPackage("org.test.example") },
                Intent(ACTION_SEND).apply {
                    setComponent(
                        ComponentName.unflattenFromString("org.test.example/.TestActivity")
                    )
                },
                Intent(ACTION_SEND).apply {
                    setSelector(Intent(ACTION_SEND).apply { setPackage("org.test.example") })
                },
                Intent(ACTION_SEND).apply {
                    setSelector(
                        Intent(ACTION_SEND).apply {
                            setComponent(
                                ComponentName.unflattenFromString("org.test.example/.TestActivity")
                            )
                        }
                    )
                },
            )

        val sanitized = sanitizePayloadIntents(intents)

        assertThat(sanitized).hasSize(intents.size)
        for (i in sanitized) {
            assertThat(i.getPackage()).isNull()
            assertThat(i.getComponent()).isNull()
            i.getSelector()?.let {
                assertThat(it.getPackage()).isNull()
                assertThat(it.getComponent()).isNull()
            }
        }
    }
}
