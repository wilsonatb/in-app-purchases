package com.wilsonatb.plugins.in_app_purchases

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.PendingPurchasesParams
import com.nativephp.mobile.bridge.BridgeError
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.lifecycle.NativePHPLifecycle
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object InAppPurchasesFunctions {
    private const val EVENT_PURCHASE_UPDATED = "Wilsonatb\\InAppPurchases\\Events\\PurchaseUpdated"
    private const val EVENT_CONNECTION_CHANGED = "Wilsonatb\\InAppPurchases\\Events\\ConnectionChanged"
    private const val EVENT_AVAILABILITY_CHANGED = "Wilsonatb\\InAppPurchases\\Events\\AvailabilityChanged"
    private const val EVENT_RESTORE_COMPLETED = "Wilsonatb\\InAppPurchases\\Events\\RestoreCompleted"
    private const val EVENT_OPERATION_FAILED = "Wilsonatb\\InAppPurchases\\Events\\OperationFailed"

    private const val PRODUCT_TYPE_INAPP = "inapp"
    private const val PRODUCT_TYPE_SUBS = "subs"

    private var billingClient: BillingClient? = null
    private var currentActivity: WeakReference<FragmentActivity>? = null
    private var initialized = false
    private var autoSyncOnResume = true
    private var lifecycleRegistered = false
    private var connectionState = "disconnected"
    private var lastError: Map<String, Any>? = null
    private var lastSyncAt: Long? = null
    private var countryCode: String? = null
    private var includeSuspendedSubscriptions = false

    private val productsById = ConcurrentHashMap<String, ProductDetails>()
    private val purchasesByToken = ConcurrentHashMap<String, Purchase>()
    private val pendingTokens = ConcurrentHashMap.newKeySet<String>()
    private val tokenProductTypes = ConcurrentHashMap<String, String>()
    private val purchaseKindsByProductId = ConcurrentHashMap<String, String>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            dispatchPurchaseUpdate(
                source = "purchases_updated_listener",
                requestId = null,
                purchases = emptyList(),
                extra = mapOf("status" to "canceled")
            )
            return@PurchasesUpdatedListener
        }

        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            dispatchOperationFailed(
                operation = "purchase_update",
                error = mapBillingError("purchase_update", billingResult)
            )
            return@PurchasesUpdatedListener
        }

        val incomingPurchases = purchases ?: emptyList()
        incomingPurchases.forEach { purchase ->
            purchasesByToken[purchase.purchaseToken] = purchase
            tokenProductTypes[purchase.purchaseToken] = inferPurchaseProductType(purchase)
            if (shouldMarkPendingCompletion(purchase)) {
                pendingTokens.add(purchase.purchaseToken)
            } else {
                pendingTokens.remove(purchase.purchaseToken)
            }
        }

        dispatchPurchaseUpdate(
            source = "purchases_updated_listener",
            requestId = null,
            purchases = incomingPurchases.map { toPurchasePayload(it, resolvePurchaseStatus(it)) }
        )
    }

    private fun ensureClient(activity: FragmentActivity): BillingClient {
        currentActivity = WeakReference(activity)

        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(activity.applicationContext)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build()
                )
                .enableAutoServiceReconnection()
                .build()
        }

        if (!lifecycleRegistered) {
            lifecycleRegistered = true
            NativePHPLifecycle.on(NativePHPLifecycle.Events.ON_RESUME) { _ ->
                if (autoSyncOnResume) {
                    syncOwnedPurchases("on_resume")
                }
            }
        }

        return billingClient!!
    }

    private fun connectIfNeeded(activity: FragmentActivity, onReady: (() -> Unit)? = null) {
        val client = ensureClient(activity)

        if (client.isReady) {
            connectionState = "ready"
            emitConnectionState("ready", true, null)
            onReady?.invoke()
            return
        }

        connectionState = "connecting"
        emitConnectionState("connecting", false, null)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    initialized = true
                    connectionState = "ready"
                    emitConnectionState("ready", true, null)
                    syncOwnedPurchases("startup")
                    queryCountryCode()
                    onReady?.invoke()
                } else {
                    connectionState = "disconnected"
                    val mapped = mapBillingError("connect", billingResult)
                    lastError = mapped
                    emitConnectionState("disconnected", false, mapped["message"] as? String)
                    dispatchOperationFailed("connect", mapped)
                }
            }

            override fun onBillingServiceDisconnected() {
                connectionState = "disconnected"
                emitConnectionState("disconnected", false, "Billing service disconnected")
            }
        })
    }

    private fun emitConnectionState(state: String, ready: Boolean, message: String?) {
        dispatchEvent(
            EVENT_CONNECTION_CHANGED,
            mutableMapOf<String, Any>(
                "state" to state,
                "ready" to ready,
                "platform" to "android"
            ).apply {
                message?.let { put("message", it) }
            }
        )
    }

    private fun syncOwnedPurchases(reason: String) {
        val activity = currentActivity?.get() ?: return
        val client = ensureClient(activity)

        if (!client.isReady) {
            return
        }

        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        client.queryPurchasesAsync(inAppParams) { billingResult, purchases ->
            handleSyncResponse(reason, PRODUCT_TYPE_INAPP, billingResult, purchases)
        }

        val subsParamsBuilder = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
        includeSuspendedSubscriptions = tryEnableSuspendedSubscriptions(subsParamsBuilder)
        val subsParams = subsParamsBuilder.build()

        client.queryPurchasesAsync(subsParams) { billingResult, purchases ->
            handleSyncResponse(reason, PRODUCT_TYPE_SUBS, billingResult, purchases)
        }
    }

    private fun handleSyncResponse(
        reason: String,
        productType: String,
        billingResult: BillingResult,
        purchases: List<Purchase>
    ) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            dispatchOperationFailed("sync_purchases", mapBillingError("sync_purchases", billingResult))
            return
        }

        purchases.forEach { purchase ->
            purchasesByToken[purchase.purchaseToken] = purchase
            tokenProductTypes[purchase.purchaseToken] = productType
            if (shouldMarkPendingCompletion(purchase)) {
                pendingTokens.add(purchase.purchaseToken)
            } else {
                pendingTokens.remove(purchase.purchaseToken)
            }
        }

        lastSyncAt = System.currentTimeMillis()
        dispatchPurchaseUpdate(
            source = "sync",
            requestId = null,
            purchases = purchases.map { toPurchasePayload(it, resolveSyncStatus(it)) },
            extra = mapOf(
                "reason" to reason,
                "productType" to productType
            )
        )
    }

    private fun tryEnableSuspendedSubscriptions(builder: QueryPurchasesParams.Builder): Boolean {
        val methodCandidates = listOf(
            "setIncludeSuspendedSubscriptions",
            "setIncludeSuspendedPurchases"
        )

        methodCandidates.forEach { methodName ->
            try {
                val method = builder.javaClass.getMethod(methodName, Boolean::class.javaPrimitiveType)
                method.invoke(builder, true)
                return true
            } catch (_: NoSuchMethodException) {
                // Try next known method name.
            } catch (_: ReflectiveOperationException) {
                // Ignore unsupported reflective call variants.
            }
        }

        return false
    }

    private fun queryCountryCode() {
        val activity = currentActivity?.get() ?: return
        val client = ensureClient(activity)

        if (!client.isReady) {
            return
        }

        // BillingConfig API varies between Billing versions/templates.
        // Keep storefront code best-effort using locale to avoid build/API mismatch.
        countryCode = activity.resources.configuration.locales.get(0)?.country
        dispatchEvent(
            EVENT_AVAILABILITY_CHANGED,
            mapOf(
                "available" to true,
                "platform" to "android",
                "countryCode" to (countryCode ?: "")
            )
        )
    }

    private fun queryProductDetails(parameters: Map<String, Any>, requestId: String): Map<String, Any> {
        val activity = currentActivity?.get()
            ?: return BridgeResponse.error(BridgeError.ExecutionFailed("No active activity"))
        val client = ensureClient(activity)

        if (!client.isReady) {
            return BridgeResponse.error(BridgeError.ExecutionFailed("Billing client is not ready"))
        }

        val descriptorsByType = mutableMapOf<String, MutableList<QueryProductDetailsParams.Product>>(
            BillingClient.ProductType.INAPP to mutableListOf(),
            BillingClient.ProductType.SUBS to mutableListOf()
        )
        val ids = extractStringList(parameters["ids"])
        ids.forEach { productId ->
            descriptorsByType[BillingClient.ProductType.INAPP]?.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
            descriptorsByType[BillingClient.ProductType.SUBS]?.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }

        val products = extractProductDescriptors(parameters["products"])
        products.forEach { descriptor ->
            val productId = descriptor.first
            val productType = descriptor.second
            val billingProductType = if (productType == PRODUCT_TYPE_SUBS) {
                BillingClient.ProductType.SUBS
            } else {
                BillingClient.ProductType.INAPP
            }

            descriptorsByType[billingProductType]?.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(billingProductType)
                    .build()
            )
        }

        val hasDescriptors = descriptorsByType.values.any { it.isNotEmpty() }

        if (!hasDescriptors) {
            return BridgeResponse.error(BridgeError.InvalidParameters("No product identifiers provided"))
        }

        descriptorsByType.forEach { (billingProductType, descriptors) ->
            if (descriptors.isEmpty()) {
                return@forEach
            }

            val uniqueDescriptors = descriptors.distinct()

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(uniqueDescriptors)
                .build()

            client.queryProductDetailsAsync(params) { billingResult, detailsResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    dispatchOperationFailed("query_products", mapBillingError("query_products", billingResult, requestId))
                    return@queryProductDetailsAsync
                }

                val fetched = detailsResult.productDetailsList ?: emptyList()
                fetched.forEach { productsById[it.productId] = it }

                dispatchEvent(
                    EVENT_PURCHASE_UPDATED,
                    mapOf(
                        "source" to "products_queried",
                        "requestId" to requestId,
                        "platform" to "android",
                        "timestamp" to System.currentTimeMillis(),
                        "reason" to "query_${billingProductType.lowercase()}",
                        "products" to fetched.map { toProductPayload(it) },
                        "notFoundIds" to detailsResult.unfetchedProductList.map { it.productId }
                    )
                )
            }
        }

        return BridgeResponse.success(
            mapOf(
                "requestId" to requestId,
                "accepted" to true
            )
        )
    }

    private fun extractStringList(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull { it as? String }
            is JSONArray -> {
                val list = mutableListOf<String>()
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    if (item is String) {
                        list.add(item)
                    }
                }
                list
            }
            else -> emptyList()
        }
    }

    private fun extractProductDescriptors(value: Any?): List<Pair<String, String>> {
        val descriptors = mutableListOf<Pair<String, String>>()

        when (value) {
            is List<*> -> {
                value.forEach { raw ->
                    val descriptor = raw as? Map<*, *> ?: return@forEach
                    val productId = descriptor["productId"] as? String ?: return@forEach
                    val productType = (descriptor["productType"] as? String)?.lowercase() ?: PRODUCT_TYPE_INAPP
                    descriptors.add(productId to productType)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    val descriptor = item as? JSONObject ?: continue
                    val productId = descriptor.optString("productId")
                    if (productId.isBlank()) {
                        continue
                    }
                    val productType = descriptor.optString("productType", PRODUCT_TYPE_INAPP).lowercase()
                    descriptors.add(productId to productType)
                }
            }
        }

        return descriptors
    }

    private fun startPurchaseFlow(parameters: Map<String, Any>, type: String): Map<String, Any> {
        val activity = currentActivity?.get()
            ?: return BridgeResponse.error(BridgeError.ExecutionFailed("No active activity"))
        val client = ensureClient(activity)

        if (!client.isReady) {
            return BridgeResponse.error(BridgeError.ExecutionFailed("Billing client is not ready"))
        }

        val productId = parameters["productId"] as? String
            ?: return BridgeResponse.error(BridgeError.InvalidParameters("productId is required"))

        val details = productsById[productId]
            ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Product not loaded: $productId"))

        val offerToken = parameters["offerToken"] as? String
            ?: details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: details.oneTimePurchaseOfferDetails?.formattedPrice?.let { null }

        val requestId = (parameters["id"] as? String) ?: UUID.randomUUID().toString()
        purchaseKindsByProductId[productId] = type

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (!offerToken.isNullOrBlank()) {
            productParamsBuilder.setOfferToken(offerToken)
        }

        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))

        val accountId = parameters["accountId"] as? String
        val profileId = parameters["profileId"] as? String

        if (!accountId.isNullOrBlank()) {
            flowParamsBuilder.setObfuscatedAccountId(accountId)
        }

        if (!profileId.isNullOrBlank()) {
            flowParamsBuilder.setObfuscatedProfileId(profileId)
        }

        // Subscription upgrade/downgrade: build SubscriptionUpdateParams when oldPurchaseToken is provided.
        if (type == "subscription") {
            val oldPurchaseToken = parameters["oldPurchaseToken"] as? String
            if (!oldPurchaseToken.isNullOrBlank()) {
                val replacementMode = (parameters["replacementMode"] as? Int)
                    ?: 0 // BillingFlowParams.SubscriptionUpdateParams default (deprecated in 8.x, use raw int)

                val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldPurchaseToken)
                    .setSubscriptionReplacementMode(replacementMode)
                    .build()

                flowParamsBuilder.setSubscriptionUpdateParams(subscriptionUpdateParams)
            }
        }

        val flowParams = flowParamsBuilder.build()
        val result = launchBillingFlowOnMainThread(activity, client, flowParams)
            ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Unable to launch billing flow"))

        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            val mapped = mapBillingError("launch_billing_flow", result, requestId)
            dispatchOperationFailed("launch_billing_flow", mapped)
            return BridgeResponse.error(BridgeError.ExecutionFailed(mapped["message"] as String))
        }

        return BridgeResponse.success(
            mapOf(
                "accepted" to true,
                "requestId" to requestId,
                "flow" to "event_driven",
                "type" to type
            )
        )
    }

    private fun launchBillingFlowOnMainThread(
        activity: FragmentActivity,
        client: BillingClient,
        flowParams: BillingFlowParams
    ): BillingResult? {
        val resultRef = AtomicReference<BillingResult?>()
        val errorRef = AtomicReference<Throwable?>()
        val launchAction = Runnable {
            try {
                resultRef.set(client.launchBillingFlow(activity, flowParams))
            } catch (exception: Exception) {
                errorRef.set(exception)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            launchAction.run()
        } else {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    launchAction.run()
                } finally {
                    latch.countDown()
                }
            }

            val executed = try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!executed) {
                dispatchOperationFailed(
                    "launch_billing_flow",
                    mapOf(
                        "code" to "MAIN_THREAD_TIMEOUT",
                        "message" to "Timed out while posting launchBillingFlow to main thread",
                        "retryable" to true
                    )
                )
                return null
            }
        }

        errorRef.get()?.let { throwable ->
            dispatchOperationFailed(
                "launch_billing_flow",
                mapOf(
                    "code" to "LAUNCH_FAILED",
                    "message" to (throwable.message ?: "launchBillingFlow failed"),
                    "retryable" to false
                )
            )
            return null
        }

        return resultRef.get()
    }

    private fun dispatchPurchaseUpdate(
        source: String,
        requestId: String?,
        purchases: List<Map<String, Any>>,
        extra: Map<String, Any>? = null
    ) {
        val payload = mutableMapOf<String, Any>(
            "source" to source,
            "requestId" to (requestId ?: ""),
            "platform" to "android",
            "timestamp" to System.currentTimeMillis(),
            "purchases" to purchases
        )

        extra?.forEach { (key, value) ->
            payload[key] = value
        }

        dispatchEvent(EVENT_PURCHASE_UPDATED, payload)
    }

    private fun dispatchOperationFailed(operation: String, error: Map<String, Any>) {
        dispatchEvent(
            EVENT_OPERATION_FAILED,
            mapOf(
                "operation" to operation,
                "code" to (error["code"] ?: "PLAY_BILLING_ERROR"),
                "message" to (error["message"] ?: "Unknown billing error"),
                "retryable" to (error["retryable"] ?: false),
                "requestId" to (error["requestId"] ?: ""),
                "platform" to "android"
            )
        )
    }

    private fun mapBillingError(operation: String, billingResult: BillingResult, requestId: String? = null): Map<String, Any> {
        val code = when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.USER_CANCELED -> "PURCHASE_CANCELED"
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "PRODUCT_UNAVAILABLE"
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "INVALID_REQUEST"
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR -> "PLAY_TRANSIENT_ERROR"
            else -> "PLAY_BILLING_ERROR"
        }

        val retryable = code in setOf("PLAY_TRANSIENT_ERROR")

        return mapOf(
            "operation" to operation,
            "code" to code,
            "message" to billingResult.debugMessage,
            "retryable" to retryable,
            "playResponseCode" to billingResult.responseCode,
            "playDebugMessage" to billingResult.debugMessage,
            "requestId" to (requestId ?: "")
        )
    }

    private fun toProductPayload(details: ProductDetails): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "productId" to details.productId,
            "productType" to details.productType,
            "title" to details.title,
            "description" to details.description,
            "name" to details.name
        )

        details.oneTimePurchaseOfferDetails?.let { oneTime ->
            base["oneTimeOffer"] = mapOf(
                "formattedPrice" to oneTime.formattedPrice,
                "priceAmountMicros" to oneTime.priceAmountMicros,
                "priceCurrencyCode" to oneTime.priceCurrencyCode
            )
        }

        details.subscriptionOfferDetails?.let { offers ->
            base["subscriptionOffers"] = offers.map { offer ->
                mapOf(
                    "basePlanId" to offer.basePlanId,
                    "offerId" to (offer.offerId ?: ""),
                    "offerToken" to offer.offerToken,
                    "offerTags" to offer.offerTags,
                    "pricingPhases" to offer.pricingPhases.pricingPhaseList.map { phase ->
                        mapOf(
                            "billingPeriod" to phase.billingPeriod,
                            "formattedPrice" to phase.formattedPrice,
                            "priceAmountMicros" to phase.priceAmountMicros,
                            "priceCurrencyCode" to phase.priceCurrencyCode,
                            "billingCycleCount" to phase.billingCycleCount,
                            "recurrenceMode" to phase.recurrenceMode
                        )
                    }
                )
            }
        }

        return base
    }

    private fun inferPurchaseProductType(purchase: Purchase): String {
        val hasSubscriptionProduct = purchase.products.any { productId ->
            productsById[productId]?.productType == BillingClient.ProductType.SUBS
        }

        return if (hasSubscriptionProduct) {
            PRODUCT_TYPE_SUBS
        } else {
            PRODUCT_TYPE_INAPP
        }
    }

    private fun resolveProductTypeForToken(purchaseToken: String): String {
        tokenProductTypes[purchaseToken]?.let { productType ->
            return productType
        }

        purchasesByToken[purchaseToken]?.let { purchase ->
            return inferPurchaseProductType(purchase)
        }

        return PRODUCT_TYPE_INAPP
    }

    private fun inferPurchaseKind(purchase: Purchase, normalizedProductType: String): String {
        if (normalizedProductType == PRODUCT_TYPE_SUBS) {
            return "subscription"
        }

        val explicitKind = purchase.products.firstNotNullOfOrNull { productId ->
            purchaseKindsByProductId[productId]
        }

        return explicitKind ?: "unknown"
    }

    private fun completionHintForKind(purchaseKind: String): String {
        return when (purchaseKind) {
            "consumable" -> "consume"
            "non_consumable", "subscription" -> "acknowledge"
            else -> "unknown"
        }
    }

    private fun shouldMarkPendingCompletion(purchase: Purchase): Boolean {
        return purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged
    }

    private fun resolvePurchaseStatus(purchase: Purchase): String {
        return when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> "pending"
            Purchase.PurchaseState.PURCHASED -> "purchased"
            else -> "unspecified"
        }
    }

    private fun resolveSyncStatus(purchase: Purchase): String {
        return when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> "pending"
            Purchase.PurchaseState.PURCHASED -> "restored"
            else -> "unspecified"
        }
    }

    private fun toPurchasePayload(
        purchase: Purchase,
        status: String,
        productType: String? = null
    ): Map<String, Any> {
        val purchaseState = when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> "pending"
            Purchase.PurchaseState.PURCHASED -> "purchased"
            else -> "unspecified"
        }
        val normalizedProductType = productType
            ?: tokenProductTypes[purchase.purchaseToken]
            ?: inferPurchaseProductType(purchase)
        val purchaseKind = inferPurchaseKind(purchase, normalizedProductType)
        val completionHint = completionHintForKind(purchaseKind)
        val payload = mutableMapOf<String, Any>(
            "status" to status,
            "platform" to "android",
            "purchaseId" to (purchase.orderId ?: purchase.purchaseToken),
            "purchaseToken" to purchase.purchaseToken,
            "productIds" to purchase.products,
            "productType" to normalizedProductType,
            "purchaseKind" to purchaseKind,
            "completionHint" to completionHint,
            "transactionDateMs" to purchase.purchaseTime,
            "purchaseState" to purchaseState,
            "acknowledged" to purchase.isAcknowledged,
            "autoRenewing" to purchase.isAutoRenewing,
            "pendingCompletePurchase" to shouldMarkPendingCompletion(purchase),
            "verificationData" to mapOf(
                "source" to "google_play",
                "server" to purchase.purchaseToken,
                "local" to purchase.originalJson
            ),
            "platformData" to mapOf(
                "android" to mapOf(
                    "orderId" to (purchase.orderId ?: ""),
                    "purchaseToken" to purchase.purchaseToken,
                    "purchaseStateCode" to purchase.purchaseState
                )
            )
        )

        if (completionHint == "consume") {
            payload["isConsumableHint"] = true
        } else if (completionHint == "acknowledge") {
            payload["isConsumableHint"] = false
        }

        return payload
    }

    private fun dispatchEvent(eventClass: String, payload: Map<String, Any>) {
        val activity = currentActivity?.get() ?: return

        Handler(Looper.getMainLooper()).post {
            val json = JSONObject()
            payload.forEach { (key, value) ->
                json.put(key, toJsonValue(value))
            }

            NativeActionCoordinator.dispatchEvent(activity, eventClass, json.toString())
        }
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val jsonObject = JSONObject()
                value.forEach { (key, item) ->
                    if (key is String) {
                        jsonObject.put(key, toJsonValue(item))
                    }
                }
                jsonObject
            }
            is Iterable<*> -> {
                val jsonArray = JSONArray()
                value.forEach { item ->
                    jsonArray.put(toJsonValue(item))
                }
                jsonArray
            }
            is Array<*> -> {
                val jsonArray = JSONArray()
                value.forEach { item ->
                    jsonArray.put(toJsonValue(item))
                }
                jsonArray
            }
            else -> value
        }
    }

    class Initialize(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            autoSyncOnResume = parameters["autoSyncOnResume"] as? Boolean ?: true
            connectIfNeeded(activity)

            return BridgeResponse.success(
                mapOf(
                    "initialized" to initialized,
                    "platform" to "android",
                    "state" to connectionState,
                    "autoSyncOnResume" to autoSyncOnResume
                )
            )
        }
    }

    class IsAvailable(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            val available = ensureClient(activity).isReady
            dispatchEvent(
                EVENT_AVAILABILITY_CHANGED,
                mapOf(
                    "available" to available,
                    "platform" to "android",
                    "reason" to if (available) "ready" else "client_not_ready"
                )
            )

            return BridgeResponse.success(
                mapOf(
                    "available" to available,
                    "platform" to "android"
                )
            )
        }
    }

    class QueryProductDetails(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)
            val requestId = (parameters["id"] as? String) ?: UUID.randomUUID().toString()

            return queryProductDetails(parameters, requestId)
        }
    }

    class BuyConsumable(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            return startPurchaseFlow(parameters, "consumable")
        }
    }

    class BuyNonConsumable(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            return startPurchaseFlow(parameters, "non_consumable")
        }
    }

    class BuySubscription(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            return startPurchaseFlow(parameters, "subscription")
        }
    }

    class CompletePurchase(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)
            val client = ensureClient(activity)
            val purchaseId = parameters["purchaseId"] as? String
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("purchaseId is required"))
            val isConsumable = parameters["isConsumable"] as? Boolean ?: false
            val requestId = (parameters["id"] as? String) ?: UUID.randomUUID().toString()
            val shouldWaitForResult = (parameters["waitForResult"] as? Boolean ?: true)
                && Looper.myLooper() != Looper.getMainLooper()

            val purchase = purchasesByToken.values.firstOrNull {
                (it.orderId == purchaseId) || (it.purchaseToken == purchaseId)
            } ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Purchase not found: $purchaseId"))

            val latch = if (shouldWaitForResult) CountDownLatch(1) else null
            val completionRef = AtomicReference<Map<String, Any>?>()
            val errorRef = AtomicReference<Map<String, Any>?>()

            fun awaitCompletion(mode: String): Map<String, Any> {
                if (latch == null) {
                    return BridgeResponse.success(
                        mapOf(
                            "accepted" to true,
                            "purchaseId" to purchaseId,
                            "requestId" to requestId,
                            "flow" to "event_driven",
                            "mode" to mode
                        )
                    )
                }

                val completed = try {
                    latch.await(12, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!completed) {
                    return BridgeResponse.error(BridgeError.ExecutionFailed("Timed out waiting for completePurchase callback"))
                }

                errorRef.get()?.let { error ->
                    return BridgeResponse.error(
                        BridgeError.ExecutionFailed(error["message"] as? String ?: "completePurchase failed")
                    )
                }

                return BridgeResponse.success(
                    completionRef.get()
                        ?: mapOf(
                            "completed" to true,
                            "purchaseId" to purchaseId,
                            "requestId" to requestId,
                            "mode" to mode
                        )
                )
            }

            if (isConsumable) {
                val productType = resolveProductTypeForToken(purchase.purchaseToken)
                if (productType == PRODUCT_TYPE_SUBS) {
                    val mappedError = mapOf(
                        "operation" to "consume_purchase",
                        "code" to "INVALID_REQUEST",
                        "message" to "Cannot consume subscription purchases. Use acknowledge flow.",
                        "retryable" to false,
                        "requestId" to requestId
                    )
                    dispatchOperationFailed("consume_purchase", mappedError)
                    return BridgeResponse.error(BridgeError.ExecutionFailed(mappedError["message"] as String))
                }

                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                client.consumeAsync(consumeParams) { billingResult, purchaseToken ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        pendingTokens.remove(purchaseToken)
                        tokenProductTypes.remove(purchaseToken)
                        val completion = mapOf(
                            "completed" to true,
                            "purchaseId" to purchaseId,
                            "requestId" to requestId,
                            "mode" to "consume"
                        )
                        completionRef.set(completion)
                        dispatchPurchaseUpdate(
                            source = "complete_purchase",
                            requestId = requestId,
                            purchases = listOf(toPurchasePayload(purchase, "consumed"))
                        )
                    } else {
                        val mappedError = mapBillingError("consume_purchase", billingResult, requestId)
                        errorRef.set(mappedError)
                        dispatchOperationFailed("consume_purchase", mappedError)
                    }
                    latch?.countDown()
                }

                return awaitCompletion("consume")
            }

            if (purchase.isAcknowledged) {
                pendingTokens.remove(purchase.purchaseToken)

                return BridgeResponse.success(
                    mapOf(
                        "completed" to true,
                        "purchaseId" to purchaseId,
                        "requestId" to requestId,
                        "mode" to "already_acknowledged"
                    )
                )
            }

            val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            client.acknowledgePurchase(acknowledgeParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    pendingTokens.remove(purchase.purchaseToken)
                    val completion = mapOf(
                        "completed" to true,
                        "purchaseId" to purchaseId,
                        "requestId" to requestId,
                        "mode" to "acknowledge"
                    )
                    completionRef.set(completion)
                    val acknowledgedPayload = toPurchasePayload(purchase, "purchased").toMutableMap()
                    acknowledgedPayload["acknowledged"] = true
                    acknowledgedPayload["pendingCompletePurchase"] = false
                    dispatchPurchaseUpdate(
                        source = "complete_purchase",
                        requestId = requestId,
                        purchases = listOf(acknowledgedPayload)
                    )
                } else {
                    val mappedError = mapBillingError("acknowledge_purchase", billingResult, requestId)
                    errorRef.set(mappedError)
                    dispatchOperationFailed("acknowledge_purchase", mappedError)
                }
                latch?.countDown()
            }

            return awaitCompletion("acknowledge")
        }
    }

    class ConsumePurchase(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)
            val client = ensureClient(activity)
            val token = parameters["purchaseToken"] as? String
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("purchaseToken is required"))
            val productType = resolveProductTypeForToken(token)

            if (productType == PRODUCT_TYPE_SUBS) {
                val mappedError = mapOf(
                    "operation" to "consume_purchase",
                    "code" to "INVALID_REQUEST",
                    "message" to "Cannot consume subscription purchases. Use completePurchase acknowledge flow.",
                    "retryable" to false
                )
                dispatchOperationFailed("consume_purchase", mappedError)
                return BridgeResponse.error(BridgeError.ExecutionFailed(mappedError["message"] as String))
            }

            val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(token)
                .build()

            client.consumeAsync(consumeParams) { billingResult, purchaseToken ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    pendingTokens.remove(purchaseToken)
                    tokenProductTypes.remove(purchaseToken)
                } else {
                    dispatchOperationFailed("consume_purchase", mapBillingError("consume_purchase", billingResult))
                }
            }

            return BridgeResponse.success(
                mapOf(
                    "consumed" to true,
                    "purchaseToken" to token
                )
            )
        }
    }

    class RestorePurchases(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)
            val requestId = (parameters["id"] as? String) ?: UUID.randomUUID().toString()

            syncOwnedPurchases("restore")

            dispatchEvent(
                EVENT_RESTORE_COMPLETED,
                mapOf(
                    "result" to "started",
                    "restoredCount" to purchasesByToken.size,
                    "requestId" to requestId,
                    "platform" to "android"
                )
            )

            return BridgeResponse.success(
                mapOf(
                    "started" to true,
                    "requestId" to requestId,
                    "flow" to "event_driven"
                )
            )
        }
    }

    class SyncPurchases(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)
            val reason = parameters["reason"] as? String ?: "manual"
            syncOwnedPurchases(reason)

            return BridgeResponse.success(
                mapOf(
                    "synced" to true,
                    "reason" to reason
                )
            )
        }
    }

    class GetPendingPurchases(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            val pending = purchasesByToken.values
                .filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PENDING
                        || pendingTokens.contains(purchase.purchaseToken)
                        || shouldMarkPendingCompletion(purchase)
                }
                .map { purchase ->
                    val productType = tokenProductTypes[purchase.purchaseToken] ?: inferPurchaseProductType(purchase)
                    toPurchasePayload(purchase, "pending", productType)
                }

            return BridgeResponse.success(
                mapOf(
                    "purchases" to pending,
                    "count" to pending.size
                )
            )
        }
    }

    class GetStatus(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            return BridgeResponse.success(
                mapOf(
                    "platform" to "android",
                    "initialized" to initialized,
                    "connectionState" to connectionState,
                    "isReady" to ensureClient(activity).isReady,
                    "productCount" to productsById.size,
                    "purchaseCount" to purchasesByToken.size,
                    "pendingCount" to pendingTokens.size,
                    "lastSyncAt" to (lastSyncAt ?: 0L),
                    "countryCode" to (countryCode ?: ""),
                    "includeSuspendedSubscriptions" to includeSuspendedSubscriptions,
                    "lastError" to (lastError ?: emptyMap<String, Any>())
                )
            )
        }
    }

    class CountryCode(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            connectIfNeeded(activity)

            return BridgeResponse.success(
                mapOf(
                    "countryCode" to (countryCode ?: ""),
                    "source" to if (countryCode.isNullOrBlank()) "unknown" else "store",
                    "platform" to "android"
                )
            )
        }
    }
}
