package server

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCompressListResponses(t *testing.T) {
	body := strings.Repeat(`{"task":"example"}`, 100)
	handler := compressListResponses(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, body)
	}))
	for _, tc := range []struct {
		path, accept string
		compressed   bool
	}{
		{"/api/tasks", "gzip, br", true},
		{"/api/exploration/intents", "gzip", true},
		{"/api/exploration/activity/history", "gzip", true},
		{"/api/intercept/task/56", "gzip", true},
		{"/api/tasks", "gzip;q=0", false},
		{"/api/exploration/activity/stream", "gzip", false},
	} {
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		req.Header.Set("Accept-Encoding", tc.accept)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if got := rec.Header().Get("Content-Encoding"); (got == "gzip") != tc.compressed {
			t.Errorf("%s %s: encoding %q", tc.path, tc.accept, got)
		}
		if tc.path != "/api/exploration/activity/stream" && rec.Header().Get("Vary") != "Accept-Encoding" {
			t.Errorf("%s %s: missing Vary", tc.path, tc.accept)
		}
		var decoded []byte
		if tc.compressed {
			zr, err := gzip.NewReader(rec.Body)
			if err != nil {
				t.Fatal(err)
			}
			decoded, err = io.ReadAll(zr)
			if err != nil {
				t.Fatal(err)
			}
			_ = zr.Close()
		} else {
			decoded = rec.Body.Bytes()
		}
		if string(decoded) != body {
			t.Errorf("%s: response changed", tc.path)
		}
	}
}
