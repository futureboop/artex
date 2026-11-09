package server

import (
	"compress/gzip"
	"net/http"
	"strconv"
	"strings"
)

// These JSON pages are large on active installations and are read repeatedly.
// Restrict compression to known JSON endpoints so streams and file downloads keep
// their original response-writer behavior.
func compressListResponses(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		selected := path == "/api/tasks" || path == "/api/exploration/intents" ||
			path == "/api/exploration/activity/history" || strings.HasPrefix(path, "/api/intercept/task/")
		if selected {
			w.Header().Add("Vary", "Accept-Encoding")
		}
		if r.Method != http.MethodGet || !selected || !acceptsAPIGzip(r.Header.Get("Accept-Encoding")) || r.Header.Get("Range") != "" {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Content-Encoding", "gzip")
		zw, err := gzip.NewWriterLevel(w, gzip.BestSpeed)
		if err != nil {
			w.Header().Del("Content-Encoding")
			next.ServeHTTP(w, r)
			return
		}
		next.ServeHTTP(&apiGzipWriter{ResponseWriter: w, writer: zw}, r)
		_ = zw.Close()
	})
}

type apiGzipWriter struct {
	http.ResponseWriter
	writer *gzip.Writer
}

func (w *apiGzipWriter) WriteHeader(status int) {
	w.Header().Del("Content-Length")
	w.ResponseWriter.WriteHeader(status)
}

func (w *apiGzipWriter) Write(data []byte) (int, error) {
	return w.writer.Write(data)
}

func acceptsAPIGzip(header string) bool {
	for _, encoding := range strings.Split(header, ",") {
		parts := strings.Split(encoding, ";")
		if !strings.EqualFold(strings.TrimSpace(parts[0]), "gzip") {
			continue
		}
		for _, parameter := range parts[1:] {
			key, value, ok := strings.Cut(strings.TrimSpace(parameter), "=")
			if ok && strings.EqualFold(key, "q") {
				quality, err := strconv.ParseFloat(value, 64)
				return err == nil && quality > 0
			}
		}
		return true
	}
	return false
}
