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

package com.android.intentresolver;

import static android.app.VoiceInteractor.PickOptionRequest.Option;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static androidx.lifecycle.LifecycleKt.getCoroutineScope;

import static com.android.intentresolver.ChooserActionFactory.EDIT_SOURCE;
import static com.android.intentresolver.Flags.delayDrawerOffsetCalculation;
import static com.android.intentresolver.Flags.fixShortcutsFlashingFixed;
import static com.android.intentresolver.Flags.interactiveSession;
import static com.android.intentresolver.Flags.keyboardNavigationFix;
import static com.android.intentresolver.Flags.rebuildAdaptersOnTargetPinning;
import static com.android.intentresolver.Flags.refineSystemActions;
import static com.android.intentresolver.Flags.shareouselUpdateExcludeComponentsExtra;
import static com.android.intentresolver.Flags.unselectFinalItem;
import static com.android.intentresolver.ext.CreationExtrasExtKt.replaceDefaultArgs;
import static com.android.intentresolver.profiles.MultiProfilePagerAdapter.PROFILE_PERSONAL;
import static com.android.intentresolver.profiles.MultiProfilePagerAdapter.PROFILE_WORK;
import static com.android.internal.util.LatencyTracker.ACTION_LOAD_SHARE_SHEET;

import static java.util.Objects.requireNonNull;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.VoiceInteractor;
import android.app.admin.DevicePolicyEventLogger;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.chooser.ChooserTarget;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.android.intentresolver.ChooserRefinementManager.RefinementType;
import com.android.intentresolver.chooser.DisplayResolveInfo;
import com.android.intentresolver.chooser.MultiDisplayResolveInfo;
import com.android.intentresolver.chooser.TargetInfo;
import com.android.intentresolver.contentpreview.ChooserContentPreviewUi;
import com.android.intentresolver.contentpreview.HeadlineGeneratorImpl;
import com.android.intentresolver.data.model.ChooserRequest;
import com.android.intentresolver.data.repository.ActivityModelRepository;
import com.android.intentresolver.data.repository.DevicePolicyResources;
import com.android.intentresolver.domain.interactor.UserInteractor;
import com.android.intentresolver.emptystate.CompositeEmptyStateProvider;
import com.android.intentresolver.emptystate.CrossProfileIntentsChecker;
import com.android.intentresolver.emptystate.EmptyStateProvider;
import com.android.intentresolver.emptystate.NoAppsAvailableEmptyStateProvider;
import com.android.intentresolver.emptystate.NoCrossProfileEmptyStateProvider;
import com.android.intentresolver.emptystate.WorkProfilePausedEmptyStateProvider;
import com.android.intentresolver.grid.ChooserGridAdapter;
import com.android.intentresolver.icons.Caching;
import com.android.intentresolver.icons.TargetDataLoader;
import com.android.intentresolver.inject.Background;
import com.android.intentresolver.logging.EventLog;
import com.android.intentresolver.measurements.Tracer;
import com.android.intentresolver.model.AbstractResolverComparator;
import com.android.intentresolver.model.AppPredictionServiceResolverComparator;
import com.android.intentresolver.model.ResolverRankerServiceResolverComparator;
import com.android.intentresolver.platform.AppPredictionAvailable;
import com.android.intentresolver.platform.ImageEditor;
import com.android.intentresolver.platform.NearbyShare;
import com.android.intentresolver.profiles.ChooserMultiProfilePagerAdapter;
import com.android.intentresolver.profiles.MultiProfilePagerAdapter.ProfileType;
import com.android.intentresolver.profiles.OnProfileSelectedListener;
import com.android.intentresolver.profiles.OnSwitchOnWorkSelectedListener;
import com.android.intentresolver.profiles.TabConfig;
import com.android.intentresolver.shared.model.ActivityModel;
import com.android.intentresolver.shared.model.Profile;
import com.android.intentresolver.shortcuts.AppPredictorFactory;
import com.android.intentresolver.shortcuts.ShortcutLoader;
import com.android.intentresolver.ui.ActionTitle;
import com.android.intentresolver.ui.ProfilePagerResources;
import com.android.intentresolver.ui.ShareResultSender;
import com.android.intentresolver.ui.ShareResultSenderFactory;
import com.android.intentresolver.ui.viewmodel.ChooserViewModel;
import com.android.intentresolver.widget.ActionRow;
import com.android.intentresolver.widget.ChooserNestedScrollView;
import com.android.intentresolver.widget.ImagePreviewView;
import com.android.intentresolver.widget.ResolverDrawerLayout;
import com.android.intentresolver.widget.ResolverDrawerLayoutExt;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;

import com.google.common.collect.ImmutableList;

import dagger.hilt.android.AndroidEntryPoint;

