<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases;

use Illuminate\Support\ServiceProvider;
use Wilsonatb\InAppPurchases\Commands\CopyAssetsCommand;

final class InAppPurchasesServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(InAppPurchases::class, function () {
            return new InAppPurchases();
        });
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}
