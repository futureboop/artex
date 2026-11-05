//go:build embedui

package server

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestEmbeddedWebAssetCompressionAndCache(t *testing.T) {
	const script = "console.log('artex');\n"
	files := fstest.MapFS{
		"_next/static/chunks/test-webui-cache.js": &fstest.MapFile{Data: []byte(strings.Repeat(script, 100))},
		"function/tasks/index.html":               &fstest.MapFile{Data: []byte(strings.Repeat("<p>loading</p>", 100))},
	}
	request := func(name, encoding string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/"+name, nil)
		if encoding != "" {
			req.Header.Set("Accept-Encoding", encoding)
		}
		response := httptest.NewRecorder()
		if !serveFileIfExists(response, req, files, name) {
			t.Fatalf("asset %q was not served", name)
		}
		return response
	}

	jsName := "_next/static/chunks/test-webui-cache.js"
	compressed := request(jsName, "br, gzip;q=1")
	if compressed.Code != http.StatusOK || compressed.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("gzip request: status=%d encoding=%q", compressed.Code, compressed.Header().Get("Content-Encoding"))
	}
	if compressed.Header().Get("Cache-Control") != "public, max-age=31536000, immutable" {
		t.Fatalf("static asset cache policy: %q", compressed.Header().Get("Cache-Control"))
	}
	if compressed.Header().Get("Vary") != "Accept-Encoding" {
		t.Fatalf("static asset vary policy: %q", compressed.Header().Get("Vary"))
	}
	zr, err := gzip.NewReader(strings.NewReader(compressed.Body.String()))
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	if string(decoded) != strings.Repeat(script, 100) {
		t.Fatal("compressed asset contents changed")
	}

	plain := request(jsName, "gzip;q=0")
	if plain.Header().Get("Content-Encoding") != "" || plain.Body.String() != strings.Repeat(script, 100) {
		t.Fatal("gzip;q=0 must receive the original asset")
	}
	if html := request("function/tasks/index.html", "gzip"); html.Header().Get("Cache-Control") != "no-cache" {
		t.Fatalf("HTML cache policy: %q", html.Header().Get("Cache-Control"))
	}
}
