package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var (
	// Marcadores case-insensitive
	startRegex = regexp.MustCompile(`(?i)\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\n`)
	endRegex   = regexp.MustCompile(`(?i)\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK`)
)

func main() {
	// Prototipo Fase 1: Solo descarga y Datalake
	datalakeDir := filepath.Join("output", "go", "datalake_book")
	os.MkdirAll(datalakeDir, 0755)

	bookID := "1342"
	fmt.Printf("[PROTOTIPO] Iniciando descarga del libro %s...\n", bookID)

	_, _, err := downloadAndSplit(bookID, datalakeDir)
	if err != nil {
		fmt.Printf("[ERROR]: %v\n", err)
		return
	}
	fmt.Printf("[PROTOTIPO] Libro %s descargado y separado con éxito en %s.\n", bookID, datalakeDir)
}

func downloadAndSplit(bookID, datalakeDir string) (string, string, error) {
	url := fmt.Sprintf("https://www.gutenberg.org/cache/epub/%s/pg%s.txt", bookID, bookID)
	resp, err := http.Get(url)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()

	contentBytes, _ := io.ReadAll(resp.Body)
	text := strings.ReplaceAll(string(contentBytes), "\r\n", "\n")

	startMatch := startRegex.FindStringIndex(text)
	endMatch := endRegex.FindStringIndex(text)
	if startMatch == nil || endMatch == nil {
		return "", "", fmt.Errorf("marcadores no encontrados")
	}

	header := strings.TrimSpace(text[:startMatch[0]])
	body := strings.TrimSpace(text[startMatch[1]:endMatch[0]])

	bookDir := filepath.Join(datalakeDir, bookID)
	os.MkdirAll(bookDir, 0755)

	atomicWrite(filepath.Join(bookDir, "header.txt"), header)
	atomicWrite(filepath.Join(bookDir, "body.txt"), body)

	return header, body, nil
}

func atomicWrite(path, data string) error {
	tmpPath := path + ".tmp"
	os.WriteFile(tmpPath, []byte(data), 0644)
	return os.Rename(tmpPath, path)
}
