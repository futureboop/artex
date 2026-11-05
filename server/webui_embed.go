//go:build embedui

package server

import (
	"bytes"
	"compress/gzip"
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
)

// webuiDist holds the statically-exported frontend (built with
// `cd web && npm run build:static`, then copied into server/webui/dist). Compiled
// into the binary only when building with `-tags embedui`. The `all:` prefix is
// required so Next's `_next/` asset dir (leading underscore) is included.
//
//go:embed all:webui/dist
var webuiDist embed.FS

// The embedded files never change within one process. Compress each text asset
// at most once, then reuse it for cold clients without paying gzip CPU again.
var webuiGzipCache sync.Map

// webuiHandler serves the embedded SPA. Public (no JWT) — auth is enforced
// client-side and on /api. Serves the exported per-route index.html files and
// falls back to index.html so client-side routing still resolves unknown paths.
func (s *Server) webuiHandler() http.Handler {
	root, err := fs.Sub(webuiDist, "webui/dist")
	if err != nil {
		panic(err)
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if p == "" {
			p = "index.html"
		}
		// 1) exact file (assets: _next/*, favicon.ico, ...)
		// 2) route dir → <p>/index.html (trailingSlash export)
		// 3) <p>.html
		// 4) SPA 兜底 → index.html（交给客户端路由）
		for _, cand := range []string{p, p + "/index.html", p + ".html"} {
			if serveFileIfExists(w, r, root, cand) {
				return
			}
		}
		serveFileIfExists(w, r, root, "index.html")
	})
}

// serveFileIfExists serves name from fsys when it exists as a regular file.
func serveFileIfExists(w http.ResponseWriter, r *http.Request, fsys fs.FS, name string) bool {
	f, err := fsys.Open(name)
	if err != nil {
		return false
	}
	st, statErr := f.Stat()
	_ = f.Close()
	if statErr != nil || st.IsDir() {
		return false
	}
	if strings.HasPrefix(name, "_next/static/") {
		// Next gives these assets content-hashed names. Reuse them across visits;
		// HTML must be revalidated so it never points at an older asset set.
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else if strings.HasSuffix(name, ".html") {
		w.Header().Set("Cache-Control", "no-cache")
	}
	if isCompressibleWebAsset(name) {
		w.Header().Add("Vary", "Accept-Encoding")
		if acceptsGzip(r.Header.Get("Accept-Encoding")) && st.Size() > 1024 {
			if cached, ok := webuiGzipCache.Load(name); ok {
				w.Header().Set("Content-Encoding", "gzip")
				http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(cached.([]byte)))
				return true
			}
			if raw, err := fs.ReadFile(fsys, name); err == nil {
				var buf bytes.Buffer
				zw, err := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
				if err == nil {
					_, err = zw.Write(raw)
					if err == nil {
						err = zw.Close()
					}
					if err == nil {
						compressed, _ := webuiGzipCache.LoadOrStore(name, buf.Bytes())
						w.Header().Set("Content-Encoding", "gzip")
						http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(compressed.([]byte)))
						return true
					}
				}
			}
		}
	}
	http.ServeFileFS(w, r, fsys, name)
	return true
}

func isCompressibleWebAsset(name string) bool {
	switch path.Ext(name) {
	case ".html", ".js", ".css", ".svg", ".json", ".txt":
		return true
	default:
		return false
	}
}

func acceptsGzip(header string) bool {
	for _, encoding := range strings.Split(header, ",") {
		parts := strings.Split(encoding, ";")
		if !strings.EqualFold(strings.TrimSpace(parts[0]), "gzip") {
			continue
		}
		for _, parameter := range parts[1:] {
			key, value, ok := strings.Cut(strings.TrimSpace(parameter), "=")
			if ok && key == "q" {
				quality, err := strconv.ParseFloat(value, 64)
				return err == nil && quality > 0
			}
		}
		return true
	}
	return false
}
