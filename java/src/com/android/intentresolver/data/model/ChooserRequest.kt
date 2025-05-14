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
package com.android.intentresolver.data.model

import android.content.ComponentName
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.Intent.ACTION_SEND_MULTIPLE
import android.content.Intent.EXTRA_REFERRER
import android.content.IntentFilter
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.service.chooser.ChooserAction
import android.service.chooser.ChooserTarget
import androidx.annotation.StringRes
import com.android.intentresolver.ContentTypeHint
import com.android.intentresolver.IChooserInteractiveSessionCallback
import com.android.intentresolver.ext.hasAction
import com.android.intentresolver.util.sanitizePayloadIntents
import com.android.systemui.shared.Flags.screenshotContextUrl

const val ANDROID_APP_SCHEME = "android-app"

/** All of the things that are consumed from an incoming share Intent (+Extras). */
data class ChooserRequest(
    /** Required. Represents the content being sent. */
    val targetIntent: Intent,

    /** The action from [targetIntent] as retrieved with [Intent.getAction]. */
    val targetAction: String? = targetIntent.action,

    /**
     * Whether [targetAction] is ACTION_SEND or ACTION_SEND_MULTIPLE. These are considered the
     * canonical "Share" actions. When handling other actions, this flag controls behavioral and
     * visual changes.
     */
    val isSendActionTarget: Boolean = targetIntent.hasAction(ACTION_SEND, ACTION_SEND_MULTIPLE),

    /** The top-level content type as retrieved using [Intent.getType]. */
    val targetType: String? = targetIntent.type,

    /** The package name of the app which started the current activity instance. */
    val launchedFromPackage: String,

    /** A custom tile for the main UI. Ignored when the intent is ACTION_SEND(_MULTIPLE). */
    val title: CharSequence? = null,

    /** A String resource ID to load when [title] is null. */
    @get:StringRes val defaultTitleResource: Int = 0,

    /**
     * The referrer value as received by the caller. It may have been supplied via [EXTRA_REFERRER]
     * or synthesized from callerPackageName. This value is merged into outgoing intents.
     */
    val referrer: Uri? = null,

    /**
     * Choices to exclude from results.
     *
     * Any resolved intents with a component in this list will be omitted before presentation.
     */
    val filteredComponentNames: List<ComponentName> = emptyList(),

    /**
     * App provided shortcut share intents (aka "direct share targets")
     *
     * Normally share shortcuts are published and consumed using
     * [ShortcutManager][android.content.pm.ShortcutManager]. This is an alternate channel to allow
     * apps to directly inject the same information.
     *
     * Historical note: This option was initially integrated with other results from the
     * ChooserTargetService API (since deprecated and removed), hence the name and data format.
     * These are more correctly called "Share Shortcuts" now.
     */
    val callerChooserTargets: List<ChooserTarget> = emptyList(),

    /**
     * Actions the user may perform. These are presented as separate affordances from the main list
     * of choices. Selecting a choice is a terminal action which results in finishing. The item
     * limit is [MAX_CHOOSER_ACTIONS]. This may be further constrained as appropriate.
     */
    val chooserActions: List<ChooserAction> = emptyList(),

    /**
     * An action to start an Activity which for user updating of shared content. Selection is a
     * terminal action, closing the current activity and launching the target of the action.
     */
    val modifyShareAction: ChooserAction? = null,

    /**
     * When false the host activity will be [finished][android.app.Activity.finish] when stopped.
     */
    @get:JvmName("shouldRetainInOnStop") val shouldRetainInOnStop: Boolean = false,

    /**
     * Intents which contain alternate representations of the content being shared. Any results from
     * resolving these _alternate_ intents are included with the results of the primary intent as
     * additional choices (e.g. share as image content vs. link to content).
     */
    val additionalTargets: List<Intent> = emptyList(),

    /**
     * Alternate [extras][Intent.getExtras] to substitute when launching a selected app.
     *
     * For a given app (by package name), the Bundle describes what parameters to substitute when
     * that app is selected.
     *
     * // TODO: Map<String, Bundle>
     */
    val replacementExtras: Bundle? = null,

    /**
     * App-supplied choices to be presented first in the list.
     *
     * Custom labels and icons may be supplied using
     * [LabeledIntent][android.content.pm.LabeledIntent].
     *
     * Limit 2.
     */
    val initialIntents: List<Intent> = emptyList(),

    /**
     * Provides for callers to be notified when a component is selected.
     *
     * The selection is reported in the Intent as [Intent.EXTRA_CHOSEN_COMPONENT] with the
     * [ComponentName] of the item.
     */
    val chosenComponentSender: IntentSender? = null,

    /**
     * Provides a mechanism for callers to post-process a target when a selection is made.
     *
     * The received intent will contain:
     * * **EXTRA_INTENT** The chosen target
     * * **EXTRA_ALTERNATE_INTENTS** Additional intents which also match the target
     * * **EXTRA_RESULT_RECEIVER** A [ResultReceiver][android.os.ResultReceiver] providing a
     *   mechanism for the caller to return information. An updated intent to send must be included
     *   as [Intent.EXTRA_INTENT].
     */
    val refinementIntentSender: IntentSender? = null,

    /**
     * Contains the text content to share supplied by the source app.
     *
     * TODO: Constrain length?
     */
    val sharedText: CharSequence? = null,
    /** Contains title to the text content to share supplied by the source app. */
    val sharedTextTitle: CharSequence? = null,

    /**
     * Supplied to
     * [ShortcutManager.getShareTargets][android.content.pm.ShortcutManager.getShareTargets] to
     * query for matching shortcuts. Specifically, only the [dataTypes][IntentFilter.hasDataType]
     * are considered for matching share shortcuts currently.
     */
    val shareTargetFilter: IntentFilter? = null,

    /** A URI for additional content */
    val additionalContentUri: Uri? = null,

    /** Focused item index (from target intent's STREAM_EXTRA) */
    val focusedItemPosition: Int = 0,

    /** Value for [Intent.EXTRA_CHOOSER_CONTENT_TYPE_HINT] on the incoming chooser intent. */
    val contentTypeHint: ContentTypeHint = ContentTypeHint.NONE,

    /**
     * Metadata to be shown to the user as a part of the sharesheet window.
     *
     * Specified by the [Intent.EXTRA_METADATA_TEXT]
     */
    val metadataText: CharSequence? = null,
    val interactiveSessionCallback: IChooserInteractiveSessionCallback? = null,
) {
    val referrerPackage = referrer?.takeIf { it.scheme == ANDROID_APP_SCHEME }?.authority

    fun getReferrerFillInIntent(): Intent {
        return Intent().apply {
            referrerPackage?.also { pkg ->
                putExtra(EXTRA_REFERRER, Uri.parse("$ANDROID_APP_SCHEME://$pkg"))
            }
        }
    }

    val payloadIntents = listOf(targetIntent) + additionalTargets

    /**
     * Payload intents that should be used for cross-profile sharing.
     *
     * These intents are a copy of `payloadIntents`. For security reasons, explicit targeting
     * information is removed from each [Intent] in the list, as well as from its
     * [selector][Intent.getSelector]. Specifically, the values that would be returned by
     * [Intent.getPackage] and [Intent.getComponent] are cleared for both the main intent and its
     * selector. This sanitization is performed because explicit intents could otherwise be used to
     * bypass the device's cross-profile sharing policy settings.
     */
    val crossProfilePayloadIntents by lazy { sanitizePayloadIntents(payloadIntents) }

    val callerAllowsTextToggle =
        screenshotContextUrl() && "com.android.systemui".equals(referrerPackage)
}