import kotlinx.coroutines.CoroutineDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * The Chooser Activity handles intent resolution specifically for sharing intents -
 * for example, as generated by {@see android.content.Intent#createChooser(Intent, CharSequence)}.
 *
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@AndroidEntryPoint(FragmentActivity.class)
public class ChooserActivity extends Hilt_ChooserActivity implements
        ResolverListAdapter.ResolverListCommunicator, PackagesChangedListener, StartsSelectedItem {
    private static final String TAG = "ChooserActivity";

    /**
     * Boolean extra to change the following behavior: Normally, ChooserActivity finishes itself
     * in onStop when launched in a new task. If this extra is set to true, we do not finish
     * ourselves when onStop gets called.
     */
    public static final String EXTRA_PRIVATE_RETAIN_IN_ON_STOP
            = "com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP";

    /**
     * Transition name for the first image preview.
     * To be used for shared element transition into this activity.
     */
    public static final String FIRST_IMAGE_PREVIEW_TRANSITION_NAME = "screenshot_preview_image";

    private static final boolean DEBUG = true;

    public static final String LAUNCH_LOCATION_DIRECT_SHARE = "direct_share";
    private static final String SHORTCUT_TARGET = "shortcut_target";

    //////////////////////////////////////////////////////////////////////////////////////////////
    // Inherited properties.
    //////////////////////////////////////////////////////////////////////////////////////////////
    private static final String TAB_TAG_PERSONAL = "personal";
    private static final String TAB_TAG_WORK = "work";

    private static final String LAST_SHOWN_PROFILE = "last_shown_tab_key";
    public static final String METRICS_CATEGORY_CHOOSER = "intent_chooser";

    private int mLayoutId;
    private UserHandle mHeaderCreatorUser;
    private boolean mRegistered;
    private PackageMonitor mPersonalPackageMonitor;
    private PackageMonitor mWorkPackageMonitor;

    protected ResolverDrawerLayout mResolverDrawerLayout;
    private TabHost mTabHost;
    private ResolverViewPager mViewPager;
    protected ChooserMultiProfilePagerAdapter mChooserMultiProfilePagerAdapter;
    protected final LatencyTracker mLatencyTracker = getLatencyTracker();

    /** See {@link #setRetainInOnStop}. */
    private boolean mRetainInOnStop;
    protected Insets mSystemWindowInsets = null;
    private ResolverActivity.PickTargetOptionRequest mPickOptionRequest;

    @Nullable
    private OnSwitchOnWorkSelectedListener mOnSwitchOnWorkSelectedListener;

    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////


    // TODO: these data structures are for one-time use in shuttling data from where they're
    // populated in `ShortcutToChooserTargetConverter` to where they're consumed in
    // `ShortcutSelectionLogic` which packs the appropriate elements into the final `TargetInfo`.
    // That flow should be refactored so that `ChooserActivity` isn't responsible for holding their
    // intermediate data, and then these members can be removed.
    private final Map<ChooserTarget, AppTarget> mDirectShareAppTargetCache = new HashMap<>();
    private final Map<ChooserTarget, ShortcutInfo> mDirectShareShortcutInfoCache = new HashMap<>();

    static final int TARGET_TYPE_DEFAULT = 0;
    static final int TARGET_TYPE_CHOOSER_TARGET = 1;
    static final int TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER = 2;
    static final int TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE = 3;

    private static final int SCROLL_STATUS_IDLE = 0;
    private static final int SCROLL_STATUS_SCROLLING_VERTICAL = 1;
    private static final int SCROLL_STATUS_SCROLLING_HORIZONTAL = 2;

    @Inject public UserInteractor mUserInteractor;
    @Inject @Background public CoroutineDispatcher mBackgroundDispatcher;
    @Inject public ChooserHelper mChooserHelper;
    @Inject public EventLog mEventLog;
    @Inject @AppPredictionAvailable public boolean mAppPredictionAvailable;
    @Inject @ImageEditor public Optional<ComponentName> mImageEditor;
    @Inject @NearbyShare public Optional<ComponentName> mNearbyShare;
    @Inject
    @Caching
    public TargetDataLoader mTargetDataLoader;
    @Inject public DevicePolicyResources mDevicePolicyResources;
    @Inject public ProfilePagerResources mProfilePagerResources;
    @Inject public PackageManager mPackageManager;
    @Inject public ClipboardManager mClipboardManager;
    @Inject public IntentForwarding mIntentForwarding;
    @Inject public ShareResultSenderFactory mShareResultSenderFactory;
    @Inject public ActivityModelRepository mActivityModelRepository;

    private ActivityModel mActivityModel;
    private ChooserRequest mRequest;
    private ProfileHelper mProfiles;
    private ProfileAvailability mProfileAvailability;
    @Nullable private ShareResultSender mShareResultSender;

    private ChooserRefinementManager mRefinementManager;

    private ChooserContentPreviewUi mChooserContentPreviewUi;

    private boolean mShouldDisplayLandscape;
    private long mChooserShownTime;
    protected boolean mIsSuccessfullySelected;

    private int mCurrAvailableWidth = 0;
    private Insets mLastAppliedInsets = null;
    private int mLastNumberOfChildren = -1;
    private int mMaxTargetsPerRow = 1;

    private static final int MAX_LOG_RANK_POSITION = 12;

    // TODO: are these used anywhere? They should probably be migrated to ChooserRequestParameters.
    private static final int MAX_EXTRA_INITIAL_INTENTS = 2;
    private static final int MAX_EXTRA_CHOOSER_TARGETS = 2;

    private SharedPreferences mPinnedSharedPrefs;
    private static final String PINNED_SHARED_PREFS_NAME = "chooser_pin_settings";

    private final ExecutorService mBackgroundThreadPoolExecutor = Executors.newFixedThreadPool(5);

    private int mScrollStatus = SCROLL_STATUS_IDLE;

    private final EnterTransitionAnimationDelegate mEnterTransitionAnimationDelegate =
            new EnterTransitionAnimationDelegate(this, () -> mResolverDrawerLayout);

    private final Map<Integer, ProfileRecord> mProfileRecords = new LinkedHashMap<>();

    private boolean mExcludeSharedText = false;
    /**
     * When we intend to finish the activity with a shared element transition, we can't immediately
     * finish() when the transition is invoked, as the receiving end may not be able to start the
     * animation and the UI breaks if this takes too long. Instead we defer finishing until onStop
     * in order to wait for the transition to begin.
     */
    private boolean mFinishWhenStopped = false;

    private final AtomicLong mIntentReceivedTime = new AtomicLong(-1);

    protected ActivityModel createActivityModel() {
        return ActivityModel.createFrom(this);
    }

    private ChooserViewModel mViewModel;

    @NonNull
    @Override
    public CreationExtras getDefaultViewModelCreationExtras() {
        // DEFAULT_ARGS_KEY extra is saved for each ViewModel we create. ComponentActivity puts the
        // initial intent's extra into DEFAULT_ARGS_KEY thus we store these values 2 times (3 if we
        // count the initial intent). We don't need those values to be saved as they don't capture
        // the state.
        return replaceDefaultArgs(super.getDefaultViewModelCreationExtras());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        mActivityModelRepository.initialize(this::createActivityModel);

        setTheme(R.style.Theme_DeviceDefault_Chooser);

        // Initializer is invoked when this function returns, via Lifecycle.
        mChooserHelper.setInitializer(this::initialize);
        mChooserHelper.setOnChooserRequestChanged(this::onChooserRequestChanged);
        mChooserHelper.setOnPendingSelection(this::onPendingSelection);
        if (unselectFinalItem()) {
            mChooserHelper.setOnHasSelections(this::onHasSelections);
        }
    }
    private int mInitialProfile = -1;

    @Override
    protected final void onStart() {
        super.onStart();
        this.getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    @Override
    protected final void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: " + getComponentName().flattenToShortString());
        mFinishWhenStopped = false;
        mRefinementManager.onActivityResume();
    }

    @Override
    protected final void onStop() {
        super.onStop();

        final Window window = this.getWindow();
        final WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.privateFlags &= ~SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        window.setAttributes(attrs);

        if (mRegistered) {
            mPersonalPackageMonitor.unregister();
            if (mWorkPackageMonitor != null) {
                mWorkPackageMonitor.unregister();
            }
            mRegistered = false;
        }
        final Intent intent = getIntent();
        if ((intent.getFlags() & FLAG_ACTIVITY_NEW_TASK) != 0 && !isVoiceInteraction()
                && !mRetainInOnStop) {
            // This resolver is in the unusual situation where it has been
            // launched at the top of a new task.  We don't let it be added
            // to the recent tasks shown to the user, and we need to make sure
            // that each time we are launched we get the correct launching
            // uid (not re-using the same resolver from an old launching uid),
            // so we will now finish ourself since being no longer visible,
            // the user probably can't get back to us.
            if (!isChangingConfigurations()) {
                Log.d(TAG, "finishing in onStop");
                finish();
            }
        }

        if (mRefinementManager != null) {
            mRefinementManager.onActivityStop(isChangingConfigurations());
        }

        if (mFinishWhenStopped) {
            mFinishWhenStopped = false;
            finish();
        }
    }

    @Override
    protected final void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mViewPager != null) {
            outState.putInt(
                    LAST_SHOWN_PROFILE, mChooserMultiProfilePagerAdapter.getActiveProfile());
        }
    }

    @Override
    protected final void onRestart() {
        super.onRestart();
        if (mChooserMultiProfilePagerAdapter.hasPageForProfile(Profile.Type.PRIVATE.ordinal())
                && !mProfileAvailability.isAvailable(mProfiles.getPrivateProfile())) {
            Log.d(TAG, "Exiting due to unavailable profile");
            finish();
            return;
        }

        if (!mRegistered) {
            mPersonalPackageMonitor.register(
                    this,
                    getMainLooper(),
                    mProfiles.getPersonalHandle(),
                    false);
            if (mProfiles.getWorkProfilePresent()) {
                if (mWorkPackageMonitor == null) {
                    mWorkPackageMonitor = createPackageMonitor(
                            mChooserMultiProfilePagerAdapter.getWorkListAdapter());
                }
                mWorkPackageMonitor.register(
                        this,
                        getMainLooper(),
                        mProfiles.getWorkHandle(),
                        false);
            }
            mRegistered = true;
        }
        mChooserMultiProfilePagerAdapter.getActiveListAdapter().handlePackagesChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations() && mPickOptionRequest != null) {
            mPickOptionRequest.cancel();
        }
        if (mChooserMultiProfilePagerAdapter != null) {
            mChooserMultiProfilePagerAdapter.destroy();
        }

        if (isFinishing()) {
            mLatencyTracker.onActionCancel(ACTION_LOAD_SHARE_SHEET);
            if (interactiveSession() && mViewModel != null) {
                mViewModel.getInteractiveSessionInteractor().endSession();
            }
        }

        mBackgroundThreadPoolExecutor.shutdownNow();

        destroyProfileRecords();
    }

    /** DO NOT CALL. Only for use from ChooserHelper as a callback. */
    private void initialize() {

        mViewModel = new ViewModelProvider(this).get(ChooserViewModel.class);
        mRequest = mViewModel.getRequest().getValue();
        mActivityModel = mViewModel.getActivityModel();

        mProfiles =  new ProfileHelper(
                mUserInteractor,
                mBackgroundDispatcher);

        mProfileAvailability = new ProfileAvailability(
                mUserInteractor,
                getCoroutineScope(getLifecycle()),
                mBackgroundDispatcher);

        mProfileAvailability.setOnProfileStatusChange(this::onWorkProfileStatusUpdated);

        mIntentReceivedTime.set(System.currentTimeMillis());
        mLatencyTracker.onActionStart(ACTION_LOAD_SHARE_SHEET);

        mPinnedSharedPrefs = getPinnedSharedPrefs(this);
        updateShareResultSender();

        mMaxTargetsPerRow =
                getResources().getInteger(R.integer.config_chooser_max_targets_per_row);
        mShouldDisplayLandscape =
                shouldDisplayLandscape(getResources().getConfiguration().orientation);

        setRetainInOnStop(mRequest.shouldRetainInOnStop());
        createProfileRecords(
                new AppPredictorFactory(
                        this,
                        Objects.toString(mRequest.getSharedText(), null),
                        mRequest.getShareTargetFilter(),
                        mAppPredictionAvailable
                ),
                mRequest.getShareTargetFilter()
        );


        mChooserMultiProfilePagerAdapter = createMultiProfilePagerAdapter(
                /* context = */ this,
                mProfilePagerResources,
                mRequest,
                mProfiles,
                mProfileRecords.values(),
                mProfileAvailability,
                mMaxTargetsPerRow);

        maybeDisableRecentsScreenshot(mProfiles, mProfileAvailability);

        if (!configureContentView(mTargetDataLoader)) {
            mPersonalPackageMonitor = createPackageMonitor(
                    mChooserMultiProfilePagerAdapter.getPersonalListAdapter());
            mPersonalPackageMonitor.register(
                    this,
                    getMainLooper(),
                    mProfiles.getPersonalHandle(),
                    false
            );
            if (mProfiles.getWorkProfilePresent()) {
                mWorkPackageMonitor = createPackageMonitor(
                        mChooserMultiProfilePagerAdapter.getWorkListAdapter());
                mWorkPackageMonitor.register(
                        this,
                        getMainLooper(),
                        mProfiles.getWorkHandle(),
                        false
                );
            }
            mRegistered = true;
            final ResolverDrawerLayout rdl = findViewById(
                    com.android.internal.R.id.contentPanel);
            if (rdl != null) {
                rdl.setOnDismissedListener(new ResolverDrawerLayout.OnDismissedListener() {
                    @Override
                    public void onDismissed() {
                        finish();
                    }
                });

                boolean hasTouchScreen = mPackageManager
                        .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

                if (isVoiceInteraction() || !hasTouchScreen) {
                    rdl.setCollapsed(false);
                }

                rdl.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                rdl.setOnApplyWindowInsetsListener(this::onApplyWindowInsets);

                mResolverDrawerLayout = rdl;
            }

            Intent intent = mRequest.getTargetIntent();
            final Set<String> categories = intent.getCategories();
            MetricsLogger.action(this,
                    mChooserMultiProfilePagerAdapter.getActiveListAdapter().hasFilteredItem()
                            ? MetricsEvent.ACTION_SHOW_APP_DISAMBIG_APP_FEATURED
                            : MetricsEvent.ACTION_SHOW_APP_DISAMBIG_NONE_FEATURED,
                    intent.getAction() + ":" + intent.getType() + ":"
                            + (categories != null ? Arrays.toString(categories.toArray())
                            : ""));
        }

        getEventLog().logSharesheetTriggered();
        mRefinementManager = new ViewModelProvider(this).get(ChooserRefinementManager.class);
        mRefinementManager.getRefinementCompletion().observe(this, completion -> {
            if (completion.consume()) {
                if (completion.getRefinedIntent() == null) {
                    finish();
                    return;
                }

                // Prepare to regenerate our "system actions" based on the refined intent.
                // TODO: optimize if needed. `TARGET_INFO` cases don't require a new action
                // factory at all. And if we break up `ChooserActionFactory`, we could avoid
                // resolving a new editor intent unless we're handling an `EDIT_ACTION`.
                ChooserActionFactory refinedActionFactory =
                        createChooserActionFactory(completion.getRefinedIntent());
                switch (completion.getType()) {
                    case TARGET_INFO: {
                        TargetInfo refinedTarget = completion
                                .getOriginalTargetInfo()
                                .tryToCloneWithAppliedRefinement(
                                        completion.getRefinedIntent());
                        if (refinedTarget == null) {
                            Log.e(TAG, "Failed to apply refinement to any matching source intent");
                        } else {
                            maybeRemoveSharedText(refinedTarget);

                            // We already block suspended targets from going to refinement, and we
                            // probably can't recover a Chooser session if that's the reason the
                            // refined target fails to launch now. Fire-and-forget the refined
                            // launch, and make sure Sharesheet gets cleaned up regardless of the
                            // outcome of that launch.launch; ignore

                            safelyStartActivity(refinedTarget);
                        }
                    }
                    break;

                    case COPY_ACTION: {
                        if (refinedActionFactory.getCopyButtonRunnable() != null) {
                            refinedActionFactory.getCopyButtonRunnable().run();
                        }
                    }
                    break;

                    case EDIT_ACTION: {
                        if (refinedActionFactory.getEditButtonRunnable() != null) {
                            refinedActionFactory.getEditButtonRunnable().run();
                        }
                    }
                    break;
                }

                finish();
            }
        });
        ChooserContentPreviewUi.ActionFactory actionFactory =
                decorateActionFactoryWithRefinement(
                        createChooserActionFactory(mRequest.getTargetIntent()));
        mChooserContentPreviewUi = new ChooserContentPreviewUi(
                getCoroutineScope(getLifecycle()),
                mViewModel.getPreviewDataProvider(),
                mRequest,
                mViewModel.getImageLoader(),
                actionFactory,
                createModifyShareActionFactory(),
                mEnterTransitionAnimationDelegate,
                new HeadlineGeneratorImpl(this),
                mRequest.getContentTypeHint(),
                mRequest.getMetadataText());
        updateStickyContentPreview();
        if (shouldShowStickyContentPreview()) {
            getEventLog().logActionShareWithPreview(
                    mChooserContentPreviewUi.getPreferredContentPreview());
        }
        mChooserShownTime = System.currentTimeMillis();
        final long systemCost = mChooserShownTime - mIntentReceivedTime.get();
        getEventLog().logChooserActivityShown(
                isWorkProfile(), mRequest.getTargetType(), systemCost);
        if (mResolverDrawerLayout != null) {
            mResolverDrawerLayout.addOnLayoutChangeListener(this::handleLayoutChange);

            mResolverDrawerLayout.setOnCollapsedChangedListener(
                    isCollapsed -> {
                        mChooserMultiProfilePagerAdapter.setIsCollapsed(isCollapsed);
                        getEventLog().logSharesheetExpansionChanged(isCollapsed);
                    });
        }
        if (DEBUG) {
            Log.d(TAG, "System Time Cost is " + systemCost);
        }
        getEventLog().logShareStarted(
                mRequest.getReferrerPackage(),
                mRequest.getTargetType(),
                mRequest.getCallerChooserTargets().size(),
                mRequest.getInitialIntents().size(),
                isWorkProfile(),
                mChooserContentPreviewUi.getPreferredContentPreview(),
                mRequest.getTargetAction(),
                mRequest.getChooserActions().size(),
                mRequest.getModifyShareAction() != null
        );
        mEnterTransitionAnimationDelegate.postponeTransition();
        mInitialProfile = findSelectedProfile();
        Tracer.INSTANCE.markLaunched();

        if (isInteractiveSession()) {
            configureInteractiveSessionWindow();
            updateInteractiveArea();
        }
    }

    private void maybeDisableRecentsScreenshot(
            ProfileHelper profileHelper, ProfileAvailability profileAvailability) {
        for (Profile profile : profileHelper.getProfiles()) {
            if (profile.getType() == Profile.Type.PRIVATE) {
                if (profileAvailability.isAvailable(profile)) {
                    // Show blank screen in Recent preview if private profile is available
                    // to not leak its presence.
                    setRecentsScreenshotEnabled(false);
                }
                return;
            }
        }
    }

    private void onChooserRequestChanged(ChooserRequest chooserRequest) {
        if (mRequest == chooserRequest) {
            return;
        }
        boolean recreateAdapters = shouldUpdateAdapters(mRequest, chooserRequest);
        mRequest = chooserRequest;
        updateShareResultSender();
        mChooserContentPreviewUi.updateModifyShareAction();
        if (recreateAdapters) {
            recreatePagerAdapter();
        } else {
            setTabsViewEnabled(true);
        }
    }

    private void onPendingSelection() {
        setTabsViewEnabled(false);
    }

    private void onHasSelections(boolean hasSelections) {
        mChooserMultiProfilePagerAdapter.setTargetsEnabled(hasSelections);
    }

    private void configureInteractiveSessionWindow() {
        if (!isInteractiveSession()) {
            Log.wtf(TAG, "Unexpected user of the method; should be an interactive session");
            return;
        }
        final Window window = getWindow();
        if (window == null) {
            return;
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY);
    }

    private void updateInteractiveArea() {
        if (!isInteractiveSession()) {
            Log.wtf(TAG, "Unexpected user of the method; should be an interactive session");
            return;
        }
        final View contentView = findViewById(android.R.id.content);
        final ResolverDrawerLayout rdl = mResolverDrawerLayout;
        if (contentView == null || rdl == null) {
            return;
        }
        final Rect rect = new Rect();
        contentView.getViewTreeObserver().addOnComputeInternalInsetsListener((info) -> {
            int oldTop = rect.top;
            rdl.getBoundsInWindow(rect, true);
            int left = rect.left;
            int top = rect.top;
            ResolverDrawerLayoutExt.getVisibleDrawerRect(rdl, rect);
            rect.offset(left, top);
            if (oldTop != rect.top) {
                mViewModel.getInteractiveSessionInteractor().sendTopDrawerTopOffsetChange(rect.top);
            }
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(new Rect(rect));
        });
    }

    private void onAppTargetsLoaded(ResolverListAdapter listAdapter) {
        Log.d(TAG, "onAppTargetsLoaded("
                + "listAdapter.userHandle=" + listAdapter.getUserHandle() + ")");

        if (mChooserMultiProfilePagerAdapter == null) {
            return;
        }
        if (!isProfilePagerAdapterAttached()
                && listAdapter == mChooserMultiProfilePagerAdapter.getActiveListAdapter()) {
            mChooserMultiProfilePagerAdapter.setupViewPager(mViewPager);
            setTabsViewEnabled(true);
        }
    }

    private void updateShareResultSender() {
        IntentSender chosenComponentSender = mRequest.getChosenComponentSender();
        if (chosenComponentSender != null) {
            mShareResultSender = mShareResultSenderFactory.create(
                    mViewModel.getActivityModel().getLaunchedFromUid(), chosenComponentSender);
        } else {
            mShareResultSender = null;
        }
    }

    private boolean shouldUpdateAdapters(
            ChooserRequest oldChooserRequest, ChooserRequest newChooserRequest) {
        Intent oldTargetIntent = oldChooserRequest.getTargetIntent();
        Intent newTargetIntent = newChooserRequest.getTargetIntent();
        List<Intent> oldAltIntents = oldChooserRequest.getAdditionalTargets();
        List<Intent> newAltIntents = newChooserRequest.getAdditionalTargets();
        List<ComponentName> oldExcluded = oldChooserRequest.getFilteredComponentNames();
        List<ComponentName> newExcluded = newChooserRequest.getFilteredComponentNames();

        // TODO: a workaround for the unnecessary target reloading caused by multiple flow updates -
        //  an artifact of the current implementation; revisit.
        return !oldTargetIntent.equals(newTargetIntent)
                || !oldAltIntents.equals(newAltIntents)
                || (shareouselUpdateExcludeComponentsExtra()
                        && !oldExcluded.equals(newExcluded));
    }

    private void recreatePagerAdapter() {
        destroyProfileRecords();
        createProfileRecords(
                new AppPredictorFactory(
                        this,
                        Objects.toString(mRequest.getSharedText(), null),
                        mRequest.getShareTargetFilter(),
                        mAppPredictionAvailable
                ),
                mRequest.getShareTargetFilter()
        );

        int currentPage = mChooserMultiProfilePagerAdapter.getCurrentPage();
        if (mChooserMultiProfilePagerAdapter != null) {
            mChooserMultiProfilePagerAdapter.destroy();
        }
        // Update the pager adapter but do not attach it to the view till the targets are reloaded,
        // see onChooserAppTargetsLoaded method.
        ChooserMultiProfilePagerAdapter oldPagerAdapter =
                mChooserMultiProfilePagerAdapter;
        mChooserMultiProfilePagerAdapter = createMultiProfilePagerAdapter(
                /* context = */ this,
                mProfilePagerResources,
                mRequest,
                mProfiles,
                mProfileRecords.values(),
                mProfileAvailability,
                mMaxTargetsPerRow);
        mChooserMultiProfilePagerAdapter.setCurrentPage(currentPage);
        for (int i = 0, count = mChooserMultiProfilePagerAdapter.getItemCount(); i < count; i++) {
            mChooserMultiProfilePagerAdapter.getPageAdapterForIndex(i)
                    .getListAdapter().setAnimateItems(false);
        }
        if (mPersonalPackageMonitor != null) {
            mPersonalPackageMonitor.unregister();
        }
        mPersonalPackageMonitor = createPackageMonitor(
                mChooserMultiProfilePagerAdapter.getPersonalListAdapter());
        mPersonalPackageMonitor.register(
                this,
                getMainLooper(),
                mProfiles.getPersonalHandle(),
                false);
        if (mProfiles.getWorkProfilePresent()) {
            if (mWorkPackageMonitor != null) {
                mWorkPackageMonitor.unregister();
            }
            mWorkPackageMonitor = createPackageMonitor(
                    mChooserMultiProfilePagerAdapter.getWorkListAdapter());
            mWorkPackageMonitor.register(
                    this,
                    getMainLooper(),
                    mProfiles.getWorkHandle(),
                    false);
        }
        postRebuildList(
                mChooserMultiProfilePagerAdapter.rebuildTabs(
                    mProfiles.getWorkProfilePresent() || mProfiles.getPrivateProfilePresent()));
        if (fixShortcutsFlashingFixed() && oldPagerAdapter != null) {
            for (int i = 0, count = mChooserMultiProfilePagerAdapter.getCount(); i < count; i++) {
                ChooserListAdapter listAdapter =
                        mChooserMultiProfilePagerAdapter.getPageAdapterForIndex(i)
                                .getListAdapter();
                ChooserListAdapter oldListAdapter =
                        oldPagerAdapter.getListAdapterForUserHandle(listAdapter.getUserHandle());
                if (oldListAdapter != null) {
                    listAdapter.copyDirectTargetsFrom(oldListAdapter);
                    listAdapter.setDirectTargetsEnabled(false);
                }
            }
        }
        setTabsViewEnabled(false);
        if (mSystemWindowInsets != null) {
            applyFooterView(mSystemWindowInsets.bottom);
        }
    }

    private void setTabsViewEnabled(boolean isEnabled) {
        TabWidget tabs = mTabHost.getTabWidget();
        if (tabs != null) {
            tabs.setEnabled(isEnabled);
        }
        View tabContent = mTabHost.findViewById(com.android.internal.R.id.profile_pager);
        if (tabContent != null) {
            tabContent.setEnabled(isEnabled);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        if (mViewPager != null) {
            int profile = savedInstanceState.getInt(LAST_SHOWN_PROFILE);
            int profileNumber = mChooserMultiProfilePagerAdapter.getPageNumberForProfile(profile);
            if (profileNumber != -1) {
                mViewPager.setCurrentItem(profileNumber);
                mInitialProfile = profile;
            }
        }
        mChooserMultiProfilePagerAdapter.clearInactiveProfileCache();
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // Inherited methods
    //////////////////////////////////////////////////////////////////////////////////////////////

    private boolean isAutolaunching() {
        return !mRegistered && isFinishing();
    }

    private boolean maybeAutolaunchIfSingleTarget() {
        int count = mChooserMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredCount();
        if (count != 1) {
            return false;
        }

        if (mChooserMultiProfilePagerAdapter.getActiveListAdapter().getOtherProfile() != null) {
            return false;
        }

        // Only one target, so we're a candidate to auto-launch!
        final TargetInfo target = mChooserMultiProfilePagerAdapter.getActiveListAdapter()
                .targetInfoForPosition(0, false);
        if (shouldAutoLaunchSingleChoice(target)) {
            Log.d(TAG, "auto launching " + target + " and finishing.");
            safelyStartActivity(target);
            finish();
            return true;
        }
        return false;
    }

    private boolean isTwoPagePersonalAndWorkConfiguration() {
        return (mChooserMultiProfilePagerAdapter.getCount() == 2)
                && mChooserMultiProfilePagerAdapter.hasPageForProfile(PROFILE_PERSONAL)
                && mChooserMultiProfilePagerAdapter.hasPageForProfile(PROFILE_WORK);
    }

    /**
     * When we have a personal and a work profile, we auto launch in the following scenario:
     * - There is 1 resolved target on each profile
     * - That target is the same app on both profiles
     * - The target app has permission to communicate cross profiles
     * - The target app has declared it supports cross-profile communication via manifest metadata
     */
    private boolean maybeAutolaunchIfCrossProfileSupported() {
        if (!isTwoPagePersonalAndWorkConfiguration()) {
            return false;
        }

        ResolverListAdapter activeListAdapter =
                (mChooserMultiProfilePagerAdapter.getActiveProfile() == PROFILE_PERSONAL)
                        ? mChooserMultiProfilePagerAdapter.getPersonalListAdapter()
                        : mChooserMultiProfilePagerAdapter.getWorkListAdapter();

        ResolverListAdapter inactiveListAdapter =
                (mChooserMultiProfilePagerAdapter.getActiveProfile() == PROFILE_PERSONAL)
                        ? mChooserMultiProfilePagerAdapter.getWorkListAdapter()
                        : mChooserMultiProfilePagerAdapter.getPersonalListAdapter();

        if (!activeListAdapter.isTabLoaded() || !inactiveListAdapter.isTabLoaded()) {
            return false;
        }

        if ((activeListAdapter.getUnfilteredCount() != 1)
                || (inactiveListAdapter.getUnfilteredCount() != 1)) {
            return false;
        }

        TargetInfo activeProfileTarget = activeListAdapter.targetInfoForPosition(0, false);
        TargetInfo inactiveProfileTarget = inactiveListAdapter.targetInfoForPosition(0, false);
        if (!Objects.equals(
                activeProfileTarget.getResolvedComponentName(),
                inactiveProfileTarget.getResolvedComponentName())) {
            return false;
        }

        if (!shouldAutoLaunchSingleChoice(activeProfileTarget)) {
            return false;
        }

        String packageName = activeProfileTarget.getResolvedComponentName().getPackageName();
        if (!mIntentForwarding.canAppInteractAcrossProfiles(this, packageName)) {
            return false;
        }

        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.RESOLVER_AUTOLAUNCH_CROSS_PROFILE_TARGET)
                .setBoolean(activeListAdapter.getUserHandle()
                        .equals(mProfiles.getPersonalHandle()))
                .setStrings(getMetricsCategory())
                .write();
        safelyStartActivity(activeProfileTarget);
        Log.d(TAG, "auto launching! " + activeProfileTarget);
        finish();
        return true;
    }

    /**
     * @return {@code true} if a resolved target is autolaunched, otherwise {@code false}
     */
    private boolean maybeAutolaunchActivity() {
        if (isInteractiveSession()) {
            return false;
        }
        int numberOfProfiles = mChooserMultiProfilePagerAdapter.getItemCount();
        // TODO(b/280988288): If the ChooserActivity is shown we should consider showing the
        //  correct intent-picker UIs (e.g., mini-resolver) if it was launched without
        //  ACTION_SEND.
        if (numberOfProfiles == 1 && maybeAutolaunchIfSingleTarget()) {
            return true;
        } else if (maybeAutolaunchIfCrossProfileSupported()) {
            return true;
        }
        return false;
    }

    @Override // ResolverListCommunicator
    public final void onPostListReady(ResolverListAdapter listAdapter, boolean doPostProcessing,
            boolean rebuildCompleted) {
        if (isAutolaunching()) {
            return;
        }
        if (mChooserMultiProfilePagerAdapter
                .shouldShowEmptyStateScreen((ChooserListAdapter) listAdapter)) {
            mChooserMultiProfilePagerAdapter
                    .showEmptyResolverListEmptyState((ChooserListAdapter) listAdapter);
        } else {
            mChooserMultiProfilePagerAdapter.showListView((ChooserListAdapter) listAdapter);
        }
        // showEmptyResolverListEmptyState can mark the tab as loaded,
        // which is a precondition for auto launching
        if (rebuildCompleted && maybeAutolaunchActivity()) {
            return;
        }
        if (doPostProcessing) {
            maybeCreateHeader(listAdapter);
            onListRebuilt(listAdapter, rebuildCompleted);
        }
    }

    private CharSequence getOrLoadDisplayLabel(TargetInfo info) {
        if (info.isDisplayResolveInfo()) {
            mTargetDataLoader.getOrLoadLabel((DisplayResolveInfo) info);
        }
        CharSequence displayLabel = info.getDisplayLabel();
        return displayLabel == null ? "" : displayLabel;
    }

    protected final CharSequence getTitleForAction(Intent intent, int defaultTitleRes) {
        final ActionTitle title = ActionTitle.forAction(intent.getAction());

        // While there may already be a filtered item, we can only use it in the title if the list
        // is already sorted and all information relevant to it is already in the list.
        final boolean named =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter().getFilteredPosition() >= 0;
        if (title == ActionTitle.DEFAULT && defaultTitleRes != 0) {
            return getString(defaultTitleRes);
        } else {
            return named
                    ? getString(
                    title.namedTitleRes,
                    getOrLoadDisplayLabel(
                            mChooserMultiProfilePagerAdapter
                                    .getActiveListAdapter().getFilteredItem()))
                    : getString(title.titleRes);
        }
    }

    /**
     * Configure the area above the app selection list (title, content preview, etc).
     */
    private void maybeCreateHeader(ResolverListAdapter listAdapter) {
        if (mHeaderCreatorUser != null
                && !listAdapter.getUserHandle().equals(mHeaderCreatorUser)) {
            return;
        }
        if (!mProfiles.getWorkProfilePresent()
                && listAdapter.getCount() == 0 && listAdapter.getPlaceholderCount() == 0) {
            final TextView titleView = findViewById(com.android.internal.R.id.title);
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
        }

        CharSequence title = mRequest.getTitle() != null
                ? mRequest.getTitle()
                : getTitleForAction(mRequest.getTargetIntent(),
                        mRequest.getDefaultTitleResource());

        if (!TextUtils.isEmpty(title)) {
            final TextView titleView = findViewById(com.android.internal.R.id.title);
            if (titleView != null) {
                titleView.setText(title);
            }
            setTitle(title);
        }

        final ImageView iconView = findViewById(com.android.internal.R.id.icon);
        if (iconView != null) {
            listAdapter.loadFilteredItemIconTaskAsync(iconView);
        }
        mHeaderCreatorUser = listAdapter.getUserHandle();
    }

    /** Start the activity specified by the {@link TargetInfo}.*/
    public final void safelyStartActivity(TargetInfo cti) {
        // In case cloned apps are present, we would want to start those apps in cloned user
        // space, which will not be same as the adapter's userHandle. resolveInfo.userHandle
        // identifies the correct user space in such cases.
        UserHandle activityUserHandle = cti.getResolveInfo().userHandle;
        safelyStartActivityAsUser(cti, activityUserHandle, null);
    }

    protected final void safelyStartActivityAsUser(
            TargetInfo cti, UserHandle user, @Nullable Bundle options) {
        // We're dispatching intents that might be coming from legacy apps, so
        // don't kill ourselves.
        StrictMode.disableDeathOnFileUriExposure();
        try {
            safelyStartActivityInternal(cti, user, options);
        } finally {
            StrictMode.enableDeathOnFileUriExposure();
        }
    }

    @VisibleForTesting
    protected void safelyStartActivityInternal(
            TargetInfo cti, UserHandle user, @Nullable Bundle options) {
        // If the target is suspended, the activity will not be successfully launched.
        // Do not unregister from package manager updates in this case
        if (!cti.isSuspended() && mRegistered) {
            if (mPersonalPackageMonitor != null) {
                mPersonalPackageMonitor.unregister();
            }
            if (mWorkPackageMonitor != null) {
                mWorkPackageMonitor.unregister();
            }
            mRegistered = false;
        }
        // If needed, show that intent is forwarded
        // from managed profile to owner or other way around.
        String profileSwitchMessage = mIntentForwarding.forwardMessageFor(
                mRequest.getTargetIntent());
        if (profileSwitchMessage != null) {
            Toast.makeText(this, profileSwitchMessage, Toast.LENGTH_LONG).show();
        }
        try {
            if (cti.startAsCaller(this, options, user.getIdentifier())) {
                // Prevent sending a second chooser result when starting the edit action intent.
                if (!cti.getTargetIntent().hasExtra(EDIT_SOURCE)) {
                    maybeSendShareResult(cti, user);
                }
                maybeLogCrossProfileTargetLaunch(cti, user);
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG,
                    "Unable to launch as uid " + mActivityModel.getLaunchedFromUid()
                            + " package " + mActivityModel.getLaunchedFromPackage()
                            + ", while running in " + ActivityThread.currentProcessName(), e);
        }
    }

    private void maybeLogCrossProfileTargetLaunch(TargetInfo cti, UserHandle currentUserHandle) {
        if (!mProfiles.getWorkProfilePresent() || currentUserHandle.equals(getUser())) {
            return;
        }
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.RESOLVER_CROSS_PROFILE_TARGET_OPENED)
                .setBoolean(currentUserHandle.equals(mProfiles.getPersonalHandle()))
                .setStrings(getMetricsCategory(),
                        cti.isInDirectShareMetricsCategory() ? "direct_share" : "other_target")
                .write();
    }

    private LatencyTracker getLatencyTracker() {
        return LatencyTracker.getInstance(this);
    }

    /**
     * If {@code retainInOnStop} is set to true, we will not finish ourselves when onStop gets
     * called and we are launched in a new task.
     */
    protected final void setRetainInOnStop(boolean retainInOnStop) {
        mRetainInOnStop = retainInOnStop;
    }

    // @NonFinalForTesting
    @VisibleForTesting
    protected CrossProfileIntentsChecker createCrossProfileIntentsChecker() {
        return new CrossProfileIntentsChecker(getContentResolver());
    }

    protected final EmptyStateProvider createEmptyStateProvider(
            ProfileHelper profileHelper,
            ProfileAvailability profileAvailability) {
        EmptyStateProvider blockerEmptyStateProvider = createBlockerEmptyStateProvider();

        EmptyStateProvider workProfileOffEmptyStateProvider =
                new WorkProfilePausedEmptyStateProvider(
                        this,
                        profileHelper,
                        profileAvailability,
                        /* onSwitchOnWorkSelectedListener = */
                        () -> {
                            if (mOnSwitchOnWorkSelectedListener != null) {
                                mOnSwitchOnWorkSelectedListener.onSwitchOnWorkSelected();
                            }
                        },
                        getMetricsCategory());

        EmptyStateProvider noAppsEmptyStateProvider = new NoAppsAvailableEmptyStateProvider(
                mProfiles,
                mProfileAvailability,
                getMetricsCategory(),
                mProfilePagerResources
        );

        // Return composite provider, the order matters (the higher, the more priority)
        return new CompositeEmptyStateProvider(
                blockerEmptyStateProvider,
                workProfileOffEmptyStateProvider,
                noAppsEmptyStateProvider
        );
    }

    /**
     * Returns the {@link List} of {@link UserHandle} to pass on to the
     * {@link ResolverRankerServiceResolverComparator} as per the provided {@code userHandle}.
     */
    private List<UserHandle> getResolverRankerServiceUserHandleList(UserHandle userHandle) {
        return getResolverRankerServiceUserHandleListInternal(userHandle);
    }

    private List<UserHandle> getResolverRankerServiceUserHandleListInternal(UserHandle userHandle) {
        List<UserHandle> userList = new ArrayList<>();
        userList.add(userHandle);
        // Add clonedProfileUserHandle to the list only if we are:
        // a. Building the Personal Tab.
        // b. CloneProfile exists on the device.
        if (userHandle.equals(mProfiles.getPersonalHandle())
                && mProfiles.getCloneUserPresent()) {
            userList.add(mProfiles.getCloneHandle());
        }
        return userList;
    }

    /**
     * Start activity as a fixed user handle.
     * @param cti TargetInfo to be launched.
     * @param user User to launch this activity as.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public final void safelyStartActivityAsUser(TargetInfo cti, UserHandle user) {
        safelyStartActivityAsUser(cti, user, null);
    }

    @Override // ResolverListCommunicator
    public final void onHandlePackagesChanged(ResolverListAdapter listAdapter) {
        mChooserMultiProfilePagerAdapter.onHandlePackagesChanged(
                (ChooserListAdapter) listAdapter,
                mProfileAvailability.getWaitingToEnableProfile());
    }

    final Option optionForChooserTarget(TargetInfo target, int index) {
        return new Option(getOrLoadDisplayLabel(target), index);
    }

    @Override // ResolverListCommunicator
    public final void sendVoiceChoicesIfNeeded() {
        if (!isVoiceInteraction()) {
            // Clearly not needed.
            return;
        }

        int count = mChooserMultiProfilePagerAdapter.getActiveListAdapter().getCount();
        final Option[] options = new Option[count];
        for (int i = 0; i < options.length; i++) {
            TargetInfo target = mChooserMultiProfilePagerAdapter.getActiveListAdapter().getItem(i);
            if (target == null) {
                // If this occurs, a new set of targets is being loaded. Let that complete,
                // and have the next call to send voice choices proceed instead.
                return;
            }
            options[i] = optionForChooserTarget(target, i);
        }

        mPickOptionRequest = new ResolverActivity.PickTargetOptionRequest(
                new VoiceInteractor.Prompt(getTitle()), options, null);
        getVoiceInteractor().submitRequest(mPickOptionRequest);
    }

    /**
     * Sets up the content view.
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    private boolean configureContentView(TargetDataLoader targetDataLoader) {
        if (mChooserMultiProfilePagerAdapter.getActiveListAdapter() == null) {
            throw new IllegalStateException("mMultiProfilePagerAdapter.getCurrentListAdapter() "
                    + "cannot be null.");
        }
        Trace.beginSection("configureContentView");
        // We partially rebuild the inactive adapter to determine if we should auto launch
        // isTabLoaded will be true here if the empty state screen is shown instead of the list.
        boolean rebuildCompleted = mChooserMultiProfilePagerAdapter.rebuildTabs(
                mProfiles.getWorkProfilePresent());

        mLayoutId = R.layout.chooser_grid_scrollable_preview;

        setContentView(mLayoutId);
        mTabHost = findViewById(com.android.internal.R.id.profile_tabhost);
        mViewPager = requireViewById(com.android.internal.R.id.profile_pager);
        mChooserMultiProfilePagerAdapter.setupViewPager(mViewPager);
        ChooserNestedScrollView scrollableContainer =
                requireViewById(R.id.chooser_scrollable_container);
        if (keyboardNavigationFix()) {
            scrollableContainer.setRequestChildFocusPredicate((child, focused) ->
                    // TabHost view will request focus on the newly activated tab. The RecyclerView
                    // from the tab gets focused and  notifies its parents (including
                    // NestedScrollView) about it through #requestChildFocus method call.
                    // NestedScrollView's view implementation of the method  will  scroll to the
                    // focused view. As we don't want to change drawer's position upon tab change,
                    // ignore focus requests from tab RecyclerViews.
                    focused == null || focused.getId() != com.android.internal.R.id.resolver_list);
        }
        boolean result = postRebuildList(rebuildCompleted);
        Trace.endSection();
        return result;
    }

    /**
     * Finishing procedures to be performed after the list has been rebuilt.
     * </p>Subclasses must call postRebuildListInternal at the end of postRebuildList.
     * @param rebuildCompleted
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    protected boolean postRebuildList(boolean rebuildCompleted) {
        return postRebuildListInternal(rebuildCompleted);
    }

    /**
     * Add a label to signify that the user can pick a different app.
     * @param adapter The adapter used to provide data to item views.
     */
    public void addUseDifferentAppLabelIfNecessary(ResolverListAdapter adapter) {
        final boolean useHeader = adapter.hasFilteredItem();
        if (useHeader) {
            FrameLayout stub = findViewById(com.android.internal.R.id.stub);
            stub.setVisibility(View.VISIBLE);
            TextView textView = (TextView) LayoutInflater.from(this).inflate(
                    R.layout.resolver_different_item_header, null, false);
            if (mProfiles.getWorkProfilePresent()) {
                textView.setGravity(Gravity.CENTER);
            }
            stub.addView(textView);
        }
    }
    private void setupViewVisibilities() {
        ChooserListAdapter activeListAdapter =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter();
        if (!mChooserMultiProfilePagerAdapter.shouldShowEmptyStateScreen(activeListAdapter)) {
            addUseDifferentAppLabelIfNecessary(activeListAdapter);
        }
    }
    /**
     * Finishing procedures to be performed after the list has been rebuilt.
     * @param rebuildCompleted
     * @return <code>true</code> if the activity is finishing and creation should halt.
     */
    final boolean postRebuildListInternal(boolean rebuildCompleted) {
        int count = mChooserMultiProfilePagerAdapter.getActiveListAdapter().getUnfilteredCount();

        // We only rebuild asynchronously when we have multiple elements to sort. In the case where
        // we're already done, we can check if we should auto-launch immediately.
        if (rebuildCompleted && maybeAutolaunchActivity()) {
            return true;
        }

        setupViewVisibilities();

        if (mProfiles.getWorkProfilePresent()
                || (mProfiles.getPrivateProfilePresent()
                        && mProfileAvailability.isAvailable(
                        requireNonNull(mProfiles.getPrivateProfile())))) {
            setupProfileTabs();
        }

        return false;
    }

    private void setupProfileTabs() {
        mChooserMultiProfilePagerAdapter.setupProfileTabs(
                getLayoutInflater(),
                mTabHost,
                mViewPager,
                R.layout.resolver_profile_tab_button,
                com.android.internal.R.id.profile_pager,
                () -> onProfileTabSelected(mViewPager.getCurrentItem()),
                new OnProfileSelectedListener() {
                    @Override
                    public void onProfilePageSelected(@ProfileType int profileId, int pageNumber) {}

                    @Override
                    public void onProfilePageStateChanged(int state) {
                        onHorizontalSwipeStateChanged(state);
                    }
                });
        mOnSwitchOnWorkSelectedListener = () -> {
            View workTab = mTabHost.getTabWidget().getChildAt(
                    mChooserMultiProfilePagerAdapter.getPageNumberForProfile(PROFILE_WORK));
            workTab.setFocusable(true);
            workTab.setFocusableInTouchMode(true);
            workTab.requestFocus();
        };
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////

    private void createProfileRecords(
            AppPredictorFactory factory, IntentFilter targetIntentFilter) {

        Profile launchedAsProfile = mProfiles.getLaunchedAsProfile();
        for (Profile profile : mProfiles.getProfiles()) {
            if (profile.getType() == Profile.Type.PRIVATE
                    && !mProfileAvailability.isAvailable(profile)) {
                continue;
            }
            ProfileRecord record = createProfileRecord(
                    profile,
                    targetIntentFilter,
                    launchedAsProfile.equals(profile)
                            ? mRequest.getCallerChooserTargets()
                            : Collections.emptyList(),
                    factory);
            if (profile.equals(launchedAsProfile) && record.shortcutLoader == null) {
                Tracer.INSTANCE.endLaunchToShortcutTrace();
            }
        }
    }

    private ProfileRecord createProfileRecord(
            Profile profile,
            IntentFilter targetIntentFilter,
            List<ChooserTarget> callerTargets,
            AppPredictorFactory factory) {
        UserHandle userHandle = profile.getPrimary().getHandle();
        AppPredictor appPredictor = factory.create(userHandle);
        ShortcutLoader shortcutLoader = ActivityManager.isLowRamDeviceStatic()
                    ? null
                    : createShortcutLoader(
                            this,
                            appPredictor,
                            userHandle,
                            targetIntentFilter,
                            shortcutsResult -> onShortcutsLoaded(userHandle, shortcutsResult));
        ProfileRecord record = new ProfileRecord(
                profile, appPredictor, shortcutLoader, callerTargets);
        mProfileRecords.put(userHandle.getIdentifier(), record);
        return record;
    }

    @Nullable
    private ProfileRecord getProfileRecord(UserHandle userHandle) {
        return mProfileRecords.get(userHandle.getIdentifier());
    }

    @VisibleForTesting
    protected ShortcutLoader createShortcutLoader(
            Context context,
            AppPredictor appPredictor,
            UserHandle userHandle,
            IntentFilter targetIntentFilter,
            Consumer<ShortcutLoader.Result> callback) {
        return new ShortcutLoader(
                context,
                getCoroutineScope(getLifecycle()),
                appPredictor,
                userHandle,
                targetIntentFilter,
                callback);
    }

    static SharedPreferences getPinnedSharedPrefs(Context context) {
        return context.getSharedPreferences(PINNED_SHARED_PREFS_NAME, MODE_PRIVATE);
    }

    private ChooserMultiProfilePagerAdapter createMultiProfilePagerAdapter(
            Context context,
            ProfilePagerResources profilePagerResources,
            ChooserRequest request,
            ProfileHelper profileHelper,
            Collection<ProfileRecord> profileRecords,
            ProfileAvailability profileAvailability,
            int maxTargetsPerRow) {
        Log.d(TAG, "createMultiProfilePagerAdapter");

        Profile launchedAs = profileHelper.getLaunchedAsProfile();

        Intent[] initialIntentArray = request.getInitialIntents().toArray(new Intent[0]);

        List<TabConfig<ChooserGridAdapter>> tabs = new ArrayList<>();
        for (ProfileRecord record : profileRecords) {
            Profile profile = record.profile;
            boolean isCrossProfile = !profile.equals(launchedAs);
            ChooserGridAdapter adapter = createChooserGridAdapter(
                    context,
                    isCrossProfile
                            ? request.getCrossProfilePayloadIntents()
                            : request.getPayloadIntents(),
                    isCrossProfile ? null : initialIntentArray,
                    profile.getPrimary().getHandle()
            );
            tabs.add(new TabConfig<>(
                    /* profile = */ profile.getType().ordinal(),
                    profilePagerResources.profileTabLabel(profile.getType()),
                    profilePagerResources.profileTabAccessibilityLabel(profile.getType()),
                    /* tabTag = */ profile.getType().name(),
                    adapter));
        }

        EmptyStateProvider emptyStateProvider =
                createEmptyStateProvider(profileHelper, profileAvailability);

        Supplier<Boolean> workProfileQuietModeChecker =
                () -> !(profileHelper.getWorkProfilePresent()
                        && profileAvailability.isAvailable(
                        requireNonNull(profileHelper.getWorkProfile())));

        return new ChooserMultiProfilePagerAdapter(
                /* context */ this,
                ImmutableList.copyOf(tabs),
                emptyStateProvider,
                workProfileQuietModeChecker,
                launchedAs.getType().ordinal(),
                profileHelper.getWorkHandle(),
                profileHelper.getCloneHandle(),
                maxTargetsPerRow);
    }

    protected EmptyStateProvider createBlockerEmptyStateProvider() {
        return new NoCrossProfileEmptyStateProvider(
                mProfiles,
                mDevicePolicyResources,
                createCrossProfileIntentsChecker(),
                mRequest.isSendActionTarget());
    }

    private int findSelectedProfile() {
        return mProfiles.getLaunchedAsProfileType().ordinal();
    }

    /**
     * Check if the profile currently used is a work profile.
     * @return true if it is work profile, false if it is parent profile (or no work profile is
     * set up)
     */
    private boolean isWorkProfile() {
        return mProfiles.getLaunchedAsProfileType() == Profile.Type.WORK;
    }

    //@Override
    protected PackageMonitor createPackageMonitor(ResolverListAdapter listAdapter) {
        return new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                handlePackagesChanged(listAdapter);
            }
        };
    }

    /**
     * Update UI to reflect changes in data.
     */
    @Override
    public void handlePackagesChanged() {
        handlePackagesChanged(/* listAdapter */ null);
    }

    /**
     * Update UI to reflect changes in data.
     * <p>If {@code listAdapter} is {@code null}, both profile list adapters are updated if
     * available.
     */
    private void handlePackagesChanged(@Nullable ResolverListAdapter listAdapter) {
        // Refresh pinned items
        mPinnedSharedPrefs = getPinnedSharedPrefs(this);
        if (rebuildAdaptersOnTargetPinning()) {
            recreatePagerAdapter();
        } else {
            if (listAdapter == null) {
                mChooserMultiProfilePagerAdapter.refreshPackagesInAllTabs();
            } else {
                listAdapter.handlePackagesChanged();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mChooserMultiProfilePagerAdapter.getActiveListAdapter().handlePackagesChanged();

        if (mSystemWindowInsets != null) {
            int topSpacing = isInteractiveSession() ? getInteractiveSessionTopSpacing() : 0;
            mResolverDrawerLayout.setPadding(
                    mSystemWindowInsets.left,
                    mSystemWindowInsets.top + topSpacing,
                    mSystemWindowInsets.right,
                    0);
        }
        if (mViewPager.isLayoutRtl()) {
            mChooserMultiProfilePagerAdapter.setupViewPager(mViewPager);
        }

        mShouldDisplayLandscape = shouldDisplayLandscape(newConfig.orientation);
        mMaxTargetsPerRow = getResources().getInteger(R.integer.config_chooser_max_targets_per_row);
        mChooserMultiProfilePagerAdapter.setMaxTargetsPerRow(mMaxTargetsPerRow);
        adjustMaxPreviewWidth();
        adjustPreviewWidth(newConfig.orientation, null);
        updateStickyContentPreview();
        updateTabPadding();
    }

    private boolean shouldDisplayLandscape(int orientation) {
        // Sharesheet fixes the # of items per row and therefore can not correctly lay out
        // when in the restricted size of multi-window mode. In the future, would be nice
        // to use minimum dp size requirements instead
        return orientation == Configuration.ORIENTATION_LANDSCAPE && !isInMultiWindowMode();
    }

    private void adjustMaxPreviewWidth() {
        if (mResolverDrawerLayout == null) {
            return;
        }
        mResolverDrawerLayout.setMaxWidth(
                getResources().getDimensionPixelSize(R.dimen.chooser_width));
    }

    private void adjustPreviewWidth(int orientation, View parent) {
        int width = -1;
        if (mShouldDisplayLandscape) {
            width = getResources().getDimensionPixelSize(R.dimen.chooser_preview_width);
        }

        parent = parent == null ? getWindow().getDecorView() : parent;

        updateLayoutWidth(com.android.internal.R.id.content_preview_file_layout, width, parent);
    }

    private void updateTabPadding() {
        if (mProfiles.getWorkProfilePresent()) {
            View tabs = findViewById(com.android.internal.R.id.tabs);
            float iconSize = getResources().getDimension(R.dimen.chooser_icon_size);
            // The entire width consists of icons or padding. Divide the item padding in half to get
            // paddingHorizontal.
            float padding = (tabs.getWidth() - mMaxTargetsPerRow * iconSize)
                    / mMaxTargetsPerRow / 2;
            // Subtract the margin the buttons already have.
            padding -= getResources().getDimension(R.dimen.resolver_profile_tab_margin);
            tabs.setPadding((int) padding, 0, (int) padding, 0);
        }
    }

    private void updateLayoutWidth(int layoutResourceId, int width, View parent) {
        View view = parent.findViewById(layoutResourceId);
        if (view != null && view.getLayoutParams() != null) {
            LayoutParams params = view.getLayoutParams();
            params.width = width;
            view.setLayoutParams(params);
        }
    }

    /**
     * Create a view that will be shown in the content preview area
     * @param parent reference to the parent container where the view should be attached to
     * @return content preview view
     */
    protected ViewGroup createContentPreviewView(ViewGroup parent) {
        ViewGroup layout = mChooserContentPreviewUi.displayContentPreview(
                getResources(),
                getLayoutInflater(),
                parent,
                requireViewById(R.id.chooser_headline_row_container));

        if (layout != null) {
            adjustPreviewWidth(getResources().getConfiguration().orientation, layout);
        }

        return layout;
    }

    @Nullable
    private View getFirstVisibleImgPreviewView() {
        View imagePreview = findViewById(R.id.scrollable_image_preview);
        return imagePreview instanceof ImagePreviewView
                ? ((ImagePreviewView) imagePreview).getTransitionView()
                : null;
    }

    /**
     * Wrapping the ContentResolver call to expose for easier mocking,
     * and to avoid mocking Android core classes.
     */
    @VisibleForTesting
    public Cursor queryResolver(ContentResolver resolver, Uri uri) {
        return resolver.query(uri, null, null, null, null);
    }

    private void destroyProfileRecords() {
        mProfileRecords.values().forEach(ProfileRecord::destroy);
        mProfileRecords.clear();
    }

    @Override // ResolverListCommunicator
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        Intent result = defIntent;
        if (mRequest.getReplacementExtras() != null) {
            final Bundle replExtras =
                    mRequest.getReplacementExtras().getBundle(aInfo.packageName);
            if (replExtras != null) {
                result = new Intent(defIntent);
                result.putExtras(replExtras);
            }
        }
        if (aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_PARENT)
                || aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            result = Intent.createChooser(result,
                    getIntent().getCharSequenceExtra(Intent.EXTRA_TITLE));

            // Don't auto-launch single intents if the intent is being forwarded. This is done
            // because automatically launching a resolving application as a response to the user
            // action of switching accounts is pretty unexpected.
            result.putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false);
        }
        return result;
    }

    private void maybeSendShareResult(TargetInfo cti, UserHandle launchedAsUser) {
        if (mShareResultSender != null) {
            final ComponentName target = cti.getResolvedComponentName();
            if (target != null) {
                boolean crossProfile = !UserHandle.of(UserHandle.myUserId()).equals(launchedAsUser);
                mShareResultSender.onComponentSelected(
                        target, cti.isChooserTargetInfo(), crossProfile);
            }
        }
    }

    private void addCallerChooserTargets(ChooserListAdapter adapter) {
        ProfileRecord record = getProfileRecord(adapter.getUserHandle());
        List<ChooserTarget> callerTargets = record == null
                ? Collections.emptyList()
                : record.callerTargets;
        if (!callerTargets.isEmpty()) {
            adapter.addServiceResults(
                    /* origTarget */ null,
                    new ArrayList<>(mRequest.getCallerChooserTargets()),
                    TARGET_TYPE_DEFAULT,
                    /* directShareShortcutInfoCache */ Collections.emptyMap(),
                    /* directShareAppTargetCache */ Collections.emptyMap());
        }
    }

    @Override // ResolverListCommunicator
    public boolean shouldGetActivityMetadata() {
        return true;
    }

    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        if (target.isSuspended()) {
            return false;
        }

        // TODO: migrate to ChooserRequest
        return mViewModel.getActivityModel().getIntent()
                .getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, true);
    }

    private void showTargetDetails(TargetInfo targetInfo) {
        if (targetInfo == null) return;

        List<DisplayResolveInfo> targetList = targetInfo.getAllDisplayTargets();
        if (targetList.isEmpty()) {
            Log.e(TAG, "No displayable data to show target details");
            return;
        }

        // TODO: implement these type-conditioned behaviors polymorphically, and consider moving
        // the logic into `ChooserTargetActionsDialogFragment.show()`.
        boolean isShortcutPinned = targetInfo.isSelectableTargetInfo() && targetInfo.isPinned();
        IntentFilter intentFilter;
        intentFilter = targetInfo.isSelectableTargetInfo()
                ? mRequest.getShareTargetFilter() : null;
        String shortcutTitle = targetInfo.isSelectableTargetInfo()
                ? targetInfo.getDisplayLabel().toString() : null;
        String shortcutIdKey = targetInfo.getDirectShareShortcutId();

        ChooserTargetActionsDialogFragment.show(
                getSupportFragmentManager(),
                targetList,
                // Adding userHandle from ResolveInfo allows the app icon in Dialog Box to be
                // resolved correctly within the same tab.
                targetInfo.getResolveInfo().userHandle,
                shortcutIdKey,
                shortcutTitle,
                isShortcutPinned,
                intentFilter);
    }

    protected boolean onTargetSelected(TargetInfo target) {
        if (mRefinementManager.maybeHandleSelection(
                target,
                mRequest.getRefinementIntentSender(),
                getApplication(),
                getMainThreadHandler())) {
            return false;
        }
        updateModelAndChooserCounts(target);
        maybeRemoveSharedText(target);
        safelyStartActivity(target);

        // Rely on the ActivityManager to pop up a dialog regarding app suspension
        // and return false
        return !target.isSuspended();
    }

    @Override
    public void startSelected(int which, /* unused */ boolean always, boolean filtered) {
        ChooserListAdapter currentListAdapter =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter();
        TargetInfo targetInfo = currentListAdapter
                .targetInfoForPosition(which, filtered);
        if (targetInfo != null && targetInfo.isNotSelectableTargetInfo()) {
            return;
        }

        final long selectionCost = System.currentTimeMillis() - mChooserShownTime;

        if ((targetInfo != null) && targetInfo.isMultiDisplayResolveInfo()) {
            MultiDisplayResolveInfo mti = (MultiDisplayResolveInfo) targetInfo;
            if (!mti.hasSelected()) {
                // Add userHandle based badge to the stackedAppDialogBox.
                ChooserStackedAppDialogFragment.show(
                        getSupportFragmentManager(),
                        mti,
                        which,
                        targetInfo.getResolveInfo().userHandle);
                return;
            }
        }
        if (isFinishing()) {
            return;
        }

        TargetInfo target = mChooserMultiProfilePagerAdapter.getActiveListAdapter()
                .targetInfoForPosition(which, filtered);
        if (target != null) {
            if (onTargetSelected(target)) {
                MetricsLogger.action(
                        this, MetricsEvent.ACTION_APP_DISAMBIG_TAP);
                MetricsLogger.action(this,
                        mChooserMultiProfilePagerAdapter.getActiveListAdapter().hasFilteredItem()
                                ? MetricsEvent.ACTION_HIDE_APP_DISAMBIG_APP_FEATURED
                                : MetricsEvent.ACTION_HIDE_APP_DISAMBIG_NONE_FEATURED);
                Log.d(TAG, "onTargetSelected() returned true, finishing! " + target);
                finish();
            }
        }

        // TODO: both of the conditions around this switch logic *should* be redundant, and
        // can be removed if certain invariants can be guaranteed. In particular, it seems
        // like targetInfo (from `ChooserListAdapter.targetInfoForPosition()`) is *probably*
        // expected to be null only at out-of-bounds indexes where `getPositionTargetType()`
        // returns TARGET_BAD; then the switch falls through to a default no-op, and we don't
        // need to null-check targetInfo. We only need the null check if it's possible that
        // the ChooserListAdapter contains null elements "in the middle" of its list data,
        // such that they're classified as belonging to one of the real target types. That
        // should probably never happen. But why would this method ever be invoked with a
        // null target at all? Even an out-of-bounds index should never be "selected"...
        if ((currentListAdapter.getCount() > 0) && (targetInfo != null)) {
            switch (currentListAdapter.getPositionTargetType(which)) {
                case ChooserListAdapter.TARGET_SERVICE:
                    getEventLog().logShareTargetSelected(
                            EventLog.SELECTION_TYPE_SERVICE,
                            targetInfo.getResolveInfo().activityInfo.processName,
                            which,
                            /* directTargetAlsoRanked= */ getRankedPosition(targetInfo),
                            mRequest.getCallerChooserTargets().size(),
                            targetInfo.getHashedTargetIdForMetrics(this),
                            targetInfo.isPinned(),
                            mIsSuccessfullySelected,
                            selectionCost
                    );
                    return;
                case ChooserListAdapter.TARGET_CALLER:
                case ChooserListAdapter.TARGET_STANDARD:
                    getEventLog().logShareTargetSelected(
                            EventLog.SELECTION_TYPE_APP,
                            targetInfo.getResolveInfo().activityInfo.processName,
                            (which - currentListAdapter.getSurfacedTargetInfo().size()),
                            /* directTargetAlsoRanked= */ -1,
                            currentListAdapter.getCallerTargetCount(),
                            /* directTargetHashed= */ null,
                            targetInfo.isPinned(),
                            mIsSuccessfullySelected,
                            selectionCost
                    );
                    return;
                case ChooserListAdapter.TARGET_STANDARD_AZ:
                    // A-Z targets are unranked standard targets; we use a value of -1 to mark that
                    // they are from the alphabetical pool.
                    // TODO: why do we log a different selection type if the -1 value already
                    // designates the same condition?
                    getEventLog().logShareTargetSelected(
                            EventLog.SELECTION_TYPE_STANDARD,
                            targetInfo.getResolveInfo().activityInfo.processName,
                            /* value= */ -1,
                            /* directTargetAlsoRanked= */ -1,
                            /* numCallerProvided= */ 0,
                            /* directTargetHashed= */ null,
                            /* isPinned= */ false,
                            mIsSuccessfullySelected,
                            selectionCost
                    );
            }
        }
    }

    private int getRankedPosition(TargetInfo targetInfo) {
        String targetPackageName =
                targetInfo.getChooserTargetComponentName().getPackageName();
        ChooserListAdapter currentListAdapter =
                mChooserMultiProfilePagerAdapter.getActiveListAdapter();
        int maxRankedResults = Math.min(
                currentListAdapter.getDisplayResolveInfoCount(), MAX_LOG_RANK_POSITION);

        for (int i = 0; i < maxRankedResults; i++) {
            if (currentListAdapter.getDisplayResolveInfo(i)
                    .getResolveInfo().activityInfo.packageName.equals(targetPackageName)) {
                return i;
            }
        }
        return -1;
    }

    protected void applyFooterView(int height) {
        mChooserMultiProfilePagerAdapter.setFooterHeightInEveryAdapter(height);
    }

    private void logDirectShareTargetReceived(UserHandle forUser) {
        ProfileRecord profileRecord = getProfileRecord(forUser);
        if (profileRecord == null) {
            return;
        }
        getEventLog().logDirectShareTargetReceived(
                MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_SHORTCUT_MANAGER,
                (int) (SystemClock.elapsedRealtime() - profileRecord.loadingStartTime));
    }

    void updateModelAndChooserCounts(TargetInfo info) {
        if (info != null && info.isMultiDisplayResolveInfo()) {
            info = ((MultiDisplayResolveInfo) info).getSelectedTarget();
        }
        if (info != null) {
            sendClickToAppPredictor(info);
            final ResolveInfo ri = info.getResolveInfo();
            Intent targetIntent = mRequest.getTargetIntent();
            if (ri != null && ri.activityInfo != null && targetIntent != null) {
                ChooserListAdapter currentListAdapter =
                        mChooserMultiProfilePagerAdapter.getActiveListAdapter();
                if (currentListAdapter != null) {
                    sendImpressionToAppPredictor(info, currentListAdapter);
                    currentListAdapter.updateModel(info);
                    currentListAdapter.updateChooserCounts(
                            ri.activityInfo.packageName,
                            targetIntent.getAction(),
                            ri.userHandle);
                }
                if (DEBUG) {
                    Log.d(TAG, "ResolveInfo Package is " + ri.activityInfo.packageName);
                    Log.d(TAG, "Action to be updated is " + targetIntent.getAction());
                }
            } else if (DEBUG) {
                Log.d(TAG, "Can not log Chooser Counts of null ResolveInfo");
            }
        }
        mIsSuccessfullySelected = true;
    }

    private void maybeRemoveSharedText(@NonNull TargetInfo targetInfo) {
        Intent targetIntent = targetInfo.getTargetIntent();
        if (targetIntent == null) {
            return;
        }
        Intent originalTargetIntent = new Intent(mRequest.getTargetIntent());
        // Our TargetInfo implementations add associated component to the intent, let's do the same
        // for the sake of the comparison below.
        if (targetIntent.getComponent() != null) {
            originalTargetIntent.setComponent(targetIntent.getComponent());
        }
        // Use filterEquals as a way to check that the primary intent is in use (and not an
        // alternative one). For example, an app is sharing an image and a link with mime type
        // "image/png" and provides an alternative intent to share only the link with mime type
        // "text/uri". Should there be a target that accepts only the latter, the alternative intent
        // will be used and we don't want to exclude the link from it.
        if (mExcludeSharedText && originalTargetIntent.filterEquals(targetIntent)) {
            targetIntent.removeExtra(Intent.EXTRA_TEXT);
        }
    }

    private void sendImpressionToAppPredictor(TargetInfo targetInfo, ChooserListAdapter adapter) {
        // Send DS target impression info to AppPredictor, only when user chooses app share.
        if (targetInfo.isChooserTargetInfo()) {
            return;
        }

        AppPredictor directShareAppPredictor = getAppPredictor(
                mChooserMultiProfilePagerAdapter.getCurrentUserHandle());
        if (directShareAppPredictor == null) {
            return;
        }
        List<TargetInfo> surfacedTargetInfo = adapter.getSurfacedTargetInfo();
        List<AppTargetId> targetIds = new ArrayList<>();
        for (TargetInfo chooserTargetInfo : surfacedTargetInfo) {
            ShortcutInfo shortcutInfo = chooserTargetInfo.getDirectShareShortcutInfo();
            if (shortcutInfo != null) {
                ComponentName componentName =
                        chooserTargetInfo.getChooserTargetComponentName();
                targetIds.add(new AppTargetId(
                        String.format(
                                "%s/%s/%s",
                                shortcutInfo.getId(),
                                componentName.flattenToString(),
                                SHORTCUT_TARGET)));
            }
        }
        directShareAppPredictor.notifyLaunchLocationShown(LAUNCH_LOCATION_DIRECT_SHARE, targetIds);
    }

    private void sendClickToAppPredictor(TargetInfo targetInfo) {
        if (!targetInfo.isChooserTargetInfo()) {
            return;
        }

        AppPredictor directShareAppPredictor = getAppPredictor(
                mChooserMultiProfilePagerAdapter.getCurrentUserHandle());
        if (directShareAppPredictor == null) {
            return;
        }
        AppTarget appTarget = targetInfo.getDirectShareAppTarget();
        if (appTarget != null) {
            // This is a direct share click that was provided by the APS
            directShareAppPredictor.notifyAppTargetEvent(
                    new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(LAUNCH_LOCATION_DIRECT_SHARE)
                        .build());
        }
    }

    @Nullable
    private AppPredictor getAppPredictor(UserHandle userHandle) {
        ProfileRecord record = getProfileRecord(userHandle);
        // We cannot use APS service when clone profile is present as APS service cannot sort
        // cross profile targets as of now.
        return ((record == null) || (mProfiles.getCloneUserPresent()))
                ? null : record.appPredictor;
    }

    protected EventLog getEventLog() {
        return mEventLog;
    }

    private ChooserGridAdapter createChooserGridAdapter(
            Context context,
            List<Intent> payloadIntents,
            Intent[] initialIntents,
            UserHandle userHandle) {
        ChooserListAdapter chooserListAdapter = createChooserListAdapter(
                context,
                payloadIntents,
                initialIntents,
                /* TODO: not used, remove. rList= */ null,
                /* TODO: not used, remove. filterLastUsed= */ false,
                createListController(userHandle),
                userHandle,
                mRequest.getTargetIntent(),
                mRequest.getReferrerFillInIntent(),
                mMaxTargetsPerRow
        );

        return new ChooserGridAdapter(
                context,
                new ChooserGridAdapter.ChooserActivityDelegate() {
                    @Override
                    public void onTargetSelected(int itemIndex) {
                        startSelected(itemIndex, false, true);
                    }

                    @Override
                    public void onTargetLongPressed(int selectedPosition) {
                        final TargetInfo longPressedTargetInfo =
                                mChooserMultiProfilePagerAdapter
                                .getActiveListAdapter()
                                .targetInfoForPosition(
                                        selectedPosition, /* filtered= */ true);
                        // Only a direct share target or an app target is expected
                        if (longPressedTargetInfo.isDisplayResolveInfo()
                                || longPressedTargetInfo.isSelectableTargetInfo()) {
                            showTargetDetails(longPressedTargetInfo);
                        }
                    }
                },
                chooserListAdapter,
                shouldShowContentPreview(),
                mMaxTargetsPerRow);
    }

    @VisibleForTesting
    public ChooserListAdapter createChooserListAdapter(
            Context context,
            List<Intent> payloadIntents,
            Intent[] initialIntents,
            List<ResolveInfo> rList,
            boolean filterLastUsed,
            ResolverListController resolverListController,
            UserHandle userHandle,
            Intent targetIntent,
            Intent referrerFillInIntent,
            int maxTargetsPerRow) {
        UserHandle initialIntentsUserSpace = mProfiles.getQueryIntentsHandle(userHandle);
        return new ChooserListAdapter(
                context,
                payloadIntents,
                initialIntents,
                rList,
                filterLastUsed,
                resolverListController,
                userHandle,
                targetIntent,
                referrerFillInIntent,
                this,
                mPackageManager,
                getEventLog(),
                maxTargetsPerRow,
                initialIntentsUserSpace,
                mTargetDataLoader,
                () -> {
                    ProfileRecord record = getProfileRecord(userHandle);
                    if (record != null && record.shortcutLoader != null) {
                        record.shortcutLoader.reset();
                    }
                });
    }

    private void onWorkProfileStatusUpdated() {
        UserHandle workUser = mProfiles.getWorkHandle();
        ProfileRecord record = workUser == null ? null : getProfileRecord(workUser);
        if (record != null && record.shortcutLoader != null) {
            record.shortcutLoader.reset();
        }
        if (mChooserMultiProfilePagerAdapter.getCurrentUserHandle().equals(
                mProfiles.getWorkHandle())) {
            mChooserMultiProfilePagerAdapter.rebuildActiveTab(true);
        } else {
            mChooserMultiProfilePagerAdapter.clearInactiveProfileCache();
        }
    }

    @VisibleForTesting
    protected ChooserListController createListController(UserHandle userHandle) {
        AppPredictor appPredictor = getAppPredictor(userHandle);
        AbstractResolverComparator resolverComparator;
        if (appPredictor != null) {
            resolverComparator = new AppPredictionServiceResolverComparator(
                    this,
                    mRequest.getTargetIntent(),
                    mRequest.getLaunchedFromPackage(),
                    appPredictor,
                    userHandle,
                    getEventLog(),
                    mNearbyShare.orElse(null)
            );
        } else {
            resolverComparator =
                    new ResolverRankerServiceResolverComparator(
                            this,
                            mRequest.getTargetIntent(),
                            mRequest.getReferrerPackage(),
                            null,
                            getEventLog(),
                            getResolverRankerServiceUserHandleList(userHandle),
                            mNearbyShare.orElse(null));
        }

        return new ChooserListController(
                this,
                mPackageManager,
                mRequest.getTargetIntent(),
                mRequest.getReferrerPackage(),
                mViewModel.getActivityModel().getLaunchedFromUid(),
                resolverComparator,
                mProfiles.getQueryIntentsHandle(userHandle),
                mRequest.getFilteredComponentNames(),
                mPinnedSharedPrefs);
    }

    private ChooserContentPreviewUi.ActionFactory decorateActionFactoryWithRefinement(
            ChooserContentPreviewUi.ActionFactory originalFactory) {
        if (!refineSystemActions()) {
            return originalFactory;
        }

        return new ChooserContentPreviewUi.ActionFactory() {
            @Override
            @Nullable
            public Runnable getEditButtonRunnable() {
                if (originalFactory.getEditButtonRunnable() == null) return null;
                return () -> {
                    if (!mRefinementManager.maybeHandleSelection(
                            RefinementType.EDIT_ACTION,
                            List.of(mRequest.getTargetIntent()),
                            null,
                            mRequest.getRefinementIntentSender(),
                            getApplication(),
                            getMainThreadHandler())) {
                        originalFactory.getEditButtonRunnable().run();
                    }
                };
            }

            @Override
            @Nullable
            public Runnable getCopyButtonRunnable() {
                if (originalFactory.getCopyButtonRunnable() == null) return null;
                return () -> {
                    if (!mRefinementManager.maybeHandleSelection(
                            RefinementType.COPY_ACTION,
                            List.of(mRequest.getTargetIntent()),
                            null,
                            mRequest.getRefinementIntentSender(),
                            getApplication(),
                            getMainThreadHandler())) {
                        originalFactory.getCopyButtonRunnable().run();
                    }
                };
            }

            @Override
            public List<ActionRow.Action> createCustomActions() {
                return originalFactory.createCustomActions();
            }

            @Override
            @Nullable
            public ActionRow.Action getModifyShareAction() {
                return originalFactory.getModifyShareAction();
            }

            @Override
            public Consumer<Boolean> getExcludeSharedTextAction() {
                return originalFactory.getExcludeSharedTextAction();
            }
        };
    }

    private ChooserActionFactory createChooserActionFactory(Intent targetIntent) {
        return new ChooserActionFactory(
                this,
                targetIntent,
                mRequest.getLaunchedFromPackage(),
                mRequest.getChooserActions(),
                mImageEditor,
                getEventLog(),
                (isExcluded) -> mExcludeSharedText = isExcluded,
                this::getFirstVisibleImgPreviewView,
                new ChooserActionFactory.ActionActivityStarter() {
                    @Override
                    public void safelyStartActivityAsPersonalProfileUser(TargetInfo targetInfo) {
                        safelyStartActivityAsUser(
                                targetInfo,
                                mProfiles.getPersonalHandle()
                        );
                        Log.d(TAG, "safelyStartActivityAsPersonalProfileUser("
                                + targetInfo + "): finishing!");
                        finish();
                    }

                    @Override
                    public void safelyStartActivityAsPersonalProfileUserWithSharedElementTransition(
                            TargetInfo targetInfo, View sharedElement, String sharedElementName) {
                        ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(
                                ChooserActivity.this, sharedElement, sharedElementName);
                        safelyStartActivityAsUser(
                                targetInfo,
                                mProfiles.getPersonalHandle(),
                                options.toBundle());
                        // Can't finish right away because the shared element transition may not
                        // be ready to start.
                        mFinishWhenStopped = true;
                    }
                },
                mShareResultSender,
                this::finishWithStatus,
                mClipboardManager);
    }

    private Supplier<ActionRow.Action> createModifyShareActionFactory() {
        return () -> ChooserActionFactory.createCustomAction(
                ChooserActivity.this,
                mRequest.getModifyShareAction(),
                () -> getEventLog().logActionSelected(EventLog.SELECTION_TYPE_MODIFY_SHARE),
                mShareResultSender,
                this::finishWithStatus);
    }

    private void finishWithStatus(@Nullable Integer status) {
        if (status != null) {
            setResult(status);
        }
        Log.d(TAG, "finishWithStatus: result=" + status);
        finish();
    }

    /*
     * Need to dynamically adjust how many icons can fit per row before we add them,
     * which also means setting the correct offset to initially show the content
     * preview area + 2 rows of targets
     */
    private void handleLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        if (mChooserMultiProfilePagerAdapter == null || !isProfilePagerAdapterAttached()) {
            return;
        }
        RecyclerView recyclerView = mChooserMultiProfilePagerAdapter.getActiveAdapterView();
        ChooserGridAdapter gridAdapter = mChooserMultiProfilePagerAdapter.getCurrentRootAdapter();
        // Skip height calculation if recycler view was scrolled to prevent it inaccurately
        // calculating the height, as the logic below does not account for the scrolled offset.
        if (gridAdapter == null || recyclerView == null
                || recyclerView.computeVerticalScrollOffset() != 0) {
            return;
        }
        if (delayDrawerOffsetCalculation() && !gridAdapter.getListAdapter().areAppTargetsReady()) {
            return;
        }

        final int availableWidth = right - left - v.getPaddingLeft() - v.getPaddingRight();
        final int maxChooserWidth = getResources().getDimensionPixelSize(R.dimen.chooser_width);
        boolean isLayoutUpdated =
                gridAdapter.calculateChooserTargetWidth(
                        maxChooserWidth >= 0
                                ? Math.min(maxChooserWidth, availableWidth)
                                : availableWidth)
                || recyclerView.getAdapter() == null
                || availableWidth != mCurrAvailableWidth;

        mCurrAvailableWidth = availableWidth;
        if (isLayoutUpdated) {
            // It is very important we call setAdapter from here. Otherwise in some cases
            // the resolver list doesn't get populated, such as b/150922090, b/150918223
            // and b/150936654
            recyclerView.setAdapter(gridAdapter);
            ((GridLayoutManager) recyclerView.getLayoutManager()).setSpanCount(
                    mMaxTargetsPerRow);

            updateTabPadding();
        }

        if (mChooserMultiProfilePagerAdapter.getActiveProfile() != mInitialProfile) {
            return;
        }

        getMainThreadHandler().post(() -> {
            if (mResolverDrawerLayout == null) {
                return;
            }
            int offset = calculateDrawerOffset(top, bottom, recyclerView, gridAdapter);
            mResolverDrawerLayout.setCollapsibleHeightReserved(offset);
            mEnterTransitionAnimationDelegate.markOffsetCalculated();
            mLastAppliedInsets = mSystemWindowInsets;
        });
    }

    private int calculateDrawerOffset(
            int top, int bottom, RecyclerView recyclerView, ChooserGridAdapter gridAdapter) {

        int offset = mSystemWindowInsets != null ? mSystemWindowInsets.bottom : 0;
        int rowsToShow = gridAdapter.getServiceTargetRowCount()
                + gridAdapter.getCallerAndRankedTargetRowCount();

        // then this is most likely not a SEND_* action, so check
        // the app target count
        if (rowsToShow == 0) {
            rowsToShow = gridAdapter.getRowCount();
        }

        // still zero? then use a default height and leave, which
        // can happen when there are no targets to show
        if (rowsToShow == 0 && !shouldShowStickyContentPreview()) {
            offset += getResources().getDimensionPixelSize(
                    R.dimen.chooser_max_collapsed_height);
            return offset;
        }

        View stickyContentPreview = findViewById(com.android.internal.R.id.content_preview_container);
        if (shouldShowStickyContentPreview() && isStickyContentPreviewShowing()) {
            offset += stickyContentPreview.getHeight();
        }

        if (mProfiles.getWorkProfilePresent()) {
            offset += findViewById(com.android.internal.R.id.tabs).getHeight();
        }

        if (recyclerView.getVisibility() == View.VISIBLE) {
            rowsToShow = Math.min(4, rowsToShow);
            boolean shouldShowExtraRow = shouldShowExtraRow(rowsToShow);
            mLastNumberOfChildren = recyclerView.getChildCount();
            for (int i = 0, childCount = recyclerView.getChildCount();
                    i < childCount && rowsToShow > 0; i++) {
                View child = recyclerView.getChildAt(i);
                if (((GridLayoutManager.LayoutParams)
                        child.getLayoutParams()).getSpanIndex() != 0) {
                    continue;
                }
                int height = child.getHeight();
                offset += height;
                if (shouldShowExtraRow) {
                    offset += height;
                }
                rowsToShow--;
            }
        } else {
            ViewGroup currentEmptyStateView =
                    mChooserMultiProfilePagerAdapter.getActiveEmptyStateView();
            if (currentEmptyStateView.getVisibility() == View.VISIBLE) {
                offset += currentEmptyStateView.getHeight();
            }
        }

        return Math.min(offset, bottom - top);
    }

    private boolean isProfilePagerAdapterAttached() {
        return mChooserMultiProfilePagerAdapter == mViewPager.getAdapter();
    }

    /**
     * If we have a tabbed view and are showing 1 row in the current profile and an empty
     * state screen in another profile, to prevent cropping of the empty state screen we show
     * a second row in the current profile.
     */
    private boolean shouldShowExtraRow(int rowsToShow) {
        return rowsToShow == 1
                && mChooserMultiProfilePagerAdapter
                        .shouldShowEmptyStateScreenInAnyInactiveAdapter();
    }

    protected void onListRebuilt(ResolverListAdapter listAdapter, boolean rebuildComplete) {
        Log.d(TAG, "onListRebuilt(listAdapter.userHandle=" + listAdapter.getUserHandle() + ", "
                + "rebuildComplete=" + rebuildComplete + ")");
        setupScrollListener();
        maybeSetupGlobalLayoutListener();

        ChooserListAdapter chooserListAdapter = (ChooserListAdapter) listAdapter;
        UserHandle listProfileUserHandle = chooserListAdapter.getUserHandle();
        if (listProfileUserHandle.equals(mChooserMultiProfilePagerAdapter.getCurrentUserHandle())) {
            mChooserMultiProfilePagerAdapter.getActiveAdapterView()
                    .setAdapter(mChooserMultiProfilePagerAdapter.getCurrentRootAdapter());
            mChooserMultiProfilePagerAdapter
                    .setupListAdapter(mChooserMultiProfilePagerAdapter.getCurrentPage());
        }

        //TODO: move this block inside ChooserListAdapter (should be called when
        // ResolverListAdapter#mPostListReadyRunnable is executed.
        chooserListAdapter.updateAlphabeticalList(
                rebuildComplete,
                () -> onAppTargetsLoaded(listAdapter));

        if (rebuildComplete) {
            long duration = Tracer.INSTANCE.endAppTargetLoadingSection(listProfileUserHandle);
            if (duration >= 0) {
                Log.d(TAG, "app target loading time " + duration + " ms");
            }
            if (!fixShortcutsFlashingFixed()) {
                addCallerChooserTargets(chooserListAdapter);
            }
            getEventLog().logSharesheetAppLoadComplete();
            maybeQueryAdditionalPostProcessingTargets(
                    listProfileUserHandle,
                    chooserListAdapter.getDisplayResolveInfos());
            mLatencyTracker.onActionEnd(ACTION_LOAD_SHARE_SHEET);
        }
    }

    private void maybeQueryAdditionalPostProcessingTargets(
            UserHandle userHandle,
            DisplayResolveInfo[] displayResolveInfos) {
        ProfileRecord record = getProfileRecord(userHandle);
        if (record == null || record.shortcutLoader == null) {
            return;
        }
        record.loadingStartTime = SystemClock.elapsedRealtime();
        record.shortcutLoader.updateAppTargets(displayResolveInfos);
    }

    @MainThread
    private void onShortcutsLoaded(UserHandle userHandle, ShortcutLoader.Result result) {
        if (DEBUG) {
            Log.d(TAG, "onShortcutsLoaded for user: " + userHandle);
        }
        mDirectShareShortcutInfoCache.putAll(result.getDirectShareShortcutInfoCache());
        mDirectShareAppTargetCache.putAll(result.getDirectShareAppTargetCache());
        ChooserListAdapter adapter =
                mChooserMultiProfilePagerAdapter.getListAdapterForUserHandle(userHandle);
        if (adapter != null) {
            if (fixShortcutsFlashingFixed()) {
                adapter.setDirectTargetsEnabled(true);
                adapter.resetDirectTargets();
                addCallerChooserTargets(adapter);
            }
            for (ShortcutLoader.ShortcutResultInfo resultInfo : result.getShortcutsByApp()) {
                adapter.addServiceResults(
                        resultInfo.getAppTarget(),
                        resultInfo.getShortcuts(),
                        result.isFromAppPredictor()
                                ? TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE
                                : TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER,
                        mDirectShareShortcutInfoCache,
                        mDirectShareAppTargetCache);
            }
            adapter.completeServiceTargetLoading();
        }

        if (mChooserMultiProfilePagerAdapter.getActiveListAdapter() == adapter) {
            long duration = Tracer.INSTANCE.endLaunchToShortcutTrace();
            if (duration >= 0) {
                Log.d(TAG, "stat to first shortcut time: " + duration + " ms");
            }
        }
        logDirectShareTargetReceived(userHandle);
        sendVoiceChoicesIfNeeded();
        getEventLog().logSharesheetDirectLoadComplete();
    }

    private void setupScrollListener() {
        if (mResolverDrawerLayout == null) {
            return;
        }
        int elevatedViewResId = mProfiles.getWorkProfilePresent()
                ? com.android.internal.R.id.tabs : com.android.internal.R.id.chooser_header;
        final View elevatedView = mResolverDrawerLayout.findViewById(elevatedViewResId);
        final float defaultElevation = elevatedView.getElevation();
        final float chooserHeaderScrollElevation =
                getResources().getDimensionPixelSize(R.dimen.chooser_header_scroll_elevation);
        mChooserMultiProfilePagerAdapter.getActiveAdapterView().addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(RecyclerView view, int scrollState) {
                        if (scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                            if (mScrollStatus == SCROLL_STATUS_SCROLLING_VERTICAL) {
                                mScrollStatus = SCROLL_STATUS_IDLE;
                                setHorizontalScrollingEnabled(true);
                            }
                        } else if (scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                            if (mScrollStatus == SCROLL_STATUS_IDLE) {
                                mScrollStatus = SCROLL_STATUS_SCROLLING_VERTICAL;
                                setHorizontalScrollingEnabled(false);
                            }
                        }
                    }

                    @Override
                    public void onScrolled(RecyclerView view, int dx, int dy) {
                        if (view.getChildCount() > 0) {
                            View child = view.getLayoutManager().findViewByPosition(0);
                            if (child == null || child.getTop() < 0) {
                                elevatedView.setElevation(chooserHeaderScrollElevation);
                                return;
                            }
                        }

                        elevatedView.setElevation(defaultElevation);
                    }
                });
    }

    private void maybeSetupGlobalLayoutListener() {
        if (mProfiles.getWorkProfilePresent()) {
            return;
        }
        final View recyclerView = mChooserMultiProfilePagerAdapter.getActiveAdapterView();
        recyclerView.getViewTreeObserver()
                .addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        // Fixes an issue were the accessibility border disappears on list creation.
                        recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        final TextView titleView = findViewById(com.android.internal.R.id.title);
                        if (titleView != null) {
                            titleView.setFocusable(true);
                            titleView.setFocusableInTouchMode(true);
                            titleView.requestFocus();
                            titleView.requestAccessibilityFocus();
                        }
                    }
                });
    }

    /**
     * The sticky content preview is shown only when we have a tabbed view. It's shown above
     * the tabs so it is not part of the scrollable list. If we are not in tabbed view,
     * we instead show the content preview as a regular list item.
     */
    private boolean shouldShowStickyContentPreview() {
        return shouldShowStickyContentPreviewNoOrientationCheck();
    }

    private boolean shouldShowStickyContentPreviewNoOrientationCheck() {
        if (isInteractiveSession() || !shouldShowContentPreview()) {
            return false;
        }
        ResolverListAdapter adapter = mChooserMultiProfilePagerAdapter.getListAdapterForUserHandle(
                UserHandle.of(UserHandle.myUserId()));
        boolean isEmpty = adapter == null || adapter.getCount() == 0;
        return !isEmpty || shouldShowContentPreviewWhenEmpty();
    }

    /**
     * This method could be used to override the default behavior when we hide the preview area
     * when the current tab doesn't have any items.
     *
     * @return true if we want to show the content preview area even if the tab for the current
     *         user is empty
     */
    protected boolean shouldShowContentPreviewWhenEmpty() {
        return false;
    }

    /**
     * @return true if we want to show the content preview area
     */
    protected boolean shouldShowContentPreview() {
        return mRequest.isSendActionTarget();
    }

    private void updateStickyContentPreview() {
        if (shouldShowStickyContentPreviewNoOrientationCheck()) {
            // The sticky content preview is only shown when we show the work and personal tabs.
            // We don't show it in landscape as otherwise there is no room for scrolling.
            // If the sticky content preview will be shown at some point with orientation change,
            // then always preload it to avoid subsequent resizing of the share sheet.
            ViewGroup contentPreviewContainer =
                    findViewById(com.android.internal.R.id.content_preview_container);
            if (contentPreviewContainer.getChildCount() == 0) {
                ViewGroup contentPreviewView = createContentPreviewView(contentPreviewContainer);
                contentPreviewContainer.addView(contentPreviewView);
            }
        }
        if (shouldShowStickyContentPreview()) {
            showStickyContentPreview();
        } else {
            hideStickyContentPreview();
        }
    }

    private void showStickyContentPreview() {
        if (isStickyContentPreviewShowing()) {
            return;
        }
        ViewGroup contentPreviewContainer = findViewById(com.android.internal.R.id.content_preview_container);
        contentPreviewContainer.setVisibility(View.VISIBLE);
    }

    private boolean isStickyContentPreviewShowing() {
        ViewGroup contentPreviewContainer = findViewById(com.android.internal.R.id.content_preview_container);
        return contentPreviewContainer.getVisibility() == View.VISIBLE;
    }

    private void hideStickyContentPreview() {
        if (!isStickyContentPreviewShowing()) {
            return;
        }
        ViewGroup contentPreviewContainer = findViewById(com.android.internal.R.id.content_preview_container);
        contentPreviewContainer.setVisibility(View.GONE);
    }

    protected String getMetricsCategory() {
        return METRICS_CATEGORY_CHOOSER;
    }

    protected void onProfileTabSelected(int currentPage) {
        setupViewVisibilities();
        maybeLogProfileChange();
        if (mProfiles.getWorkProfilePresent()) {
            // The device policy logger is only concerned with sessions that include a work profile.
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.RESOLVER_SWITCH_TABS)
                    .setInt(currentPage)
                    .setStrings(getMetricsCategory())
                    .write();
        }

        // This fixes an edge case where after performing a variety of gestures, vertical scrolling
        // ends up disabled. That's because at some point the old tab's vertical scrolling is
        // disabled and the new tab's is enabled. For context, see b/159997845
        setVerticalScrollEnabled(true);
        if (mResolverDrawerLayout != null) {
            mResolverDrawerLayout.scrollNestedScrollableChildBackToTop();
        }
    }

    private int getInteractiveSessionTopSpacing() {
        return getResources().getDimensionPixelSize(R.dimen.chooser_preview_image_height_tall);
    }

    private boolean isInteractiveSession() {
        return interactiveSession() && mRequest.getInteractiveSessionCallback() != null
                && !isTaskRoot();
    }

    protected WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        mSystemWindowInsets = insets.getInsets(WindowInsets.Type.systemBars());
        mChooserMultiProfilePagerAdapter
                .setEmptyStateBottomOffset(mSystemWindowInsets.bottom);

        final int topSpacing = isInteractiveSession() ? getInteractiveSessionTopSpacing() : 0;
        mResolverDrawerLayout.setPadding(
                mSystemWindowInsets.left,
                mSystemWindowInsets.top + topSpacing,
                mSystemWindowInsets.right,
                0);

        // Need extra padding so the list can fully scroll up
        // To accommodate for window insets
        applyFooterView(mSystemWindowInsets.bottom);

        if (mResolverDrawerLayout != null) {
            mResolverDrawerLayout.requestLayout();
        }
        return WindowInsets.CONSUMED;
    }

    private void setHorizontalScrollingEnabled(boolean enabled) {
        mViewPager.setSwipingEnabled(enabled);
    }

    private void setVerticalScrollEnabled(boolean enabled) {
        ChooserGridLayoutManager layoutManager =
                (ChooserGridLayoutManager) mChooserMultiProfilePagerAdapter.getActiveAdapterView()
                        .getLayoutManager();
        layoutManager.setVerticalScrollEnabled(enabled);
    }

    void onHorizontalSwipeStateChanged(int state) {
        if (state == ViewPager.SCROLL_STATE_DRAGGING) {
            if (mScrollStatus == SCROLL_STATUS_IDLE) {
                mScrollStatus = SCROLL_STATUS_SCROLLING_HORIZONTAL;
                setVerticalScrollEnabled(false);
            }
        } else if (state == ViewPager.SCROLL_STATE_IDLE) {
            if (mScrollStatus == SCROLL_STATUS_SCROLLING_HORIZONTAL) {
                mScrollStatus = SCROLL_STATUS_IDLE;
                setVerticalScrollEnabled(true);
            }
        }
    }

    protected void maybeLogProfileChange() {
        getEventLog().logSharesheetProfileChanged();
    }

    private static class ProfileRecord {
        public final Profile profile;

        /** The {@link AppPredictor} for this profile, if any. */
        @Nullable
        public final AppPredictor appPredictor;
        /**
         * null if we should not load shortcuts.
         */
        @Nullable
        public final ShortcutLoader shortcutLoader;
        public final List<ChooserTarget> callerTargets;
        public long loadingStartTime;

        private ProfileRecord(
                Profile profile,
                @Nullable AppPredictor appPredictor,
                @Nullable ShortcutLoader shortcutLoader,
                List<ChooserTarget> callerTargets) {
            this.profile = profile;
            this.appPredictor = appPredictor;
            this.shortcutLoader = shortcutLoader;
            this.callerTargets = callerTargets;
        }

        public void destroy() {
            if (appPredictor != null) {
                appPredictor.destroy();
            }
            if (shortcutLoader != null) {
                shortcutLoader.destroy();
            }
        }
    }
}
