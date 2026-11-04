package server

import (
	"strconv"
	"sync"
	"testing"
)

func BenchmarkManagerList(b *testing.B) {
	m := &Manager{tasks: make(map[string]*Task, 1000)}
	for i := 1; i <= 1000; i++ {
		id := strconv.Itoa(i)
		m.tasks[id] = &Task{
			ID: id, PinnedAt: int64(i % 13),
			SourceTaskIDs: []int64{1, 2, 3, 4, 5},
			CompanyIDs:    []int64{1, 2, 3, 4, 5},
		}
	}
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = m.List()
	}
}

func TestManagerListPinnedAndIDOrder(t *testing.T) {
	m := &Manager{tasks: map[string]*Task{
		"2":  {ID: "2"},
		"10": {ID: "10"},
		"3":  {ID: "3", PinnedAt: 100},
		"5":  {ID: "5", PinnedAt: 100},
		"4":  {ID: "4", PinnedAt: 200},
	}}
	want := []string{"4", "5", "3", "10", "2"}
	got := m.List()
	if len(got) != len(want) {
		t.Fatalf("got %d tasks, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i].ID != want[i] {
			t.Fatalf("task at %d = %q, want %q", i, got[i].ID, want[i])
		}
	}
}

func TestManagerListConcurrentPinUpdates(t *testing.T) {
	task := &Task{ID: "1"}
	m := &Manager{tasks: map[string]*Task{"1": task}}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 1000; i++ {
			task.updateLifecycle(func(state *taskLifecycleState) { state.PinnedAt = int64(i % 2) })
		}
	}()
	for i := 0; i < 1000; i++ {
		if got := m.List(); len(got) != 1 || got[0] != task {
			t.Fatalf("unexpected list: %#v", got)
		}
	}
	wg.Wait()
}
