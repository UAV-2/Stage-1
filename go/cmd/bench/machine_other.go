//go:build !windows

package main

// En Linux el pico de memoria y la máquina se leen de /proc.
func peakWorkingSet() (int64, bool) { return 0, false }

func windowsMachine() (string, uint64, bool) { return "", 0, false }
