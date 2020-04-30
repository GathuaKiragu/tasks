package org.tasks.analytics

import android.content.Context
import android.os.Bundle
import com.android.billingclient.api.BillingClient.BillingResponse
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import io.fabric.sdk.android.Fabric
import org.tasks.billing.BillingClientImpl
import org.tasks.injection.ApplicationScope
import org.tasks.injection.ForApplication
import org.tasks.jobs.RemoteConfigWork
import org.tasks.preferences.Preferences
import timber.log.Timber
import javax.inject.Inject

@ApplicationScope
class Firebase @Inject constructor(@ForApplication context: Context?, preferences: Preferences) {

    private var enabled: Boolean = preferences.isTrackingEnabled
    private var analytics: FirebaseAnalytics? = null
    private var remoteConfig: FirebaseRemoteConfig? = null

    fun reportException(t: Throwable?) {
        Timber.e(t)
        if (enabled) {
            Crashlytics.logException(t)
        }
    }

    fun reportIabResult(@BillingResponse response: Int, sku: String?) {
        if (!enabled) {
            return
        }
        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, sku)
        bundle.putString(FirebaseAnalytics.Param.SUCCESS, BillingClientImpl.BillingResponseToString(response))
        analytics!!.logEvent(FirebaseAnalytics.Event.ECOMMERCE_PURCHASE, bundle)
    }

    fun updateRemoteConfig() {
        if (!enabled) {
            return
        }
        remoteConfig?.fetchAndActivate()?.addOnSuccessListener {
            Timber.d(it.toString())
        }
    }

    init {
        if (enabled) {
            analytics = FirebaseAnalytics.getInstance(context!!)
            analytics?.setAnalyticsCollectionEnabled(true)
            Fabric.with(context, Crashlytics())
            remoteConfig = FirebaseRemoteConfig.getInstance()
            remoteConfig?.setConfigSettingsAsync(remoteConfigSettings {
                minimumFetchIntervalInSeconds = RemoteConfigWork.WORK_INTERVAL_HOURS
            })
        }
    }
}