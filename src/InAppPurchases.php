<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases;

final class InAppPurchases
{
    public function initialize(bool $autoSyncOnResume = true): ?object
    {
        return $this->call('InAppPurchases.Initialize', [
            'autoSyncOnResume' => $autoSyncOnResume,
        ]);
    }

    public function isAvailable(): ?object
    {
        return $this->call('InAppPurchases.IsAvailable');
    }

    /**
     * @param  array<int, string>  $ids
     * @param  array<int, array{productId: string, productType: string}>|null  $products
     */
    public function queryProductDetails(array $ids = [], ?array $products = null): ?object
    {
        $payload = [];

        if ($ids !== []) {
            $payload['ids'] = $ids;
        }

        if (is_array($products) && $products !== []) {
            $payload['products'] = $products;
        }

        return $this->call('InAppPurchases.QueryProductDetails', $payload);
    }

    /**
     * @param array{
     *   productId: string,
     *   offerToken?: string,
     *   accountId?: string,
     *   profileId?: string,
     *   applicationUserName?: string,
     *   id?: string
     * } $options
     */
    public function buyConsumable(array $options): ?object
    {
        return $this->call('InAppPurchases.BuyConsumable', $options);
    }

    /**
     * @param array{
     *   productId: string,
     *   offerToken?: string,
     *   accountId?: string,
     *   profileId?: string,
     *   applicationUserName?: string,
     *   id?: string
     * } $options
     */
    public function buyNonConsumable(array $options): ?object
    {
        return $this->call('InAppPurchases.BuyNonConsumable', $options);
    }

    /**
     * @param array{
     *   productId: string,
     *   offerToken?: string,
     *   oldPurchaseToken?: string,
     *   replacementMode?: int,
     *   accountId?: string,
     *   profileId?: string,
     *   applicationUserName?: string,
     *   id?: string
     * } $options
     *
     * Android SubscriptionUpdateParams.ReplacementMode values:
     *   0 = UNKNOWN_REPLACEMENT_MODE (default)
     *   1 = IMMEDIATE_WITH_TIME_PRORATION
     *   2 = CHARGE_PRORATED_PRICE
     *   3 = IMMEDIATE_WITHOUT_PRORATION
     *   4 = DEFERRED
     *   5 = CHARGE_FULL_PRICE
     */
    public function buySubscription(array $options): ?object
    {
        return $this->call('InAppPurchases.BuySubscription', $options);
    }

    public function restorePurchases(?string $id = null): ?object
    {
        return $this->call('InAppPurchases.RestorePurchases', array_filter([
            'id' => $id,
        ], static fn (mixed $value): bool => $value !== null));
    }

    public function syncPurchases(?string $reason = null): ?object
    {
        return $this->call('InAppPurchases.SyncPurchases', array_filter([
            'reason' => $reason,
        ], static fn (mixed $value): bool => $value !== null));
    }

    public function completePurchase(
        string $purchaseId,
        ?bool $isConsumable = null,
        ?bool $waitForResult = null,
        ?string $id = null
    ): ?object {
        return $this->call('InAppPurchases.CompletePurchase', array_filter([
            'purchaseId' => $purchaseId,
            'isConsumable' => $isConsumable,
            'waitForResult' => $waitForResult,
            'id' => $id,
        ], static fn (mixed $value): bool => $value !== null));
    }

    public function consumePurchase(string $purchaseToken): ?object
    {
        return $this->call('InAppPurchases.ConsumePurchase', [
            'purchaseToken' => $purchaseToken,
        ]);
    }

    public function getPendingPurchases(): ?object
    {
        return $this->call('InAppPurchases.GetPendingPurchases');
    }

    public function getStatus(): ?object
    {
        return $this->call('InAppPurchases.GetStatus');
    }

    public function countryCode(): ?object
    {
        return $this->call('InAppPurchases.CountryCode');
    }

    /**
     * @param  array<string, mixed>  $payload
     */
    private function call(string $method, array $payload = []): ?object
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        $result = nativephp_call($method, json_encode($payload));

        if (! is_string($result) || $result === '') {
            return null;
        }

        $decoded = json_decode($result);

        return $decoded->data ?? null;
    }
}
