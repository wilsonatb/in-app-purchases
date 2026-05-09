import Foundation
import StoreKit

final class InAppPurchasesManager {
    static let shared = InAppPurchasesManager()

    private let purchaseUpdatedEvent = "Wilsonatb\\InAppPurchases\\Events\\PurchaseUpdated"
    private let connectionChangedEvent = "Wilsonatb\\InAppPurchases\\Events\\ConnectionChanged"
    private let availabilityChangedEvent = "Wilsonatb\\InAppPurchases\\Events\\AvailabilityChanged"
    private let restoreCompletedEvent = "Wilsonatb\\InAppPurchases\\Events\\RestoreCompleted"
    private let operationFailedEvent = "Wilsonatb\\InAppPurchases\\Events\\OperationFailed"

    private var updateTask: Task<Void, Never>?
    private var productCache: [String: Product] = [:]
    private var pendingCompletion: [String: Transaction] = [:]
    private var initialized = false
    private var autoSyncOnResume = true

    // Cached values for synchronous bridge access.
    private var cachedCountryCode: String = ""
    private var cachedStatus: [String: Any] = [
        "platform": "ios",
        "initialized": false,
        "connectionState": "disconnected",
        "isReady": false,
        "productCount": 0,
        "pendingCount": 0,
        "countryCode": "",
        "autoSyncOnResume": true,
    ]

    private init() {}

    func initialize(autoSyncOnResume: Bool) {
        self.autoSyncOnResume = autoSyncOnResume

        if initialized {
            return
        }

        initialized = true
        cachedStatus["initialized"] = true
        cachedStatus["connectionState"] = "ready"
        cachedStatus["isReady"] = true
        cachedStatus["autoSyncOnResume"] = autoSyncOnResume
        emitConnection(state: "ready", ready: true, message: nil)

        updateTask = Task(priority: .background) {
            await self.listenForTransactionUpdates()
        }

        Task(priority: .background) {
            await self.processUnfinishedTransactions()
            await self.refreshCurrentEntitlements(source: "initialize")
            await self.refreshCaches()
        }
    }

    private func refreshCaches() async {
        cachedCountryCode = await storefrontCountryCode() ?? ""
        cachedStatus["countryCode"] = cachedCountryCode
        cachedStatus["productCount"] = productCache.count
        cachedStatus["pendingCount"] = pendingCompletion.count
    }

    func isAvailable() -> [String: Any] {
        // StoreKit 2 availability is tied to platform support. Runtime store availability
        // is reflected through operation failures/events.
        let payload: [String: Any] = [
            "available": true,
            "platform": "ios",
        ]

        dispatchEvent(availabilityChangedEvent, payload: payload)
        return payload
    }

    func queryProducts(_ productIds: [String], requestId: String? = nil) async -> [String: Any] {
        do {
            let products = try await Product.products(for: productIds)
            products.forEach { product in
                productCache[product.id] = product
            }

            cachedStatus["productCount"] = productCache.count

            let foundIds = Set(products.map(\.id))
            let missingIds = productIds.filter { !foundIds.contains($0) }

            let result: [String: Any] = [
                "products": products.map { mapProduct($0) },
                "notFoundIds": missingIds,
                "countryCode": await storefrontCountryCode() ?? "",
                "fetchedAt": Date().ISO8601Format(),
            ]

            // Dispatch event so PHP side receives product catalog
            dispatchEvent(
                purchaseUpdatedEvent,
                payload: [
                    "source": "products_queried",
                    "requestId": requestId ?? "",
                    "platform": "ios",
                    "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                    "products": result["products"] ?? [],
                    "notFoundIds": result["notFoundIds"] ?? [],
                ]
            )

            return result
        } catch {
            emitOperationFailed(
                operation: "query_products",
                code: "IAP_QUERY_PRODUCTS_FAILED",
                message: error.localizedDescription,
                retryable: true,
                requestId: requestId
            )

            return [
                "products": [],
                "notFoundIds": productIds,
                "countryCode": await storefrontCountryCode() ?? "",
                "fetchedAt": Date().ISO8601Format(),
            ]
        }
    }

