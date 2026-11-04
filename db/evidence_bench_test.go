package db

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

func BenchmarkRecordFindingsAcrossTasks(b *testing.B) {
	dsn, _, err := DSN()
	if err != nil {
		b.Skip("PostgreSQL is not configured")
	}
	d, err := Open(dsn)
	if err != nil {
		b.Fatal(err)
	}
	b.Cleanup(func() { _ = d.Close() })
	tasks := make([]*Task, 8)
	for i := range tasks {
		task, err := d.CreateTask(fmt.Sprintf("concurrency benchmark %d", i), "test", nil, 0, 0)
		if err != nil {
			b.Fatal(err)
		}
		tasks[i] = task
		b.Cleanup(func() { _ = d.DeleteTask(task.ID) })
	}
	var next atomic.Uint64
	var mu sync.Mutex
	var runErr error
	b.SetParallelism(4)
	b.ResetTimer()
	b.RunParallel(func(pb *testing.PB) {
		task := tasks[(next.Add(1)-1)%uint64(len(tasks))]
		store := d.Exploration(task.ExplorationID)
		for pb.Next() {
			_, err := store.RecordFinding(context.Background(), RecordFindingInput{TaskID: task.ID, Summary: "benchmark", Severity: "low"})
			if err != nil {
				mu.Lock()
				runErr = err
				mu.Unlock()
				return
			}
		}
	})
	b.StopTimer()
	if runErr != nil {
		b.Fatal(runErr)
	}
}
