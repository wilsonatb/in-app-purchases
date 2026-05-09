<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class OperationFailed
{
    use Dispatchable;
    use SerializesModels;

    public function __construct(
        public string $operation,
        public string $code,
        public string $message,
        public ?bool $retryable = null,
        public ?string $requestId = null,
        public ?string $platform = null,
    ) {}
}
