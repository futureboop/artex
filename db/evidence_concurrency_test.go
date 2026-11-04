package db

import (
	"context"
	"database/sql"
	"testing"
	"time"
)

func concurrencyTestDB(tb testing.TB) *DB {
	tb.Helper()
	dsn, _, err := DSN()
	if err != nil {
		tb.Skip("PostgreSQL is not configured")
	}
	d, err := Open(dsn)
	if err != nil {
		tb.Fatal(err)
	}
	tb.Cleanup(func() { _ = d.Close() })
	return d
}

func TestConcurrentFindingLocksExcludeEvidenceOperations(t *testing.T) {
	d := concurrencyTestDB(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	errs := make(chan error, 3)
	for i := 0; i < 2; i++ {
		go func() {
			errs <- d.WithConcurrentFindingTx(ctx, func(*sql.Tx) error {
				entered <- struct{}{}
				select {
				case <-release:
					return nil
				case <-ctx.Done():
					return ctx.Err()
				}
			})
		}()
	}
	for i := 0; i < 2; i++ {
		select {
		case <-entered:
		case <-ctx.Done():
			t.Fatal("independent finding transactions did not enter concurrently")
		}
	}
	exclusiveEntered := make(chan struct{})
	go func() {
		errs <- d.WithEvidenceTx(ctx, func(*sql.Tx) error { close(exclusiveEntered); return nil })
	}()
	select {
	case <-exclusiveEntered:
		t.Fatal("exclusive evidence operation entered while findings were running")
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	for i := 0; i < 3; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	select {
	case <-exclusiveEntered:
	default:
		t.Fatal("exclusive evidence operation did not resume")
	}
}