    func purchase(
        productId: String,
        appAccountToken: String?,
        requestId: String
    ) async -> [String: Any] {
        guard let product = productCache[productId] else {
            return [
                "accepted": false,
                "requestId": requestId,
                "error": [
                    "code": "IAP_PRODUCT_NOT_FOUND",
                    "message": "Product not loaded: \(productId)",
                ],
            ]
        }

        do {
            var options: Set<Product.PurchaseOption> = []

            if let appAccountToken, let uuid = UUID(uuidString: appAccountToken) {
                options.insert(.appAccountToken(uuid))
            }

            let result: Product.PurchaseResult
            if options.isEmpty {
                result = try await product.purchase()
            } else {
                result = try await product.purchase(options: options)
            }

            switch result {
            case .success(let verificationResult):
                await handleVerificationResult(
                    verificationResult,
                    fallbackStatus: "purchased",
                    source: "purchase_result",
                    requestId: requestId
                )
            case .pending:
                let pendingKind = mapPurchaseKind(for: product.id)
                let pendingCompletionHint = completionHint(for: pendingKind)
                let pendingType = mapProductType(for: product.id)
                dispatchEvent(
                    purchaseUpdatedEvent,
                    payload: [
                        "source": "purchase_result",
                        "requestId": requestId,
                        "platform": "ios",
                        "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                        "purchases": [[
                            "status": "pending",
                            "platform": "ios",
                            "purchaseId": "",
                            "purchaseToken": "",
                            "productIds": [productId],
                            "productType": pendingType,
                            "purchaseKind": pendingKind,
                            "completionHint": pendingCompletionHint,
                            "isConsumableHint": pendingCompletionHint == "consume",
                            "transactionDateMs": Int(Date().timeIntervalSince1970 * 1000),
                            "pendingCompletePurchase": false,
                            "verificationData": [
                                "source": "app_store",
                                "local": "",
                                "server": "",
                            ],
                        ]],
                    ]
                )
            case .userCancelled:
                dispatchEvent(
                    purchaseUpdatedEvent,
                    payload: [
                        "source": "purchase_result",
                        "requestId": requestId,
                        "platform": "ios",
                        "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                        "status": "canceled",
                        "purchases": [],
                    ]
                )
            @unknown default:
                break
            }

