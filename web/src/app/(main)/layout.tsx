"use client";

import type { ReactNode } from "react";
import * as React from "react";

import dynamic from "next/dynamic";

import { auth } from "@/lib/auth";

function StartupStatus({ message }: { message: string }) {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-6 text-foreground" role="status">
      <div className="flex w-full max-w-xs flex-col items-center gap-4 text-center">
        <img src="/logo.png" alt="ARTEX" width={48} height={48} />
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

// Keep the token check independent of navigation, toolbars and page code.
const AuthenticatedShell = dynamic(
  () => import("./_components/authenticated-shell").then((m) => m.AuthenticatedShell),
  { loading: () => <StartupStatus message="正在载入界面…" /> },
);

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
  return authed ? <AuthenticatedShell>{children}</AuthenticatedShell> : <StartupStatus message="正在检查登录状态…" />;
}
