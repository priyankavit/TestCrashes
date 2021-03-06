package com.microsoft.azure.mobile.push;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.RemoteMessage;
import com.microsoft.azure.mobile.AbstractMobileCenterService;
import com.microsoft.azure.mobile.channel.Channel;
import com.microsoft.azure.mobile.ingestion.models.json.LogFactory;
import com.microsoft.azure.mobile.push.ingestion.models.PushInstallationLog;
import com.microsoft.azure.mobile.push.ingestion.models.json.PushInstallationLogFactory;
import com.microsoft.azure.mobile.utils.HandlerUtils;
import com.microsoft.azure.mobile.utils.MobileCenterLog;
import com.microsoft.azure.mobile.utils.storage.StorageHelper.PreferencesStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Push notifications interface.
 */
public class Push extends AbstractMobileCenterService {

    /**
     * Google message identifier extra intent key.
     */
    @VisibleForTesting
    static final String EXTRA_GOOGLE_MESSAGE_ID = "google.message_id";

    /**
     * Intent extras not part of custom data.
     */
    @VisibleForTesting
    static final Set<String> EXTRA_STANDARD_KEYS = new HashSet<String>() {
        {
            add(EXTRA_GOOGLE_MESSAGE_ID);
            add("google.sent_time");
            add("collapse_key");
            add("from");
        }
    };

    /**
     * Name of the service.
     */
    private static final String SERVICE_NAME = "Push";

    /**
     * TAG used in logging for Analytics.
     */
    private static final String LOG_TAG = MobileCenterLog.LOG_TAG + SERVICE_NAME;

    /**
     * Constant marking event of the push group.
     */
    private static final String PUSH_GROUP = "group_push";

    /**
     * Base key for stored preferences.
     */
    private static final String PREFERENCE_PREFIX = SERVICE_NAME + ".";

    /**
     * Preference key to store push token.
     */
    @VisibleForTesting
    static final String PREFERENCE_KEY_PUSH_TOKEN = PREFERENCE_PREFIX + "push_token";

    /**
     * Firebase analytics flag.
     */
    private static boolean sFirebaseAnalyticsEnabled;

    /**
     * Shared instance.
     */
    @SuppressLint("StaticFieldLeak")
    private static Push sInstance;

    /**
     * Log factories managed by this service.
     */
    private final Map<String, LogFactory> mFactories;

    /**
     * The firebase registration identifier.
     */
    private String mPushToken;

    /**
     * Push listener.
     */
    private PushListener mInstanceListener;

    /**
     * Check if push already inspected from intent.
     * Not reset on disabled to avoid repeat push callback when enabled again...
     */
    private String mLastGoogleMessageId;

    /**
     * Current activity.
     */
    private Activity mActivity;

    /**
     * Init.
     */
    private Push() {
        mFactories = new HashMap<>();
        mFactories.put(PushInstallationLog.TYPE, new PushInstallationLogFactory());
    }

    /**
     * Get shared instance.
     *
     * @return shared instance.
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized Push getInstance() {
        if (sInstance == null) {
            sInstance = new Push();
        }
        return sInstance;
    }

    @VisibleForTesting
    static synchronized void unsetInstance() {
        sInstance = null;
    }

    /**
     * Check whether Push service is enabled or not.
     *
     * @return <code>true</code> if enabled, <code>false</code> otherwise.
     */
    @SuppressWarnings("WeakerAccess")
    public static boolean isEnabled() {
        return getInstance().isInstanceEnabled();
    }

    /**
     * Enable or disable Push service.
     *
     * @param enabled <code>true</code> to enable, <code>false</code> to disable.
     */
    @SuppressWarnings("WeakerAccess")
    public static void setEnabled(boolean enabled) {
        getInstance().setInstanceEnabled(enabled);
    }

    /**
     * Set push listener.
     *
     * @param pushListener push listener.
     */
    public static void setListener(PushListener pushListener) {
        getInstance().setInstanceListener(pushListener);
    }

    /**
     * Enable firebase analytics collection.
     *
     * @param context the context to retrieve FirebaseAnalytics instance.
     */
    @SuppressWarnings("WeakerAccess")
    public static void enableFirebaseAnalytics(@NonNull Context context) {
        MobileCenterLog.debug(LOG_TAG, "Enabling firebase analytics collection.");
        setFirebaseAnalyticsEnabled(context, true);
    }

    /**
     * Enable or disable firebase analytics collection.
     *
     * @param context the context to retrieve FirebaseAnalytics instance.
     * @param enabled <code>true</code> to enable, <code>false</code> to disable.
     */
    @SuppressWarnings("MissingPermission")
    private static void setFirebaseAnalyticsEnabled(@NonNull Context context, boolean enabled) {
        FirebaseAnalyticsUtils.setEnabled(context, enabled);
        sFirebaseAnalyticsEnabled = enabled;
    }

