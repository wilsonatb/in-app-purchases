<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class AvailabilityChanged
{
    use Dispatchable;
    use SerializesModels;

    public function __construct(
        public bool $available,
        public ?string $platform = null,
        public ?string $reason = null,
    ) {}
}
