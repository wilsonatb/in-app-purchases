<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class RestoreCompleted
{
    use Dispatchable;
    use SerializesModels;

    public function __construct(
        public string $result,
        public int $restoredCount = 0,
        public ?string $requestId = null,
        public ?string $platform = null,
        public ?string $error = null,
    ) {}
}
