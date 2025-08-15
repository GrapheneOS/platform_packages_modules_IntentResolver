/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.intentresolver.contentpreview.payloadtoggle.domain.update

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentInterface
import android.content.Intent
import android.content.Intent.ACTION_CHOOSER
import android.content.Intent.ACTION_SEND
import android.content.Intent.ACTION_SEND_MULTIPLE
import android.content.Intent.EXTRA_ALTERNATE_INTENTS
import android.content.Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS
import android.content.Intent.EXTRA_CHOOSER_MODIFY_SHARE_ACTION
import android.content.Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER
import android.content.Intent.EXTRA_CHOOSER_RESULT_INTENT_SENDER
import android.content.Intent.EXTRA_CHOOSER_TARGETS
import android.content.Intent.EXTRA_EXCLUDE_COMPONENTS
import android.content.Intent.EXTRA_INTENT
import android.content.Intent.EXTRA_METADATA_TEXT
import android.content.Intent.EXTRA_STREAM
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.platform.test.flag.junit.SetFlagsRule
import android.service.chooser.AdditionalContentContract.MethodNames.ON_SELECTION_CHANGED
import android.service.chooser.ChooserAction
import android.service.chooser.ChooserTarget
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.intentresolver.contentpreview.payloadtoggle.domain.model.ValueUpdate
import com.android.intentresolver.contentpreview.payloadtoggle.domain.model.ValueUpdate.Absent
import com.android.intentresolver.domain.FakeUriGrantsManager
import com.android.intentresolver.domain.UriCallerReadAccessValidator
import com.google.common.truth.Correspondence
import com.google.common.truth.Correspondence.BinaryPredicate
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.IllegalArgumentException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class SelectionChangeCallbackImplTest {
    @get:Rule val setFlagsRule = SetFlagsRule()

    private val uri = Uri.parse("content://org.pkg/content-provider")
    private val chooserIntent = Intent(ACTION_CHOOSER)
    private val contentResolver = mock<ContentInterface>()
    private val context = InstrumentationRegistry.getInstrumentation().context

    private val uriCallerAccessValidator =
        UriCallerReadAccessValidator(FakeUriGrantsManager(), 1234, "org.package")

    @Test
    fun testPayloadChangeCallbackContact() = runTest {
        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val u1 = createUri(1)
        val u2 = createUri(2)
        val targetIntent =
            Intent(ACTION_SEND_MULTIPLE).apply {
                val uris =
                    ArrayList<Uri>().apply {
                        add(u1)
                        add(u2)
                    }
                putExtra(EXTRA_STREAM, uris)
                type = "image/jpg"
            }
        testSubject.onSelectionChanged(targetIntent)

        val authorityCaptor = argumentCaptor<String>()
        val methodCaptor = argumentCaptor<String>()
        val argCaptor = argumentCaptor<String>()
        val extraCaptor = argumentCaptor<Bundle>()
        verify(contentResolver, times(1))
            .call(
                authorityCaptor.capture(),
                methodCaptor.capture(),
                argCaptor.capture(),
                extraCaptor.capture(),
            )
        assertWithMessage("Wrong additional content provider authority")
            .that(authorityCaptor.firstValue)
            .isEqualTo(uri.authority)
        assertWithMessage("Wrong additional content provider #call() method name")
            .that(methodCaptor.firstValue)
            .isEqualTo(ON_SELECTION_CHANGED)
        assertWithMessage("Wrong additional content provider argument value")
            .that(argCaptor.firstValue)
            .isEqualTo(uri.toString())
        val extraBundle = extraCaptor.firstValue
        assertWithMessage("Additional content provider #call() should have a non-null extras arg.")
            .that(extraBundle)
            .isNotNull()
        val argChooserIntent = extraBundle.getParcelable(EXTRA_INTENT, Intent::class.java)
        assertWithMessage("#call() extras arg. should contain Intent#EXTRA_INTENT")
            .that(argChooserIntent)
            .isNotNull()
        requireNotNull(argChooserIntent)
        assertWithMessage("#call() extras arg's Intent#EXTRA_INTENT should be a Chooser intent")
            .that(argChooserIntent.action)
            .isEqualTo(chooserIntent.action)
        val argTargetIntent = argChooserIntent.getParcelableExtra(EXTRA_INTENT, Intent::class.java)
        assertWithMessage(
                "A chooser intent passed into #call() method should contain updated target intent"
            )
            .that(argTargetIntent)
            .isNotNull()
        requireNotNull(argTargetIntent)
        assertWithMessage("Incorrect target intent")
            .that(argTargetIntent.action)
            .isEqualTo(targetIntent.action)
        assertWithMessage("Incorrect target intent")
            .that(argTargetIntent.getParcelableArrayListExtra(EXTRA_STREAM, Uri::class.java))
            .containsExactly(u1, u2)
            .inOrder()
    }

    @Test
    fun testPayloadChangeCallbackUpdatesCustomActions() = runTest {
        val a1 =
            ChooserAction.Builder(
                    Icon.createWithContentUri(createUri(10)),
                    "Action 1",
                    PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent("test"),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
        val a2 =
            ChooserAction.Builder(
                    Icon.createWithContentUri(createUri(11)),
                    "Action 2",
                    PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent("test"),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply { putParcelableArray(EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(a1, a2)) }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND_MULTIPLE)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertWithMessage("Unexpected custom actions")
            .that(result.customActions.getOrThrow().map { it.icon to it.label })
            .containsExactly(a1.icon to a1.label, a2.icon to a2.label)
            .inOrder()

        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesReselectionAction() = runTest {
        val modifyShare =
            ChooserAction.Builder(
                    Icon.createWithContentUri(createUri(10)),
                    "Modify Share",
                    PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent("test"),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply { putParcelable(EXTRA_CHOOSER_MODIFY_SHARE_ACTION, modifyShare) }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertWithMessage("Unexpected modify share action: wrong icon")
            .that(result.modifyShareAction.getOrThrow()?.icon)
            .isEqualTo(modifyShare.icon)
        assertWithMessage("Unexpected modify share action: wrong label")
            .that(result.modifyShareAction.getOrThrow()?.label)
            .isEqualTo(modifyShare.label)

        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesAlternateIntents() = runTest {
        val alternateIntents =
            arrayOf(
                Intent(ACTION_SEND_MULTIPLE).apply {
                    addCategory("test")
                    type = ""
                }
            )
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply { putParcelableArray(EXTRA_ALTERNATE_INTENTS, alternateIntents) }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertWithMessage("Wrong number of alternate intents")
            .that(result.alternateIntents.getOrThrow())
            .hasSize(1)
        assertWithMessage("Wrong alternate intent: action")
            .that(result.alternateIntents.getOrThrow()[0].action)
            .isEqualTo(alternateIntents[0].action)
        assertWithMessage("Wrong alternate intent: categories")
            .that(result.alternateIntents.getOrThrow()[0].categories)
            .containsExactlyElementsIn(alternateIntents[0].categories)
        assertWithMessage("Wrong alternate intent: mime type")
            .that(result.alternateIntents.getOrThrow()[0].type)
            .isEqualTo(alternateIntents[0].type)

        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesCallerTargets() = runTest {
        val t1 =
            ChooserTarget(
                "Target 1",
                Icon.createWithContentUri(createUri(1)),
                0.99f,
                ComponentName("org.pkg.app", ".ClassA"),
                null,
            )
        val t2 =
            ChooserTarget(
                "Target 2",
                Icon.createWithContentUri(createUri(1)),
                1f,
                ComponentName("org.pkg.app", ".ClassB"),
                null,
            )
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply { putParcelableArray(EXTRA_CHOOSER_TARGETS, arrayOf(t1, t2)) }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertWithMessage("Wrong caller targets")
            .that(result.callerTargets.getOrThrow())
            .comparingElementsUsing(
                Correspondence.from(
                    BinaryPredicate<ChooserTarget?, ChooserTarget> { actual, expected ->
                        expected.componentName == actual?.componentName &&
                            expected.title == actual?.title &&
                            expected.icon == actual?.icon &&
                            expected.score == actual?.score
                    },
                    "",
                )
            )
            .containsExactly(t1, t2)
            .inOrder()

        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesRefinementIntentSender() = runTest {
        val broadcast =
            PendingIntent.getBroadcast(context, 1, Intent("test"), PendingIntent.FLAG_IMMUTABLE)

        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply {
                    putParcelable(EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, broadcast.intentSender)
                }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender.getOrThrow()).isNotNull()
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesResultIntentSender() = runTest {
        val broadcast =
            PendingIntent.getBroadcast(context, 1, Intent("test"), PendingIntent.FLAG_IMMUTABLE)

        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply {
                    putParcelable(EXTRA_CHOOSER_RESULT_INTENT_SENDER, broadcast.intentSender)
                }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender.getOrThrow()).isNotNull()
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesMetadataTextWithEnabledFlag_valueUpdated() = runTest {
        val metadataText = "[Metadata]"
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(Bundle().apply { putCharSequence(EXTRA_METADATA_TEXT, metadataText) })

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText.getOrThrow()).isEqualTo(metadataText)
        assertThat(result.excludeComponents).isEqualTo(Absent)
    }

    @Test
    fun testPayloadChangeCallbackUpdatesExcludedComponents_valueUpdated() = runTest {
        val excludedComponent = ComponentName("org.pkg.app", "org.pkg.app.TheClass")
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply {
                    putParcelableArray(EXTRA_EXCLUDE_COMPONENTS, arrayOf(excludedComponent))
                }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)

        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertThat(result.customActions).isEqualTo(Absent)
        assertThat(result.modifyShareAction).isEqualTo(Absent)
        assertThat(result.alternateIntents).isEqualTo(Absent)
        assertThat(result.callerTargets).isEqualTo(Absent)
        assertThat(result.refinementIntentSender).isEqualTo(Absent)
        assertThat(result.resultIntentSender).isEqualTo(Absent)
        assertThat(result.metadataText).isEqualTo(Absent)
        assertThat(result.excludeComponents.getOrThrow()).containsExactly(excludedComponent)
    }

    @Test
    fun testPayloadChangeCallbackProvidesInvalidData_invalidDataIgnored() = runTest {
        whenever(contentResolver.call(any<String>(), any(), any(), any()))
            .thenReturn(
                Bundle().apply {
                    putParcelableArrayList(EXTRA_CHOOSER_CUSTOM_ACTIONS, ArrayList<ChooserAction>())
                    putParcelable(EXTRA_CHOOSER_MODIFY_SHARE_ACTION, createUri(1))
                    putParcelableArrayList(EXTRA_ALTERNATE_INTENTS, ArrayList<Intent>())
                    putParcelableArrayList(EXTRA_CHOOSER_TARGETS, ArrayList<ChooserTarget>())
                    putParcelable(EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, createUri(2))
                    putParcelable(EXTRA_CHOOSER_RESULT_INTENT_SENDER, createUri(1))
                    putInt(EXTRA_METADATA_TEXT, 123)
                }
            )

        val testSubject =
            SelectionChangeCallbackImpl(
                uri,
                chooserIntent,
                contentResolver,
                uriCallerAccessValidator,
            )

        val targetIntent = Intent(ACTION_SEND)
        val result = testSubject.onSelectionChanged(targetIntent)
        assertWithMessage("Callback result should not be null").that(result).isNotNull()
        requireNotNull(result)
        assertThat(result.customActions.getOrThrow()).isEmpty()
        assertThat(result.modifyShareAction.getOrThrow()).isNull()
        assertThat(result.alternateIntents.getOrThrow()).isEmpty()
        assertThat(result.callerTargets.getOrThrow()).isEmpty()
        assertThat(result.refinementIntentSender.getOrThrow()).isNull()
        assertThat(result.resultIntentSender.getOrThrow()).isNull()
        assertThat(result.metadataText.getOrThrow()).isNull()
    }

    @Test
    fun testContentProviderUriIsNotAccessibleToTheCaller_nullShareouselUpdateReturned() = runTest {
        whenever(contentResolver.call(any<String>(), any(), any(), any())).thenReturn(Bundle())
        val uriReadAccessValidator =
            UriCallerReadAccessValidator(
                FakeUriGrantsManager(mutableSetOf(uri)),
                1234,
                "org.pkg.app",
            )
        val testSubject =
            SelectionChangeCallbackImpl(uri, chooserIntent, contentResolver, uriReadAccessValidator)

        assertThat(testSubject.onSelectionChanged(Intent(ACTION_SEND))).isNull()
    }
}

private fun <T> ValueUpdate<T>.getOrThrow(): T =
    when (this) {
        is ValueUpdate.Value -> value
        else -> throw IllegalArgumentException("Value is expected")
    }

private fun createUri(id: Int) = Uri.parse("content://org.pkg.images/$id.png")
