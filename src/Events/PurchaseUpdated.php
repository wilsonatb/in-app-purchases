<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class PurchaseUpdated
{
    use Dispatchable;
    use SerializesModels;

    /**
     * @var array<int, array<string, mixed>>
     */
    public array $purchases;

    public ?string $source;

    public ?string $reason;

    public ?string $requestId;

    public ?string $platform;

    public ?int $timestamp;

    /**
     * @var array<int, array<string, mixed>>
     */
    public array $products;

    /**
     * @var array<int, string>
     */
    public array $notFoundIds;

    public ?string $status;

    public ?string $purchaseId;

    public ?string $purchaseToken;

    public ?string $productType;

    public ?string $purchaseKind;

    public ?string $completionHint;

    public ?bool $isConsumableHint;

    /**
     * @var array<int, string>
     */
    public array $productIds;

    public ?string $purchaseState;

    public ?bool $acknowledged;

    public ?bool $autoRenewing;

    public ?int $transactionDateMs;

    /**
     * @var array<string, mixed>
     */
    public array $verificationData;

    /**
     * @var array<string, mixed>
     */
    public array $platformData;

    /**
     * @var array<string, mixed>
     */
    public array $extra;

    /**
     * @param  array<int, array<string, mixed>>  $purchases
     * @param  array<int, array<string, mixed>>  $products
     * @param  array<int, string>  $notFoundIds
     */
    public function __construct(
        mixed $purchases = [],
        ?string $source = null,
        ?string $reason = null,
        ?string $requestId = null,
        ?string $platform = null,
        ?int $timestamp = null,
        mixed $products = [],
        mixed $notFoundIds = [],
        ?string $status = null,
        ?string $purchaseId = null,
        ?string $purchaseToken = null,
        ?string $productType = null,
        ?string $purchaseKind = null,
        ?string $completionHint = null,
        ?bool $isConsumableHint = null,
        mixed $productIds = [],
        ?string $purchaseState = null,
        ?bool $acknowledged = null,
        ?bool $autoRenewing = null,
        ?int $transactionDateMs = null,
        mixed $verificationData = [],
        mixed $platformData = [],
        mixed ...$extra,
    ) {
        $this->purchases = self::normalizeAssociativeList($purchases);
        $this->source = $source;
        $this->reason = $reason;
        $this->requestId = $requestId;
        $this->platform = $platform;
        $this->timestamp = $timestamp;
        $this->products = self::normalizeAssociativeList($products);
        $this->notFoundIds = self::normalizeStringList($notFoundIds);
        $this->status = $status;
        $this->purchaseId = $purchaseId;
        $this->purchaseToken = $purchaseToken;
        $this->productType = $productType;
        $this->purchaseKind = $purchaseKind;
        $this->completionHint = $completionHint;
        $this->isConsumableHint = $isConsumableHint;
        $this->productIds = self::normalizeStringList($productIds);
        $this->purchaseState = $purchaseState;
        $this->acknowledged = $acknowledged;
        $this->autoRenewing = $autoRenewing;
        $this->transactionDateMs = $transactionDateMs;
        $this->verificationData = self::normalizeAssociativeMap($verificationData);
        $this->platformData = self::normalizeAssociativeMap($platformData);
        $this->extra = self::normalizeAssociativeMap($extra);
    }

    /**
     * @return array<int, array<string, mixed>>
     */
    private static function normalizeAssociativeList(mixed $value): array
    {
        $decoded = self::decodeJsonIfString($value);

        if (! is_array($decoded)) {
            return [];
        }

        if (self::isAssociative($decoded)) {
            return [$decoded];
        }

        return array_values(array_filter(
            $decoded,
            static fn (mixed $item): bool => is_array($item)
        ));
    }

    /**
     * @return array<int, string>
     */
    private static function normalizeStringList(mixed $value): array
    {
        $decoded = self::decodeJsonIfString($value);

        if (! is_array($decoded)) {
            return [];
        }

        return array_values(array_filter(array_map(
            static fn (mixed $item): string => is_string($item) ? $item : '',
            $decoded
        ), static fn (string $item): bool => $item !== ''));
    }

    /**
     * @return array<string, mixed>
     */
    private static function normalizeAssociativeMap(mixed $value): array
    {
        $decoded = self::decodeJsonIfString($value);

        return is_array($decoded) ? $decoded : [];
    }

    private static function decodeJsonIfString(mixed $value): mixed
    {
        if (! is_string($value)) {
            return $value;
        }

        $decoded = json_decode($value, true);

        if (json_last_error() !== JSON_ERROR_NONE) {
            return $value;
        }

        return $decoded;
    }

    /**
     * @param  array<int|string, mixed>  $value
     */
    private static function isAssociative(array $value): bool
    {
        return array_keys($value) !== range(0, count($value) - 1);
    }
}
