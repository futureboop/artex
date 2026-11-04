package server

import (
	"testing"

	"github.com/Autumn-27/artex/agent"
)

func TestResolvedTaskStatusSkipsReadinessForUnstartedTask(t *testing.T) {
	engine := NewEngine(nil)
	calls := 0
	engine.SetAuthoritativeAgentResolver(func(*Task) (*agent.Planner, *agent.Worker) {
		calls++
		return &agent.Planner{}, &agent.Worker{}
	})
	s := &Server{engine: engine}
	task := &Task{ID: "7", Status: "created"}
	if got := s.resolvedTaskStatus(task); got != "created" || calls != 0 {
		t.Fatalf("unstarted status=%q, readiness calls=%d; want created, 0", got, calls)
	}
	engine.started.Store(task.ID, true)
	if got := s.resolvedTaskStatus(task); got != "running" || calls != 1 {
		t.Fatalf("started status=%q, readiness calls=%d; want running, 1", got, calls)
	}
}

func TestTaskListReadinessFollowsGlobalAndTaskChain(t *testing.T) {
	s := &Server{m: &Manager{}}
	task := &Task{ID: "7"}
	if s.taskListReadiness()(task) {
		t.Fatal("task without a provider must not be ready")
	}
	s.llmOn, s.llmProv = true, &scriptedLLMProvider{}
	if !s.taskListReadiness()(task) {
		t.Fatal("global provider must make an unbound task ready")
	}
	profileID := int64(11)
	task.setLLMState(&profileID, nil, []int64{profileID}, 1, "chain_exhausted", "quota exhausted")
	if s.taskListReadiness()(task) {
		t.Fatal("exhausted task chain must not fall back to the global provider")
	}
}
