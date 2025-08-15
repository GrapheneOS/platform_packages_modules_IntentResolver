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

package com.android.intentresolver.contentpreview.payloadtoggle.domain.update

import android.content.ComponentName
import android.content.ContentInterface
import android.content.Intent
import android.content.Intent.EXTRA_ALTERNATE_INTENTS
import android.content.Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS
import android.content.Intent.EXTRA_CHOOSER_MODIFY_SHARE_ACTION
import android.content.Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER
import android.content.Intent.EXTRA_CHOOSER_RESULT_INTENT_SENDER
import android.content.Intent.EXTRA_CHOOSER_TARGETS
import android.content.Intent.EXTRA_EXCLUDE_COMPONENTS
import android.content.Intent.EXTRA_INTENT
import android.content.Intent.EXTRA_METADATA_TEXT
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.service.chooser.AdditionalContentContract.MethodNames.ON_SELECTION_CHANGED
import android.service.chooser.ChooserAction
import android.service.chooser.ChooserTarget
import com.android.intentresolver.contentpreview.payloadtoggle.domain.model.ShareouselUpdate
import com.android.intentresolver.contentpreview.payloadtoggle.domain.model.ValueUpdate
import com.android.intentresolver.domain.UriCallerReadAccessValidator
import com.android.intentresolver.inject.AdditionalContent
import com.android.intentresolver.inject.ChooserIntent
import com.android.intentresolver.ui.viewmodel.readAlternateIntents
import com.android.intentresolver.ui.viewmodel.readChooserActions
import com.android.intentresolver.validation.Invalid
import com.android.intentresolver.validation.Valid
import com.android.intentresolver.validation.ValidationResult
import com.android.intentresolver.validation.log
import com.android.intentresolver.validation.types.array
import com.android.intentresolver.validation.types.value
import com.android.intentresolver.validation.validateFrom
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SelectionChangeCallback"

/**
 * Encapsulates payload change callback invocation to the sharing app; handles callback arguments
 * and result format mapping.
 */
fun interface SelectionChangeCallback {
    suspend fun onSelectionChanged(targetIntent: Intent): ShareouselUpdate?
}

class SelectionChangeCallbackImpl
@Inject
constructor(
    @AdditionalContent private val uri: Uri,
    @ChooserIntent private val chooserIntent: Intent,
    private val contentResolver: ContentInterface,
    private val uriAccessValidator: UriCallerReadAccessValidator,
) : SelectionChangeCallback {
    private val mutex = Mutex()
    private val validatedUri by lazy { if (uriAccessValidator.checkAccess(uri)) uri else null }

    override suspend fun onSelectionChanged(targetIntent: Intent): ShareouselUpdate? =
        validatedUri?.let { uri -> onSelectionChanged(targetIntent, uri) }

    private suspend fun onSelectionChanged(targetIntent: Intent, safeUri: Uri): ShareouselUpdate? =
        mutex
            .withLock {
                contentResolver.call(
                    requireNotNull(safeUri.authority) { "URI authority can not be null" },
                    ON_SELECTION_CHANGED,
                    safeUri.toString(),
                    Bundle().apply {
                        putParcelable(
                            EXTRA_INTENT,
                            Intent(chooserIntent).apply { putExtra(EXTRA_INTENT, targetIntent) },
                        )
                    },
                )
            }
            ?.let { bundle ->
                return when (val result = readCallbackResponse(bundle)) {
                    is Valid -> {
                        result.warnings.forEach { it.log(TAG) }
                        result.value
                    }

                    is Invalid -> {
                        result.errors.forEach { it.log(TAG) }
                        null
                    }
                }
            }
}

private fun readCallbackResponse(bundle: Bundle): ValidationResult<ShareouselUpdate> {
    return validateFrom(bundle::get) {
        // An error is treated as an empty collection or null as the presence of a value indicates
        // an intention to change the old value implying that the old value is obsolete (and should
        // not be used).
        val customActions =
            bundle.readValueUpdate(EXTRA_CHOOSER_CUSTOM_ACTIONS) {
                readChooserActions() ?: emptyList()
            }
        val modifyShareAction =
            bundle.readValueUpdate(EXTRA_CHOOSER_MODIFY_SHARE_ACTION) { key ->
                optional(value<ChooserAction>(key))
            }
        val alternateIntents =
            bundle.readValueUpdate(EXTRA_ALTERNATE_INTENTS) {
                readAlternateIntents() ?: emptyList()
            }
        val callerTargets =
            bundle.readValueUpdate(EXTRA_CHOOSER_TARGETS) { key ->
                optional(array<ChooserTarget>(key)) ?: emptyList()
            }
        val refinementIntentSender =
            bundle.readValueUpdate(EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER) { key ->
                optional(value<IntentSender>(key))
            }
        val resultIntentSender =
            bundle.readValueUpdate(EXTRA_CHOOSER_RESULT_INTENT_SENDER) { key ->
                optional(value<IntentSender>(key))
            }
        val metadataText =
            bundle.readValueUpdate(EXTRA_METADATA_TEXT) { key ->
                optional(value<CharSequence>(key))
            }
        val excludedComponents: ValueUpdate<List<ComponentName>> =
            bundle.readValueUpdate(EXTRA_EXCLUDE_COMPONENTS) { key ->
                optional(array<ComponentName>(key)) ?: emptyList()
            }

        ShareouselUpdate(
            customActions,
            modifyShareAction,
            alternateIntents,
            callerTargets,
            refinementIntentSender,
            resultIntentSender,
            metadataText,
            excludedComponents,
        )
    }
}

private inline fun <reified T> Bundle.readValueUpdate(
    key: String,
    block: (String) -> T,
): ValueUpdate<T> =
    if (containsKey(key)) {
        ValueUpdate.Value(block(key))
    } else {
        ValueUpdate.Absent
    }

@Module
@InstallIn(ViewModelComponent::class)
interface SelectionChangeCallbackModule {
    @Binds fun bind(impl: SelectionChangeCallbackImpl): SelectionChangeCallback
}
