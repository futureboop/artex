"use client";

import type { ReactNode } from "react";
import * as React from "react";
import dynamic from "next/dynamic";

import { SidebarProvider } from "@/components/ui/sidebar";
import { getClientCookie } from "@/lib/cookie.client";
import { readUiMode, saveUiMode, type UiMode } from "@/lib/ui-mode";

import { SimpleShell } from "./simple-shell";

const ClassicShell = dynamic(() => import("./classic-shell").then((module) => module.ClassicShell), {
  loading: () => (
    <div className="flex min-h-dvh w-full items-center justify-center text-sm text-muted-foreground" role="status">
      正在载入完整模式…
    </div>
  ),
});

export function AuthenticatedShell({ children }: Readonly<{ children: ReactNode }>) {
  const [mode, setMode] = React.useState<UiMode>(readUiMode);
  const onModeChange = React.useCallback((next: UiMode) => {
    saveUiMode(next);
    setMode(next);
  }, []);

  const defaultOpen = typeof document === "undefined" ? true : getClientCookie("sidebar_state") !== "false";

  return (
    <SidebarProvider
      defaultOpen={defaultOpen}
      style={
        {
          "--sidebar-width": mode === "simple" ? "calc(var(--spacing) * 60)" : "calc(var(--spacing) * 68)",
        } as React.CSSProperties
      }
    >
      {mode === "simple" ? (
        <SimpleShell onModeChange={onModeChange}>{children}</SimpleShell>
      ) : (
        <ClassicShell onModeChange={onModeChange}>{children}</ClassicShell>
      )}
    </SidebarProvider>
  );
}
