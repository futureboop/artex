package agent

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/Autumn-27/norma/tool"
)

func TestNoaCompressGuardStopsRepeatedNoops(t *testing.T) {
	calls := 0
	underlying := tool.Build(tool.Spec{
		Name: "Compress",
		Run: func(context.Context, json.RawMessage, *tool.ToolContext) (tool.Result, error) {
			calls++
			return tool.Text("▣ noa | 0 blocks created\n  error: Every requested range is already compressed"), nil
		},
	})
	guard := &noaCompressGuard{CoreTool: underlying}
	for i := 0; i < 2; i++ {
		result, err := guard.Call(context.Background(), nil, nil)
		if err != nil || !result.IsError || !strings.Contains(result.Flatten(), "Do not retry") {
			t.Fatalf("attempt %d: result=%+v err=%v", i, result, err)
		}
	}
	result, err := guard.Call(context.Background(), nil, nil)
	if err != nil || !result.IsError || !strings.Contains(result.Flatten(), "unavailable") || calls != 2 {
		t.Fatalf("guard did not stop the third attempt: calls=%d result=%+v err=%v", calls, result, err)
	}
}

func TestNoaCompressGuardResetsAfterProgress(t *testing.T) {
	responses := []string{"▣ noa | 0 blocks created", "▣ noa | 179K → 170K tokens (~9K reclaimed)", "▣ noa | 0 blocks created"}
	underlying := tool.Build(tool.Spec{
		Name: "Compress",
		Run: func(context.Context, json.RawMessage, *tool.ToolContext) (tool.Result, error) {
			response := responses[0]
			responses = responses[1:]
			return tool.Text(response), nil
		},
	})
	guard := &noaCompressGuard{CoreTool: underlying}
	for i, wantError := range []bool{true, false, true} {
		result, err := guard.Call(context.Background(), nil, nil)
		if err != nil || result.IsError != wantError {
			t.Fatalf("attempt %d: isError=%v want=%v err=%v", i, result.IsError, wantError, err)
		}
	}
}
