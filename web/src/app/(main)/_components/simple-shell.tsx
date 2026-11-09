"use client";

import type { ReactNode } from "react";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Brain, Bug, MessageSquare, Settings2, Target } from "lucide-react";

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import { useCurrentUser } from "@/hooks/use-current-user";
import type { UiMode } from "@/lib/ui-mode";
import { cn } from "@/lib/utils";

import { AccountSwitcher } from "./sidebar/account-switcher";
import { NavUser } from "./sidebar/nav-user";
import { ThemeSwitcher } from "./sidebar/theme-switcher";
import { UiModeSwitch } from "./sidebar/ui-mode-switch";

const links = [
  { label: "任务", description: "运行与管理", href: "/function/tasks", icon: Target },
  { label: "对话", description: "与 Agent 沟通", href: "/chat", icon: MessageSquare },
  { label: "LLM 配置", description: "模型与密钥", href: "/system/llm", icon: Brain },
  { label: "系统配置", description: "服务与选项", href: "/system/settings", icon: Settings2 },
  { label: "发现", description: "结果与复测", href: "/function/findings", icon: Bug },
] as const;

function currentLink(pathname: string) {
  return links.find((item) => pathname === item.href || pathname.startsWith(`${item.href}/`));
}

export function SimpleShell({ children, onModeChange }: { children: ReactNode; onModeChange: (mode: UiMode) => void }) {
  const pathname = usePathname().replace(/\/+$/, "");
  const currentUser = useCurrentUser();
  const active = currentLink(pathname);
  const isDetail = pathname.startsWith("/function/tasks/") || pathname.startsWith("/function/findings/");
  const isChat = pathname === "/chat";
  const pageTitle = active?.label ?? "工作区";
  const pageDescription = active?.description ?? "从左侧选择常用功能";

  return (
    <>
      <Sidebar variant="sidebar" collapsible="offcanvas" className="border-r bg-sidebar">
        <SidebarHeader className="border-b px-4 py-5">
          <Link
            prefetch={false}
            href="/function/tasks"
            className="flex items-center gap-3 rounded-xl px-1 outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <span
              className="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-2xl"
              role="img"
              aria-label="ARTEX"
            >
              🛡️
            </span>
            <span className="flex flex-col leading-tight">
              <span className="font-semibold tracking-tight">ARTEX</span>
              <span className="text-xs text-muted-foreground">极简工作台</span>
            </span>
          </Link>
        </SidebarHeader>
        <SidebarContent className="px-3 py-5">
          <p className="px-3 pb-3 text-[11px] font-medium tracking-[0.16em] text-muted-foreground">工作区</p>
          <SidebarMenu className="gap-1.5">
            {links.map((item) => {
              const Icon = item.icon;
              const selected = active?.href === item.href;
              return (
                <SidebarMenuItem key={item.href}>
                  <SidebarMenuButton
                    asChild
                    isActive={selected}
                    className="h-auto rounded-xl px-3 py-3 data-[active=true]:bg-primary/10 data-[active=true]:text-primary"
                  >
                    <Link prefetch={false} href={item.href} aria-current={selected ? "page" : undefined}>
                      <Icon className="size-5 shrink-0" />
                      <span className="flex flex-col gap-0.5 leading-tight">
                        <span className="text-sm font-medium">{item.label}</span>
                        <span className="text-xs font-normal text-muted-foreground">{item.description}</span>
                      </span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              );
            })}
          </SidebarMenu>
        </SidebarContent>
        <SidebarFooter className="gap-3 border-t p-3">
          <UiModeSwitch mode="simple" onChange={onModeChange} />
          <NavUser user={currentUser} />
        </SidebarFooter>
      </Sidebar>
      <SidebarInset className="min-w-0 overflow-x-hidden bg-muted/25">
        {!isDetail && (
          <header className="sticky top-0 z-40 flex h-12 shrink-0 items-center justify-between gap-3 border-b bg-background/95 px-4 backdrop-blur-sm md:h-16 md:px-7">
            <div className="flex min-w-0 items-center gap-3">
              <SidebarTrigger aria-label="打开导航" className="-ml-1" />
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold leading-tight">{pageTitle}</p>
                <p className="hidden text-xs text-muted-foreground sm:block">{pageDescription}</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <span className="hidden rounded-full border bg-background px-2.5 py-1 text-xs text-muted-foreground sm:inline-flex">
                极简模式
              </span>
              <ThemeSwitcher />
              <AccountSwitcher users={[currentUser]} />
            </div>
          </header>
        )}
        <div className={cn("min-w-0 flex-1", isDetail || isChat ? "" : "mx-auto w-full max-w-[1600px] p-4 md:p-7")}>
          {children}
        </div>
      </SidebarInset>
    </>
  );
}
