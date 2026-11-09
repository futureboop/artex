"use client";

import type { ReactNode } from "react";
import * as React from "react";

import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { auth } from "@/lib/auth";
import { PREFERENCE_DEFAULTS } from "@/lib/preferences/preferences-config";
import { PreferencesStoreProvider } from "@/stores/preferences/preferences-provider";
import { AuthenticatedShell } from "./_components/authenticated-shell";

function StartupStatus({ message }: { message: string }) {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-6 text-foreground" role="status">
      <div className="flex w-full max-w-xs flex-col items-center gap-4 text-center">
        <span role="img" aria-label="ARTEX" className="text-5xl leading-none">
          🛡️
        </span>
        <div className="space-y-1">
          <p className="font-semibold">正在打开控制台</p>
          <p className="text-sm text-muted-foreground">{message}</p>
        </div>
        <div className="h-1 w-32 overflow-hidden rounded-full bg-muted">
          <div className="h-full w-1/2 animate-pulse rounded-full bg-primary" />
        </div>
      </div>
    </main>
  );
}

export default function Layout({ children }: Readonly<{ children: ReactNode }>) {
  const [authed, setAuthed] = React.useState(false);
  React.useEffect(() => {
    if (auth.getToken()) {
      setAuthed(true);
    } else {
      auth.clearToken();
      window.location.href = "/login";
    }
  }, []);

  // Static export cannot read browser credentials while rendering HTML.
  if (!authed) return <StartupStatus message="正在检查登录状态…" />;
  const { theme_mode, theme_preset, content_layout, navbar_style, font } = PREFERENCE_DEFAULTS;
  return (
    <TooltipProvider>
      <PreferencesStoreProvider
        themeMode={theme_mode}
        themePreset={theme_preset}
        contentLayout={content_layout}
        navbarStyle={navbar_style}
        font={font}
      >
        <AuthenticatedShell>{children}</AuthenticatedShell>
        <Toaster />
      </PreferencesStoreProvider>
    </TooltipProvider>
  );
}
