package com.ironsource.adapters.bidmachine.banner

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ironsource.adapters.bidmachine.BidMachineAdapter
import com.ironsource.adapters.bidmachine.BidMachineConstants
import com.ironsource.mediationsdk.AdapterUtils
import com.ironsource.mediationsdk.ISBannerSize
import com.ironsource.mediationsdk.adunit.adapter.listener.BannerAdListener
import com.ironsource.mediationsdk.adunit.adapter.utility.AdData
import com.ironsource.mediationsdk.adunit.adapter.utility.AdapterErrorType
import com.ironsource.mediationsdk.adunit.adapter.utility.AdapterErrors
import com.ironsource.mediationsdk.bidding.BiddingDataCallback
import com.ironsource.mediationsdk.logger.IronLog
import com.ironsource.mediationsdk.model.NetworkSettings
import com.unity3d.mediation.adapters.levelplay.LevelPlayBaseBanner
import io.bidmachine.AdLoadCallback
import io.bidmachine.BMError
import io.bidmachine.format.bannerad.BannerView
import io.bidmachine.model.BannerAdSize

class BidMachineBannerAdapter(networkSettings: NetworkSettings) :
    LevelPlayBaseBanner<BidMachineAdapter>(networkSettings) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bannerAdView: BannerView? = null
    private var loadId = 0

    // region LevelPlay Banner API

    @Synchronized
    override fun loadAd(
        adData: AdData,
        activity: Activity,
        bannerSize: ISBannerSize,
        listener: BannerAdListener
    ) {
        destroyAd(adData)

        val instance = BidMachineAdapter.bidMachineInstance
        if (instance == null) {
            listener.onAdLoadFailed(
                AdapterErrorType.ADAPTER_ERROR_TYPE_INTERNAL,
                BMError.NOT_INITIALIZED,
                BidMachineConstants.SDK_NOT_INITIALIZED
            )
            return
        }

        val placementId = adData.getString(BidMachineConstants.PLACEMENT_ID_KEY)
        IronLog.ADAPTER_API.verbose(BidMachineConstants.Logs.PLACEMENT_ID.format(placementId ?: ""))

        val appContext = activity.applicationContext
        val bidMachineBannerSize = getBannerSize(bannerSize, appContext)
        if (bidMachineBannerSize == null) {
            IronLog.INTERNAL.error(BidMachineConstants.UNSUPPORTED_BANNER_SIZE)
            listener.onAdLoadFailed(
                AdapterErrorType.ADAPTER_ERROR_TYPE_INTERNAL,
                AdapterErrors.ADAPTER_ERROR_INTERNAL,
                BidMachineConstants.UNSUPPORTED_BANNER_SIZE
            )
            return
        }

        val currentLoadId = loadId
        val bidMachineListener = BidMachineBannerListener(listener)
        val loadCallback = object : AdLoadCallback<BannerView> {
            override fun onAdLoaded(ad: BannerView) {
                synchronized(this@BidMachineBannerAdapter) {
                    // A destroyed or replaced load must not retain an ad or notify LevelPlay.
                    if (currentLoadId != loadId) {
                        ad.destroy()
                        return
                    }
                    bannerAdView = ad
                    ad.setListener(bidMachineListener)
                    bidMachineListener.onAdLoaded(ad)
                }
            }

            override fun onAdLoadFailed(bmError: BMError) {
                synchronized(this@BidMachineBannerAdapter) {
                    if (currentLoadId == loadId) {
                        bidMachineListener.onAdLoadFailed(bmError)
                    }
                }
            }
        }

        val bidResponse = adData.serverData
        mainHandler.post {
            synchronized(this@BidMachineBannerAdapter) {
                if (currentLoadId != loadId) {
                    return@post
                }
                if (!bidResponse.isNullOrEmpty()) {
                    instance.loadBanner(bidResponse, bidMachineBannerSize, loadCallback)
                } else {
                    instance.loadBanner(
                        BidMachineAdapter.createPlacement(placementId),
                        bidMachineBannerSize,
                        loadCallback
                    )
                }
            }
        }
    }

    @Synchronized
    override fun destroyAd(adData: AdData) {
        IronLog.ADAPTER_API.verbose()
        loadId++
        val ad = bannerAdView
        bannerAdView = null
        ad?.setListener(null)
        mainHandler.post {
            ad?.destroy()
        }
    }

    override fun collectBiddingData(
        adData: AdData?,
        context: Context,
        biddingDataCallback: BiddingDataCallback
    ) {
        val placementId = adData?.getString(BidMachineConstants.PLACEMENT_ID_KEY)
        IronLog.ADAPTER_API.verbose(BidMachineConstants.Logs.PLACEMENT_ID.format(placementId ?: ""))

        val networkAdapter = getNetworkAdapter()
        if (networkAdapter == null) {
            IronLog.INTERNAL.error(BidMachineConstants.Logs.NETWORK_ADAPTER_IS_NULL)
            biddingDataCallback.onFailure(BidMachineConstants.Logs.NETWORK_ADAPTER_IS_NULL)
            return
        }

        val bannerSize = adData?.adUnitData?.get(BidMachineConstants.BANNER_SIZE_KEY)
        if (bannerSize !is ISBannerSize) {
            IronLog.INTERNAL.error(BidMachineConstants.BANNER_SIZE_INVALID)
            biddingDataCallback.onFailure(BidMachineConstants.Logs.TOKEN_FAILED.format(BidMachineConstants.BANNER_SIZE_INVALID))
            return
        }

        if (getBannerSize(bannerSize, context.applicationContext) == null) {
            IronLog.INTERNAL.error(BidMachineConstants.UNSUPPORTED_BANNER_SIZE)
            biddingDataCallback.onFailure(BidMachineConstants.Logs.TOKEN_FAILED.format(BidMachineConstants.UNSUPPORTED_BANNER_SIZE))
            return
        }

        networkAdapter.collectBiddingData(biddingDataCallback, placementId)
    }

    // endregion

    // region Helper Methods

    private fun getBannerSize(bannerSize: ISBannerSize, context: Context): BannerAdSize? {
        if (bannerSize.isAdaptive) {
            val width = bannerSize.toLevelPlayAdSize(context)?.getWidth() ?: 0
            if (width > 0) {
                return BannerAdSize.adaptive(width, 0)
            }
        }

        return when (bannerSize.description) {
            BidMachineConstants.BANNER -> BannerAdSize.Banner
            BidMachineConstants.LEADERBOARD -> BannerAdSize.Leaderboard
            BidMachineConstants.RECTANGLE -> BannerAdSize.MediumRectangle
            BidMachineConstants.SMART -> if (AdapterUtils.isLargeScreen(context)) BannerAdSize.Leaderboard else BannerAdSize.Banner
            else -> null
        }
    }

    // endregion
}