            return [
                "accepted": true,
                "requestId": requestId,
                "flow": "event_driven",
            ]
        } catch {
            emitOperationFailed(
                operation: "purchase",
                code: "IAP_PURCHASE_FAILED",
                message: error.localizedDescription,
                retryable: true,
                requestId: requestId
            )

            return [
                "accepted": false,
                "requestId": requestId,
                "error": [
                    "code": "IAP_PURCHASE_FAILED",
                    "message": error.localizedDescription,
                ],
            ]
        }
    }

    func completePurchase(purchaseId: String) async -> [String: Any] {
        guard let transaction = pendingCompletion[purchaseId] else {
            return [
                "completed": false,
                "purchaseId": purchaseId,
                "message": "Pending transaction not found",
            ]
        }

        await transaction.finish()
        pendingCompletion.removeValue(forKey: purchaseId)
        cachedStatus["pendingCount"] = pendingCompletion.count

        return [
            "completed": true,
            "purchaseId": purchaseId,
            "mode": "finish",
        ]
    }

    func restorePurchases(requestId: String) async -> [String: Any] {
        do {
            try await AppStore.sync()
            await refreshCurrentEntitlements(source: "restore")

            dispatchEvent(
                restoreCompletedEvent,
                payload: [
                    "result": "completed",
                    "restoredCount": pendingCompletion.count,
                    "requestId": requestId,
                    "platform": "ios",
                ]
            )

            return [
                "started": true,
                "requestId": requestId,
                "flow": "event_driven",
            ]
        } catch {
            emitOperationFailed(
                operation: "restore_purchases",
                code: "IAP_SYNC_FAILED",
                message: error.localizedDescription,
                retryable: true,
                requestId: requestId
            )

            dispatchEvent(
                restoreCompletedEvent,
                payload: [
                    "result": "failed",
                    "restoredCount": 0,
                    "requestId": requestId,
                    "platform": "ios",
                    "error": error.localizedDescription,
                ]
            )

            return [
                "started": false,
                "requestId": requestId,
                "flow": "event_driven",
            ]
        }
    }

    func syncPurchases(source: String) async -> [String: Any] {
        await refreshCurrentEntitlements(source: source)

        return [
            "synced": true,
            "source": source,
        ]
    }

    func getPendingPurchases() -> [String: Any] {
        let pending = pendingCompletion.values.map { transaction in
            mapTransaction(transaction, status: "pending")
        }

        return [
            "purchases": pending,
            "count": pending.count,
        ]
    }

    func status() async -> [String: Any] {
        cachedStatus["platform"] = "ios"
        cachedStatus["initialized"] = initialized
        cachedStatus["connectionState"] = initialized ? "ready" : "disconnected"
        cachedStatus["isReady"] = initialized
        cachedStatus["productCount"] = productCache.count
        cachedStatus["pendingCount"] = pendingCompletion.count
        cachedStatus["countryCode"] = await storefrontCountryCode() ?? ""
        cachedStatus["autoSyncOnResume"] = autoSyncOnResume

        return cachedStatus
    }

    func getCachedStatus() -> [String: Any] {
        return cachedStatus
    }

    func getCachedCountryCode() -> String {
        return cachedCountryCode
    }

    func storefront() async -> [String: Any] {
        let code = await storefrontCountryCode()
        cachedCountryCode = code ?? ""
        cachedStatus["countryCode"] = cachedCountryCode

        return [
            "countryCode": cachedCountryCode,
            "source": cachedCountryCode.isEmpty ? "unknown" : "store",
            "platform": "ios",
        ]
    }

    private func listenForTransactionUpdates() async {
        for await verificationResult in Transaction.updates {
            await handleVerificationResult(
                verificationResult,
                fallbackStatus: "purchased",
                source: "transaction_updates",
                requestId: nil
            )
        }
    }

    private func processUnfinishedTransactions() async {
        for await verificationResult in Transaction.unfinished {
            await handleVerificationResult(
                verificationResult,
                fallbackStatus: "purchased",
                source: "unfinished",
                requestId: nil
            )
        }
    }

    private func refreshCurrentEntitlements(source: String) async {
        var purchases: [[String: Any]] = []

        for await verificationResult in Transaction.currentEntitlements {
            let jws = verificationResult.jwsRepresentation
            switch verificationResult {
            case .verified(let transaction):
                pendingCompletion[String(transaction.id)] = transaction
                purchases.append(mapTransaction(transaction, status: source == "restore" ? "restored" : "purchased", jwsRepresentation: jws))
            case .unverified(let transaction, let error):
                purchases.append([
                    "status": "error",
                    "platform": "ios",
                    "purchaseId": String(transaction.id),
                    "purchaseToken": String(transaction.id),
                    "productIds": [transaction.productID],
                    "productType": mapProductType(for: transaction.productID),
                    "purchaseKind": mapPurchaseKind(for: transaction.productID),
                    "completionHint": "unknown",
                    "transactionDateMs": Int((transaction.purchaseDate.timeIntervalSince1970) * 1000),
                    "pendingCompletePurchase": false,
                    "verificationData": [
                        "source": "app_store",
                        "local": jws,
                        "server": String(transaction.id),
                    ],
                    "error": [
                        "code": "IAP_VERIFICATION_FAILED",
                        "message": error.localizedDescription,
                    ],
                ])
            }
        }

        dispatchEvent(
            purchaseUpdatedEvent,
            payload: [
                "source": source,
                "requestId": "",
                "platform": "ios",
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                "purchases": purchases,
            ]
        )
    }

    private func handleVerificationResult(
        _ verificationResult: VerificationResult<Transaction>,
        fallbackStatus: String,
        source: String,
        requestId: String?
    ) async {
        let jws = verificationResult.jwsRepresentation
        switch verificationResult {
        case .verified(let transaction):
            pendingCompletion[String(transaction.id)] = transaction

            dispatchEvent(
                purchaseUpdatedEvent,
                payload: [
                    "source": source,
                    "requestId": requestId ?? "",
                    "platform": "ios",
                    "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                    "purchases": [
                        mapTransaction(transaction, status: fallbackStatus, jwsRepresentation: jws),
                    ],
                ]
            )
        case .unverified(let transaction, let error):
            emitOperationFailed(
                operation: "verification",
                code: "IAP_VERIFICATION_FAILED",
                message: error.localizedDescription,
                retryable: false,
                requestId: requestId
            )

            dispatchEvent(
                purchaseUpdatedEvent,
                payload: [
                    "source": source,
                    "requestId": requestId ?? "",
                    "platform": "ios",
                    "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                    "purchases": [[
                        "status": "error",
                        "platform": "ios",
                        "purchaseId": String(transaction.id),
                        "purchaseToken": String(transaction.id),
                        "productIds": [transaction.productID],
                        "productType": mapProductType(for: transaction.productID),
                        "purchaseKind": mapPurchaseKind(for: transaction.productID),
                        "completionHint": "unknown",
                        "transactionDateMs": Int((transaction.purchaseDate.timeIntervalSince1970) * 1000),
                        "pendingCompletePurchase": false,
                        "verificationData": [
                            "source": "app_store",
                            "local": jws,
                            "server": String(transaction.id),
                        ],
                        "error": [
                            "code": "IAP_VERIFICATION_FAILED",
                            "message": error.localizedDescription,
                        ],
                    ]],
                ]
            )
        }
    }

    private func mapProduct(_ product: Product) -> [String: Any] {
        var payload: [String: Any] = [
            "productId": product.id,
            "title": product.displayName,
            "description": product.description,
            "price": product.displayPrice,
        ]

        switch product.type {
        case .consumable:
            payload["productType"] = "inapp"
            payload["subtype"] = "consumable"
        case .nonConsumable:
            payload["productType"] = "inapp"
            payload["subtype"] = "non_consumable"
        case .autoRenewable:
            payload["productType"] = "subs"
            payload["subtype"] = "auto_renewable"
        case .nonRenewable:
            payload["productType"] = "subs"
            payload["subtype"] = "non_renewable"
        default:
            payload["productType"] = "unknown"
        }

        return payload
    }

    private func mapProductType(for productId: String) -> String {
        guard let product = productCache[productId] else {
            return "unknown"
        }

        switch product.type {
        case .consumable, .nonConsumable:
            return "inapp"
        case .autoRenewable, .nonRenewable:
            return "subs"
        default:
            return "unknown"
        }
    }

    private func mapPurchaseKind(for productId: String) -> String {
        guard let product = productCache[productId] else {
            return "unknown"
        }

        switch product.type {
        case .consumable:
            return "consumable"
        case .nonConsumable:
            return "non_consumable"
        case .autoRenewable, .nonRenewable:
            return "subscription"
        default:
            return "unknown"
        }
    }

    private func completionHint(for purchaseKind: String) -> String {
        switch purchaseKind {
        case "consumable":
            return "consume"
        case "non_consumable", "subscription":
            return "acknowledge"
        default:
            return "unknown"
        }
    }

    private func mapTransaction(_ transaction: Transaction, status: String, jwsRepresentation: String? = nil) -> [String: Any] {
        let purchaseKind = mapPurchaseKind(for: transaction.productID)
        let completionHint = completionHint(for: purchaseKind)
        let productType = mapProductType(for: transaction.productID)

        return [
            "status": status,
            "platform": "ios",
            "purchaseId": String(transaction.id),
            "purchaseToken": String(transaction.id),
            "productIds": [transaction.productID],
            "productType": productType,
            "purchaseKind": purchaseKind,
            "completionHint": completionHint,
            "isConsumableHint": completionHint == "consume",
            "transactionDateMs": Int((transaction.purchaseDate.timeIntervalSince1970) * 1000),
            "pendingCompletePurchase": true,
            "verificationData": [
                "source": "app_store",
                "local": jwsRepresentation ?? "",
                "server": String(transaction.id),
            ],
            "platformData": [
                "ios": [
                    "transactionId": String(transaction.id),
                    "originalTransactionId": String(transaction.originalID),
                ],
            ],
        ]
    }

    private func emitConnection(state: String, ready: Bool, message: String?) {
        var payload: [String: Any] = [
            "state": state,
            "ready": ready,
            "platform": "ios",
        ]

        if let message {
            payload["message"] = message
        }

        dispatchEvent(connectionChangedEvent, payload: payload)
    }

    private func emitOperationFailed(
        operation: String,
        code: String,
        message: String,
        retryable: Bool,
        requestId: String?
    ) {
        var payload: [String: Any] = [
            "operation": operation,
            "code": code,
            "message": message,
            "retryable": retryable,
            "platform": "ios",
        ]

        if let requestId {
            payload["requestId"] = requestId
        }

        dispatchEvent(operationFailedEvent, payload: payload)
    }

    func dispatchPurchaseError(operation: String, error: [String: Any], requestId: String, productId: String) {
        emitOperationFailed(
            operation: operation,
            code: (error["code"] as? String) ?? "IAP_PURCHASE_FAILED",
            message: error["message"] as? String ?? "Purchase failed",
            retryable: true,
            requestId: requestId
        )
    }

    private func dispatchEvent(_ eventClass: String, payload: [String: Any]) {
        LaravelBridge.shared.send?(eventClass, payload)
    }

    private func storefrontCountryCode() async -> String? {
        // AppStore.current requires Xcode 16+ / iOS 18 SDK.
        // Fall back to nil so country code detection is delegated to locale-based
        // approaches on the PHP side.
        return nil
    }
}