    /**
     * Enqueue a push installation log.
     *
     * @param pushToken the push token value
     */
    private void enqueuePushInstallationLog(@NonNull String pushToken) {
        PushInstallationLog log = new PushInstallationLog();
        log.setPushToken(pushToken);
        mChannel.enqueue(log, PUSH_GROUP);
    }

    /**
     * Handle push token update success.
     *
     * @param pushToken the push token value.
     */
    synchronized void onTokenRefresh(@NonNull String pushToken) {
        if (isInactive())
            return;
        if (mPushToken != null && mPushToken.equals(pushToken))
            return;
        MobileCenterLog.debug(LOG_TAG, "Push token: " + pushToken);
        PreferencesStorage.putString(PREFERENCE_KEY_PUSH_TOKEN, pushToken);
        enqueuePushInstallationLog(pushToken);
        mPushToken = pushToken;
    }

    /**
     * React to enable state change.
     *
     * @param enabled current state.
     */
    private synchronized void applyEnabledState(boolean enabled) {
        if (enabled && mChannel != null) {
            String token = FirebaseInstanceId.getInstance().getToken();
            if (token != null) {
                onTokenRefresh(token);
            }
        } else {

            /* Reset module state if disabled */
            mPushToken = null;
        }
    }

    @Override
    protected String getGroupName() {
        return PUSH_GROUP;
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    protected String getLoggerTag() {
        return LOG_TAG;
    }

    @Override
    protected int getTriggerCount() {
        return 1;
    }

    @Override
    public Map<String, LogFactory> getLogFactories() {
        return mFactories;
    }

    @Override
    public synchronized void onStarted(@NonNull Context context, @NonNull String appSecret, @NonNull Channel channel) {
        super.onStarted(context, appSecret, channel);
        applyEnabledState(isInstanceEnabled());
        if (!sFirebaseAnalyticsEnabled) {
            MobileCenterLog.debug(LOG_TAG, "Disabling firebase analytics collection by default.");
            setFirebaseAnalyticsEnabled(context, false);
        }
    }

    @Override
    public synchronized void setInstanceEnabled(boolean enabled) {
        super.setInstanceEnabled(enabled);
        applyEnabledState(enabled);
    }

    /**
     * Implements {@link #setListener} at instance level.
     */
    private synchronized void setInstanceListener(PushListener instanceListener) {
        mInstanceListener = instanceListener;
    }

    /*
     * We can miss onCreate onStarted depending on how developers init the SDK.
     * So look for multiple events.
     */

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        checkPushInActivityIntent(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        checkPushInActivityIntent(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        checkPushInActivityIntent(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        mActivity = null;
    }

    /**
     * Check for push message clicked from notification center in activity intent.
     *
     * @param activity activity to inspect.
     */
    private synchronized void checkPushInActivityIntent(Activity activity) {
        mActivity = activity;
        if (isEnabled() && mInstanceListener != null) {
            Bundle extras = activity.getIntent().getExtras();
            if (extras != null) {
                String googleMessageId = extras.getString(EXTRA_GOOGLE_MESSAGE_ID);
                if (googleMessageId != null && !googleMessageId.equals(mLastGoogleMessageId)) {
                    MobileCenterLog.info(LOG_TAG, "Clicked push message from background id=" + googleMessageId);
                    mLastGoogleMessageId = googleMessageId;
                    Map<String, String> customData = new HashMap<>();
                    Map<String, Object> allData = new HashMap<>();
                    for (String extra : extras.keySet()) {
                        allData.put(extra, extras.get(extra));
                        if (!EXTRA_STANDARD_KEYS.contains(extra)) {
                            customData.put(extra, extras.getString(extra));
                        }
                    }
                    MobileCenterLog.debug(LOG_TAG, "Push intent extra=" + allData);
                    mInstanceListener.onPushNotificationReceived(activity, new PushNotification(null, null, customData));
                }
            }
        }
    }

    /**
     * Called when push message received in foreground.
     *
     * @param remoteMessage push message details.
     */
    synchronized void onMessageReceived(RemoteMessage remoteMessage) {
        MobileCenterLog.info(LOG_TAG, "Received push message in foreground id=" + remoteMessage.getMessageId());
        if (isEnabled() && mInstanceListener != null) {
            String title = null;
            String message = null;
            RemoteMessage.Notification notification = remoteMessage.getNotification();
            if (notification != null) {
                title = notification.getTitle();
                message = notification.getBody();
            }
            final PushNotification pushNotification = new PushNotification(title, message, remoteMessage.getData());
            HandlerUtils.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    deliverForegroundPushNotification(pushNotification);
                }
            });
        }
    }

    /**
     * Top level method needed for synchronized code coverage.
     */
    private synchronized void deliverForegroundPushNotification(PushNotification pushNotification) {

        /* State can change between the post from 1 thread to another. */
        if (isEnabled() && mInstanceListener != null) {
            mInstanceListener.onPushNotificationReceived(mActivity, pushNotification);
        }
    }
}
