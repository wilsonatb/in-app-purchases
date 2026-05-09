<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static object|null initialize(bool $autoSyncOnResume = true)
 * @method static object|null isAvailable()
 * @method static object|null queryProductDetails(array $ids = [], ?array $products = null)
 * @method static object|null buyConsumable(array $options)
 * @method static object|null buyNonConsumable(array $options)
 * @method static object|null buySubscription(array $options)
 * @method static object|null restorePurchases(?string $id = null)
 * @method static object|null syncPurchases(?string $reason = null)
 * @method static object|null completePurchase(string $purchaseId, ?bool $isConsumable = null, ?bool $waitForResult = null, ?string $id = null)
 * @method static object|null consumePurchase(string $purchaseToken)
 * @method static object|null getPendingPurchases()
 * @method static object|null getStatus()
 * @method static object|null countryCode()
 *
 * @see \Wilsonatb\InAppPurchases\InAppPurchases
 */
final class InAppPurchases extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Wilsonatb\InAppPurchases\InAppPurchases::class;
    }
}
