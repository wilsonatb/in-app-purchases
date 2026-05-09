<?php

declare(strict_types=1);

namespace Wilsonatb\InAppPurchases\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;

final class CopyAssetsCommand extends NativePluginHookCommand
{
    protected $signature = 'nativephp:in-app-purchases:copy-assets';

    protected $description = 'Copy assets for InAppPurchases plugin';

    public function handle(): int
    {
        if ($this->isAndroid()) {
            $this->copyAndroidAssets();
        }

        if ($this->isIos()) {
            $this->copyIosAssets();
        }

        return self::SUCCESS;
    }

    protected function copyAndroidAssets(): void
    {
        $this->info('Android assets copied for InAppPurchases');
    }

    protected function copyIosAssets(): void
    {
        $this->info('iOS assets copied for InAppPurchases');
    }
}
