//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	procGetProcessMemoryInfo = kernel32.NewProc("K32GetProcessMemoryInfo")
	procGlobalMemoryStatusEx = kernel32.NewProc("GlobalMemoryStatusEx")
)

// processMemoryCounters es PROCESS_MEMORY_COUNTERS de Windows.
type processMemoryCounters struct {
	cb                         uint32
	pageFaultCount             uint32
	peakWorkingSetSize         uintptr
	workingSetSize             uintptr
	quotaPeakPagedPoolUsage    uintptr
	quotaPagedPoolUsage        uintptr
	quotaPeakNonPagedPoolUsage uintptr
	quotaNonPagedPoolUsage     uintptr
	pagefileUsage              uintptr
	peakPagefileUsage          uintptr
}

// peakWorkingSet es el pico del working set del proceso: el equivalente en
// Windows de VmHWM.
func peakWorkingSet() (int64, bool) {
	process, err := syscall.GetCurrentProcess()
	if err != nil {
		return 0, false
	}
	var counters processMemoryCounters
	counters.cb = uint32(unsafe.Sizeof(counters))
	ok, _, _ := procGetProcessMemoryInfo.Call(uintptr(process), uintptr(unsafe.Pointer(&counters)), uintptr(counters.cb))
	return int64(counters.peakWorkingSetSize), ok != 0
}

// memoryStatus es MEMORYSTATUSEX de Windows.
type memoryStatus struct {
	length               uint32
	memoryLoad           uint32
	totalPhys            uint64
	availPhys            uint64
	totalPageFile        uint64
	availPageFile        uint64
	totalVirtual         uint64
	availVirtual         uint64
	availExtendedVirtual uint64
}

// windowsMachine devuelve el nombre de la CPU y la RAM total en bytes.
func windowsMachine() (string, uint64, bool) {
	var key syscall.Handle
	path, _ := syscall.UTF16PtrFromString(`HARDWARE\DESCRIPTION\System\CentralProcessor\0`)
	if syscall.RegOpenKeyEx(syscall.HKEY_LOCAL_MACHINE, path, 0, syscall.KEY_READ, &key) != nil {
		return "", 0, false
	}
	defer syscall.RegCloseKey(key)
	name, _ := syscall.UTF16PtrFromString("ProcessorNameString")
	buffer := make([]uint16, 256)
	size := uint32(len(buffer) * 2)
	if syscall.RegQueryValueEx(key, name, nil, nil, (*byte)(unsafe.Pointer(&buffer[0])), &size) != nil {
		return "", 0, false
	}
	status := memoryStatus{length: uint32(unsafe.Sizeof(memoryStatus{}))}
	procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&status)))
	return syscall.UTF16ToString(buffer), status.totalPhys, true
}
