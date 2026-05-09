## wilsonatb/in-app-purchases

Cross-platform NativePHP in-app purchases plugin (Google Play Billing + StoreKit 2).

### Installation

```bash
composer require wilsonatb/in-app-purchases
php artisan vendor:publish --tag=nativephp-plugins-provider
php artisan native:plugin:register wilsonatb/in-app-purchases
php artisan native:plugin:list
```

### iOS Testing Setup (required)

In-app purchases **will not work** without products registered in App Store Connect — even in the sandbox environment.

**1. Register products in App Store Connect:**

- Go to [App Store Connect](https://appstoreconnect.apple.com)
- Navigate to your app → **In-App Purchases**
- Create a product for each Product ID (e.g. `diwdesign.calculadoradiw.coins.1`, `diwdesign.calculadoradiw.coins.2`, `diwdesign.calculadoradiw.pro.1`)
- For each product: set **Type** (Consumable, Non-Consumable, Auto-Renewable Subscription), **Reference Name**, and **Product ID** (must match exactly the string used in code)

**2. Sign paid applications agreement:**

- App Store Connect → **Agreements, Tax and Banking**
- Accept the **Paid Applications Agreement** and complete banking/tax info
- Without this, products return as `notFoundIds` even when correctly created

**3. Xcode StoreKit testing (simulator):**

- Open the Xcode project and use Product → Scheme → Edit Scheme → Run → Options → **StoreKit Configuration**
- Define products, prices, and test scenarios in a `.storekit` file
- See: [Setting up StoreKit Testing in Xcode](https://developer.apple.com/documentation/Xcode/setting-up-storekit-testing-in-xcode)

**4. Sandbox testers (physical device / TestFlight):**

- App Store Connect → **Users and Access → Sandbox Testers**
- Create a sandbox tester (email + password)
- On the iOS device: **Settings → App Store → Sandbox Account** and sign in

### Lifecycle (required)

Follow this order:

1. `initialize`
2. `isAvailable`
3. `queryProductDetails`
4. `buy*`
5. handle `PurchaseUpdated`
6. backend verify
7. grant entitlement
8. if `pendingCompletePurchase`, call `completePurchase`

Use `pendingCompletePurchase` and `completionHint` as authoritative completion signals.

> ⚠️ If completion is delayed/skipped, test and production transactions can be auto-revoked/refunded by stores.

### Completion mapping by product type

- consumable (`purchaseKind=consumable`, `productType=inapp`)  
  `completePurchase(..., isConsumable: true)` → consume on Android
- non-consumable (`purchaseKind=non_consumable`)  
  `completePurchase(..., isConsumable: false)` → acknowledge on Android
- subscription (`purchaseKind=subscription`, `productType=subs`)  
  `completePurchase(..., isConsumable: false)` → acknowledge on Android
- iOS all types  
  `completePurchase` finishes StoreKit transaction

### PHP Usage (Livewire/Blade)

@verbatim
<code-snippet name="InAppPurchases lifecycle (PHP)" lang="php">
use Native\Mobile\Attributes\OnNative;
use Wilsonatb\InAppPurchases\Events\PurchaseUpdated;
use Wilsonatb\InAppPurchases\Facades\InAppPurchases;

InAppPurchases::initialize(autoSyncOnResume: true);
$availability = InAppPurchases::isAvailable();

if (! ($availability->available ?? false)) {
    return;
}

InAppPurchases::queryProductDetails(ids: ['coins_100', 'pro_upgrade', 'pro_monthly']);

InAppPurchases::buyConsumable([
    'productId' => 'coins_100',
    'id' => (string) str()->uuid(),
]);

#[OnNative(PurchaseUpdated::class)]
public function handlePurchaseUpdates(array $purchases): void
{
    foreach ($purchases as $purchase) {
        if (! in_array($purchase['status'] ?? '', ['purchased', 'restored'], true)) {
            continue;
        }

        // verify backend + grant entitlement first

        if (($purchase['pendingCompletePurchase'] ?? false) === true) {
            $isConsumable = (bool) (
                $purchase['isConsumableHint']
                ?? (($purchase['completionHint'] ?? 'unknown') === 'consume')
            );

            InAppPurchases::completePurchase(
                purchaseId: (string) ($purchase['purchaseId'] ?? ''),
                isConsumable: $isConsumable,
                waitForResult: true,
                id: (string) str()->uuid(),
            );
        }
    }
}
</code-snippet>
@endverbatim

### JavaScript Usage (Vue/React/Inertia)

@verbatim
<code-snippet name="InAppPurchases lifecycle (JS)" lang="javascript">
import { InAppPurchases, Events } from '@wilsonatb/in-app-purchases';
import { on, off } from '@nativephp/native';

await InAppPurchases.initialize({ autoSyncOnResume: true });
const availability = await InAppPurchases.isAvailable();

if (!availability?.available) {
    // disable paywall actions
}

await InAppPurchases.queryProductDetails({
    products: [
        { productId: 'coins_100', productType: 'inapp' },
        { productId: 'pro_monthly', productType: 'subs' },
    ],
});

const purchaseHandler = async (payload) => {
    for (const purchase of payload.purchases ?? []) {
        if (!['purchased', 'restored'].includes(purchase.status)) continue;

        // verify backend + grant entitlement first

        if (purchase.pendingCompletePurchase) {
            const isConsumable =
                purchase.isConsumableHint ?? (purchase.completionHint === 'consume');

            await InAppPurchases.completePurchase({
                purchaseId: purchase.purchaseId,
                isConsumable,
                waitForResult: true,
                id: crypto.randomUUID(),
            });
        }
    }
};

on(Events.PurchaseUpdated, purchaseHandler);
await InAppPurchases.buyNonConsumable({ productId: 'pro_upgrade', id: crypto.randomUUID() });
off(Events.PurchaseUpdated, purchaseHandler);
</code-snippet>
@endverbatim

### PurchaseUpdated payload keys

Top-level keys: `source`, `requestId`, `platform`, `timestamp`, `purchases`, optional `status`.

Purchase keys:

- `status`
- `platform`
- `purchaseId`
- `purchaseToken`
- `productIds`
- `productType`
- `purchaseKind`
- `completionHint`
- `isConsumableHint`
- `pendingCompletePurchase`
- `transactionDateMs`
- `verificationData` (`source`, `server`, `local`)
- `platformData` (`android` or `ios` details)
- Android-only fields: `purchaseState`, `acknowledged`, `autoRenewing`
- Optional `error` payload for failed verification/events

### Methods

- `initialize(options?)`
- `isAvailable()`
- `queryProductDetails(options)`
- `buyConsumable(options)`
- `buyNonConsumable(options)`
- `buySubscription(options)`
- `restorePurchases(options?)`
- `syncPurchases(options?)`
- `completePurchase(options)`
- `consumePurchase(options)` (Android)
- `getPendingPurchases()`
- `getStatus()`
- `countryCode()`

### Events

- `PurchaseUpdated`
- `ConnectionChanged`
- `AvailabilityChanged`
- `RestoreCompleted`
- `OperationFailed`

### Completion Safety Rules (Revenue Critical)

- Only grant/complete for `purchased` or `restored`.
- Never complete when `status` is `pending`, `error`, or canceled.
- Use `pendingCompletePurchase` as the completion gate.
- `completionHint=consume` => `completePurchase({ isConsumable: true })` (Android consumables).
- `completionHint=acknowledge` => `completePurchase({ isConsumable: false })` (non-consumables/subscriptions).
- Complete quickly after verification + delivery (Google Play may auto-refund if not completed in time).