enum InAppPurchasesFunctions {
    final class Initialize: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let autoSyncOnResume = parameters["autoSyncOnResume"] as? Bool ?? true
            InAppPurchasesManager.shared.initialize(autoSyncOnResume: autoSyncOnResume)

            return BridgeResponse.success(data: [
                "initialized": true,
                "platform": "ios",
                "state": "ready",
                "autoSyncOnResume": autoSyncOnResume,
            ])
        }
    }

    final class IsAvailable: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: InAppPurchasesManager.shared.isAvailable())
        }
    }

    final class QueryProductDetails: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let ids = parameters["ids"] as? [String] ?? []
            let products = parameters["products"] as? [[String: Any]] ?? []
            let explicitIds = products.compactMap { $0["productId"] as? String }
            let requestedIds = Array(Set(ids + explicitIds))
            let requestId = parameters["id"] as? String ?? UUID().uuidString

            Task {
                _ = await InAppPurchasesManager.shared.queryProducts(requestedIds, requestId: requestId)
            }

            return BridgeResponse.success(data: [
                "accepted": true,
                "requestId": requestId,
            ])
        }
    }

    final class BuyConsumable: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            try enqueuePurchase(parameters: parameters)
        }
    }

    final class BuyNonConsumable: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            try enqueuePurchase(parameters: parameters)
        }
    }

    final class BuySubscription: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            try enqueuePurchase(parameters: parameters)
        }
    }

    final class CompletePurchase: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let purchaseId = parameters["purchaseId"] as? String else {
                return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "purchaseId is required")
            }

            let shouldWaitForResult = (parameters["waitForResult"] as? Bool) ?? true
            let resultLock = NSLock()
            var completedResult: [String: Any]?

            let semaphore = shouldWaitForResult ? DispatchSemaphore(value: 0) : nil

            Task {
                let result = await InAppPurchasesManager.shared.completePurchase(purchaseId: purchaseId)
                resultLock.lock()
                completedResult = result
                resultLock.unlock()
                semaphore?.signal()
            }

            if let semaphore {
                let waitResult = semaphore.wait(timeout: .now() + 12)
                if waitResult == .timedOut {
                    return BridgeResponse.error(
                        code: "IAP_TIMEOUT",
                        message: "Timed out waiting for completePurchase to finish"
                    )
                }
            }

            resultLock.lock()
            let finalResult = completedResult
            resultLock.unlock()

            if let finalResult {
                let didComplete = finalResult["completed"] as? Bool ?? false
                if didComplete {
                    return BridgeResponse.success(data: finalResult)
                } else {
                    return BridgeResponse.error(
                        code: "IAP_COMPLETE_FAILED",
                        message: finalResult["message"] as? String ?? "completePurchase failed"
                    )
                }
            }

            return BridgeResponse.success(data: [
                "accepted": true,
                "purchaseId": purchaseId,
                "flow": "event_driven",
                "mode": "finish",
            ])
        }
    }

    final class ConsumePurchase: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            // iOS does not expose consumption. Keep API parity and no-op successfully.
            return BridgeResponse.success(data: [
                "consumed": false,
                "message": "consumePurchase is Android-only; iOS transactions are finished via completePurchase.",
            ])
        }
    }

    final class RestorePurchases: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let requestId = parameters["id"] as? String ?? UUID().uuidString

            Task {
                _ = await InAppPurchasesManager.shared.restorePurchases(requestId: requestId)
            }

            return BridgeResponse.success(data: [
                "started": true,
                "requestId": requestId,
                "flow": "event_driven",
            ])
        }
    }

    final class SyncPurchases: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let reason = parameters["reason"] as? String ?? "manual"

            Task {
                _ = await InAppPurchasesManager.shared.syncPurchases(source: reason)
            }

            return BridgeResponse.success(data: [
                "synced": true,
                "reason": reason,
            ])
        }
    }

    final class GetPendingPurchases: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            BridgeResponse.success(data: InAppPurchasesManager.shared.getPendingPurchases())
        }
    }

    final class GetStatus: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            // Launch async refresh; return latest cached snapshot synchronously.
            Task {
                _ = await InAppPurchasesManager.shared.status()
            }

            return BridgeResponse.success(data: InAppPurchasesManager.shared.getCachedStatus())
        }
    }

    final class CountryCode: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            // Launch async refresh; return latest cached snapshot synchronously.
            Task {
                _ = await InAppPurchasesManager.shared.storefront()
            }

            return BridgeResponse.success(data: [
                "countryCode": InAppPurchasesManager.shared.getCachedCountryCode(),
                "source": InAppPurchasesManager.shared.getCachedCountryCode().isEmpty ? "unknown" : "store",
                "platform": "ios",
            ])
        }
    }
}

private extension InAppPurchasesFunctions {
    static func enqueuePurchase(parameters: [String: Any]) throws -> [String: Any] {
        guard let productId = parameters["productId"] as? String else {
            return BridgeResponse.error(code: "INVALID_PARAMETERS", message: "productId is required")
        }

        let requestId = parameters["id"] as? String ?? UUID().uuidString
        let appAccountToken = parameters["applicationUserName"] as? String

        Task {
            let result = await InAppPurchasesManager.shared.purchase(
                productId: productId,
                appAccountToken: appAccountToken,
                requestId: requestId
            )

            if let accepted = result["accepted"] as? Bool, !accepted,
               let error = result["error"] as? [String: Any] {
                InAppPurchasesManager.shared.dispatchPurchaseError(
                    operation: "purchase",
                    error: error,
                    requestId: requestId,
                    productId: productId
                )
            }
        }

        return BridgeResponse.success(data: [
            "accepted": true,
            "requestId": requestId,
            "flow": "event_driven",
        ])
    }
}
