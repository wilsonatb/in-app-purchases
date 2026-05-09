/**
 * InAppPurchases Plugin for NativePHP Mobile.
 *
 * API model inspired by Flutter's in_app_purchase lifecycle:
 * initialize -> queryProductDetails -> buy* -> purchase stream updates -> completePurchase.
 */

const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || '',
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result.status === 'error') {
        const message = result.message || 'Native call failed';
        throw new Error(message);
    }

    const nativeResponse = result.data;
    if (nativeResponse && nativeResponse.data !== undefined) {
        return nativeResponse.data;
    }

    return nativeResponse;
}

function normalizeOptions(options = {}) {
    return options ?? {};
}

export async function initialize(options = {}) {
    return bridgeCall('InAppPurchases.Initialize', normalizeOptions(options));
}

export async function isAvailable() {
    return bridgeCall('InAppPurchases.IsAvailable');
}

export async function queryProductDetails(options = {}) {
    return bridgeCall('InAppPurchases.QueryProductDetails', normalizeOptions(options));
}

export async function buyConsumable(options = {}) {
    return bridgeCall('InAppPurchases.BuyConsumable', normalizeOptions(options));
}

export async function buyNonConsumable(options = {}) {
    return bridgeCall('InAppPurchases.BuyNonConsumable', normalizeOptions(options));
}

export async function buySubscription(options = {}) {
    return bridgeCall('InAppPurchases.BuySubscription', normalizeOptions(options));
}

export async function restorePurchases(options = {}) {
    return bridgeCall('InAppPurchases.RestorePurchases', normalizeOptions(options));
}

export async function syncPurchases(options = {}) {
    return bridgeCall('InAppPurchases.SyncPurchases', normalizeOptions(options));
}

export async function completePurchase(options = {}) {
    return bridgeCall('InAppPurchases.CompletePurchase', normalizeOptions(options));
}

export async function consumePurchase(options = {}) {
    return bridgeCall('InAppPurchases.ConsumePurchase', normalizeOptions(options));
}

export async function getPendingPurchases() {
    return bridgeCall('InAppPurchases.GetPendingPurchases');
}

export async function getStatus() {
    return bridgeCall('InAppPurchases.GetStatus');
}

export async function countryCode() {
    return bridgeCall('InAppPurchases.CountryCode');
}

export const InAppPurchases = {
    initialize,
    isAvailable,
    queryProductDetails,
    buyConsumable,
    buyNonConsumable,
    buySubscription,
    restorePurchases,
    syncPurchases,
    completePurchase,
    consumePurchase,
    getPendingPurchases,
    getStatus,
    countryCode,
};

export const Events = {
    PurchaseUpdated: 'Wilsonatb\\InAppPurchases\\Events\\PurchaseUpdated',
    ConnectionChanged: 'Wilsonatb\\InAppPurchases\\Events\\ConnectionChanged',
    AvailabilityChanged: 'Wilsonatb\\InAppPurchases\\Events\\AvailabilityChanged',
    RestoreCompleted: 'Wilsonatb\\InAppPurchases\\Events\\RestoreCompleted',
    OperationFailed: 'Wilsonatb\\InAppPurchases\\Events\\OperationFailed',
};

export default InAppPurchases;
