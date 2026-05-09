<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class ConnectionChanged
{
    use Dispatchable;
    use SerializesModels;

    public function __construct(
        public string $state,
        public ?bool $ready = null,
        public ?string $platform = null,
        public ?string $message = null,
    ) {}
}
