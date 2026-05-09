<?php

declare(strict_types=1);

beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifestPath = $this->pluginPath.'/nativephp.json';
});

describe('Plugin Manifest', function () {
    it('has valid nativephp.json', function () {
        expect(file_exists($this->manifestPath))->toBeTrue();

        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($manifest)->toHaveKeys(['namespace', 'bridge_functions', 'android', 'ios', 'events']);
        expect($manifest['namespace'])->toBe('InAppPurchases');
    });

    it('declares required bridge functions', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        $names = array_map(
            static fn (array $function): ?string => $function['name'] ?? null,
            $manifest['bridge_functions']
        );

        expect($names)->toContain('InAppPurchases.Initialize');
        expect($names)->toContain('InAppPurchases.QueryProductDetails');
        expect($names)->toContain('InAppPurchases.BuyConsumable');
        expect($names)->toContain('InAppPurchases.BuyNonConsumable');
        expect($names)->toContain('InAppPurchases.BuySubscription');
        expect($names)->toContain('InAppPurchases.CompletePurchase');
        expect($names)->toContain('InAppPurchases.RestorePurchases');
    });

    it('declares platform dependencies and permissions', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['android']['dependencies']['implementation'])->toContain('com.android.billingclient:billing:8.3.0');
        expect($manifest['android']['permissions'])->toContain('com.android.vending.BILLING');
        expect($manifest['platforms'])->toContain('android');
        expect($manifest['platforms'])->toContain('ios');
    });
});

describe('Native Code', function () {
    it('has android kotlin bridge implementation', function () {
        $file = $this->pluginPath.'/resources/android/src/InAppPurchasesFunctions.kt';

        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('BridgeFunction');
        expect($content)->toContain('BillingClient');
        expect($content)->toContain('queryPurchasesAsync');
        expect($content)->toContain('NativeActionCoordinator.dispatchEvent');
    });

    it('has ios swift bridge implementation', function () {
        $file = $this->pluginPath.'/resources/ios/Sources/InAppPurchasesFunctions.swift';

        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('StoreKit');
        expect($content)->toContain('Transaction.updates');
        expect($content)->toContain('BridgeFunction');
        expect($content)->toContain('purchaseKind');
        expect($content)->toContain('completionHint');
    });

    it('applies android play billing safeguards', function () {
        $file = $this->pluginPath.'/resources/android/src/InAppPurchasesFunctions.kt';

        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('resolvePurchaseStatus');
        expect($content)->toContain('shouldMarkPendingCompletion');
        expect($content)->toContain('launchBillingFlowOnMainThread');
        expect($content)->toContain('waitForResult');
        expect($content)->toContain('setIncludeSuspendedSubscriptions');
        expect($content)->toContain('purchaseKind');
        expect($content)->toContain('completionHint');
        expect($content)->toContain('isConsumableHint');
    });

    it('supports subscription upgrade/downgrade via oldPurchaseToken', function () {
        $file = $this->pluginPath.'/resources/android/src/InAppPurchasesFunctions.kt';

        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('setOldPurchaseToken');
        expect($content)->toContain('SubscriptionUpdateParams');
        expect($content)->toContain('setSubscriptionReplacementMode');
        expect($content)->toContain('setSubscriptionUpdateParams');
    });

    it('supports ios jwsRepresentation for server-side verification', function () {
        $file = $this->pluginPath.'/resources/ios/Sources/InAppPurchasesFunctions.swift';

        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('jwsRepresentation');
        expect($content)->toContain('getCachedStatus');
        expect($content)->toContain('getCachedCountryCode');
        expect($content)->toContain('DispatchSemaphore');
    });
});

describe('PHP and JS Surfaces', function () {
    it('has provider facade and core class', function () {
        expect(file_exists($this->pluginPath.'/src/InAppPurchasesServiceProvider.php'))->toBeTrue();
        expect(file_exists($this->pluginPath.'/src/Facades/InAppPurchases.php'))->toBeTrue();
        expect(file_exists($this->pluginPath.'/src/InAppPurchases.php'))->toBeTrue();

        $phpSurface = file_get_contents($this->pluginPath.'/src/InAppPurchases.php');
        expect($phpSurface)->toContain('waitForResult');
        expect($phpSurface)->toContain('completePurchase(');
    });

    it('has js api wrapper with event constants', function () {
        $jsPath = $this->pluginPath.'/resources/js/InAppPurchases.js';
        expect(file_exists($jsPath))->toBeTrue();

        $content = file_get_contents($jsPath);
        expect($content)->toContain('export const InAppPurchases');
        expect($content)->toContain('Events');
        expect($content)->toContain('InAppPurchases.Initialize');
    });
});

describe('Events', function () {
    it('declares purchase lifecycle events', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        expect($manifest['events'])->toContain('Wilsonatb\\InAppPurchases\\Events\\PurchaseUpdated');
        expect($manifest['events'])->toContain('Wilsonatb\\InAppPurchases\\Events\\ConnectionChanged');
        expect($manifest['events'])->toContain('Wilsonatb\\InAppPurchases\\Events\\AvailabilityChanged');
        expect($manifest['events'])->toContain('Wilsonatb\\InAppPurchases\\Events\\RestoreCompleted');
        expect($manifest['events'])->toContain('Wilsonatb\\InAppPurchases\\Events\\OperationFailed');
    });

    it('accepts expanded purchase payload keys in php event constructor', function () {
        $eventPath = $this->pluginPath.'/src/Events/PurchaseUpdated.php';
        expect(file_exists($eventPath))->toBeTrue();

        $content = file_get_contents($eventPath);
        expect($content)->toContain('public ?string $productType;');
        expect($content)->toContain('public ?string $purchaseKind;');
        expect($content)->toContain('public ?string $completionHint;');
        expect($content)->toContain('mixed ...$extra');
        expect($content)->toContain('decodeJsonIfString');
    });

    it('hydrates purchase event with flexible payload shapes', function () {
        $event = new Wilsonatb\InAppPurchases\Events\PurchaseUpdated(
            purchases: '[{"purchaseId":"tx-1","productIds":["coins_100"]}]',
            products: '[{"productId":"coins_100","productType":"inapp"}]',
            notFoundIds: '["missing_sku"]',
            productType: 'inapp',
            purchaseKind: 'consumable',
            completionHint: 'consume',
            unknownKeyFromNative: 'ignored',
        );

        expect($event->purchases)->toHaveCount(1);
        expect($event->products)->toHaveCount(1);
        expect($event->notFoundIds)->toContain('missing_sku');
        expect($event->extra)->toHaveKey('unknownKeyFromNative');
    });
});

describe('Composer Configuration', function () {
    it('has valid nativephp plugin composer metadata', function () {
        $composerPath = $this->pluginPath.'/composer.json';
        expect(file_exists($composerPath))->toBeTrue();

        $composer = json_decode(file_get_contents($composerPath), true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($composer['type'])->toBe('nativephp-plugin');
        expect($composer['extra']['nativephp']['manifest'])->toBe('nativephp.json');
    });
});
