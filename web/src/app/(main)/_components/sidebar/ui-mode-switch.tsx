"use client";

import { PanelsTopLeft, Rows3 } from "lucide-react";

import { Button } from "@/components/ui/button";
import type { UiMode } from "@/lib/ui-mode";

export function UiModeSwitch({
  mode,
  onChange,
  compact = false,
}: {
  mode: UiMode;
  onChange: (mode: UiMode) => void;
  compact?: boolean;
}) {
  const simple = mode === "simple";
  return (
    <Button
      type="button"
      variant="outline"
      size={compact ? "icon" : "sm"}
      onClick={() => onChange(simple ? "classic" : "simple")}
      aria-label={simple ? "切换到完整模式" : "切换到极简模式"}
      title={simple ? "切换到完整模式" : "切换到极简模式"}
      className={compact ? "shrink-0" : "w-full justify-start gap-2"}
    >
      {simple ? <PanelsTopLeft className="size-4" /> : <Rows3 className="size-4" />}
      {!compact && <span>{simple ? "完整模式" : "极简模式"}</span>}
    </Button>
  );
}
